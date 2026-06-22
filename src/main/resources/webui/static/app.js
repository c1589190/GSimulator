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
    var loaderId = panelName + '-chat-loader';
    var loader = document.getElementById(loaderId);
    if (!loader) return;
    // 检查是否已有内容（避免重复加载）
    if (loader.querySelector('#chat-form')) return;
    // 直接用 htmx.ajax 加载，target 用唯一的 loader ID
    htmx.ajax('GET', '/chat', {target: '#' + loaderId, swap:'innerHTML'});
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

// ---- 聊天表单初始化（HTMX 加载后通过 afterSettle 事件绑定） ----
(function() {
    var chatInitialized = false;
    var es = null;
    var taskId = null;

    function initChat() {
        var form = document.getElementById('chat-form');
        if (!form) return;
        // 如果已初始化且表单未被替换（onsubmit 仍绑定），跳过
        if (chatInitialized && form.onsubmit) return;
        var btn = document.getElementById('chat-send-btn');
        if (!btn) return;
        chatInitialized = true;

        var origText = btn.textContent;
        var origClass = btn.className;

        function resetBtn() {
            btn.textContent = origText;
            btn.className = origClass;
            btn.type = 'submit';   // 恢复 submit 类型，表单提交走 onsubmit
            btn.onclick = null;
        }

        function setCancelBtn() {
            btn.textContent = '取消';
            btn.className = 'px-4 py-2 bg-red-700 hover:bg-red-600 text-white text-sm rounded';
            btn.type = 'button';   // 临时改为 button 防止触发表单 submit
            btn.onclick = function(e) {
                if (e) e.preventDefault();
                if (es) { es.close(); es = null; }
                if (taskId) {
                    fetch('/chat/cancel?taskId=' + taskId, {method:'POST'}).catch(function(){});
                    taskId = null;
                }
                resetBtn();
                htmx.ajax('GET', '/chat/messages', {target:'#chat-messages', swap:'innerHTML'});
            };
        }

        function finish(asstId, errMsg) {
            if (es) { es.close(); es = null; }
            taskId = null;
            resetBtn();
            if (errMsg) {
                var el = document.getElementById(asstId);
                if (el) {
                    var c = el.querySelector('.content');
                    if (c && !c.textContent) c.textContent = errMsg;
                    var t = el.querySelector('.think');
                    if (t) t.classList.add('hidden');
                }
            }
        }

        form.onsubmit = function() {
            var input = document.getElementById('chat-input');
            var msg = input ? input.value.trim() : '';
            if (!msg) return false;

            var mc = document.getElementById('chat-messages');
            if (mc) {
                mc.insertAdjacentHTML('beforeend',
                    '<div class="msg-user rounded p-2 text-sm mb-1">'
                    + '<span class="text-xs text-gray-500">You</span>'
                    + '<div>' + msg.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;') + '</div>'
                    + '</div>');
            }
            input.value = '';

            var asstId = 'a' + Date.now();
            if (mc) {
                mc.insertAdjacentHTML('beforeend',
                    '<div id="' + asstId + '" class="msg-assistant rounded p-2 text-sm mb-1">'
                    + '<div class="think text-gray-500 text-xs">⏳ 正在生成...</div>'
                    + '<div class="content"></div>'
                    + '<div class="tools"></div>'
                    + '</div>');
            }

            setCancelBtn();

            fetch('/chat/send', {
                method: 'POST',
                headers: {'Content-Type': 'application/x-www-form-urlencoded'},
                body: 'message=' + encodeURIComponent(msg)
            })
            .then(function(r) { return r.json(); })
            .then(function(resp) {
                if (!resp.taskId) { finish(asstId, '[错误] 未收到任务ID'); return; }
                taskId = resp.taskId;

                es = new EventSource(resp.streamUrl);
                var el = document.getElementById(asstId);
                if (!el) { finish(asstId); return; }
                var contentEl = el.querySelector('.content');
                var thinkEl = el.querySelector('.think');
                var toolsEl = el.querySelector('.tools');

                es.addEventListener('llm_started', function() {
                    if (thinkEl) thinkEl.innerHTML = '<span class="text-green-400">●</span> 生成中...';
                });
                es.addEventListener('llm_delta', function(e) {
                    var d = JSON.parse(e.data);
                    if (d.content && contentEl) contentEl.textContent += d.content;
                    if (thinkEl) thinkEl.classList.add('hidden');
                });
                es.addEventListener('llm_reasoning_delta', function(e) {
                    var d = JSON.parse(e.data);
                    if (d.content && el) {
                        var r = el.querySelector('.reasoning');
                        if (!r) {
                            r = document.createElement('div');
                            r.className = 'reasoning text-gray-500 text-xs italic mt-1';
                            el.appendChild(r);
                        }
                        r.textContent += d.content;
                    }
                });
                es.addEventListener('tool_started', function(e) {
                    var d = JSON.parse(e.data);
                    if (toolsEl) toolsEl.insertAdjacentHTML('beforeend',
                        '<div class="tool-card rounded p-1 text-xs mt-1">'
                        + '<span class="text-yellow-400">⚙</span> ' + (d.tool || 'tool')
                        + ' running...</div>');
                });
                es.addEventListener('tool_done', function() {
                    var cards = toolsEl ? toolsEl.querySelectorAll('.tool-card') : [];
                    var c = cards[cards.length - 1];
                    if (c) { c.classList.add('success'); c.innerHTML = c.innerHTML.replace('running...','done'); }
                });
                es.addEventListener('command_done', function() {
                    finish(asstId, null);
                    htmx.ajax('GET', '/chat/messages', {target:'#chat-messages', swap:'innerHTML'});
                });
                es.addEventListener('command_error', function(e) {
                    var d = JSON.parse(e.data);
                    finish(asstId, '[错误] ' + (d.error || 'unknown'));
                    htmx.ajax('GET', '/chat/messages', {target:'#chat-messages', swap:'innerHTML'});
                });
                es.addEventListener('done', function() { finish(asstId, null); });
                es.onerror = function() {
                    finish(asstId, null);
                    htmx.ajax('GET', '/chat/messages', {target:'#chat-messages', swap:'innerHTML'});
                };
            })
            .catch(function(err) {
                finish(asstId, '[错误] ' + err.message);
            });

            return false;
        };
    }

    // 页面加载时尝试初始化
    if (document.readyState === 'complete') initChat();
    else window.addEventListener('load', initChat);

    // HTMX 每次交换后也重试（移动端/桌面端 chat-panel 动态加载）
    document.body.addEventListener('htmx:afterSettle', initChat);
})();
