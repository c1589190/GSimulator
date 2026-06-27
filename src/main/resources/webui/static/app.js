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

// ===== Phase 4: 基于 SessionWs (WebSocket) 的聊天逻辑 =====
// @deprecated 旧 SSE 路径保留兼容但不再使用。SessionWs 通过 WebSocket + SessionNode JSON 驱动 UI。
(function() {
    'use strict';

    var sessionWs = null;   // SessionWs 实例

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
            cancelCurrent();
        };
    }

    function cancelCurrent() {
        fetch('/chat/cancel', {method: 'POST'}).catch(function(){});
        resetSendBtn();
    }

    // ---- 初始化 ----

    function initChatPanel() {
        console.log('[chat] initChatPanel via SessionWs');

        // 0. 先检查后端状态（抗刷新丢失）
        fetch('/chat/status')
        .then(function(r) { return r.json(); })
        .then(function(status) {
            window._chatStatus = status;
            console.log('[chat] status:', status.worldId, '/', status.activeNodeId,
                'streaming:', status.isStreaming);

            // 更新上下文栏
            if (status.worldId) {
                var ctxBar = document.getElementById('chat-context');
                if (ctxBar) {
                    ctxBar.textContent = '📍 ' + status.worldId + ' / ' + status.activeNodeId
                        + ' | Turn ' + status.turn + ' | ' + status.worldTime;
                }
            }

            // 如果有流式中的内容，前端标记待续接
            if (status.isStreaming) {
                console.log('[chat] Active stream detected: ' + status.streamingNodeId
                    + ' (will resume via WebSocket)');
            }
        }).catch(function(err) {
            console.warn('[chat] Failed to check status:', err.message);
        });

        // 1. 先从 localStorage 恢复（即时显示，不闪烁）
        var restored = MessageStore.restoreFromLocal();
        if (restored) {
            console.log('[chat] restored', MessageStore.getAll().length, 'messages from localStorage');
            var container = document.getElementById('chat-messages');
            if (container) container.innerHTML = '';
            ClientCache.renderAllMessages(MessageStore.getAll());
        }

        // 2. 断开旧连接，创建新 SessionWs
        if (sessionWs) {
            sessionWs.close();
        }
        sessionWs = new SessionWs('default');
        ClientCache.bindSessionWs(sessionWs);

        // 3. 连接后加载节点摘要
        sessionWs.onConnected(function() {
            fetch('/chat/node-summary')
            .then(function(r) { return r.json(); })
            .then(function(info) {
                var statusText = '📍 ' + (info.worldId || '—') + ' / ' + (info.activeNodeId || '—');
                var ctxBar = document.getElementById('chat-context');
                if (ctxBar) ctxBar.textContent = statusText;
            }).catch(function(){});
        });

        sessionWs.connect();

        // 3b. 加载对话列表
        setTimeout(function() { window.loadConversations && window.loadConversations(); }, 300);

        // 4. 从后端加载历史消息（与 SessionPool 互补）
        fetch('/chat/messages?format=json', {
            headers: {'Accept': 'application/json'}
        })
        .then(function(r) { return r.json(); })
        .then(function(data) {
            var msgList = data.messages || [];
            console.log('[chat] loaded', msgList.length, 'messages from backend');
            MessageStore.loadFromJson(msgList);
            // SessionWs 已经通过 history 事件渲染了 SessionNode 历史，
            // 这里只做 localStorage 备份
        })
        .catch(function(err) {
            console.warn('[chat] Failed to load history:', err.message);
        });
    }

    // 监听 HTMX 将聊天片段加载到 DOM 后触发初始化
    document.body.addEventListener('htmx:afterSwap', function(e) {
        var target = e.detail && e.detail.target;
        if (!target) return;
        if (target.querySelector && target.querySelector('#chat-form')) {
            console.log('[chat] chat-form detected in DOM after htmx swap, initializing...');
            setTimeout(initChatPanel, 50);
        }
    });

    // 备用：页面加载时
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

    // ---- 文件上传 ----

    window._chatUpload = function(input) {
        var file = input.files && input.files[0];
        if (!file) return;

        var url = '/chat/upload?sessionId=default&filename=' + encodeURIComponent(file.name);
        fetch(url, {method: 'POST', body: file})
        .then(function(r) { return r.json(); })
        .then(function(data) {
            if (data.success) addUploadTag(data.filename);
        })
        .catch(function(err) {
            console.warn('[chat] upload failed:', err.message);
        })
        .finally(function() { input.value = ''; });
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
        if (container) { container.innerHTML = ''; container.classList.add('hidden'); }
    }

    // ---- 发送消息 ----

    window._chatSend = function() {
        var input = document.getElementById('chat-input');
        if (!input) return;
        var msg = input.value.trim();
        if (!msg) return;

        input.value = '';
        clearUploadTags();

        // 通过 SessionWs 发送消息到后端（POST /chat/send）
        if (sessionWs) {
            sessionWs.send(msg).then(function(resp) {
                console.log('[chat] message sent:', resp.sessionId);
            }).catch(function(err) {
                console.error('[chat] send error:', err.message);
            });
        } else {
            // Fallback — 直接 POST
            fetch('/chat/send', {
                method: 'POST',
                headers: {'Content-Type': 'application/x-www-form-urlencoded'},
                body: 'message=' + encodeURIComponent(msg)
            }).catch(function(err) {
                console.error('[chat] send error:', err.message);
            });
        }

        setCancelBtn();
    };

    // 事件委托兜底：拦截 chat-form 的 submit
    document.body.addEventListener('submit', function(e) {
        var form = e.target;
        if (!form || form.id !== 'chat-form') return;
        e.preventDefault();
        window._chatSend && window._chatSend();
    });

    // ---- 对话管理 (Task 4) ----
    window.loadConversations = function() {
        fetch('/chat/conversations')
        .then(function(r) { return r.json(); })
        .then(function(data) {
            var sel = document.getElementById('conversation-select');
            if (!sel) return;
            var currentVal = sel.value;
            sel.innerHTML = '';
            var conversations = data.conversations || [];
            conversations.forEach(function(c) {
                var opt = document.createElement('option');
                opt.value = c.sessionId;
                opt.textContent = (c.isActive ? '● ' : '') + c.agentName
                    + ' (' + c.messageCount + ' msgs, ' + (c.createdAt || '').substring(0,16) + ')';
                if (c.isActive) opt.selected = true;
                sel.appendChild(opt);
            });
            if (conversations.length === 0) {
                sel.innerHTML = '<option value="">无对话</option>';
            }
        })
        .catch(function(err) { console.warn('loadConversations:', err.message); });
    };

    window.switchConversation = function(sessionId) {
        if (!sessionId) return;
        fetch('/chat/conversations/' + encodeURIComponent(sessionId) + '/load', {method: 'POST'})
        .then(function(r) { return r.json(); })
        .then(function(data) {
            if (data.success) {
                location.reload();  // 简单粗暴：重载页面以载入新对话
            }
        })
        .catch(function(err) { console.warn('switchConversation:', err.message); });
    };

    window.newConversation = function() {
        fetch('/chat/conversations', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({agentName: 'Orchestrator'})
        })
        .then(function(r) { return r.json(); })
        .then(function(data) {
            if (data.success) {
                fetch('/chat/conversations/' + encodeURIComponent(data.sessionId) + '/load', {method: 'POST'})
                .then(function() { location.reload(); });
            }
        })
        .catch(function(err) { console.warn('newConversation:', err.message); });
    };

    window.deleteConversation = function() {
        var sel = document.getElementById('conversation-select');
        if (!sel || !sel.value) return;
        var sessionId = sel.value;
        if (!confirm('删除此对话？')) return;
        fetch('/chat/conversations/' + encodeURIComponent(sessionId), {method: 'DELETE'})
        .then(function(r) { return r.json(); })
        .then(function(data) {
            if (data.success) window.loadConversations();
        })
        .catch(function(err) { console.warn('deleteConversation:', err.message); });
    };

})();
