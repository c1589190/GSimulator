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
            ClientCache.renderAllMessages(MessageStore.getAll());
        }

        // 2. 加载节点态势摘要（显示在消息顶部）
        fetch('/chat/node-summary')
        .then(function(r) { return r.json(); })
        .then(function(info) {
            if (info.ready) {
                var statusText = '📍 ' + info.rootId + ' / ' + info.branch;
                if (info.isCompact) statusText += ' (💾 compact)';
                // 服务端重置过 → 清空本地缓存
                if (info.needsBootstrap) {
                    statusText = '⚠️ 需要初始化根节点';
                    localStorage.clear();
                    MessageStore.clear();
                    ClientCache.reset();
                }
                var ctxBar = document.getElementById('chat-context');
                if (ctxBar) ctxBar.textContent = statusText;
            }
        }).catch(function(){});

        // 3. 再从后端加载（合并/确认 complete 消息）
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
            ClientCache.renderAllMessages(MessageStore.getAll());
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
        ClientCache.createAssistantCard(asstMsg);

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
    function connectStream(streamUrl, asstMsgId) {
        console.log('[chat] connectStream:', streamUrl, 'asstMsgId:', asstMsgId);
        es = new EventSource(streamUrl);

        var needsBranchReload = false;

        // 所有 SSE 事件统一由 ClientCache 分发
        var evtNames = ['llm_started','llm_delta','llm_reasoning_delta','llm_error','llm_done',
            'llm_tool_delta','tool_started','tool_done','tool_error','log','result',
            'command_done','command_error','done'];
        for (var i = 0; i < evtNames.length; i++) {
            es.addEventListener(evtNames[i], (function(n) { return function(e) {
                if (n === 'result') {
                    try { var d = JSON.parse(e.data); var t = d.displayText || d.message || '';
                        if (t.indexOf('上下文已压缩')>=0 || t.indexOf('新节点:')>=0
                            || t.indexOf('已切换到')>=0) needsBranchReload = true;
                    } catch(_) {}
                }
                ClientCache.dispatch(e);
                if (n === 'command_done' || n === 'command_error') finishStream(null);
                if (n === 'done') {
                    if (es) { es.close(); es = null; }
                    taskId = null; resetSendBtn();
                    if (needsBranchReload) { needsBranchReload = false;
                        console.log('[chat] detected branch change, reloading');
                        setTimeout(function(){ MessageStore.clear(); ClientCache.reset(); initChatPanel(); }, 300);
                    }
                }
            }; })(evtNames[i]));
        }
        es.onerror = function() {
            console.log('[chat] onerror, taskId:', taskId);
            if (taskId) finishStream(null);
        };
    }
})();
