// GSimulator WebUI — ChatRenderer
// 消息渲染层：负责将 MessageStore 中的消息渲染为 DOM 元素，
// 并在流式输出过程中实时更新单个消息模块。
//
// 所有 DOM 操作集中在此模块，app.js 不直接操作 DOM 结构。

(function() {
    'use strict';

    var MSG_CONTAINER_ID = 'chat-messages';
    var EMPTY_STATE_ID = 'chat-empty-state';

    // ---- 内部工具 ----

    function escapeHtml(s) {
        if (!s) return '';
        return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
                .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    function scrollToBottom() {
        var container = document.getElementById(MSG_CONTAINER_ID);
        if (container) {
            container.scrollTop = container.scrollHeight;
        }
    }

    /**
     * HTML 转义，但保留换行 → <br>，保留已经是合法 HTML 的片段（如 tool card）。
     * 用于流式文本追加。
     */
    function textToHtml(text) {
        return escapeHtml(text).replace(/\n/g, '<br>');
    }

    // ---- 空状态管理 ----

    /**
     * 移除空状态占位符（如果存在）。
     */
    function removeEmptyState() {
        var el = document.getElementById(EMPTY_STATE_ID);
        if (el) el.remove();
    }

    // ---- DOM 查找 ----

    /**
     * 根据 msgId 查找消息的 DOM 根元素。
     */
    function findMsgDom(msgId) {
        return document.getElementById('msg-' + msgId);
    }

    // ---- 用户消息渲染 ----

    /**
     * 渲染用户消息并挂到 #chat-messages。
     */
    function renderUserMessage(msg) {
        removeEmptyState();
        var container = document.getElementById(MSG_CONTAINER_ID);
        if (!container) return null;

        var div = document.createElement('div');
        div.id = 'msg-' + msg.msgId;
        div.className = 'msg-user rounded p-2 text-sm mb-1';

        var label = document.createElement('span');
        label.className = 'text-xs text-gray-500';
        label.textContent = 'You';

        var content = document.createElement('div');
        content.textContent = msg.content || '';

        div.appendChild(label);
        div.appendChild(content);
        container.appendChild(div);
        msg.domRef = div;
        scrollToBottom();
        return div;
    }

    // ---- Assistant 消息渲染 ----

    /**
     * 渲染 assistant 消息（含 think / content / tools 子元素）。
     * 用于初始化加载历史，以及创建流式占位符。
     */
    function renderAssistantMessage(msg) {
        removeEmptyState();
        var container = document.getElementById(MSG_CONTAINER_ID);
        if (!container) return null;

        var div = document.createElement('div');
        div.id = 'msg-' + msg.msgId;
        div.className = 'msg-assistant rounded p-2 text-sm mb-1';

        // Think 指示器
        var think = document.createElement('div');
        think.className = 'think text-gray-500 text-xs';
        if (msg.status === 'streaming') {
            think.innerHTML = '<span class="text-green-400">●</span> 生成中...';
        } else if (msg.status === 'pending') {
            think.innerHTML = '<span class="text-yellow-400">⏳</span> 正在生成...';
        } else if (msg.status === 'error') {
            think.innerHTML = '<span class="text-red-400">✖</span> 出错了';
        } else {
            think.classList.add('hidden');
        }

        // Content 区域
        var content = document.createElement('div');
        content.className = 'content';
        if (msg.content) {
            content.innerHTML = textToHtml(msg.content);
        }

        // Tools 区域
        var tools = document.createElement('div');
        tools.className = 'tools';

        div.appendChild(think);
        div.appendChild(content);
        div.appendChild(tools);
        container.appendChild(div);
        msg.domRef = div;
        scrollToBottom();
        return div;
    }

    // ---- 根据 role 分发渲染 ----

    /**
     * 根据消息 role 和 type 渲染到 DOM。
     * @param {Object} msg — MessageStore 中的消息
     * @returns {HTMLElement|null}
     */
    function renderMessage(msg) {
        if (msg.role === 'user') {
            return renderUserMessage(msg);
        } else if (msg.role === 'assistant' || msg.role === 'tool' || msg.role === 'system') {
            return renderAssistantMessage(msg);
        } else {
            // 默认按 assistant 处理
            return renderAssistantMessage(msg);
        }
    }

    /**
     * 将所有 MessageStore 中的消息批量渲染到 DOM。
     * 用于初始化时从 JSON API 加载历史。
     */
    function renderAll(messages) {
        for (var i = 0; i < messages.length; i++) {
            renderMessage(messages[i]);
        }
    }

    // ---- 流式更新 ----

    /**
     * 更新 assistant 消息的 think 指示器。
     * @param {string} msgId
     * @param {string} html — 内部 HTML
     */
    function updateThink(msgId, html) {
        var div = findMsgDom(msgId);
        if (!div) return;
        var think = div.querySelector('.think');
        if (think) {
            think.innerHTML = html;
            think.classList.remove('hidden');
        }
    }

    /**
     * 隐藏 think 指示器（内容开始到达时）。
     */
    function hideThink(msgId) {
        var div = findMsgDom(msgId);
        if (!div) return;
        var think = div.querySelector('.think');
        if (think) think.classList.add('hidden');
    }

    /**
     * 更新 assistant 消息的流式内容（完整替换）。
     * @param {string} msgId
     * @param {string} text — 累积的完整文本
     */
    function updateMessageContent(msgId, text) {
        var div = findMsgDom(msgId);
        if (!div) return;
        var content = div.querySelector('.content');
        if (content) {
            content.innerHTML = textToHtml(text);
        }
        hideThink(msgId);
        scrollToBottom();
    }

    /**
     * 追加推理内容（折叠显示，使用 reasoning 子元素）。
     * @param {string} msgId
     * @param {string} reasoningText — 要追加的推理文本
     */
    function appendReasoning(msgId, reasoningText) {
        var div = findMsgDom(msgId);
        if (!div) return;
        var reasoning = div.querySelector('.reasoning');
        if (!reasoning) {
            reasoning = document.createElement('div');
            reasoning.className = 'reasoning text-gray-500 text-xs italic mt-1 border-l-2 border-gray-600 pl-2';
            var content = div.querySelector('.content');
            if (content) {
                content.before(reasoning);
            } else {
                div.appendChild(reasoning);
            }
        }
        reasoning.textContent += reasoningText;
        scrollToBottom();
    }

    // ---- Tool Card 管理 ----

    /**
     * 添加一个 tool card 到 assistant 消息的 .tools 区域。
     * @param {string} asstMsgId — assistant 消息 ID
     * @param {string} toolName
     * @param {string} status — "streaming" | "running" | "done" | "error"
     * @param {string} toolIndex — 索引，用于后续精确更新
     * @returns {string} toolIndex
     */
    function addToolCard(asstMsgId, toolName, status, toolIndex) {
        var div = findMsgDom(asstMsgId);
        if (!div) return toolIndex;
        var tools = div.querySelector('.tools');
        if (!tools) return toolIndex;

        var icon, label;
        if (status === 'streaming') {
            icon = '<span class="text-blue-400">🔧</span>';
            label = '<span class="text-gray-500">streaming...</span>';
        } else if (status === 'running') {
            icon = '<span class="text-yellow-400">⚙</span>';
            label = '<span class="text-gray-500">running...</span>';
        } else if (status === 'done') {
            icon = '<span class="text-green-400">✓</span>';
            label = '<span class="text-green-400">done</span>';
        } else if (status === 'error') {
            icon = '<span class="text-red-400">✖</span>';
            label = '<span class="text-red-400">error</span>';
        } else {
            icon = '<span class="text-gray-400">🔧</span>';
            label = '<span class="text-gray-500">...</span>';
        }

        var idx = toolIndex;
        var card = document.createElement('div');
        card.className = 'tool-card rounded p-1 text-xs mt-1';
        card.setAttribute('data-tool-idx', idx);
        card.setAttribute('data-tool-name', toolName);
        if (status === 'done') card.classList.add('success');
        if (status === 'error') card.classList.add('error');
        card.innerHTML = icon + ' ' + escapeHtml(toolName) + ' ' + label;
        tools.appendChild(card);
        scrollToBottom();
        return idx;
    }

    /**
     * 更新 tool card 的状态。
     * @param {string} asstMsgId
     * @param {string} toolName — 用于匹配（空字符串匹配任意未完成卡片）
     * @param {string} status — "done" | "error"
     * @param {string} errMsg — 仅 error 时使用
     */
    function updateToolCard(asstMsgId, toolName, status, errMsg) {
        var div = findMsgDom(asstMsgId);
        if (!div) return;
        var tools = div.querySelector('.tools');
        if (!tools) return;

        // 从后向前找第一个未完成的匹配卡片
        var cards = tools.querySelectorAll('.tool-card:not(.success):not(.error)');
        var matched = null;
        for (var i = cards.length - 1; i >= 0; i--) {
            if (!toolName || cards[i].getAttribute('data-tool-name') === toolName) {
                matched = cards[i];
                break;
            }
        }
        if (!matched) return;

        if (status === 'done') {
            matched.classList.add('success');
            matched.querySelector('span:last-child').textContent = 'done';
        } else if (status === 'error') {
            matched.classList.add('error');
            matched.querySelector('span:last-child').textContent = errMsg || 'failed';
        }
    }

    // ---- 状态标记 ----

    /**
     * 标记消息为完成状态。
     */
    function markComplete(msgId) {
        var div = findMsgDom(msgId);
        if (!div) return;
        hideThink(msgId);
    }

    /**
     * 标记消息为错误状态。
     */
    function markError(msgId, err) {
        var div = findMsgDom(msgId);
        if (!div) return;
        updateThink(msgId, '<span class="text-red-400">✖</span> ' + escapeHtml(err || '出错了'));
        var think = div.querySelector('.think');
        if (think) think.classList.remove('hidden');
    }

    // ---- 导出 ----

    window.ChatRenderer = {
        renderMessage: renderMessage,
        renderUserMessage: renderUserMessage,
        renderAssistantMessage: renderAssistantMessage,
        renderAll: renderAll,
        updateThink: updateThink,
        hideThink: hideThink,
        updateMessageContent: updateMessageContent,
        appendReasoning: appendReasoning,
        addToolCard: addToolCard,
        updateToolCard: updateToolCard,
        markComplete: markComplete,
        markError: markError,
        removeEmptyState: removeEmptyState,
        scrollToBottom: scrollToBottom,
        findMsgDom: findMsgDom,
        MSG_CONTAINER_ID: MSG_CONTAINER_ID,
        EMPTY_STATE_ID: EMPTY_STATE_ID
    };

    console.log('[ChatRenderer] initialized');
})();
