// GSimulator WebUI — MessageStore
// 消息数据层：管理所有消息的添加、查询、更新和生命周期
//
// 每条 Message：{ msgId, role, type, content, toolName, createdAt, status, domRef }
//   msgId    — 唯一标识（后端 "m0001" 或前端临时 "a1734567890123"）
//   role     — "user" | "assistant" | "tool" | "system"
//   type     — "chat_user" | "chat_assistant" | "tool_call" | "tool_result" | "error"
//   content  — 消息文本内容
//   toolName — tool 类型消息的工具名称（可选）
//   createdAt — ISO 时间字符串
//   status   — "pending" | "streaming" | "complete" | "error"
//   domRef   — DOM 元素引用（ChatRenderer 管理）

(function() {
    'use strict';

    var messages = [];        // 有序消息数组
    var messagesById = {};    // msgId -> Message 快速查找
    var activeAsstId = null;  // 当前正在流式输出的 assistant msgId

    /**
     * 添加一条消息到 Store。
     * @param {Object} msg — 消息对象
     * @returns {Object} 添加的消息
     */
    function add(msg) {
        if (!msg.msgId) {
            msg.msgId = generateId();
        }
        msg.status = msg.status || 'pending';
        msg.createdAt = msg.createdAt || new Date().toISOString();
        messages.push(msg);
        messagesById[msg.msgId] = msg;
        return msg;
    }

    /**
     * 按 msgId 获取消息。
     * @param {string} msgId
     * @returns {Object|undefined}
     */
    function get(msgId) {
        return messagesById[msgId];
    }

    /**
     * 获取全部消息（有序）。
     * @returns {Object[]}
     */
    function getAll() {
        return messages.slice();
    }

    /**
     * 更新消息的部分字段。
     * @param {string} msgId
     * @param {Object} patch — 要合并的字段
     * @returns {Object|undefined} 更新后的消息
     */
    function update(msgId, patch) {
        var msg = messagesById[msgId];
        if (!msg) return undefined;
        var keys = Object.keys(patch);
        for (var i = 0; i < keys.length; i++) {
            msg[keys[i]] = patch[keys[i]];
        }
        return msg;
    }

    /**
     * 从后端 JSON 数组批量加载消息（用于初始化时加载历史）。
     * 清除已有消息，替换为后端数据。
     * @param {Object[]} jsonArray — 后端 /chat/messages?format=json 返回的 messages 数组
     */
    function loadFromJson(jsonArray) {
        clear();
        if (!jsonArray || !jsonArray.length) return;
        for (var i = 0; i < jsonArray.length; i++) {
            var item = jsonArray[i];
            var msg = {
                msgId: item.id,
                role: item.role,
                type: item.type,
                content: item.content,
                toolName: item.toolName || null,
                createdAt: item.createdAt,
                status: 'complete',   // 后端返回的都是已完成的消息
                domRef: null
            };
            messages.push(msg);
            messagesById[msg.msgId] = msg;
        }
    }

    /**
     * 设置当前活跃的 assistant 消息 ID。
     */
    function setActiveAsstId(msgId) {
        activeAsstId = msgId;
    }

    /**
     * 获取当前活跃的 assistant 消息 ID。
     */
    function getActiveAsstId() {
        return activeAsstId;
    }

    /**
     * 清空所有消息。
     */
    function clear() {
        messages = [];
        messagesById = {};
        activeAsstId = null;
    }

    /**
     * 生成临时 ID（前端创建 assistant placeholder 时使用）。
     */
    function generateId() {
        return 'a' + Date.now() + '-' + Math.random().toString(36).substring(2, 6);
    }

    // 导出到全局
    window.MessageStore = {
        add: add,
        get: get,
        getAll: getAll,
        update: update,
        loadFromJson: loadFromJson,
        setActiveAsstId: setActiveAsstId,
        getActiveAsstId: getActiveAsstId,
        clear: clear,
        generateId: generateId
    };

    console.log('[MessageStore] initialized');
})();
