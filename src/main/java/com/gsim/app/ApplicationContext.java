package com.gsim.app;

import com.gsim.api.ApiConfig;
import com.gsim.api.ApiManager;
import com.gsim.api.SessionManager;
import com.gsim.event.EventBus;
import com.gsim.event.ConsoleEventSink;
import com.gsim.interaction.InteractionContext;
import com.gsim.interaction.InteractionManager;
import com.gsim.interaction.InteractionSession;
import com.gsim.cache.CachesManager;
import com.gsim.cache.FileSystemCachesManager;
import com.gsim.llm.LlmConfig;
import com.gsim.llm.LlmManager;
import com.gsim.llm.LlmProviderRegistry;
import com.gsim.llm.LlmsConfigLoader;
import com.gsim.llm.ProviderConfig;
import com.gsim.session.SessionPool;
import com.gsim.commands.ChatCommand;
import com.gsim.commands.NodeCommand;
import com.gsim.commands.WorldCommand;
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

    private final LlmProviderRegistry llmProviderRegistry;
    private final LlmManager llmManager;
    private final CachesManager cachesManager;
    private final ToolRegistry toolRegistry;
    private final LocalFileSearchService localFileSearchService;
    private final InteractionContext interactionContext;
    private final InteractionSession interactionSession;
    private final InteractionManager interactionManager;

    private final EventBus eventBus;
    private final ConsoleEventSink consoleEventSink;
    private final SessionPool sessionPool;
    private final ApiManager apiManager;

    // Root 就绪回调（bootstrap 完成后触发 memory tools 重注册等）
    private Runnable onRootReadyCallback;
    private String activeRootId;

    // Command instances (injected by GSimulatorApplication)
    private ChatCommand chatCommand;
    private WorldCommand worldCommand;
    private NodeCommand nodeCommand;

    public ApplicationContext(AppConfig config) {
        this.config = config;
        this.timeProvider = new TimeProvider();

        // LLM — 从 llms.json 加载所有 provider
        LlmsConfigLoader llmsLoader = new LlmsConfigLoader(config.getLlmsPath());
        LlmsConfigLoader.LoadResult llmsResult = llmsLoader.load();
        this.llmProviderRegistry = LlmProviderRegistry.fromConfig(llmsResult.file());

        // 保留 llmManager 引用指向默认 provider（向后兼容）
        this.llmManager = (LlmManager) llmProviderRegistry.getDefault();

        // Cache 管理器
        this.cachesManager = new FileSystemCachesManager(config.worldsDir());

        if (llmsResult.wasNewlyCreated()) {
            System.out.println();
            System.out.println("📋 LLM 配置初始化");
            System.out.println("   在 " + llmsLoader.getLlmsPath() + " 创建了默认 LLM provider 模板。");
            System.out.println("   你可以编辑该文件添加更多 provider。");
            System.out.println();
            System.out.println(LlmsConfigLoader.formatProviderList(llmsResult.file()));
        }

        // Tool 系统
        this.toolRegistry = new ToolRegistry();
        Path wikiDir = config.getImportDir().resolve("web").resolve("prts.wiki");
        this.localFileSearchService = new LocalFileSearchService(wikiDir);
        this.toolRegistry.register(new WikiSearchTool(localFileSearchService));

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

        // 会话节点池（统一交互通道，逐步替代 EventBus）
        this.sessionPool = new SessionPool();

        // HTTP API
        ApiConfig apiConfig = new ApiConfig(
                config.getApiHost(), config.getApiPort(), config.isApiEnabled());
        this.apiManager = new ApiManager(apiConfig, this, eventBus);
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
    public LlmProviderRegistry getLlmProviderRegistry() { return llmProviderRegistry; }
    public CachesManager getCachesManager() { return cachesManager; }
    public ToolRegistry getToolRegistry() { return toolRegistry; }
    public InteractionContext getInteractionContext() { return interactionContext; }
    public InteractionSession getInteractionSession() { return interactionSession; }
    public InteractionManager getInteractionManager() { return interactionManager; }
    public EventBus getEventBus() { return eventBus; }
    public ConsoleEventSink getConsoleEventSink() { return consoleEventSink; }
    public SessionPool getSessionPool() { return sessionPool; }
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

    // ---- Command accessors (for WebUI handlers) ----

    public ChatCommand getChatCommand() { return chatCommand; }
    public void setChatCommand(ChatCommand cc) { this.chatCommand = cc; }

    public WorldCommand getWorldCommand() { return worldCommand; }
    public void setWorldCommand(WorldCommand wc) { this.worldCommand = wc; }

    public NodeCommand getNodeCommand() { return nodeCommand; }
    public void setNodeCommand(NodeCommand nc) { this.nodeCommand = nc; }

    public LocalFileSearchService getLocalFileSearchService() { return localFileSearchService; }

    public Path getWorldsDir() { return config.worldsDir(); }

    /**
     * 关闭所有资源：LLM client、event bus、API server。
     */
    public void shutdown() {
        if (llmProviderRegistry != null) {
            llmProviderRegistry.closeAll();
        }
        eventBus.shutdown();
        apiManager.stop();
    }
}
