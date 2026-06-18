package com.gsim.api.handlers;

import com.gsim.api.ApiResponse;
import com.gsim.api.JsonBodyParser;
import com.gsim.api.dto.CreateActionRequest;
import com.gsim.api.dto.CreateCampaignRequest;
import com.gsim.api.dto.CreateTurnRequest;
import com.gsim.app.ApplicationContext;
import com.gsim.campaign.Campaign;
import com.gsim.campaign.CampaignService;
import com.gsim.campaign.PlayerAction;
import com.gsim.campaign.PlayerActionService;
import com.gsim.campaign.Turn;
import com.gsim.campaign.TurnService;
import com.gsim.event.EventBus;
import com.gsim.event.GSimEvent;
import com.gsim.util.TimeProvider;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Campaign / Turn / PlayerAction CRUD API handler。
 *
 * <p>路由：
 * <ul>
 *   <li>GET    /api/campaigns</li>
 *   <li>POST   /api/campaigns</li>
 *   <li>GET    /api/campaigns/{id}</li>
 *   <li>POST   /api/campaigns/{id}/load</li>
 *   <li>GET    /api/campaigns/{id}/turns</li>
 *   <li>POST   /api/campaigns/{id}/turns</li>
 *   <li>GET    /api/campaigns/{id}/turns/{turnId}</li>
 *   <li>POST   /api/campaigns/{id}/turns/{turnId}/activate</li>
 *   <li>GET    /api/campaigns/{id}/turns/{turnId}/actions</li>
 *   <li>POST   /api/campaigns/{id}/turns/{turnId}/actions</li>
 *   <li>DELETE /api/campaigns/{id}/turns/{turnId}/actions</li>
 * </ul>
 */
public class CampaignsApiHandler implements HttpHandler {

    private static final String PREFIX = "/api/campaigns";

    private final ApplicationContext ctx;
    private final EventBus eventBus;

    public CampaignsApiHandler(ApplicationContext ctx, EventBus eventBus) {
        this.ctx = ctx;
        this.eventBus = eventBus;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase();
        String[] segs = BaseApiHandler.pathSegments(exchange, PREFIX);

        try {
            if (segs.length == 0) {
                // /api/campaigns
                switch (method) {
                    case "GET" -> handleListCampaigns(exchange);
                    case "POST" -> handleCreateCampaign(exchange);
                    default -> BaseApiHandler.sendError(exchange, 405, "Method not allowed");
                }
            } else if (segs.length == 1) {
                // /api/campaigns/{id}
                String campaignId = segs[0];
                switch (method) {
                    case "GET" -> handleGetCampaign(exchange, campaignId);
                    default -> BaseApiHandler.sendError(exchange, 405, "Method not allowed");
                }
            } else if (segs.length == 2 && "load".equals(segs[1])) {
                // /api/campaigns/{id}/load
                if ("POST".equals(method)) handleLoadCampaign(exchange, segs[0]);
                else BaseApiHandler.sendError(exchange, 405, "Method not allowed");
            } else if (segs.length == 2 && "turns".equals(segs[1])) {
                // /api/campaigns/{id}/turns
                String campaignId = segs[0];
                switch (method) {
                    case "GET" -> handleListTurns(exchange, campaignId);
                    case "POST" -> handleCreateTurn(exchange, campaignId);
                    default -> BaseApiHandler.sendError(exchange, 405, "Method not allowed");
                }
            } else if (segs.length == 3 && "turns".equals(segs[1])) {
                // /api/campaigns/{id}/turns/{turnId}
                String campaignId = segs[0];
                String turnId = segs[2];
                if ("GET".equals(method)) {
                    handleGetTurn(exchange, campaignId, turnId);
                } else {
                    BaseApiHandler.sendError(exchange, 405, "Method not allowed");
                }
            } else if (segs.length == 4 && "turns".equals(segs[1]) && "activate".equals(segs[3])) {
                // /api/campaigns/{id}/turns/{turnId}/activate
                if ("POST".equals(method)) handleActivateTurn(exchange, segs[0], segs[2]);
                else BaseApiHandler.sendError(exchange, 405, "Method not allowed");
            } else if (segs.length == 4 && "turns".equals(segs[1]) && "actions".equals(segs[3])) {
                // /api/campaigns/{id}/turns/{turnId}/actions
                String campaignId = segs[0];
                String turnId = segs[2];
                switch (method) {
                    case "GET" -> handleListActions(exchange, campaignId, turnId);
                    case "POST" -> handleAddAction(exchange, campaignId, turnId);
                    case "DELETE" -> handleClearActions(exchange, campaignId, turnId);
                    default -> BaseApiHandler.sendError(exchange, 405, "Method not allowed");
                }
            } else {
                BaseApiHandler.sendNotFound(exchange, "Unknown campaigns endpoint");
            }
        } catch (Exception e) {
            BaseApiHandler.sendError(exchange, 500, "Internal error: " + e.getMessage());
        }
    }

    // ---- Campaign ----

    private void handleListCampaigns(HttpExchange exchange) throws IOException {
        Campaign current = ctx.getCampaignService().getCurrentCampaign().orElse(null);
        List<Map<String, Object>> list = new ArrayList<>();
        if (current != null) {
            list.add(campaignToMap(current));
        }
        BaseApiHandler.sendOk(exchange, "Campaigns retrieved", Map.of("campaigns", list));
    }

    private void handleCreateCampaign(HttpExchange exchange) throws IOException {
        String body = BaseApiHandler.readBody(exchange);
        CreateCampaignRequest req = JsonBodyParser.parse(body, CreateCampaignRequest.class);
        var cs = ctx.getCampaignService();
        // 尝试加载，如果不存在则使用 getOrCreateDefault
        var loaded = cs.load(req.name());
        Campaign c;
        if (loaded.isPresent()) {
            c = loaded.get();
        } else {
            c = cs.getOrCreateDefault();
        }
        BaseApiHandler.sendOk(exchange, "Campaign created", Map.of("campaign", campaignToMap(c)));
    }

    private void handleGetCampaign(HttpExchange exchange, String campaignId) throws IOException {
        var cs = ctx.getCampaignService();
        var loaded = cs.load(campaignId);
        if (loaded.isPresent()) {
            BaseApiHandler.sendOk(exchange, "Campaign found", Map.of("campaign", campaignToMap(loaded.get())));
        } else {
            BaseApiHandler.sendNotFound(exchange, "Campaign not found: " + campaignId);
        }
    }

    private void handleLoadCampaign(HttpExchange exchange, String campaignId) throws IOException {
        var cs = ctx.getCampaignService();
        var loaded = cs.load(campaignId);
        if (loaded.isPresent()) {
            ctx.getInteractionContext().setCurrentCampaignId(campaignId);
            eventBus.publish(GSimEvent.of("api", "log", Map.of("message", "Loaded campaign: " + campaignId)));
            BaseApiHandler.sendOk(exchange, "Campaign loaded", Map.of("campaign", campaignToMap(loaded.get())));
        } else {
            BaseApiHandler.sendNotFound(exchange, "Campaign not found: " + campaignId);
        }
    }

    // ---- Turn ----

    private void handleListTurns(HttpExchange exchange, String campaignId) throws IOException {
        Turn current = ctx.getTurnService().getCurrentTurn().orElse(null);
        List<Map<String, Object>> list = new ArrayList<>();
        if (current != null) {
            list.add(turnToMap(current));
        }
        BaseApiHandler.sendOk(exchange, "Turns retrieved", Map.of("turns", list));
    }

    private void handleCreateTurn(HttpExchange exchange, String campaignId) throws IOException {
        TurnService ts = ctx.getTurnService();
        Turn turn = ts.createNext(campaignId);
        BaseApiHandler.sendOk(exchange, "Turn created", Map.of("turn", turnToMap(turn)));
    }

    private void handleGetTurn(HttpExchange exchange, String campaignId, String turnId) throws IOException {
        var t = ctx.getTurnService().load(campaignId, turnId);
        if (t.isPresent()) {
            BaseApiHandler.sendOk(exchange, "Turn found", Map.of("turn", turnToMap(t.get())));
        } else {
            BaseApiHandler.sendNotFound(exchange, "Turn not found: " + turnId);
        }
    }

    private void handleActivateTurn(HttpExchange exchange, String campaignId, String turnId) throws IOException {
        ctx.getInteractionContext().setCurrentTurnId(turnId);
        ctx.getInteractionContext().setCurrentCampaignId(campaignId);
        var t = ctx.getTurnService().load(campaignId, turnId);
        if (t.isEmpty()) {
            BaseApiHandler.sendNotFound(exchange, "Turn not found: " + turnId);
            return;
        }
        ctx.getPlayerActionService().loadActions(campaignId, turnId);
        eventBus.publish(GSimEvent.of("api", "log", Map.of("message", "Activated turn: " + turnId)));
        BaseApiHandler.sendOk(exchange, "Turn activated", Map.of("turnId", turnId));
    }

    // ---- Player Actions ----

    private void handleListActions(HttpExchange exchange, String campaignId, String turnId) throws IOException {
        PlayerActionService pas = ctx.getPlayerActionService();
        pas.loadActions(campaignId, turnId);
        List<PlayerAction> actions = pas.getActions();
        List<Map<String, Object>> list = new ArrayList<>();
        for (PlayerAction a : actions) {
            list.add(actionToMap(a));
        }
        BaseApiHandler.sendOk(exchange, "Actions retrieved",
                Map.of("actions", list, "count", actions.size()));
    }

    private void handleAddAction(HttpExchange exchange, String campaignId, String turnId) throws IOException {
        String body = BaseApiHandler.readBody(exchange);
        CreateActionRequest req = JsonBodyParser.parse(body, CreateActionRequest.class);
        PlayerAction action = ctx.getPlayerActionService().addAction(
                campaignId, turnId, req.playerName(), req.content());
        eventBus.publish(GSimEvent.of("api", "log",
                Map.of("message", "Player action added: " + req.playerName())));
        BaseApiHandler.sendOk(exchange, "Action added", Map.of("action", actionToMap(action)));
    }

    private void handleClearActions(HttpExchange exchange, String campaignId, String turnId) throws IOException {
        ctx.getPlayerActionService().clearActions();
        BaseApiHandler.sendOk(exchange, "Actions cleared");
    }

    // ---- Serialization helpers ----

    static Map<String, Object> campaignToMap(Campaign c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("campaignId", c.campaignId());
        m.put("name", c.name());
        m.put("currentTurnId", c.currentTurnId());
        m.put("turnIds", c.turnIds());
        m.put("createdAt", c.createdAt() != null ? c.createdAt().toString() : null);
        return m;
    }

    static Map<String, Object> turnToMap(Turn t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("campaignId", t.campaignId());
        m.put("turnId", t.turnId());
        m.put("index", t.index());
        m.put("status", t.status().name());
        m.put("createdAt", t.createdAt() != null ? t.createdAt().toString() : null);
        m.put("resolvedAt", t.resolvedAt() != null ? t.resolvedAt().toString() : null);
        return m;
    }

    static Map<String, Object> actionToMap(PlayerAction a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.id());
        m.put("campaignId", a.campaignId());
        m.put("turnId", a.turnId());
        m.put("playerName", a.playerName());
        m.put("content", a.content());
        m.put("createdAt", a.createdAt() != null ? a.createdAt().toString() : null);
        m.put("tags", a.tags());
        return m;
    }
}
