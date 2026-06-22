// GSimulator WebUI — ChatRenderer
// 消息渲染层：负责将 MessageStore 中的消息渲染为 DOM 元素，
// 并在流式输出过程中实时更新单个消息模块。
//
// Assistant 消息结构（交错排列）：
//   div.msg-assistant
//     div.think
//     div.blocks
//       div.content-block   ← 文本块
//       div.tool-card       ← 工具调用
//       div.content-block   ← 工具后的文本块
//       ...
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

    function textToHtml(text) {
        return escapeHtml(text).replace(/\n/g, '<br>');
    }

    // ---- 空状态管理 ----

    function removeEmptyState() {
        var el = document.getElementById(EMPTY_STATE_ID);
        if (el) el.remove();
    }

    // ---- DOM 查找 ----

    function findMsgDom(msgId) {
        return document.getElementById('msg-' + msgId);
    }

    /**
     * 获取 assistant 消息的 .blocks 容器。
     */
    function getBlocks(asstMsgId) {
        var div = findMsgDom(asstMsgId);
        if (!div) return null;
        return div.querySelector('.blocks');
    }

    /**
     * 获取 .blocks 中最后一个 .content-block，如果不存在或最后一个不是文本块则创建新的。
     */
    function ensureContentBlock(asstMsgId) {
        var blocks = getBlocks(asstMsgId);
        if (!blocks) return null;
        var children = blocks.children;
        var last = children.length > 0 ? children[children.length - 1] : null;
        if (last && last.classList.contains('content-block')) {
            return last;
        }
        // 创建新的文本块
        var cb = document.createElement('div');
        cb.className = 'content-block';
        blocks.appendChild(cb);
        scrollToBottom();
        return cb;
    }

    // ---- 用户消息渲染 ----

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

        // Blocks 容器 — 文本块和工具卡片在此交错排列
        var blocks = document.createElement('div');
        blocks.className = 'blocks';

        // 初始化一个文本块
        var contentBlock = document.createElement('div');
        contentBlock.className = 'content-block';
        if (msg.content) {
            contentBlock.innerHTML = textToHtml(msg.content);
        }
        blocks.appendChild(contentBlock);

        div.appendChild(think);
        div.appendChild(blocks);
        container.appendChild(div);
        msg.domRef = div;
        scrollToBottom();
        return div;
    }

    // ---- 根据 role 分发渲染 ----

    function renderMessage(msg) {
        if (msg.role === 'user') {
            return renderUserMessage(msg);
        } else if (msg.role === 'assistant' || msg.role === 'tool' || msg.role === 'system') {
            return renderAssistantMessage(msg);
        } else {
            return renderAssistantMessage(msg);
        }
    }

    function renderAll(messages) {
        for (var i = 0; i < messages.length; i++) {
            renderMessage(messages[i]);
        }
    }

    // ---- 流式更新 ----

    function updateThink(msgId, html) {
        var div = findMsgDom(msgId);
        if (!div) return;
        var think = div.querySelector('.think');
        if (think) {
            think.innerHTML = html;
            think.classList.remove('hidden');
        }
    }

    function hideThink(msgId) {
        var div = findMsgDom(msgId);
        if (!div) return;
        var think = div.querySelector('.think');
        if (think) think.classList.add('hidden');
    }

    /**
     * 更新最后一个 .content-block 的文本（流式追加用完整替换）。
     * 如果上一个块是 tool-card，自动创建新的 .content-block。
     */
    function updateMessageContent(msgId, text) {
        var block = ensureContentBlock(msgId);
        if (block) {
            block.innerHTML = textToHtml(text);
        }
        hideThink(msgId);
        scrollToBottom();
    }

    /**
     * 追加推理内容（在 blocks 前插入 .reasoning 元素）。
     */
    function appendReasoning(msgId, reasoningText) {
        var div = findMsgDom(msgId);
        if (!div) return;
        var reasoning = div.querySelector('.reasoning');
        if (!reasoning) {
            reasoning = document.createElement('div');
            reasoning.className = 'reasoning text-gray-500 text-xs italic mt-1 border-l-2 border-gray-600 pl-2';
            var blocks = div.querySelector('.blocks');
            if (blocks) {
                blocks.before(reasoning);
            } else {
                div.appendChild(reasoning);
            }
        }
        reasoning.textContent += reasoningText;
        scrollToBottom();
    }

    // ---- Tool Card 管理 ----

    /**
     * 在 .blocks 中添加 tool card。
     * 添加后会自动结束当前文本块，后续文本将进入新的 .content-block。
     */
    function addToolCard(asstMsgId, toolName, status, toolIndex) {
        var blocks = getBlocks(asstMsgId);
        if (!blocks) return toolIndex;

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

        blocks.appendChild(card);
        scrollToBottom();
        return idx;
    }

    /**
     * 更新 tool card 的状态（在 .blocks 中查找）。
     */
    function updateToolCard(asstMsgId, toolName, status, errMsg) {
        var blocks = getBlocks(asstMsgId);
        if (!blocks) return;

        // 从后向前找第一个未完成的匹配卡片
        var cards = blocks.querySelectorAll('.tool-card:not(.success):not(.error)');
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
            var span = matched.querySelector('span:last-child');
            if (span) span.textContent = 'done';
        } else if (status === 'error') {
            matched.classList.add('error');
            var span2 = matched.querySelector('span:last-child');
            if (span2) span2.textContent = errMsg || 'failed';
        }
    }

    // ---- 状态标记 ----

    function markComplete(msgId) {
        var div = findMsgDom(msgId);
        if (!div) return;
        hideThink(msgId);
    }

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
        ensureContentBlock: ensureContentBlock,
        getBlocks: getBlocks,
        MSG_CONTAINER_ID: MSG_CONTAINER_ID,
        EMPTY_STATE_ID: EMPTY_STATE_ID
    };

    console.log('[ChatRenderer] initialized');
})();
