// GSimulator WebUI — 前端交互脚本

// ---- Mermaid 初始化（CDN 故障不阻塞其他 JS） ----
try {
    mermaid.initialize({
        startOnLoad: false,
        theme: 'dark',
        themeVariables: {
            primaryColor: '#1f2937',
            primaryTextColor: '#e5e7eb',
            primaryBorderColor: '#374151',
            lineColor: '#34d399',
            secondaryColor: '#1a1a2e',
            tertiaryColor: '#111827'
        }
    });
} catch (e) {
    // mermaid CDN 加载失败 — 图表渲染不可用，其他功能不受影响
    console.warn('Mermaid init failed, charts disabled:', e.message);
}

function renderMermaid(elementId, code) {
    if (typeof mermaid === 'undefined') return;
    var el = document.getElementById(elementId);
    if (!el || !code) return;
    try {
        mermaid.render(elementId + '-svg', code).then(function(r) { el.innerHTML = r.svg; });
    } catch (e) {
        el.innerHTML = '<div class="text-red-500 text-xs">渲染失败</div>';
    }
}

// ---- 移动端侧边栏 ----
function toggleSidebar() {
    var overlay = document.getElementById('sidebar-overlay');
    var sidebar = document.getElementById('sidebar');
    if (!overlay || !sidebar) return;
    var isOpen = sidebar.style.display === 'block';
    overlay.style.display = isOpen ? 'none' : 'block';
    sidebar.style.display = isOpen ? 'none' : 'block';
}

function switchMobilePanel(name) {
    var panels = document.querySelectorAll('.mobile-panel');
    panels.forEach(function(p) { p.classList.add('hidden'); p.classList.remove('active'); });
    var target = document.getElementById('mobile-' + name);
    if (target) {
        target.classList.remove('hidden');
        target.classList.add('active');
    }
    toggleSidebar();
}

// ---- 聊天面板加载：只加载到当前屏幕尺寸对应的面板，避免重复 ID ----
var currentChatPanel = null; // 'desktop' or 'mobile'

function loadChatIntoPanel(panelName) {
    if (currentChatPanel === panelName) return; // 已加载到目标面板
    var targetId = panelName + '-chat'; // 'desktop-chat' or 'mobile-chat'
    var target = document.getElementById(targetId);
    if (!target) return;
    // 检查是否已有内容（避免重复加载）
    if (target.querySelector('#chat-form')) return;
    // 直接用 htmx.ajax 加载到面板，替换"加载中..."占位
    htmx.ajax('GET', '/chat', {target: '#' + targetId, swap:'innerHTML'});
    currentChatPanel = panelName;
}

function loadChatForCurrentScreen() {
    if (window.innerWidth >= 768) {
        loadChatIntoPanel('desktop');
    } else {
        loadChatIntoPanel('mobile');
    }
}

// 初始加载聊天面板
if (document.readyState === 'complete') loadChatForCurrentScreen();
else window.addEventListener('load', loadChatForCurrentScreen);

// 跨断点切换时重新加载到对应面板
var wasDesktop = window.innerWidth >= 768;
window.addEventListener('resize', function() {
    var nowDesktop = window.innerWidth >= 768;
    if (nowDesktop !== wasDesktop) {
        wasDesktop = nowDesktop;
        currentChatPanel = null; // 重置，强制重新加载
        loadChatForCurrentScreen();
    }
});

// ---- 移动端面板懒加载 ----
(function() {
    if (window.innerWidth >= 768) return;
    var loaded = { chat: false, timeline: false, knowledge: false };
    var origSwitch = switchMobilePanel;
    switchMobilePanel = function(name) {
        if (!loaded[name]) {
            loaded[name] = true;
            if (name === 'chat') {
                // 聊天面板使用统一的加载逻辑
                loadChatIntoPanel('mobile');
            } else {
                var el = document.getElementById('mobile-' + name);
                if (el) {
                    var child = el.querySelector('[hx-get]');
                    if (child) htmx.trigger(child, 'load');
                }
            }
        }
        origSwitch(name);
    };
})();

// ===== Phase 3: 基于 MessageStore + ChatRenderer 的聊天逻辑 =====
(function() {
    'use strict';

    var es = null;           // EventSource 引用
    var taskId = null;       // 当前任务 ID
    var chatInitialized = false;  // 是否已完成初始化（加载了历史）

    // ---- 按钮状态管理 ----

    function getSendBtn() {
        return document.getElementById('chat-send-btn');
    }

    function getBtnOrigText() { return '发送'; }
    function getBtnOrigClass() {
        return 'px-4 py-2 bg-green-700 hover:bg-green-600 text-white text-sm rounded';
    }

    function resetSendBtn() {
        var btn = getSendBtn();
        if (!btn) return;
        btn.textContent = getBtnOrigText();
        btn.className = getBtnOrigClass();
        btn.type = 'button';
        btn.onclick = function() { window._chatSend && window._chatSend(); };
    }

    function setCancelBtn() {
        var btn = getSendBtn();
        if (!btn) return;
        btn.textContent = '取消';
        btn.className = 'px-4 py-2 bg-red-700 hover:bg-red-600 text-white text-sm rounded';
        btn.type = 'button';
        btn.onclick = function(e) {
            if (e) e.preventDefault();
            cancelCurrentStream();
        };
    }

    function cancelCurrentStream() {
        if (es) { es.close(); es = null; }
        if (taskId) {
            fetch('/chat/cancel?taskId=' + taskId, {method:'POST'}).catch(function(){});
            var asstId = MessageStore.getActiveAsstId();
            if (asstId) {
                MessageStore.update(asstId, {status: 'error'});
                ChatRenderer.markError(asstId, '已取消');
            }
            taskId = null;
        }
        MessageStore.setActiveAsstId(null);
        resetSendBtn();
    }

    function finishStream(errMsg) {
        if (es) { es.close(); es = null; }
        taskId = null;
        var asstId = MessageStore.getActiveAsstId();
        if (errMsg && asstId) {
            MessageStore.update(asstId, {status: 'error'});
            ChatRenderer.markError(asstId, errMsg);
        }
        MessageStore.setActiveAsstId(null);
        resetSendBtn();
    }

    // ---- 初始化：加载历史消息 ----

    function initChatPanel() {
        if (chatInitialized) return;
        chatInitialized = true;
        console.log('[chat] initChatPanel: loading history from JSON API');

        fetch('/chat/messages?format=json', {
            headers: {'Accept': 'application/json'}
        })
        .then(function(r) {
            console.log('[chat] /chat/messages response status:', r.status);
            return r.json();
        })
        .then(function(data) {
            var msgList = data.messages || [];
            console.log('[chat] loaded', msgList.length, 'messages from history');
            msgList.forEach(function(m, i) {
                console.log('[chat] msg[' + i + '] role=' + m.role + ' type=' + m.type + ' id=' + m.id + ' content=' + (m.content || '').substring(0, 50));
            });
            if (msgList.length > 0) {
                var container = document.getElementById('chat-messages');
                console.log('[chat] #chat-messages found:', !!container);
                MessageStore.loadFromJson(msgList);
                console.log('[chat] MessageStore size after load:', MessageStore.getAll().length);
                ChatRenderer.renderAll(MessageStore.getAll());
                console.log('[chat] renderAll done, DOM children:', container ? container.children.length : 'N/A');
            }
        })
        .catch(function(err) {
            console.warn('[chat] Failed to load history:', err.message);
        });
    }

    // 监听 HTMX 将聊天片段加载到 DOM 后触发初始化
    document.body.addEventListener('htmx:afterSwap', function(e) {
        var target = e.detail && e.detail.target;
        if (!target) return;
        // 检查目标元素或其子元素中是否出现了 chat-form
        if (target.querySelector && target.querySelector('#chat-form')) {
            console.log('[chat] chat-form detected in DOM after htmx swap, initializing...');
            // 延迟一点确保所有 DOM 就绪
            setTimeout(initChatPanel, 50);
        }
    });

    // 备用：如果页面加载时 chat-form 已经在 DOM 中（首次从服务端渲染）
    if (document.readyState === 'complete') {
        setTimeout(function() {
            if (document.getElementById('chat-form')) initChatPanel();
        }, 100);
    } else {
        window.addEventListener('load', function() {
            setTimeout(function() {
                if (document.getElementById('chat-form')) initChatPanel();
            }, 100);
        });
    }

    // ---- 发送消息 ----

    window._chatSend = function() {
        var input = document.getElementById('chat-input');
        if (!input) return;
        var msg = input.value.trim();
        if (!msg) return;

        // 如果有正在进行的流，先取消
        if (es || taskId) cancelCurrentStream();

        // ---- 创建用户消息模块 ----
        var userMsg = MessageStore.add({
            role: 'user',
            type: 'chat_user',
            content: msg,
            status: 'complete'
        });
        ChatRenderer.renderUserMessage(userMsg);

        input.value = '';

        // ---- 创建 assistant 占位模块 ----
        var asstMsgId = MessageStore.generateId();
        var asstMsg = MessageStore.add({
            msgId: asstMsgId,
            role: 'assistant',
            type: 'chat_assistant',
            content: '',
            status: 'pending'
        });
        MessageStore.setActiveAsstId(asstMsgId);
        ChatRenderer.renderAssistantMessage(asstMsg);

        // 立即切换按钮为取消状态
        setCancelBtn();

        // 发送消息到后端
        fetch('/chat/send', {
            method: 'POST',
            headers: {'Content-Type': 'application/x-www-form-urlencoded'},
            body: 'message=' + encodeURIComponent(msg)
        })
        .then(function(r) { return r.json(); })
        .then(function(resp) {
            if (!resp.taskId) {
                finishStream('[错误] 未收到任务ID');
                return;
            }
            taskId = resp.taskId;
            console.log('[chat] task created:', taskId);
            connectStream(resp.streamUrl, asstMsgId);
        })
        .catch(function(err) {
            finishStream('[错误] ' + err.message);
        });
    };

    // 事件委托兜底：拦截 chat-form 的 submit
    document.body.addEventListener('submit', function(e) {
        var form = e.target;
        if (!form || form.id !== 'chat-form') return;
        e.preventDefault();
        window._chatSend && window._chatSend();
    });

    // ---- SSE 流连接和处理 ----

    function connectStream(streamUrl, asstMsgId) {
        console.log('[chat] connectStream:', streamUrl, 'asstMsgId:', asstMsgId);
        es = new EventSource(streamUrl);

        var accumulatedContent = '';

        // llm_started — 更新 thinking 指示器
        es.addEventListener('llm_started', function() {
            console.log('[chat] llm_started rcvd');
            MessageStore.update(asstMsgId, {status: 'streaming'});
            ChatRenderer.updateThink(asstMsgId, '<span class="text-green-400">●</span> 生成中...');
        });

        // llm_delta — 流式文本内容
        es.addEventListener('llm_delta', function(e) {
            console.log('[chat] llm_delta rcvd, data len:', e.data ? e.data.length : 0);
            try {
                var d = JSON.parse(e.data);
                if (d.content) {
                    accumulatedContent += d.content;
                    MessageStore.update(asstMsgId, {content: accumulatedContent});
                    ChatRenderer.updateMessageContent(asstMsgId, accumulatedContent);
                }
            } catch (err) {}
        });

        // llm_reasoning_delta — 流式推理内容（折叠显示）
        es.addEventListener('llm_reasoning_delta', function(e) {
            try {
                var d = JSON.parse(e.data);
                if (d.content) {
                    ChatRenderer.appendReasoning(asstMsgId, d.content);
                }
            } catch (err) {}
        });

        // llm_error — LLM 流失败
        es.addEventListener('llm_error', function(e) {
            try {
                var d = JSON.parse(e.data);
                var errMsg = d.error || 'LLM 流失败';
                if (!accumulatedContent) {
                    accumulatedContent = '[错误] ' + errMsg;
                    MessageStore.update(asstMsgId, {content: accumulatedContent});
                    ChatRenderer.updateMessageContent(asstMsgId, accumulatedContent);
                }
                ChatRenderer.updateThink(asstMsgId, '<span class="text-red-400">✖</span> ' + errMsg);
            } catch (err) {}
        });

        // llm_done — LLM 流式输出完成
        es.addEventListener('llm_done', function() {
            ChatRenderer.hideThink(asstMsgId);
        });

        // llm_tool_delta — 流式 tool_call 进度
        // 重置 accumulatedContent，使后续文本进入新的 .content-block
        var toolCardIndex = 0;
        es.addEventListener('llm_tool_delta', function(e) {
            try {
                var d = JSON.parse(e.data);
                var toolName = d.tool || 'tool';
                toolCardIndex = ChatRenderer.addToolCard(asstMsgId, toolName, 'streaming', toolCardIndex);
                toolCardIndex++;
                accumulatedContent = '';
            } catch (err) {}
        });

        // tool_started — 工具开始执行
        // 重置 accumulatedContent，使后续文本进入新的 .content-block
        es.addEventListener('tool_started', function(e) {
            try {
                var d = JSON.parse(e.data);
                var toolName = d.tool || 'tool';
                toolCardIndex = ChatRenderer.addToolCard(asstMsgId, toolName, 'running', toolCardIndex);
                toolCardIndex++;
                accumulatedContent = '';
            } catch (err) {}
        });

        // tool_done — 工具执行成功
        es.addEventListener('tool_done', function(e) {
            try {
                var d = JSON.parse(e.data);
                var toolName = d.tool || '';
                ChatRenderer.updateToolCard(asstMsgId, toolName, 'done');
            } catch (err) {}
        });

        // tool_error — 工具执行失败
        es.addEventListener('tool_error', function(e) {
            try {
                var d = JSON.parse(e.data);
                var toolName = d.tool || '';
                var errMsg = d.error || 'failed';
                ChatRenderer.updateToolCard(asstMsgId, toolName, 'error', errMsg);
            } catch (err) {}
        });

        // log — Agent 公开消息（包含 finish_action 的用户可见输出）
        // 子类型 "simulation_content" 渲染为推文卡片
        es.addEventListener('log', function(e) {
            console.log('[chat] log rcvd, msg len:', e.data ? e.data.length : 0);
            try {
                var d = JSON.parse(e.data);
                if (d.subType === 'simulation_content') {
                    var body = d.body || '';
                    // 剥离 ANSI 转义码
                    body = body.replace(/\x1B\[[0-9;]*m/g, '').trim();
                    if (body) {
                        ChatRenderer.addTweetCard(asstMsgId, d.title || '推演内容', body);
                    }
                    return;
                }
                var message = d.message;
                if (message) {
                    // 剥离 ANSI 转义码（CLI 颜色码会污染 Web 显示）
                    var clean = message.replace(/\x1B\[[0-9;]*m/g, '').trim();
                    if (!clean) return;
                    if (!accumulatedContent) {
                        accumulatedContent = clean;
                    } else {
                        accumulatedContent += '\n\n' + clean;
                    }
                    MessageStore.update(asstMsgId, {content: accumulatedContent});
                    ChatRenderer.updateMessageContent(asstMsgId, accumulatedContent);
                }
            } catch (err) {}
        });

        // result — 最终结果，优先使用 displayText（当没有流式内容时作为兜底）
        es.addEventListener('result', function(e) {
            console.log('[chat] result rcvd');
            try {
                var d = JSON.parse(e.data);
                var text = d.displayText || d.message || '';
                if (text && !accumulatedContent) {
                    accumulatedContent = text;
                    MessageStore.update(asstMsgId, {content: accumulatedContent});
                    ChatRenderer.updateMessageContent(asstMsgId, accumulatedContent);
                }
            } catch (err) {}
        });

        // command_done — 任务完成
        // [关键变更] 流式内容即为最终内容，不再调用 htmx.ajax 重载！
        es.addEventListener('command_done', function() {
            console.log('[chat] command_done rcvd');
            MessageStore.update(asstMsgId, {status: 'complete'});
            ChatRenderer.markComplete(asstMsgId);
            finishStream(null);
        });

        // command_error — 任务出错
        es.addEventListener('command_error', function(e) {
            try {
                var d = JSON.parse(e.data);
                var errMsg = d.error || 'unknown error';
                if (!accumulatedContent) {
                    accumulatedContent = '[错误] ' + errMsg;
                    MessageStore.update(asstMsgId, {content: accumulatedContent});
                    ChatRenderer.updateMessageContent(asstMsgId, accumulatedContent);
                }
                ChatRenderer.updateThink(asstMsgId, '<span class="text-red-400">✖</span> 执行出错');
            } catch (err) {}
            MessageStore.update(asstMsgId, {status: 'error'});
            finishStream(null);
        });

        // done — 兜底：恢复按钮
        es.addEventListener('done', function() {
            console.log('[chat] done rcvd');
            ChatRenderer.hideThink(asstMsgId);
            if (es) { es.close(); es = null; }
            taskId = null;
            MessageStore.setActiveAsstId(null);
            resetSendBtn();
        });

        // EventSource 连接错误
        es.onerror = function() {
            console.log('[chat] onerror fired, taskId:', taskId, 'readyState:', es ? es.readyState : 'null');
            if (!taskId) return;
            if (accumulatedContent) {
                // 已有内容收到 → 可能是连接提前关闭，视为正常完成
                MessageStore.update(asstMsgId, {status: 'complete'});
                ChatRenderer.markComplete(asstMsgId);
                finishStream(null);
            } else {
                finishStream('[错误] 连接中断');
            }
        };
    }
})();
