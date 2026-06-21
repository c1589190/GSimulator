// GSimulator WebUI — 前端交互脚本

// ---- Mermaid 初始化 ----
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

// 全局函数：渲染 Mermaid 图表
async function renderMermaid(elementId, code) {
    const el = document.getElementById(elementId);
    if (!el || !code) return;
    try {
        const { svg } = await mermaid.render(elementId + '-svg', code);
        el.innerHTML = svg;
    } catch (e) {
        el.innerHTML = '<div class="text-red-500 text-xs">渲染失败: ' + e.message + '</div>';
    }
}

// ---- 移动端侧边栏 ----
function toggleSidebar() {
    const overlay = document.getElementById('sidebar-overlay');
    const sidebar = document.getElementById('sidebar');
    const isOpen = sidebar.style.display === 'block';
    overlay.style.display = isOpen ? 'none' : 'block';
    sidebar.style.display = isOpen ? 'none' : 'block';
}

function switchMobilePanel(name) {
    document.querySelectorAll('.mobile-panel').forEach(p => p.classList.add('hidden'));
    document.querySelectorAll('.mobile-panel').forEach(p => p.classList.remove('active'));
    const target = document.getElementById('mobile-' + name);
    if (target) {
        target.classList.remove('hidden');
        target.classList.add('active');
    }
    toggleSidebar();
}
