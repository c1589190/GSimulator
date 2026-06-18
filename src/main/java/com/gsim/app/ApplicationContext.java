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

    // Knowledge 系统（SQLite + Embedding + Agent Tools）
    private SQLiteKnowledgeStore knowledgeStore;
    private EmbeddingProfileManager embeddingProfileManager;
    private KnowledgeSearchService knowledgeSearchService;
    private KnowledgeToolFactory knowledgeToolFactory;

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

        // Knowledge 系统初始化
        initKnowledge(config);

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
     * 初始化：创建目录、注册命令等。
     */
    public void initialize() throws Exception {
        dataPaths.initialize();
    }

    // ---- Getters ----

    public AppConfig getConfig() {
        return config;
    }

    public DataPaths getDataPaths() {
        return dataPaths;
    }

    public TimeProvider getTimeProvider() {
        return timeProvider;
    }

    public CampaignService getCampaignService() {
        return campaignService;
    }

    public TurnService getTurnService() {
        return turnService;
    }

    public PlayerActionService getPlayerActionService() {
        return playerActionService;
    }

    public LlmClient getLlmClient() {
        return llmClient;
    }

    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    public InteractionContext getInteractionContext() {
        return interactionContext;
    }

    public InteractionSession getInteractionSession() {
        return interactionSession;
    }

    public InteractionManager getInteractionManager() {
        return interactionManager;
    }

    public EventBus getEventBus() {
        return eventBus;
    }

    public ConsoleEventSink getConsoleEventSink() {
        return consoleEventSink;
    }

    public ApiManager getApiManager() {
        return apiManager;
    }

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

    // ---- Knowledge 系统 ----

    public SQLiteKnowledgeStore getKnowledgeStore() { return knowledgeStore; }
    public EmbeddingProfileManager getEmbeddingProfileManager() { return embeddingProfileManager; }
    public KnowledgeSearchService getKnowledgeSearchService() { return knowledgeSearchService; }

    private void initKnowledge(AppConfig config) {
        try {
            // 确保 knowledge 目录存在
            Path knowledgeDir = config.getKnowledgeDbPath().getParent();
            if (knowledgeDir != null) {
                Files.createDirectories(knowledgeDir);
            }

            // 创建 SQLite store 并初始化 schema
            this.knowledgeStore = new SQLiteKnowledgeStore(
                    config.getKnowledgeDbPath().toString());
            this.knowledgeStore.initialize();

            // 创建 embedding model（基于配置）
            EmbeddingModel embeddingModel = null;
            String provider = config.getEmbeddingProvider();
            if ("external".equals(provider) && !isBlank(config.getEmbeddingBaseUrl())
                    && !isBlank(config.getEmbeddingApiKey()) && !isBlank(config.getEmbeddingModel())) {
                embeddingModel = new ExternalEmbeddingModel(
                        config.getEmbeddingBaseUrl(), config.getEmbeddingApiKey(),
                        config.getEmbeddingModel(), config.getEmbeddingDimensions(),
                        30);
                System.out.println("[Knowledge] 检测到 external embedding 配置。");
                System.out.println("[Knowledge] 可运行 /embedding test 验证。");
            } else if ("local-small".equals(provider)) {
                String modelDir = config.getEmbeddingModelDir() != null
                        ? config.getEmbeddingModelDir() : "data/models/local-small";
                embeddingModel = new LocalSmallEmbeddingModel(
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

            // 创建 profile manager
            this.embeddingProfileManager = new EmbeddingProfileManager(knowledgeStore, embeddingModel);
            if (embeddingModel != null) {
                embeddingProfileManager.initialize();
            }

            // 创建 search service
            this.knowledgeSearchService = new KnowledgeSearchService(knowledgeStore, embeddingProfileManager);

            // 创建 tool factory 并注册所有 knowledge tools
            this.knowledgeToolFactory = new KnowledgeToolFactory(
                    knowledgeStore, knowledgeSearchService, embeddingProfileManager);
            for (var tool : knowledgeToolFactory.createAll()) {
                this.toolRegistry.register(tool);
            }

        } catch (Exception e) {
            System.err.println("[Knowledge] 初始化失败: " + e.getMessage());
            e.printStackTrace();
            // 不阻塞启动 — knowledge 系统不可用时其他功能仍可用
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * 关闭事件系统。
     */
    public void shutdown() {
        if (knowledgeStore != null) {
            knowledgeStore.close();
        }
        eventBus.shutdown();
        apiManager.stop();
    }
}
