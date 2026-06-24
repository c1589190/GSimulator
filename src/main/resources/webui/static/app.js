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

// ---- 页面加载：HTMX 交换整个模块 ----

/**
 * 桌面端：点击侧栏导航 → HTMX 加载对应模块到 #desktop-page-body
 */
function loadDesktopPage(name) {
    // 更新侧栏激活态
    var navItems = document.querySelectorAll('.nav-item');
    for (var i = 0; i < navItems.length; i++) {
        var isActive = navItems[i].getAttribute('data-panel') === name;
        navItems[i].classList.toggle('active', isActive);
    }
    // HTMX 加载模块
    htmx.ajax('GET', '/' + name, {target: '#desktop-page-body', swap: 'innerHTML'});
}

/**
 * 移动端：点击汉堡菜单 → HTMX 加载对应模块到 #mobile-page-body
 */
function loadMobilePage(name) {
    // HTMX 加载模块
    htmx.ajax('GET', '/' + name, {target: '#mobile-page-body', swap: 'innerHTML'});
    // 关闭侧栏
    toggleSidebar();
}

// ---- 初始加载：只加载到当前屏幕尺寸对应的容器，杜绝重复 ID ----
(function() {
    var targetId = window.innerWidth >= 768 ? 'desktop-page-body' : 'mobile-page-body';
    htmx.ajax('GET', '/chat', {target: '#' + targetId, swap: 'innerHTML'});
})();

// ===== Phase 3: 基于 MessageStore + ChatRenderer 的聊天逻辑 =====
(function() {
    'use strict';

    var es = null;           // EventSource 引用
    var taskId = null;       // 当前任务 ID


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
        console.log('[chat] initChatPanel: loading messages');

        // 1. 先从 localStorage 恢复（即时显示，不闪烁）
        var restored = MessageStore.restoreFromLocal();
        if (restored) {
            console.log('[chat] restored', MessageStore.getAll().length, 'messages from localStorage');
            var container = document.getElementById('chat-messages');
            if (container) container.innerHTML = '';
            ChatRenderer.renderAll(MessageStore.getAll());
        }

        // 2. 再从后端加载（合并/确认 complete 消息）
        fetch('/chat/messages?format=json', {
            headers: {'Accept': 'application/json'}
        })
        .then(function(r) {
            console.log('[chat] /chat/messages response status:', r.status);
            return r.json();
        })
        .then(function(data) {
            var msgList = data.messages || [];
            console.log('[chat] loaded', msgList.length, 'messages from backend');
            MessageStore.loadFromJson(msgList);
            console.log('[chat] MessageStore size after merge:', MessageStore.getAll().length);
            // 重新渲染（合并后的完整列表）
            var container = document.getElementById('chat-messages');
            if (container) container.innerHTML = '';
            ChatRenderer.renderAll(MessageStore.getAll());
        })
        .catch(function(err) {
            console.warn('[chat] Failed to load history:', err.message);
            // 后端不可用时，localStorage 数据已经显示了
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

    // ---- 文件上传 ----

    window._chatUpload = function(input) {
        var file = input.files && input.files[0];
        if (!file) return;

        var url = '/chat/upload?sessionId=default&filename=' + encodeURIComponent(file.name);
        fetch(url, {method: 'POST', body: file})
        .then(function(r) { return r.json(); })
        .then(function(data) {
            if (data.success) {
                addUploadTag(data.filename);
            }
        })
        .catch(function(err) {
            console.warn('[chat] upload failed:', err.message);
        })
        .finally(function() {
            // 重置 file input，允许重复上传同名文件
            input.value = '';
        });
    };

    function addUploadTag(filename) {
        var container = document.getElementById('chat-uploads');
        if (!container) return;
        container.classList.remove('hidden');

        var tag = document.createElement('span');
        tag.className = 'upload-tag';
        tag.setAttribute('data-filename', filename);
        tag.innerHTML = '<span>' + escapeUploadName(filename) + '</span>'
            + '<button class="ml-1 text-gray-400 hover:text-red-400" onclick="this.parentElement.remove();'
            + 'if(!document.getElementById(\'chat-uploads\').querySelector(\'.upload-tag\'))'
            + 'document.getElementById(\'chat-uploads\').classList.add(\'hidden\')">&times;</button>';
        container.appendChild(tag);
    }

    function escapeUploadName(s) {
        if (!s) return '';
        return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    }

    function clearUploadTags() {
        var container = document.getElementById('chat-uploads');
        if (container) {
            container.innerHTML = '';
            container.classList.add('hidden');
        }
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
        clearUploadTags();

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

    // SubAgent 状态追踪：agentId → {parentAsstId, type, accumulatedContent, toolCardIndex}
    var subAgentState = {};

    function getOrCreateSubAgent(agentId, agentType, parentAsstId) {
        if (subAgentState[agentId]) return subAgentState[agentId];
        var st = {
            agentId: agentId,
            parentAsstId: parentAsstId,
            type: agentType || 'unknown',
            accumulatedContent: '',
            toolCardIndex: 0
        };
        subAgentState[agentId] = st;
        // 在父 assistant 消息中创建 sub-agent 卡片
        ChatRenderer.renderSubAgentCard(parentAsstId, agentId, st.type);
        return st;
    }

    function connectStream(streamUrl, asstMsgId) {
        console.log('[chat] connectStream:', streamUrl, 'asstMsgId:', asstMsgId);
        es = new EventSource(streamUrl);

        var accumulatedContent = '';
        var toolCardIndex = 0;

        // ---- 辅助：判断事件是否属于 SubAgent ----
        function routeByAgent(e) {
            var agentId = null;
            try {
                if (e.data) {
                    var d = JSON.parse(e.data);
                    agentId = d.agentId || null;
                }
            } catch (err) {}
            // 返回 {agentId, subState} 或 null（主 Agent）
            if (agentId) {
                // 从 agentId 推断类型：sim-1 → sim, search-2 → search
                var agentType = 'unknown';
                if (agentId.startsWith('sim')) agentType = 'sim';
                else if (agentId.startsWith('search')) agentType = 'search';
                var st = getOrCreateSubAgent(agentId, agentType, asstMsgId);
                return {agentId: agentId, subState: st, data: (function() {
                    try { return JSON.parse(e.data); } catch (err) { return {}; }
                })()};
            }
            return null;
        }

        // llm_started — 更新 thinking 指示器
        es.addEventListener('llm_started', function(e) {
            var rt = routeByAgent(e);
            if (rt) {
                // SubAgent 启动 — 状态已由 getOrCreateSubAgent 创建
                console.log('[chat] sub-agent started:', rt.agentId);
                return;
            }
            console.log('[chat] llm_started rcvd');
            MessageStore.update(asstMsgId, {status: 'streaming'});
            ChatRenderer.updateThink(asstMsgId, '<span class="text-green-400">●</span> 生成中...');
        });

        // llm_delta — 流式文本内容
        es.addEventListener('llm_delta', function(e) {
            var rt = routeByAgent(e);
            if (rt) {
                if (rt.data.content) {
                    rt.subState.accumulatedContent += rt.data.content;
                    ChatRenderer.updateSubAgentContent(rt.agentId, rt.subState.accumulatedContent);
                }
                return;
            }
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

        // llm_reasoning_delta — 流式推理内容
        es.addEventListener('llm_reasoning_delta', function(e) {
            var rt = routeByAgent(e);
            if (rt) {
                if (rt.data.content) {
                    ChatRenderer.appendSubAgentReasoning(rt.agentId, rt.data.content);
                }
                return;
            }
            try {
                var d = JSON.parse(e.data);
                if (d.content) {
                    ChatRenderer.appendReasoning(asstMsgId, d.content);
                }
            } catch (err) {}
        });

        // llm_error — LLM 流失败
        es.addEventListener('llm_error', function(e) {
            var rt = routeByAgent(e);
            if (rt) {
                ChatRenderer.markSubAgentComplete(rt.agentId, false, rt.data.error || 'LLM 流失败');
                return;
            }
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
        es.addEventListener('llm_done', function(e) {
            var rt = routeByAgent(e);
            if (rt) {
                ChatRenderer.markSubAgentComplete(rt.agentId, true, null);
                return;
            }
            ChatRenderer.hideThink(asstMsgId);
        });

        // llm_tool_delta — 流式 tool_call 进度
        es.addEventListener('llm_tool_delta', function(e) {
            var rt = routeByAgent(e);
            if (rt) {
                var tn = rt.data.tool || 'tool';
                rt.subState.toolCardIndex = ChatRenderer.addSubAgentToolCard(
                    rt.agentId, tn, 'streaming', rt.subState.toolCardIndex);
                rt.subState.toolCardIndex++;
                rt.subState.accumulatedContent = '';
                return;
            }
            try {
                var d = JSON.parse(e.data);
                var toolName = d.tool || 'tool';
                toolCardIndex = ChatRenderer.addToolCard(asstMsgId, toolName, 'streaming', toolCardIndex);
                toolCardIndex++;
                accumulatedContent = '';
            } catch (err) {}
        });

        // tool_started — 工具开始执行
        es.addEventListener('tool_started', function(e) {
            var rt = routeByAgent(e);
            if (rt) {
                var tn = rt.data.tool || 'tool';
                rt.subState.toolCardIndex = ChatRenderer.addSubAgentToolCard(
                    rt.agentId, tn, 'running', rt.subState.toolCardIndex);
                rt.subState.toolCardIndex++;
                rt.subState.accumulatedContent = '';
                return;
            }
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
            var rt = routeByAgent(e);
            if (rt) {
                var tn = rt.data.tool || '';
                ChatRenderer.updateSubAgentToolCard(rt.agentId, tn, 'done');
                return;
            }
            try {
                var d = JSON.parse(e.data);
                var toolName = d.tool || '';
                ChatRenderer.updateToolCard(asstMsgId, toolName, 'done');
            } catch (err) {}
        });

        // tool_error — 工具执行失败
        es.addEventListener('tool_error', function(e) {
            var rt = routeByAgent(e);
            if (rt) {
                var tn = rt.data.tool || '';
                ChatRenderer.updateSubAgentToolCard(rt.agentId, tn, 'error', rt.data.error);
                return;
            }
            try {
                var d = JSON.parse(e.data);
                var toolName = d.tool || '';
                var errMsg = d.error || 'failed';
                ChatRenderer.updateToolCard(asstMsgId, toolName, 'error', errMsg);
            } catch (err) {}
        });

        // log — Agent 公开消息
        es.addEventListener('log', function(e) {
            var rt = routeByAgent(e);
            if (rt) {
                var message = rt.data.message;
                if (message) {
                    var clean = message.replace(/\x1B\[[0-9;]*m/g, '').trim();
                    if (clean) {
                        if (!rt.subState.accumulatedContent) {
                            rt.subState.accumulatedContent = clean;
                        } else {
                            rt.subState.accumulatedContent += '\n\n' + clean;
                        }
                        ChatRenderer.updateSubAgentContent(rt.agentId, rt.subState.accumulatedContent);
                    }
                }
                return;
            }
            console.log('[chat] log rcvd, msg len:', e.data ? e.data.length : 0);
            try {
                var d = JSON.parse(e.data);
                if (d.subType === 'simulation_content') {
                    var body = d.body || '';
                    body = body.replace(/\x1B\[[0-9;]*m/g, '').trim();
                    if (body) {
                        ChatRenderer.addTweetCard(asstMsgId, d.title || '推演内容', body);
                    }
                    return;
                }
                var message = d.message;
                if (message) {
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

        // result — 最终结果兜底
        var needsBranchReload = false;
        es.addEventListener('result', function(e) {
            var rt = routeByAgent(e);
            if (rt) return; // SubAgent 不处理 result
            console.log('[chat] result rcvd');
            try {
                var d = JSON.parse(e.data);
                var text = d.displayText || d.message || '';
                // 检测 compact / branch 切换命令，标记需要 reload
                if (text && (text.indexOf('上下文已压缩') >= 0
                        || text.indexOf('新节点:') >= 0
                        || text.indexOf('已切换到') >= 0)) {
                    needsBranchReload = true;
                }
                if (text && !accumulatedContent) {
                    accumulatedContent = text;
                    MessageStore.update(asstMsgId, {content: accumulatedContent});
                    ChatRenderer.updateMessageContent(asstMsgId, accumulatedContent);
                }
            } catch (err) {}
        });

        // command_done — 任务完成
        es.addEventListener('command_done', function() {
            console.log('[chat] command_done rcvd');
            MessageStore.update(asstMsgId, {status: 'complete'});
            ChatRenderer.markComplete(asstMsgId);
            // 清理 sub-agent 状态
            subAgentState = {};
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
            subAgentState = {};
            finishStream(null);
        });

        // done — 兜底：恢复按钮
        es.addEventListener('done', function() {
            console.log('[chat] done rcvd');
            ChatRenderer.hideThink(asstMsgId);
            if (es) { es.close(); es = null; }
            taskId = null;
            MessageStore.setActiveAsstId(null);
            subAgentState = {};
            resetSendBtn();

            // compact / branch 切换后，重新从当前节点加载消息
            if (needsBranchReload) {
                needsBranchReload = false;
                console.log('[chat] detected branch change, reloading messages');
                setTimeout(function() {
                    MessageStore.clear();
                    var container = document.getElementById(ChatRenderer.MSG_CONTAINER_ID);
                    if (container) container.innerHTML = '';
                    initChatPanel();
                }, 300);
            }
        });

        // EventSource 连接错误
        es.onerror = function() {
            console.log('[chat] onerror fired, taskId:', taskId, 'readyState:', es ? es.readyState : 'null');
            if (!taskId) return;
            if (accumulatedContent) {
                MessageStore.update(asstMsgId, {status: 'complete'});
                ChatRenderer.markComplete(asstMsgId);
                finishStream(null);
            } else {
                // 检查是否有 sub-agent 内容
                var hasSubContent = false;
                for (var key in subAgentState) {
                    if (subAgentState[key].accumulatedContent) { hasSubContent = true; break; }
                }
                if (hasSubContent) {
                    finishStream(null);
                } else {
                    finishStream('[错误] 连接中断');
                }
            }
        };
    }
})();
