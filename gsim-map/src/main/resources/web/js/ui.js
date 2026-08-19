// ── Tool Switcher ──────────────────────────────────────
function setTool(t) {
  State.tool = t;
  ['btnPen','btnFill','btnEraser','btnProvince','btnPathway','btnMapEdit'].forEach(id => {
    const el = document.getElementById(id);
    if (el) el.classList.toggle('active', false);
  });
  const activeBtn = document.getElementById(
    t==='pen'?'btnPen':t==='fill'?'btnFill':t==='eraser'?'btnEraser':t==='province'?'btnProvince':t==='pathway'?'btnPathway':'btnMapEdit');
  if (activeBtn) activeBtn.classList.add('active');
  State.provinceLasso = [];
  State.lassoPts = [];
  if (t !== 'province') { State.relassoTarget = null; State.selectedProvince = null; State.activeTag = null; }
  if (t === 'pathway') {
    // Show both panels: left = group detail, right = group list
    showPathwayGroupList();
    if (State.activePathwayGroup) showPathwayGroupDetail(State.activePathwayGroup);
    document.getElementById('leftPanel').style.display = 'block';
    document.getElementById('rightPanel').style.display = 'block';
    document.getElementById('mapEditPanel').style.display = 'none';
    State.pathwayStart = null;
  } else if (t === 'province') {
    showTagList();
    document.getElementById('rightPanel').style.display = 'block';
    document.getElementById('mapEditPanel').style.display = 'none';
  } else if (t === 'mapedit') {
    document.getElementById('rightPanel').style.display = 'none';
  } else {
    document.getElementById('rightPanel').style.display = 'none';
    document.getElementById('mapEditPanel').style.display = 'none';
    document.getElementById('leftPanel').style.display = 'none';
    State.canvas._boundaryHexes = null;
    State.canvas.style.cursor = '';
  }
  render();
}

function setStatus(msg) { document.getElementById('statusBar').textContent = msg; }

// ── Info Panel ──────────────────────────────────────────
function showHexDetail(q, r) {
  const key = `${q}_${r}`;
  const cell = (State.mapData?.hexes || {})[key];

  const body = document.getElementById('leftBody');
  const title = document.getElementById('leftTitle');

  if (!cell) {
    document.getElementById('leftPanel').style.display = 'none';
    return;
  }

  // Check if this hex belongs to a compressed region
  const compMeta = State.mapData._compressedMeta?.get(key);
  const compLabel = compMeta
    ? ` <span style="font-size:10px;color:var(--accent);background:#333;padding:1px 6px;border-radius:3px">压缩区域 · ${escapeHtml(compMeta.terrain)} ×${escapeHtml(String(compMeta.size))}格</span>`
    : '';

  let provName = '';
  for (const [pname, prov] of Object.entries(State.mapData.provinces || {})) {
    if (prov.hexes?.includes(key)) { provName = pname; break; }
  }

  const tt = State.terrainTypes[cell.terrain] || {};
  title.innerHTML = `📍 (${q}, ${r})${compLabel}`;
  document.getElementById('leftPanel').style.display = 'block';

  // ── 可编辑标签表单（key-value）──
  if (!cell.tags) cell.tags = {};
  const tagRows = Object.entries(cell.tags).map(([k, v]) => `
      <div class="hex-tag-row" data-q="${q}" data-r="${r}" data-orig-key="${escapeAttr(k)}" style="display:flex;gap:4px;align-items:center;margin-bottom:4px">
        <input type="text" class="hex-tag-key" placeholder="key" value="${escapeAttr(k)}" style="width:34%;flex:none;min-width:0">
        <input type="text" class="hex-tag-value" placeholder="value" value="${escapeAttr(v == null ? '' : v)}" style="flex:1;min-width:0">
        <button type="button" class="hex-tag-remove" title="删除标签" style="font-size:10px;flex:none">✕</button>
      </div>`).join('');
  const tagsField = `<div class="field"><label>标签</label>
    <div id="hexTagRows">${tagRows}</div>
    <button type="button" id="hexTagAdd" data-q="${q}" data-r="${r}" style="font-size:10px;margin-top:4px">+ 添加标签</button>
  </div>`;

  body.innerHTML = `
    <div class="field"><label>地形</label>
      <span class="val"><span id="hexColorSwatch" style="display:inline-block;width:12px;height:12px;border-radius:2px;margin-right:4px;vertical-align:middle"></span>${escapeHtml(cell.terrain)}</span>
    </div>
    <div class="field"><label>产出</label>
      <span class="val">\u{1F56F}${tt.food||0} \u{1F4B0}${tt.gold||0} \u{1FAA8}${tt.stone||0} \u{1F463}${tt.moveCost||0}</span>
    </div>
    ${cell.symbol ? `<div class="field"><label>符号</label><span class="val">${escapeHtml(cell.symbol)}</span></div>` : ''}
    ${cell.riverMask > 0 ? `<div class="field"><label>河流</label><span class="val">掩码: ${cell.riverMask}</span></div>` : ''}
    <div class="field"><label>描述</label>
      <textarea rows="2" onchange="updateHexDesc(${q},${r},this.value)">${escapeHtml(cell.description||'')}</textarea>
    </div>
    ${provName ? `<div class="field"><label>所属区域</label>
      <span class="val prov-link" style="cursor:pointer;color:var(--accent);text-decoration:underline">${escapeHtml(provName)}</span>
    </div>` : '<div class="field" style="color:var(--dim)">不属于任何区域</div>'}
    ${tagsField}
    <div style="margin-top:12px">
      <button onclick="document.getElementById('leftPanel').style.display='none'" style="font-size:10px">关闭</button>
    </div>`;

  const swatch = body.querySelector('#hexColorSwatch');
  if (swatch) swatch.style.background = cell.color || getTerrainColor(cell.terrain);

  const provLink = body.querySelector('.prov-link');
  if (provLink) provLink.addEventListener('click', () => selectProvince(provName));
}

function updateHexDesc(q, r, val) {
  const key = `${q}_${r}`;
  if (!State.mapData.hexes[key]) State.mapData.hexes[key] = {color:'#808080',terrain:'unknown'};
  State.mapData.hexes[key].description = val;
}

// ── Editable Hex Tags (key-value) ──────────────────────
const leftBodyEl = document.getElementById('leftBody');
leftBodyEl.addEventListener('change', e => {
  const el = e.target;
  const row = el.closest('.hex-tag-row');
  if (!row) return;
  const { q, r, origKey } = row.dataset;
  if (el.classList.contains('hex-tag-key')) {
    if (updateHexTagKey(q, r, origKey, el.value)) row.dataset.origKey = el.value;
  } else if (el.classList.contains('hex-tag-value')) {
    updateHexTagValue(q, r, origKey, el.value);
  }
});
leftBodyEl.addEventListener('click', e => {
  const addBtn = e.target.closest('#hexTagAdd');
  if (addBtn) { addHexTag(addBtn.dataset.q, addBtn.dataset.r); return; }
  const rmBtn = e.target.closest('.hex-tag-remove');
  if (rmBtn) {
    const { q, r, origKey } = rmBtn.closest('.hex-tag-row').dataset;
    removeHexTag(q, r, origKey);
  }
});

function updateHexTagKey(q, r, origKey, newKey) {
  const key = `${q}_${r}`;
  const hex = State.mapData.hexes[key];
  if (!hex) return false;
  if (!hex.tags) hex.tags = {};
  if (!newKey) {
    if (origKey === '' && hex.tags[''] !== undefined && hex.tags[''] !== '') {
      delete hex.tags[''];
      render();
    }
    return false;
  }
  if (newKey === origKey) return false;
  const oldVal = hex.tags[origKey];
  delete hex.tags[origKey];
  hex.tags[newKey] = oldVal === undefined ? '' : oldVal;
  render();
  return true;
}

function updateHexTagValue(q, r, key, val) {
  const hexKey = `${q}_${r}`;
  const hex = State.mapData.hexes[hexKey];
  if (!hex) return;
  if (!hex.tags) hex.tags = {};
  hex.tags[key] = val;
  render();
}

function removeHexTag(q, r, key) {
  const hexKey = `${q}_${r}`;
  const hex = State.mapData.hexes[hexKey];
  if (!hex || !hex.tags) return;
  delete hex.tags[key];
  showHexDetail(q, r);
  render();
}

function addHexTag(q, r) {
  const hexKey = `${q}_${r}`;
  const hex = State.mapData.hexes[hexKey];
  if (!hex) return;
  if (!hex.tags) hex.tags = {};
  if (hex.tags[''] === undefined) hex.tags[''] = '';
  showHexDetail(q, r);
}

// ── Keyboard Shortcuts ─────────────────────────────────
document.addEventListener('keydown', e => {
  if (e.ctrlKey && e.key==='s') { e.preventDefault(); saveMap(); }
  if (e.key==='f' && !e.ctrlKey) { fitView(); }
  if (e.key==='p' && !e.ctrlKey) setTool('pen');
  if (e.key==='e' && !e.ctrlKey) setTool('eraser');
});

// ── Generator Panel Toggle ─────────────────────────────
let genCollapsed = false;
function toggleGenPanel() {
  genCollapsed = !genCollapsed;
  document.getElementById('genBody').classList.toggle('collapsed', genCollapsed);
  document.getElementById('genArrow').textContent = genCollapsed ? '▶' : '▼';
}
function syncGenRange(id) {
  const r = document.getElementById(id);
  document.getElementById(id+'Val').textContent = r.value;
}

// ── Latest Texts Panel ─────────────────────────────────
let latestCollapsed = false;
function toggleLatestPanel() {
  latestCollapsed = !latestCollapsed;
  document.getElementById('latestBody').classList.toggle('collapsed', latestCollapsed);
  document.getElementById('latestArrow').textContent = latestCollapsed ? '▶' : '▼';
}

async function loadLatestTexts() {
  const body = document.getElementById('latestBody');
  body.innerHTML = '<div style="color:var(--dim);font-size:10px;padding:4px">加载中...</div>';
  try {
    const nodeParam = MapAPI.nodeId ? `?node=${MapAPI.nodeId}` : '';
    const r = await fetch(`/api/map/${MapAPI.worldId}/latest-texts${nodeParam}`);
    if (!r.ok) throw new Error(r.status);
    const data = await r.json();
    const texts = data.texts || [];
    if (texts.length === 0) {
      body.innerHTML = '<div style="color:var(--dim);font-size:10px;padding:4px">暂无推文</div>';
      return;
    }
    body.innerHTML = texts.map(t => {
      const src = t.checkpoint === 'narrative' ? '📜 推文' : '🏛 势力';
      const key = t.key || '';
      return `<div class="item">
        <div class="src">${src} · ${key}</div>
        <div class="text" title="点击全选后复制">${escapeHtml(t.value||'')}</div>
      </div>`;
    }).join('');
  } catch(e) {
    body.innerHTML = `<div style="color:var(--dim);font-size:10px;padding:4px">加载失败: ${e.message}</div>`;
  }
}

function escapeHtml(s) {
  return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
}

// Attribute-context escaping: also escapes " so user input cannot break out of value="..." / data-*="...".
function escapeAttr(s) {
  return escapeHtml(s).replace(/"/g,'&quot;');
}

// Auto-load on startup
setTimeout(loadLatestTexts, 2000);

// ── Map Edit Panel ─────────────────────────────────────
function toggleMapEdit() {
  const panel = document.getElementById('mapEditPanel');
  panel.style.display = panel.style.display === 'block' ? 'none' : 'block';
  setTool('mapedit');
}

function toggleMeSection(header) {
  const arrow = header.querySelector('.me-arrow');
  const body = header.nextElementSibling;
  body.classList.toggle('collapsed');
  arrow.classList.toggle('rotated');
}

async function doCompress() {
  const minSize = parseInt(document.getElementById('compressMinSize').value) || 100;
  const info = document.getElementById('compressInfo');
  info.textContent = '压缩中...';
  try {
    const nodeParam = MapAPI.nodeId ? `&node=${MapAPI.nodeId}` : '';
    const r = await fetch(`/api/map/${MapAPI.worldId}/compress?minSize=${minSize}${nodeParam}`, {method:'POST'});
    const data = await r.json();
    if (data.ok) {
      info.textContent = `✅ ${data.compressedCount} 格 → ${data.regions} 个区域 (${data.compressionRatio})`;
      showToast(`压缩完成: ${data.compressedCount} 格 → ${data.regions} 个区域`);
      await loadMap();
    } else {
      info.textContent = `❌ ${data.error || '失败'}`;
      showToast('压缩失败: ' + (data.error || 'unknown'));
    }
  } catch(e) {
    info.textContent = `❌ ${e.message}`;
    showToast('压缩失败: ' + e.message);
  }
}
