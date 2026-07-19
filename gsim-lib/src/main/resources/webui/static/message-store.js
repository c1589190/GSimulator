// GSimulator WebUI — MessageStore
// 消息数据层：管理所有消息的添加、查询、更新和生命周期。
// 支持 localStorage 持久化，刷新页面不丢消息。
//
// 每条 Message：{ msgId, role, type, content, toolName, createdAt, status, domRef }
//   msgId    — 唯一标识（后端 "m0001" 或前端临时 "a1734567890123"）
//   role     — "user" | "assistant" | "tool" | "system"
//   type     — "chat_user" | "chat_assistant" | "tool_call" | "tool_result" | "error"
//   content  — 消息文本内容
//   toolName — tool 类型消息的工具名称（可选）
//   createdAt — ISO 时间字符串
//   status   — "pending" | "streaming" | "complete" | "error"
//   domRef   — DOM 元素引用（ChatRenderer 管理，不持久化）

(function() {
    'use strict';

    var LS_KEY_PREFIX = 'gsim-msgs-';
    var MAX_STORED = 200;  // localStorage 最多保留条数

    var messages = [];
    var messagesById = {};
    var activeAsstId = null;
    var currentBranchId = 'default';
    var dirty = false;

    // ---- localStorage key ----

    function lsKey() {
        return LS_KEY_PREFIX + currentBranchId;
    }

    // ---- 序列化 / 反序列化 ----

    function toStorable(msg) {
        // 不存 domRef（DOM 引用不可序列化）
        return {
            msgId: msg.msgId,
            role: msg.role,
            type: msg.type,
            content: msg.content,
            toolName: msg.toolName || null,
            createdAt: msg.createdAt,
            status: msg.status || 'complete',
            reasoning: msg.reasoning || ''
        };
    }

    function fromStorable(obj) {
        return {
            msgId: obj.msgId,
            role: obj.role,
            type: obj.type,
            content: obj.content || '',
            toolName: obj.toolName || null,
            createdAt: obj.createdAt || new Date().toISOString(),
            status: obj.status || 'complete',
            reasoning: obj.reasoning || '',
            domRef: null
        };
    }

    // ---- 持久化 ----

    function saveToLocal() {
        try {
            var toSave = messages.slice(-MAX_STORED);
            var json = JSON.stringify(toSave.map(toStorable));
            localStorage.setItem(lsKey(), json);
            dirty = false;
        } catch (e) {
            console.warn('[MessageStore] localStorage save failed:', e.message);
        }
    }

    function loadFromLocal() {
        try {
            var raw = localStorage.getItem(lsKey());
            if (!raw) return [];
            var arr = JSON.parse(raw);
            return arr.map(fromStorable);
        } catch (e) {
            console.warn('[MessageStore] localStorage load failed:', e.message);
            return [];
        }
    }

    // 延迟批量写（避免高频写入）
    var saveTimer = null;
    function scheduleSave() {
        dirty = true;
        if (saveTimer) return;
        saveTimer = setTimeout(function() {
            saveTimer = null;
            if (dirty) saveToLocal();
        }, 200);
    }

    function saveNow() {
        if (saveTimer) { clearTimeout(saveTimer); saveTimer = null; }
        saveToLocal();
    }

    // ---- API ----

    function add(msg) {
        if (!msg.msgId) {
            msg.msgId = generateId();
        }
        msg.status = msg.status || 'pending';
        msg.createdAt = msg.createdAt || new Date().toISOString();
        messages.push(msg);
        messagesById[msg.msgId] = msg;
        scheduleSave();
        return msg;
    }

    function get(msgId) {
        return messagesById[msgId];
    }

    function getAll() {
        return messages.slice();
    }

    function update(msgId, patch) {
        var msg = messagesById[msgId];
        if (!msg) return undefined;
        var keys = Object.keys(patch);
        for (var i = 0; i < keys.length; i++) {
            msg[keys[i]] = patch[keys[i]];
        }
        scheduleSave();
        return msg;
    }

    /**
     * 从后端 JSON 加载消息，与 localStorage 合并。
     * - 后端有且 status=complete → 以后端为准
     * - 后端没有但 localStorage 有 → 保留（in-progress / cancelled / error）
     * - 后端有但 localStorage 没有 → 加入
     */
    function loadFromJson(jsonArray) {
        // 1. 保存当前 localStorage 中的非完成消息
        var localMsgs = loadFromLocal();
        var localById = {};
        for (var i = 0; i < localMsgs.length; i++) {
            localById[localMsgs[i].msgId] = localMsgs[i];
        }

        // 2. 清空内存
        messages = [];
        messagesById = {};

        // 3. 先加入后端消息（complete 状态）
        var backendIds = {};
        if (jsonArray && jsonArray.length) {
            for (var j = 0; j < jsonArray.length; j++) {
                var item = jsonArray[j];
                var msg = {
                    msgId: item.id,
                    role: item.role,
                    type: item.type,
                    content: item.content,
                    toolName: item.toolName || null,
                    createdAt: item.createdAt,
                    status: 'complete',
                    domRef: null
                };
                messages.push(msg);
                messagesById[msg.msgId] = msg;
                backendIds[msg.msgId] = true;
            }
        }

        // 4. 加入 localStorage 中后端没有的消息（in-progress / cancelled）
        //    且只保留"非完成"状态的消息（complete 的以后端为准）
        for (var k = 0; k < localMsgs.length; k++) {
            var lm = localMsgs[k];
            if (backendIds[lm.msgId]) continue;  // 后端已有，跳过
            if (lm.status === 'complete') continue;  // 完成后端没收录 = 孤儿，跳过
            // 保留：pending / streaming / error / cancelled
            var clone = fromStorable(lm);
            clone.domRef = null;
            messages.push(clone);
            messagesById[clone.msgId] = clone;
        }

        // 5. 持久化合并后的结果
        saveNow();
    }

    /**
     * 从 localStorage 恢复（页面刷新时使用，不依赖后端）。
     * @returns {boolean} 是否恢复了消息
     */
    function restoreFromLocal() {
        var localMsgs = loadFromLocal();
        if (!localMsgs.length) return false;

        messages = [];
        messagesById = {};
        for (var i = 0; i < localMsgs.length; i++) {
            var m = localMsgs[i];
            m.domRef = null;  // DOM 引用需要重建
            messages.push(m);
            messagesById[m.msgId] = m;
        }
        return true;
    }

    function setActiveAsstId(msgId) { activeAsstId = msgId; }
    function getActiveAsstId() { return activeAsstId; }

    function setBranchId(branchId) {
        if (branchId && branchId !== currentBranchId) {
            saveNow();  // 保存当前分支的消息
            currentBranchId = branchId;
            // 不自动加载 — 由调用方决定
        }
    }

    function clear() {
        messages = [];
        messagesById = {};
        activeAsstId = null;
        saveNow();
    }

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
        restoreFromLocal: restoreFromLocal,
        setActiveAsstId: setActiveAsstId,
        getActiveAsstId: getActiveAsstId,
        setBranchId: setBranchId,
        clear: clear,
        generateId: generateId,
        saveNow: saveNow
    };

    console.log('[MessageStore] initialized with localStorage persistence');
})();
