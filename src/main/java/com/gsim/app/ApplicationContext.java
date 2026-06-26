package com.gsim.app;

import com.gsim.api.ApiConfig;
import com.gsim.api.ApiManager;
import com.gsim.api.SessionManager;
import com.gsim.event.EventBus;
import com.gsim.event.ConsoleEventSink;
import com.gsim.interaction.InteractionContext;
import com.gsim.interaction.InteractionManager;
import com.gsim.interaction.InteractionSession;
import com.gsim.knowledge.embed.EmbeddingModel;
import com.gsim.knowledge.embed.EmbeddingProfileManager;
import com.gsim.knowledge.embed.ExternalEmbeddingModel;
import com.gsim.knowledge.embed.LocalSmallEmbeddingModel;
import com.gsim.knowledge.scope.ScopedKnowledgeStoreFactory;
import com.gsim.knowledge.search.KnowledgeSearchService;
import com.gsim.knowledge.store.SQLiteKnowledgeStore;
import com.gsim.knowledge.tool.KnowledgeToolFactory;
import com.gsim.llm.LlmManager;
import com.gsim.llm.ProviderConfig;
import com.gsim.tool.LocalFileSearchService;
import com.gsim.tool.ToolRegistry;
import com.gsim.tool.WikiSearchTool;
import com.gsim.util.TimeProvider;

import java.nio.file.Path;

/**
 * 应用上下文 — 整个应用的单例依赖容器。
 * 不使用 Spring DI，手动装配依赖。
 */
public class ApplicationContext {

    private final AppConfig config;
    private final TimeProvider timeProvider;

    private final LlmManager llmManager;
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

    // Root 就绪回调（bootstrap 完成后触发 memory tools 重注册等）
    private Runnable onRootReadyCallback;

    public ApplicationContext(AppConfig config) {
        this.config = config;
        this.timeProvider = new TimeProvider();

        // LLM — 只有配置完整时才创建真正的客户端
        if (config.isLlmConfigured()) {
            this.llmManager = new LlmManager(ProviderConfig.generic(
                    "custom",
                    config.getLlmBaseUrl(), config.getLlmApiKey(),
                    config.getLlmModel(), config.getLlmTemperature(),
                    config.getLlmTimeoutSeconds()));
        } else {
            this.llmManager = null;
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
                toolRegistry, llmManager);
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
    public void rebindKnowledgeTools(String rootId) {
        var store = scopedStoreFactory.getStore(rootId);
        var pm = scopedStoreFactory.getProfileManager(rootId);
        var ss = scopedStoreFactory.getSearchService(rootId);
        if (store != null) {
            knowledgeToolFactory.rebind(store, ss, pm);
        }
        this.activeRootId = rootId;
    }

    /**
     * 初始化：创建目录、注册命令等。
     */
    public void initialize() throws Exception {
    }

    // ---- Getters ----

    public AppConfig getConfig() { return config; }
    public TimeProvider getTimeProvider() { return timeProvider; }
    public LlmManager getLlmManager() { return llmManager; }
    public ToolRegistry getToolRegistry() { return toolRegistry; }
    public InteractionContext getInteractionContext() { return interactionContext; }
    public InteractionSession getInteractionSession() { return interactionSession; }
    public InteractionManager getInteractionManager() { return interactionManager; }
    public EventBus getEventBus() { return eventBus; }
    public ConsoleEventSink getConsoleEventSink() { return consoleEventSink; }
    public ApiManager getApiManager() { return apiManager; }

    public String getActiveRootId() { return activeRootId; }
    public void setActiveRootId(String rootId) { this.activeRootId = rootId; }

    /** 设置 root 就绪回调（bootstrap/root create/switch 后触发）。 */
    public void setOnRootReadyCallback(Runnable callback) { this.onRootReadyCallback = callback; }

    /** 触发 root 就绪回调。 */
    public void fireOnRootReady() {
        if (onRootReadyCallback != null) {
            onRootReadyCallback.run();
        }
    }

    public SessionManager getSessionManager() {
        return apiManager != null ? apiManager.getSessionManager() : null;
    }

    // ---- Knowledge 系统（root-scoped） ----

    /** 获取指定 root 的 store。 */
    public SQLiteKnowledgeStore getKnowledgeStore(String rootId) {
        if (rootId == null) return null;
        return scopedStoreFactory.getStore(rootId);
    }

    /** 获取指定 root 的 profile manager。 */
    public EmbeddingProfileManager getEmbeddingProfileManager(String rootId) {
        if (rootId == null) return null;
        return scopedStoreFactory.getProfileManager(rootId);
    }

    /** 获取指定 root 的 search service。 */
    public KnowledgeSearchService getKnowledgeSearchService(String rootId) {
        if (rootId == null) return null;
        return scopedStoreFactory.getSearchService(rootId);
    }

    public ScopedKnowledgeStoreFactory getScopedStoreFactory() { return scopedStoreFactory; }
    public EmbeddingModel getEmbeddingModel() { return embeddingModel; }

    /** 获取 KnowledgeToolFactory（用于注入 branch context suppliers）。 */
    public KnowledgeToolFactory getKnowledgeToolFactory() { return knowledgeToolFactory; }

    /**
     * 关闭所有资源：LLM client、embedding model、knowledge stores、event bus、API server。
     */
    public void shutdown() {
        if (llmManager != null) {
            llmManager.close();
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
