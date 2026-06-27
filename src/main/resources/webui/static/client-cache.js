// GSimulator WebUI — ClientCache
// 统一前端渲染缓存：每个视觉元素是一个 Card/Block，自管理 DOM 生命周期。
//
// 结构:
//   ClientCache
//     └── cards[]
//           ├── UserCard        — 用户消息
//           ├── AssistantCard   — 助手消息容器
//           │     ├── ThinkBlock      — 状态指示
//           │     ├── ReasoningBlock  — 推理链（每轮可折叠）
//           │     ├── ContentBlock    — 文本段落
//           │     ├── ToolCard        — 工具调用（单卡状态切换）
//           │     ├── TweetCard       — 推文卡片
//           │     └── SubAgentCard    — 子代理
//           └── SystemCard       — 系统通知

(function() {
    'use strict';

    var MSG_CONTAINER_ID = 'chat-messages';
    var EMPTY_STATE_ID = 'chat-empty-state';

    // ══════════════════════════════════════════
    // 工具函数
    // ══════════════════════════════════════════

    function esc(s) {
        if (!s) return '';
        return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
    }

    function txt(s) { return document.createTextNode(s); }

    function el(tag, cls, attrs) {
        var e = document.createElement(tag);
        if (cls) e.className = cls;
        if (attrs) { for (var k in attrs) e.setAttribute(k, attrs[k]); }
        return e;
    }

    function scrollBottom() {
        var c = document.getElementById(MSG_CONTAINER_ID);
        if (c) c.scrollTop = c.scrollHeight;
    }

    function removeEmpty() {
        var e = document.getElementById(EMPTY_STATE_ID);
        if (e) e.remove();
    }

    function stripAnsi(s) {
        return (s||'').replace(/\x1B\[[0-9;]*m/g, '').trim();
    }

    function textToHtml(s) {
        return esc(s||'').replace(/\n/g, '<br>');
    }

    // ══════════════════════════════════════════
    // Card 基类
    // ══════════════════════════════════════════

    function Card(cardId, parentMsgId) {
        this.cardId = cardId;
        this.parentMsgId = parentMsgId; // 所属 AssistantCard 的 msgId
        this.el = null;
    }

    Card.prototype.mountTo = function(parentEl) {
        if (this.el && parentEl) parentEl.appendChild(this.el);
    };

    // ══════════════════════════════════════════
    // UserCard
    // ══════════════════════════════════════════

    function UserCard(msg) {
        Card.call(this, 'user-' + msg.msgId, null);
        this.msg = msg;
    }
    UserCard.prototype = Object.create(Card.prototype);

    UserCard.prototype.render = function() {
        removeEmpty();
        var div = el('div', 'msg-user rounded p-2 text-sm mb-1', {id: this.cardId});
        var label = el('span', 'text-xs text-gray-500');
        label.textContent = 'You';
        var content = el('div');
        content.textContent = this.msg.content || '';
        div.appendChild(label);
        div.appendChild(content);
        this.el = div;
        return div;
    };

    // ══════════════════════════════════════════
    // SystemCard — 系统通知（compact 摘要等）
    // ══════════════════════════════════════════

    function SystemCard(msg) {
        Card.call(this, 'sys-' + msg.msgId, null);
        this.msg = msg;
    }
    SystemCard.prototype = Object.create(Card.prototype);

    SystemCard.prototype.render = function() {
        removeEmpty();
        var div = el('div', 'msg-assistant rounded p-2 text-sm mb-1 border-l-2 border-indigo-500', {id: this.cardId});
        div.style.background = '#1a1a2e';
        var label = el('span', 'text-xs text-indigo-400');
        label.textContent = '💡 System';
        var content = el('div', 'mt-1 text-gray-300');
        content.innerHTML = textToHtml(this.msg.content || '');
        div.appendChild(label);
        div.appendChild(content);
        this.el = div;
        return div;
    };

    // ══════════════════════════════════════════
    // AssistantCard — 助手消息容器
    // ══════════════════════════════════════════

    function AssistantCard(msg) {
        Card.call(this, 'asst-' + msg.msgId, null);
        this.msg = msg;
        this.thinkBlock = null;
        this.blocks = [];       // 有序子元素: [ReasoningBlock|ContentBlock|ToolCard|TweetCard|SubAgentCard]
        this.toolCards = [];    // 工具卡片引用（用于更新）
        this.reasoningBlocks = []; // 推理块引用
    }
    AssistantCard.prototype = Object.create(Card.prototype);

    AssistantCard.prototype.render = function() {
        removeEmpty();
        var div = el('div', 'msg-assistant rounded p-2 text-sm mb-1', {id: this.cardId});

        // Think 指示器
        var think = el('div', 'think text-gray-500 text-xs');
        if (this.msg.status === 'streaming') {
            think.innerHTML = '<span class="text-green-400">●</span> 生成中...';
        } else if (this.msg.status === 'pending') {
            think.innerHTML = '<span class="text-yellow-400">⏳</span> 正在生成...';
        } else if (this.msg.status === 'error') {
            think.innerHTML = '<span class="text-red-400">✖</span> 出错了';
        } else {
            think.classList.add('hidden');
        }
        this.thinkBlock = think;

        // Blocks 容器
        var blocksDiv = el('div', 'blocks space-y-1-5');

        // 初始内容
        if (this.msg.content && this.msg.type !== 'tool_call' && this.msg.type !== 'tool_result') {
            var cb = el('div', 'content-block mb-1');
            cb.innerHTML = textToHtml(this.msg.content);
            blocksDiv.appendChild(cb);
        }

        div.appendChild(think);
        div.appendChild(blocksDiv);
        this.el = div;
        this._blocksDiv = blocksDiv;
        return div;
    };

    // 获取 blocks 容器
    AssistantCard.prototype.getBlocks = function() {
        return this._blocksDiv;
    };

    // 添加一个 Block
    AssistantCard.prototype.addBlock = function(block) {
        this.blocks.push(block);
        if (block.render) {
            var dom = block.render();
            if (dom && this._blocksDiv) this._blocksDiv.appendChild(dom);
        }
    };

    // 查找最后一个 block
    AssistantCard.prototype.lastBlock = function() {
        return this.blocks.length > 0 ? this.blocks[this.blocks.length - 1] : null;
    };

    // 更新 Think
    AssistantCard.prototype.setThink = function(html) {
        if (this.thinkBlock) { this.thinkBlock.innerHTML = html; this.thinkBlock.classList.remove('hidden'); }
    };
    AssistantCard.prototype.hideThink = function() {
        if (this.thinkBlock) this.thinkBlock.classList.add('hidden');
    };

    // ══════════════════════════════════════════
    // ContentBlock
    // ══════════════════════════════════════════

    function ContentBlock(initialText) {
        this.text = initialText || '';
        this.el = null;
    }

    ContentBlock.prototype.render = function() {
        this.el = el('div', 'content-block mb-1');
        this.el.innerHTML = textToHtml(this.text);
        return this.el;
    };

    ContentBlock.prototype.update = function(text) {
        this.text = text;
        if (this.el) this.el.innerHTML = textToHtml(text);
    };

    ContentBlock.prototype.append = function(text) {
        this.text += text;
        if (this.el) this.el.innerHTML = textToHtml(this.text);
    };

    // ══════════════════════════════════════════
    // ReasoningBlock — 推理链块（可折叠，每工具轮一个）
    // ══════════════════════════════════════════

    function ReasoningBlock(round) {
        this.round = round || 1;
        this.text = '';
        this.el = null;
        this.collapsed = true;
    }

    ReasoningBlock.prototype.render = function() {
        var div = el('div', 'reasoning-block mb-2');
        // 折叠标题
        var header = el('div', 'reasoning-header text-xs text-gray-500 cursor-pointer flex items-center gap-1');
        header.innerHTML = '<span class="reasoning-toggle">▶</span> 🧠 思考链 #' + this.round;
        var self = this;
        header.onclick = function() {
            self.collapsed = !self.collapsed;
            var body = div.querySelector('.reasoning-body');
            var toggle = div.querySelector('.reasoning-toggle');
            if (self.collapsed) { body.style.display = 'none'; toggle.textContent = '▶'; }
            else { body.style.display = 'block'; toggle.textContent = '▼'; }
        };
        // 内容
        var body = el('div', 'reasoning-body text-gray-500 text-xs italic border-l-2 border-gray-600 pl-2 mt-1');
        body.style.display = 'none'; // 默认折叠
        body.textContent = this.text;
        div.appendChild(header);
        div.appendChild(body);
        this.el = div;
        return div;
    };

    ReasoningBlock.prototype.append = function(delta) {
        this.text += delta;
        if (this.el) {
            var body = this.el.querySelector('.reasoning-body');
            if (body) body.textContent = this.text;
        }
    };

    ReasoningBlock.prototype.getText = function() {
        return this.text;
    };

    // ══════════════════════════════════════════
    // ToolCard — 单卡片状态切换（streaming → running → done → error）
    // ══════════════════════════════════════════

    function ToolCard(toolName, status) {
        this.toolName = toolName;
        this.status = status || 'streaming';
        this.errorMsg = null;
        this.el = null;
    }

    ToolCard.STATUS_ICONS = {
        streaming: '<span class="text-blue-400">\u{1F527}</span>',
        running:   '<span class="text-yellow-400">⚙</span>',
        done:      '<span class="text-green-400">✓</span>',
        error:     '<span class="text-red-400">✖</span>'
    };
    ToolCard.STATUS_LABELS = {
        streaming: 'streaming...',
        running:   'running...',
        done:      'done',
        error:     ''
    };

    ToolCard.prototype.render = function() {
        var div = el('div', 'tool-card rounded p-1 text-xs mt-1 mb-1-5', {
            'data-tool-name': this.toolName
        });
        if (this.status === 'done') div.classList.add('success');
        if (this.status === 'error') div.classList.add('error');
        this._updateDom(div);
        this.el = div;
        return div;
    };

    ToolCard.prototype._updateDom = function(dom) {
        var icon = ToolCard.STATUS_ICONS[this.status] || ToolCard.STATUS_ICONS.streaming;
        var label = this.status === 'error'
            ? (this.errorMsg || 'error')
            : ToolCard.STATUS_LABELS[this.status];
        dom.innerHTML = icon + ' ' + esc(this.toolName) + ' ' +
            '<span class="text-gray-500 text-xs">' + esc(label) + '</span>';
    };

    /** 状态切换 — 不改 DOM 创建，只改内容和 CSS */
    ToolCard.prototype.transition = function(newStatus, errorMsg) {
        var prev = this.status;
        this.status = newStatus;
        this.errorMsg = errorMsg || null;
        if (this.el) {
            this._updateDom(this.el);
            this.el.classList.remove('success', 'error');
            if (newStatus === 'done') this.el.classList.add('success');
            if (newStatus === 'error') this.el.classList.add('error');
        }
        return prev !== newStatus; // 返回是否真正切换了
    };

    // ══════════════════════════════════════════
    // TweetCard
    // ══════════════════════════════════════════

    function TweetCard(title, body) {
        this.title = title || '推演内容';
        this.body = body || '';
    }

    TweetCard.prototype.render = function() {
        var card = el('div', 'tweet-card');
        var header = el('div', 'tweet-header');
        header.textContent = this.title;
        var textarea = el('textarea', 'tweet-body');
        textarea.value = this.body;
        textarea.readOnly = true;
        var lines = this.body.split('\n').length;
        textarea.rows = Math.max(4, Math.min(lines, 20));
        var copyBtn = el('button', 'tweet-copy-btn');
        copyBtn.textContent = '\u{1F4CB} 复制';
        var self = this;
        copyBtn.onclick = function() {
            if (navigator.clipboard && navigator.clipboard.writeText) {
                navigator.clipboard.writeText(self.body).then(function() {
                    copyBtn.textContent = '✓ 已复制';
                    setTimeout(function(){ copyBtn.textContent = '\u{1F4CB} 复制'; }, 2000);
                }).catch(function(){ fallbackCopy(); });
            } else { fallbackCopy(); }
            function fallbackCopy() {
                textarea.focus(); textarea.select();
                try { document.execCommand('copy'); copyBtn.textContent = '✓ 已复制';
                    setTimeout(function(){ copyBtn.textContent = '\u{1F4CB} 复制'; }, 2000); }
                catch(e){ copyBtn.textContent = '✗ 复制失败'; }
            }
        };
        card.appendChild(header);
        card.appendChild(textarea);
        card.appendChild(copyBtn);
        this.el = card;
        return card;
    };

    // ══════════════════════════════════════════
    // SubAgentCard — 子代理容器
    // ══════════════════════════════════════════

    function SubAgentCard(agentId, agentType) {
        this.agentId = agentId;
        this.agentType = agentType || 'unknown';
        this.el = null;
        this._blocksEl = null;
        this._statusEl = null;
    }

    SubAgentCard.prototype.render = function() {
        var typeIcon = this.agentType === 'sim' ? '\u{1F4DD}' : '\u{1F50D}';
        var typeLabel = this.agentType === 'sim' ? 'SimAgent' : 'SearchAgent';

        var card = el('div', 'sub-agent-card rounded p-2 text-xs mt-2', {
            'data-agent-id': this.agentId,
            'data-agent-type': this.agentType
        });

        var header = el('div', 'sub-agent-header flex items-center gap-1 cursor-pointer');
        header.onclick = function() { toggleBody(); };

        var toggle = el('span', 'sub-toggle text-gray-500');
        toggle.textContent = '▼';

        var iconS = el('span');
        iconS.textContent = typeIcon;

        var label = el('span', 'sub-agent-label font-bold');
        label.textContent = typeLabel + ' ';

        var idSpan = el('span', 'sub-agent-id text-gray-500');
        idSpan.textContent = this.agentId;

        var status = el('span', 'sub-agent-status ml-auto text-yellow-400');
        status.innerHTML = '● 执行中...';
        this._statusEl = status;

        header.appendChild(toggle);
        header.appendChild(iconS);
        header.appendChild(label);
        header.appendChild(idSpan);
        header.appendChild(status);

        var body = el('div', 'sub-agent-body');
        var blocks = el('div', 'sub-blocks');
        body.appendChild(blocks);

        card.appendChild(header);
        card.appendChild(body);
        this.el = card;
        this._blocksEl = blocks;

        var self = this;
        function toggleBody() {
            var collapsed = body.style.display === 'none';
            body.style.display = collapsed ? '' : 'none';
            toggle.textContent = collapsed ? '▼' : '▶';
        }
        return card;
    };

    SubAgentCard.prototype.getBlocks = function() { return this._blocksEl; };

    SubAgentCard.prototype.addToolCard = function(tc) {
        if (this._blocksEl && tc.el) this._blocksEl.appendChild(tc.el);
    };

    SubAgentCard.prototype.addContent = function(text) {
        var cb = el('div', 'sub-content-block');
        cb.innerHTML = textToHtml(text);
        if (this._blocksEl) this._blocksEl.appendChild(cb);
        return cb;
    };

    SubAgentCard.prototype.setStatus = function(html, cls) {
        if (this._statusEl) {
            this._statusEl.innerHTML = html;
            this._statusEl.className = 'sub-agent-status ml-auto ' + (cls || 'text-green-400');
        }
    };

    // ══════════════════════════════════════════
    // ClientCache — 全局卡牌管理器
    // ══════════════════════════════════════════

    var cards = [];              // 有序卡片列表
    var cardsById = {};          // cardId → Card
    var pendingAsstCard = null;  // 当前活跃的 AssistantCard
    var pendingToolCard = null;  // 当前流式 ToolCard（用于去重）
    var subAgents = {};          // agentId → SubAgentCard
    var container = null;

    function getContainer() {
        if (!container) container = document.getElementById(MSG_CONTAINER_ID);
        return container;
    }

    /** 添加卡片到容器 */
    function addCard(card) {
        cards.push(card);
        cardsById[card.cardId] = card;
        var c = getContainer();
        if (c && card.el) c.appendChild(card.el);
        scrollBottom();
        return card;
    }

    /** 查找 AssistantCard（用于实时更新） */
    function findAsstCard(msgId) {
        var cid = 'asst-' + msgId;
        return cardsById[cid] || null;
    }

    /** 创建新的 AssistantCard 并设为 pending */
    function createAssistantCard(msg) {
        var card = new AssistantCard(msg);
        card.render();
        addCard(card);
        pendingAsstCard = card;
        pendingToolCard = null;
        return card;
    }

    /** 确保最后有一个 ContentBlock（用于追加文本） */
    function ensureContentBlock() {
        if (!pendingAsstCard) return null;
        var last = pendingAsstCard.lastBlock();
        if (last instanceof ContentBlock) return last;
        var cb = new ContentBlock('');
        pendingAsstCard.addBlock(cb);
        scrollBottom();
        return cb;
    }

    // ══════════════════════════════════════════
    // SSE 事件分发
    // ══════════════════════════════════════════

    function dispatch(e) {
        // 解析数据
        var evtType = e.type;   // SSE named event 类型
        var data = {};
        try { if (e.data) data = JSON.parse(e.data); } catch(_) {}

        var agentId = data.agentId || null;

        // debug
        debugEvent(evtType, data, agentId);
        debugRefresh();

        // ═══ SubAgent 事件 ═══
        if (agentId) {
            dispatchSubAgent(agentId, evtType, data);
            return;
        }

        // ═══ 主 Agent 事件 ═══
        switch (evtType) {
        case 'llm_started':
            if (pendingAsstCard) {
                pendingAsstCard.msg.status = 'streaming';
                pendingAsstCard.setThink('<span class="text-green-400">●</span> 生成中...');
            }
            break;

        case 'llm_delta':
            if (data.content) {
                // 如果有 pending tool card，先结束它（文本表示工具调用完成）
                if (pendingToolCard) {
                    pendingToolCard = null;
                }
                var cb = ensureContentBlock();
                if (cb) {
                    cb.append(data.content);
                    // 同步到 MessageStore
                    if (pendingAsstCard) {
                        var full = '';
                        for (var i = 0; i < pendingAsstCard.blocks.length; i++) {
                            var b = pendingAsstCard.blocks[i];
                            if (b instanceof ContentBlock) full += (full ? '\n\n' : '') + b.text;
                        }
                        window.MessageStore.update(pendingAsstCard.msg.msgId, {content: full});
                    }
                }
            }
            scrollBottom();
            break;

        case 'llm_reasoning_delta':
            if (data.content) {
                // 找最后一个 ReasoningBlock 或新建
                var rb;
                if (pendingAsstCard) {
                    var last = pendingAsstCard.lastBlock();
                    if (last instanceof ContentBlock && last.text) {
                        // 创建新的 ReasoningBlock（新轮次）
                        var round = pendingAsstCard.reasoningBlocks.length + 1;
                        rb = new ReasoningBlock(round);
                        pendingAsstCard.addBlock(rb);
                        pendingAsstCard.reasoningBlocks.push(rb);
                    } else if (pendingAsstCard.reasoningBlocks.length > 0) {
                        rb = pendingAsstCard.reasoningBlocks[pendingAsstCard.reasoningBlocks.length - 1];
                    } else {
                        rb = new ReasoningBlock(1);
                        pendingAsstCard.addBlock(rb);
                        pendingAsstCard.reasoningBlocks.push(rb);
                    }
                    if (rb) {
                        rb.append(data.content);
                        // 持久化 reasoning 到 MessageStore
                        var reasoningText = '';
                        for (var i = 0; i < pendingAsstCard.reasoningBlocks.length; i++) {
                            reasoningText += pendingAsstCard.reasoningBlocks[i].getText();
                        }
                        window.MessageStore.update(pendingAsstCard.msg.msgId, {reasoning: reasoningText});
                    }
                }
            }
            scrollBottom();
            break;

        case 'llm_tool_delta':
            if (data.tool) {
                // 单卡片模式：不重复创建
                if (pendingToolCard && pendingToolCard.toolName === data.tool) {
                    // 同一工具 → 忽略（等 tool_started 更新）
                } else {
                    // 新工具 → 结束旧卡片，创建新卡片
                    pendingToolCard = new ToolCard(data.tool, 'streaming');
                    pendingToolCard.render();
                    if (pendingAsstCard) {
                        pendingAsstCard.addBlock(pendingToolCard);
                        pendingAsstCard.toolCards.push(pendingToolCard);
                    }
                }
            }
            scrollBottom();
            break;

        case 'tool_started':
            if (data.tool) {
                if (pendingToolCard && pendingToolCard.toolName === data.tool) {
                    // 同一卡片 → 切换为 running
                    pendingToolCard.transition('running');
                } else {
                    // 新工具（跳过 streaming 阶段）
                    if (pendingToolCard) pendingToolCard = null;
                    pendingToolCard = new ToolCard(data.tool, 'running');
                    pendingToolCard.render();
                    if (pendingAsstCard) {
                        pendingAsstCard.addBlock(pendingToolCard);
                        pendingAsstCard.toolCards.push(pendingToolCard);
                    }
                }
            }
            scrollBottom();
            break;

        case 'tool_done':
            if (data.tool) {
                if (pendingToolCard && pendingToolCard.toolName === data.tool) {
                    pendingToolCard.transition('done');
                    pendingToolCard = null; // 完成，不再是 pending
                } else if (pendingAsstCard) {
                    // 回退：在 toolCards 中查找匹配
                    for (var ti = pendingAsstCard.toolCards.length - 1; ti >= 0; ti--) {
                        var tc = pendingAsstCard.toolCards[ti];
                        if (tc.toolName === data.tool && tc.status !== 'done' && tc.status !== 'error') {
                            tc.transition('done');
                            break;
                        }
                    }
                }
            }
            scrollBottom();
            break;

        case 'tool_error':
            if (data.tool) {
                var errMsg = data.error || 'failed';
                if (pendingToolCard && pendingToolCard.toolName === data.tool) {
                    pendingToolCard.transition('error', errMsg);
                    pendingToolCard = null;
                } else if (pendingAsstCard) {
                    for (var ei = pendingAsstCard.toolCards.length - 1; ei >= 0; ei--) {
                        var te = pendingAsstCard.toolCards[ei];
                        if (te.toolName === data.tool && te.status !== 'done' && te.status !== 'error') {
                            te.transition('error', errMsg);
                            break;
                        }
                    }
                }
            }
            scrollBottom();
            break;

        case 'llm_error':
            if (pendingAsstCard) {
                var err = data.error || 'LLM 流失败';
                pendingAsstCard.setThink('<span class="text-red-400">✖</span> ' + esc(err));
            }
            break;

        case 'llm_done':
            if (pendingAsstCard) pendingAsstCard.hideThink();
            break;

        case 'log':
            if (data.subType === 'simulation_content') {
                var body = stripAnsi(data.body || '');
                if (body) {
                    var tweet = new TweetCard(data.title || '推演内容', body);
                    if (pendingAsstCard) pendingAsstCard.addBlock(tweet);
                }
            } else if (data.message) {
                var clean = stripAnsi(data.message);
                if (clean) {
                    var logCb = ensureContentBlock();
                    if (logCb) {
                        if (logCb.text) logCb.append('\n\n' + clean);
                        else logCb.update(clean);
                    }
                }
            }
            scrollBottom();
            break;

        case 'result':
            // 不做特殊处理 — 最终内容由 finish_action 通过 command_done 发送
            break;

        case 'command_done':
            if (pendingAsstCard) {
                pendingAsstCard.msg.status = 'complete';
                pendingAsstCard.hideThink();
            }
            break;

        case 'command_error':
            if (pendingAsstCard) {
                pendingAsstCard.msg.status = 'error';
                pendingAsstCard.setThink('<span class="text-red-400">✖</span> 执行出错');
            }
            break;

        case 'done':
            if (pendingAsstCard) pendingAsstCard.hideThink();
            pendingAsstCard = null;
            pendingToolCard = null;
            break;
        }
    }

    /** SubAgent 事件分发 */
    function dispatchSubAgent(agentId, evtType, data) {
        var sub = subAgents[agentId];
        // 获取父 AssistantCard 用于首次创建
        if (!sub && pendingAsstCard) {
            var agentType = agentId.startsWith('sim') ? 'sim' : 'search';
            sub = new SubAgentCard(agentId, agentType);
            sub.render();
            pendingAsstCard.addBlock(sub);
            subAgents[agentId] = sub;
        }
        if (!sub) return;

        switch (evtType) {
        case 'llm_started':
            sub.setStatus('● 执行中...', 'text-yellow-400');
            break;
        case 'llm_delta':
            if (data.content) sub.addContent(data.content);
            break;
        case 'llm_done':
            sub.setStatus('✓ 完成', 'text-green-400');
            break;
        case 'llm_error':
            sub.setStatus('✖ ' + esc(data.error || '失败'), 'text-red-400');
            break;
        case 'tool_started':
            if (data.tool) {
                var stc = new ToolCard(data.tool, 'running');
                stc.render();
                sub.addToolCard(stc);
            }
            break;
        case 'tool_done':
            // simple — no card tracking for sub-agents yet
            break;
        case 'tool_error':
            break;
        case 'log':
            if (data.message) {
                var clean = stripAnsi(data.message);
                if (clean) sub.addContent(clean);
            }
            break;
        }
        scrollBottom();
    }

    // ══════════════════════════════════════════
    // 历史渲染（页面刷新 / 历史消息加载）
    // ══════════════════════════════════════════

    function renderAllMessages(msgs) {
        var c = getContainer();
        if (!c) return;
        // 清空 DOM
        c.innerHTML = '';
        cards = [];
        cardsById = {};
        subAgents = {};
        pendingAsstCard = null;
        pendingToolCard = null;

        var currentAsstCard = null;

        for (var i = 0; i < msgs.length; i++) {
            var m = msgs[i];

            if (m.role === 'user') {
                var uc = new UserCard(m);
                uc.render();
                addCard(uc);
                currentAsstCard = null;
            }
            else if (m.role === 'system' && m.type === 'system_note') {
                var sc = new SystemCard(m);
                sc.render();
                addCard(sc);
                currentAsstCard = null;
            }
            else if (m.type === 'tool_call') {
                // 渲染为已完成工具卡片
                if (currentAsstCard) {
                    var tc = new ToolCard(m.toolName || 'tool', 'done');
                    tc.render();
                    currentAsstCard.addBlock(tc);
                    currentAsstCard.toolCards.push(tc);
                }
            }
            else if (m.type === 'tool_result') {
                // 跳过 tool_result — 结果已反映在 tool_card 的 done 状态中
            }
            else {
                // assistant / chat_assistant / error 等
                var card = new AssistantCard(m);
                card.render();
                addCard(card);

                // 恢复 reasoning（如果有）
                if (m.reasoning) {
                    var rb = new ReasoningBlock(1);
                    rb.text = m.reasoning;
                    card.addBlock(rb);
                    card.reasoningBlocks.push(rb);
                }

                currentAsstCard = card;
            }
        }
        scrollBottom();
    }

    /** 清空所有缓存 */
    function reset() {
        cards = [];
        cardsById = {};
        pendingAsstCard = null;
        pendingToolCard = null;
        subAgents = {};
        var c = getContainer();
        if (c) c.innerHTML = '';
    }

    // ══════════════════════════════════════════
    // Debug 日志
    // ══════════════════════════════════════════

    var debugLog = [];
    var MAX_DEBUG = 80;

    function debugEvent(evtType, data, agentId) {
        var entry = {
            time: new Date().toISOString().substring(11, 23),
            type: evtType,
            agentId: agentId || '-',
            data: JSON.stringify(data).substring(0, 120)
        };
        debugLog.push(entry);
        if (debugLog.length > MAX_DEBUG) debugLog.shift();
    }

    /** 在页面底部渲染调试面板 */
    function renderDebugPanel() {
        var existing = document.getElementById('gsim-debug-panel');
        if (existing) existing.remove();

        var panel = el('div', '', {id: 'gsim-debug-panel'});
        panel.style.cssText = 'position:fixed;bottom:0;left:0;right:0;max-height:200px;overflow-y:auto;'
            + 'background:#0a0e14;border-top:1px solid #333;color:#888;font:10px monospace;z-index:9999;padding:4px 8px;';

        var header = el('div', '');
        header.style.cssText = 'color:#34d399;margin-bottom:4px;cursor:pointer;';
        header.textContent = '🔍 SSE Debug (' + debugLog.length + ' events) — click to toggle';
        header.onclick = function() {
            var body = panel.querySelector('.debug-body');
            if (body) body.style.display = body.style.display === 'none' ? '' : 'none';
        };

        var body = el('div', 'debug-body');
        for (var i = Math.max(0, debugLog.length - 30); i < debugLog.length; i++) {
            var e = debugLog[i];
            var line = el('div', '');
            var color = e.agentId !== '-' ? '#a78bfa' : '#888';
            line.style.cssText = 'color:' + color + ';white-space:nowrap;';
            line.textContent = e.time + ' [' + e.type + ']' + (e.agentId !== '-' ? ' (' + e.agentId + ')' : '') + ' ' + e.data;
            body.appendChild(line);
        }

        panel.appendChild(header);
        panel.appendChild(body);
        document.body.appendChild(panel);
    }

    // 每 20 条事件刷新一次面板
    var debugRefreshCount = 0;
    function debugRefresh() {
        debugRefreshCount++;
        if (debugRefreshCount % 20 === 0) renderDebugPanel();
    }

    // ══════════════════════════════════════════
    // SessionWs 绑定 — 映射 SessionNode 事件到 Card 渲染
    // ══════════════════════════════════════════

    /** nodeId → Card 查找表（用于 SessionWs 实时更新） */
    var cardsByNodeId = {};

    /**
     * 将 SessionWs 绑定到 ClientCache。
     * @param {SessionWs} ws
     */
    function bindSessionWs(ws) {
        ws.onHistory(function(nodes) {
            console.log('[ClientCache] history:', nodes.length, 'nodes');
            cards = [];
            cardsById = {};
            cardsByNodeId = {};
            pendingAsstCard = null;
            pendingToolCard = null;
            subAgents = {};
            var c = getContainer();
            if (c) c.innerHTML = '';

            for (var i = 0; i < nodes.length; i++) {
                handleNodePush(nodes[i]);
            }
            scrollBottom();
        });

        ws.onNodePushed(function(node) {
            handleNodePush(node);
            scrollBottom();
        });

        ws.onNodeUpdated(function(nodeId, key, value) {
            handleNodeUpdate(nodeId, key, value);
        });

        ws.onStatusChanged(function(nodeId, oldStatus, newStatus) {
            handleStatusChange(nodeId, oldStatus, newStatus);
        });

        ws.onStreamingState(function(node) {
            // 抗刷新丢失：重连时收到正在流式中的节点
            // 如果该节点不在 cardsByNodeId 中（新连接场景），渲染它
            if (!cardsByNodeId[node.nodeId]) {
                console.log('[ClientCache] streamingState: rendering active stream node', node.nodeId);
                handleNodePush(node);
                scrollBottom();
            }
        });
    }

    /** 根据 SessionNode 创建/更新 Card */
    function handleNodePush(node) {
        removeEmpty();
        var nodeId = node.nodeId;
        var nodeType = node.type;
        var payload = node.payload || {};
        var status = node.status || (payload.status || 'PENDING');

        switch (nodeType) {
            case 'USER_INPUT':
                var userMsg = MessageStore.add({
                    msgId: nodeId,
                    role: 'user',
                    type: 'chat_user',
                    content: payload.text || '',
                    status: 'complete'
                });
                var uc = new UserCard(userMsg);
                uc.render();
                addCard(uc);
                cardsByNodeId[nodeId] = uc;
                break;

            case 'LLM_STREAMING':
                // 获取或创建 AssistantCard
                var asstId = nodeId;
                var asstMsg = MessageStore.add({
                    msgId: asstId,
                    role: 'assistant',
                    type: 'chat_assistant',
                    content: '',
                    status: 'pending'
                });
                var ac = new AssistantCard(asstMsg);
                ac.render();
                addCard(ac);
                ac.el.setAttribute('data-node-id', nodeId);
                cardsByNodeId[nodeId] = ac;
                pendingAsstCard = ac;
                break;

            case 'TOOL_CALL':
                var toolName = payload.tool || 'tool';
                var tc = new ToolCard(toolName, status === 'DONE' ? 'done' : status === 'ERROR' ? 'error' : 'pending');
                tc.render();
                if (tc.el) tc.el.setAttribute('data-node-id', nodeId);
                cardsByNodeId[nodeId] = tc;
                // 挂到当前 AssistantCard
                var parent = pendingAsstCard || findAsstCard(node.parentId);
                if (parent) {
                    parent.addBlock(tc);
                    if (!parent.toolCards) parent.toolCards = [];
                    parent.toolCards.push(tc);
                    parent.el.setAttribute('data-tool-' + nodeId, '');
                } else {
                    addCard(tc);
                }
                pendingToolCard = tc;
                break;

            case 'AGENT_MESSAGE':
                var subType = payload.subType || '';
                if (subType === 'simulation_content') {
                    var twc = new TweetCard(payload.title || '', payload.body || '');
                    twc.render();
                    addCard(twc);
                    cardsByNodeId[nodeId] = twc;
                } else {
                    var fakeMsg = {msgId: nodeId, content: payload.message || payload.content || ''};
                    var sc = new SystemCard(fakeMsg);
                    sc.render();
                    addCard(sc);
                    cardsByNodeId[nodeId] = sc;
                }
                break;

            case 'SYSTEM':
                var msgText = payload.message || payload.content || payload.text || '';
                if (payload.error) msgText += ' Error: ' + payload.error;
                var syc = new SystemCard({msgId: nodeId, content: msgText});
                syc.render();
                addCard(syc);
                cardsByNodeId[nodeId] = syc;
                break;
        }
    }

    /** 更新节点（流式内容追加等） */
    function handleNodeUpdate(nodeId, key, value) {
        if (key !== 'content') return;
        var card = cardsByNodeId[nodeId];
        if (!card) {
            // 可能是 LLM_STREAMING 节点的 content 更新
            card = pendingAsstCard;
        }
        if (!card) return;

        if (card instanceof AssistantCard || card.constructor.name === 'AssistantCard') {
            // 找到或创建 ContentBlock
            if (!card._streamBlock) {
                card._streamBlock = new ContentBlock('');
                card._streamBlock.render();
                card.addBlock(card._streamBlock);
            }
            card._streamBlock.append(value);
        }
    }

    /** 状态变更 */
    function handleStatusChange(nodeId, oldStatus, newStatus) {
        var card = cardsByNodeId[nodeId];
        if (!card) return;

        if (newStatus === 'DONE') {
            if (card instanceof ToolCard || card.constructor.name === 'ToolCard') {
                card.transition('done');
                pendingToolCard = null;
            }
            if (card instanceof AssistantCard || card.constructor.name === 'AssistantCard') {
                pendingAsstCard = null;
                card._streamBlock = null;
            }
        } else if (newStatus === 'ERROR') {
            if (card instanceof ToolCard || card.constructor.name === 'ToolCard') {
                card.transition('error');
                pendingToolCard = null;
            }
            if (card instanceof AssistantCard || card.constructor.name === 'AssistantCard') {
                if (card._streamBlock) {
                    card._streamBlock.append('\n[错误]');
                    card._streamBlock = null;
                }
                pendingAsstCard = null;
            }
        } else if (newStatus === 'STREAMING') {
            if (card instanceof ToolCard || card.constructor.name === 'ToolCard') {
                card.transition('running');
            }
        }
    }

    // ══════════════════════════════════════════
    // 导出
    // ══════════════════════════════════════════

    window.ClientCache = {
        dispatch: dispatch,
        createAssistantCard: createAssistantCard,
        addCard: addCard,
        findAsstCard: findAsstCard,
        renderAllMessages: renderAllMessages,
        reset: reset,
        bindSessionWs: bindSessionWs,
        renderDebugPanel: renderDebugPanel,
        get pendingAsstCard() { return pendingAsstCard; },
        set pendingAsstCard(c) { pendingAsstCard = c; },
        get pendingToolCard() { return pendingToolCard; },
        set pendingToolCard(c) { pendingToolCard = c; },
        get subAgents() { return subAgents; },
        get cards() { return cards; }
    };

    console.log('[ClientCache] initialized');
})();
