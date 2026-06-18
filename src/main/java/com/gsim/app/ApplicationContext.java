package com.gsim.app;

import com.gsim.api.ApiConfig;
import com.gsim.api.ApiManager;
import com.gsim.campaign.CampaignService;
import com.gsim.campaign.TurnService;
import com.gsim.campaign.PlayerActionService;
import com.gsim.event.EventBus;
import com.gsim.event.ConsoleEventSink;
import com.gsim.interaction.InteractionContext;
import com.gsim.interaction.InteractionManager;
import com.gsim.interaction.InteractionSession;
import com.gsim.llm.LlmClient;
import com.gsim.llm.OpenAiLlmClient;
import com.gsim.storage.DataPaths;
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

    /**
     * 关闭事件系统。
     */
    public void shutdown() {
        eventBus.shutdown();
        apiManager.stop();
    }
}
