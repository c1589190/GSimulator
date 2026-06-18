package com.gsim.app;

import com.gsim.api.ApiConfig;
import com.gsim.api.ApiManager;
import com.gsim.api.SessionManager;
import com.gsim.campaign.CampaignService;
import com.gsim.campaign.TurnService;
import com.gsim.campaign.PlayerActionService;
import com.gsim.context.BranchContextRenderer;
import com.gsim.context.session.ContextSessionManager;
import com.gsim.data.DataManager;
import com.gsim.event.EventBus;
import com.gsim.event.ConsoleEventSink;
import com.gsim.interaction.InteractionContext;
import com.gsim.interaction.InteractionManager;
import com.gsim.interaction.InteractionSession;
import com.gsim.knowledge.embed.EmbeddingModel;
import com.gsim.knowledge.embed.EmbeddingProfileManager;
import com.gsim.knowledge.embed.ExternalEmbeddingModel;
import com.gsim.knowledge.embed.LocalSmallEmbeddingModel;
import com.gsim.knowledge.scope.KnowledgeScope;
import com.gsim.knowledge.scope.ScopedKnowledgeStoreFactory;
import com.gsim.knowledge.search.KnowledgeSearchService;
import com.gsim.knowledge.store.SQLiteKnowledgeStore;
import com.gsim.knowledge.tool.KnowledgeToolFactory;
import com.gsim.llm.LlmClient;
import com.gsim.llm.OpenAiLlmClient;
import com.gsim.storage.DataPaths;
import com.gsim.tool.LocalFileSearchService;
import com.gsim.tool.ToolRegistry;
import com.gsim.tool.WikiSearchTool;
import com.gsim.util.TimeProvider;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 应用上下文 — 整个应用的单例依赖容器。
 * 不使用 Spring DI，手动装配依赖。
 */
public class ApplicationContext {

    private final AppConfig config;
    private final DataPaths dataPaths;
    private final TimeProvider timeProvider;

    private final CampaignService campaignService;
    private final TurnService turnService;
    private final PlayerActionService playerActionService;
    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final InteractionContext interactionContext;
    private final InteractionSession interactionSession;
    private final InteractionManager interactionManager;

    private final EventBus eventBus;
    private final ConsoleEventSink consoleEventSink;
    private final ApiManager apiManager;

    // Knowledge 系统（root-scoped）
    private EmbeddingModel embeddingModel;
    private ScopedKnowledgeStoreFactory scopedStoreFactory;
    private KnowledgeToolFactory knowledgeToolFactory;
    private String activeRootId;

    // 上下文系统（Phase Context Session）
    private DataManager dataManager;
    private BranchContextRenderer branchContextRenderer;
    private ContextSessionManager contextSessionManager;

    public ApplicationContext(AppConfig config) {
        this.config = config;
        this.dataPaths = new DataPaths(config);
        this.timeProvider = new TimeProvider();

        // 服务层
        this.campaignService = new CampaignService(dataPaths, timeProvider);
        this.turnService = new TurnService(dataPaths, timeProvider);
        this.playerActionService = new PlayerActionService(dataPaths, timeProvider);

        // LLM — 只有配置完整时才创建真正的客户端
        if (config.isLlmConfigured()) {
            this.llmClient = new OpenAiLlmClient(
                    config.getLlmBaseUrl(), config.getLlmApiKey(),
                    config.getLlmModel(), config.getLlmTemperature(),
                    config.getLlmTimeoutSeconds());
        } else {
            this.llmClient = null;
        }

        // Tool 系统
        this.toolRegistry = new ToolRegistry();
        Path wikiDir = config.getImportDir().resolve("web").resolve("prts.wiki");
        LocalFileSearchService searchService = new LocalFileSearchService(wikiDir);
        this.toolRegistry.register(new WikiSearchTool(searchService));

        // Embedding model（全局配置，root 间共享）
        initEmbeddingModel(config);

        // Scoped store factory（延迟初始化每个 root 的 store）
        this.scopedStoreFactory = new ScopedKnowledgeStoreFactory(embeddingModel);

        // Knowledge tools（注册到 ToolRegistry，动态解析当前 root 的 store）
        this.knowledgeToolFactory = new KnowledgeToolFactory(null, null, null);
        registerKnowledgeTools();

        // 交互层
        this.interactionContext = new InteractionContext();
        this.interactionSession = new InteractionSession(
                interactionContext, config,
                campaignService, turnService, playerActionService,
                toolRegistry, llmClient);
        this.interactionManager = new InteractionManager();

        // 事件系统
        this.eventBus = new EventBus();
        this.consoleEventSink = new ConsoleEventSink();
        this.eventBus.subscribe(consoleEventSink);

        // HTTP API
        ApiConfig apiConfig = new ApiConfig(
                config.getApiHost(), config.getApiPort(), config.isApiEnabled());
        this.apiManager = new ApiManager(apiConfig, this, eventBus);
    }

    /**
     * 当 active root 变化时调用，切换 knowledge runtime。
     */
    public void resolveKnowledgeForActiveRoot() {
        if (dataManager == null) return;
        String rootId = dataManager.getActiveRootId();
        if (rootId == null) return;
        if (rootId.equals(activeRootId)) return; // 已经是当前 root

        Path dataRoot = dataManager.getDataRoot();
        KnowledgeScope scope = KnowledgeScope.of(dataRoot, rootId);

        // 确保 root-scoped store 已创建
        scopedStoreFactory.getOrCreateStore(scope);
        if (embeddingModel != null) {
            scopedStoreFactory.getOrCreateProfileManager(scope);
            scopedStoreFactory.getOrCreateSearchService(scope);
        }

        // 重新绑定 tools 到新 root 的 store
        rebindKnowledgeTools(rootId);
        this.activeRootId = rootId;
    }

    /** 初始化 embedding model（全局配置，不在 initKnowledge 中创建 store）。 */
    private void initEmbeddingModel(AppConfig config) {
        try {
            String provider = config.getEmbeddingProvider();
            if ("external".equals(provider) && !isBlank(config.getEmbeddingBaseUrl())
                    && !isBlank(config.getEmbeddingApiKey()) && !isBlank(config.getEmbeddingModel())) {
                this.embeddingModel = new ExternalEmbeddingModel(
                        config.getEmbeddingBaseUrl(), config.getEmbeddingApiKey(),
                        config.getEmbeddingModel(), config.getEmbeddingDimensions(),
                        30);
                System.out.println("[Knowledge] 检测到 external embedding 配置。");
                System.out.println("[Knowledge] 可运行 /embedding test 验证。");
            } else if ("local-small".equals(provider)) {
                String modelDir = config.getEmbeddingModelDir() != null
                        ? config.getEmbeddingModelDir() : "data/models/local-small";
                this.embeddingModel = new LocalSmallEmbeddingModel(
                        modelDir, config.getEmbeddingModel() != null ? config.getEmbeddingModel() : "local-small",
                        config.getEmbeddingDimensions() > 0 ? config.getEmbeddingDimensions() : 384);
                if (!embeddingModel.isAvailable()) {
                    System.out.println("[Knowledge] local-small 模型文件不存在。");
                    System.out.println("[Knowledge] 请放置模型文件或改用 external。");
                }
            } else {
                System.out.println("[Knowledge] 当前未配置语义 embedding profile。");
                System.out.println("[Knowledge] keyword_search 可用。");
                System.out.println("[Knowledge] knowledge_search 需要配置 external 或 local-small embedding。");
            }
        } catch (Exception e) {
            System.err.println("[Knowledge] Embedding model init failed: " + e.getMessage());
        }
    }

    private void registerKnowledgeTools() {
        for (var tool : knowledgeToolFactory.createAll()) {
            this.toolRegistry.register(tool);
        }
    }

    /** 重新绑定 knowledge tools 到指定 root 的 store。 */
    private void rebindKnowledgeTools(String rootId) {
        var store = scopedStoreFactory.getStore(rootId);
        var pm = scopedStoreFactory.getProfileManager(rootId);
        var ss = scopedStoreFactory.getSearchService(rootId);
        // 更新 factory 的内部引用（仅当有 root 级 store 时）
        if (store != null) {
            knowledgeToolFactory.rebind(store, ss, pm);
        }
    }

    /**
     * 初始化：创建目录、注册命令等。
     */
    public void initialize() throws Exception {
        dataPaths.initialize();
    }

    // ---- Getters ----

    public AppConfig getConfig() { return config; }
    public DataPaths getDataPaths() { return dataPaths; }
    public TimeProvider getTimeProvider() { return timeProvider; }
    public CampaignService getCampaignService() { return campaignService; }
    public TurnService getTurnService() { return turnService; }
    public PlayerActionService getPlayerActionService() { return playerActionService; }
    public LlmClient getLlmClient() { return llmClient; }
    public ToolRegistry getToolRegistry() { return toolRegistry; }
    public InteractionContext getInteractionContext() { return interactionContext; }
    public InteractionSession getInteractionSession() { return interactionSession; }
    public InteractionManager getInteractionManager() { return interactionManager; }
    public EventBus getEventBus() { return eventBus; }
    public ConsoleEventSink getConsoleEventSink() { return consoleEventSink; }
    public ApiManager getApiManager() { return apiManager; }

    // ---- Context Session 系统 ----

    public DataManager getDataManager() { return dataManager; }
    public void setDataManager(DataManager dm) { this.dataManager = dm; }

    public BranchContextRenderer getBranchContextRenderer() { return branchContextRenderer; }
    public void setBranchContextRenderer(BranchContextRenderer r) { this.branchContextRenderer = r; }

    public ContextSessionManager getContextSessionManager() { return contextSessionManager; }
    public void setContextSessionManager(ContextSessionManager m) { this.contextSessionManager = m; }

    public SessionManager getSessionManager() {
        return apiManager != null ? apiManager.getSessionManager() : null;
    }

    // ---- Knowledge 系统（root-scoped） ----

    /** 获取当前 active root 的 store（可能为 null，如果尚无 root）。 */
    public SQLiteKnowledgeStore getKnowledgeStore() {
        if (dataManager == null || dataManager.getActiveRootId() == null) return null;
        return scopedStoreFactory.getStore(dataManager.getActiveRootId());
    }

    /** 获取当前 active root 的 profile manager。 */
    public EmbeddingProfileManager getEmbeddingProfileManager() {
        if (dataManager == null || dataManager.getActiveRootId() == null) return null;
        return scopedStoreFactory.getProfileManager(dataManager.getActiveRootId());
    }

    /** 获取当前 active root 的 search service。 */
    public KnowledgeSearchService getKnowledgeSearchService() {
        if (dataManager == null || dataManager.getActiveRootId() == null) return null;
        return scopedStoreFactory.getSearchService(dataManager.getActiveRootId());
    }

    public ScopedKnowledgeStoreFactory getScopedStoreFactory() { return scopedStoreFactory; }
    public EmbeddingModel getEmbeddingModel() { return embeddingModel; }

    /**
     * 关闭所有资源：LLM client、embedding model、knowledge stores、event bus、API server。
     */
    public void shutdown() {
        if (llmClient instanceof OpenAiLlmClient openAi) {
            openAi.close();
        }
        if (embeddingModel instanceof ExternalEmbeddingModel ext) {
            ext.close();
        }
        if (scopedStoreFactory != null) {
            scopedStoreFactory.closeAll();
        }
        eventBus.shutdown();
        apiManager.stop();
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
