// GSimulator WebUI — SessionWs
// WebSocket 客户端，通过 Chat JSON 协议连接后端 SessionPool。
//
// 连接: ws://host:8712/chat?sessionId=default
// 协议: JSON 帧 {event, ...}
//
// 事件:
//   history               — 连接时回放已有节点
//   streamingState        — 连接时当前流式节点（抗刷新丢失）
//   nodePushed            — 新节点 (node: SessionNode JSON)
//   nodeUpdated           — 节点更新 (nodeId, key, value)
//   nodeStatusChanged     — 状态变更 (nodeId, oldStatus, newStatus)

(function() {
    'use strict';

    window.SessionWs = function(sessionId) {
        this.sessionId = sessionId || 'default';
        this.ws = null;
        this.reconnectDelay = 3000;
        this.reconnectTimer = null;
        this._intentionalClose = false;

        // 回调
        this._onNodePushed = null;
        this._onNodeUpdated = null;
        this._onStatusChanged = null;
        this._onStreamingState = null;
        this._onHistory = null;
        this._onConnected = null;
        this._onDisconnected = null;
    };

    SessionWs.prototype.connect = function() {
        var self = this;
        var proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        var host = window.location.hostname;
        var url = proto + '//' + host + ':8712/chat?sessionId=' + encodeURIComponent(this.sessionId);

        console.log('[SessionWs] Connecting to', url);
        try {
            this.ws = new WebSocket(url);
        } catch (e) {
            console.error('[SessionWs] WebSocket constructor failed:', e.message);
            this._scheduleReconnect();
            return;
        }

        this.ws.onopen = function() {
            console.log('[SessionWs] Connected');
            if (self._onConnected) self._onConnected();
        };

        this.ws.onmessage = function(e) {
            var msg;
            try { msg = JSON.parse(e.data); } catch (err) {
                console.warn('[SessionWs] Invalid JSON:', e.data.substring(0, 100));
                return;
            }
            switch (msg.event) {
                case 'history':
                    if (self._onHistory) self._onHistory(msg.nodes || []);
                    break;
                case 'nodePushed':
                    if (self._onNodePushed) self._onNodePushed(msg.node);
                    break;
                case 'nodeUpdated':
                    if (self._onNodeUpdated) self._onNodeUpdated(msg.nodeId, msg.key, msg.value);
                    break;
                case 'nodeStatusChanged':
                    if (self._onStatusChanged) self._onStatusChanged(msg.nodeId, msg.oldStatus, msg.newStatus);
                    break;
                case 'streamingState':
                    if (self._onStreamingState) self._onStreamingState(msg.node);
                    break;
                case 'pong':
                    break;
                default:
                    console.log('[SessionWs] Unknown event:', msg.event);
            }
        };

        this.ws.onclose = function() {
            console.log('[SessionWs] Disconnected');
            if (self._onDisconnected) self._onDisconnected();
            if (!self._intentionalClose) self._scheduleReconnect();
        };

        this.ws.onerror = function(err) {
            console.warn('[SessionWs] Error:', err);
        };
    };

    SessionWs.prototype._scheduleReconnect = function() {
        var self = this;
        if (this.reconnectTimer) return;
        console.log('[SessionWs] Reconnecting in', this.reconnectDelay, 'ms');
        this.reconnectTimer = setTimeout(function() {
            self.reconnectTimer = null;
            self.connect();
        }, this.reconnectDelay);
    };

    SessionWs.prototype.close = function() {
        this._intentionalClose = true;
        if (this.reconnectTimer) {
            clearTimeout(this.reconnectTimer);
            this.reconnectTimer = null;
        }
        if (this.ws) {
            this.ws.close();
            this.ws = null;
        }
    };

    /** 发送用户消息到后端（通过 POST /chat/send，不通过 WebSocket）。 */
    SessionWs.prototype.send = function(message) {
        return fetch('/chat/send', {
            method: 'POST',
            headers: {'Content-Type': 'application/x-www-form-urlencoded'},
            body: 'message=' + encodeURIComponent(message)
        }).then(function(r) { return r.json(); });
    };

    // ── 回调注册 ──

    SessionWs.prototype.onNodePushed = function(fn) { this._onNodePushed = fn; };
    SessionWs.prototype.onNodeUpdated = function(fn) { this._onNodeUpdated = fn; };
    SessionWs.prototype.onStatusChanged = function(fn) { this._onStatusChanged = fn; };
    SessionWs.prototype.onStreamingState = function(fn) { this._onStreamingState = fn; };
    SessionWs.prototype.onHistory = function(fn) { this._onHistory = fn; };
    SessionWs.prototype.onConnected = function(fn) { this._onConnected = fn; };
    SessionWs.prototype.onDisconnected = function(fn) { this._onDisconnected = fn; };
})();
