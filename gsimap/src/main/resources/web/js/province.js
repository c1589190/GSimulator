// ── Province Tool ──────────────────────────────────────
function findProvinceAt(q, r) {
  if (!State.mapData?.provinces) return null;
  const key = `${q}_${r}`;
  for (const [name, p] of Object.entries(State.mapData.provinces)) {
    if (p.hexes?.includes(key)) return name;
  }
  return null;
}

function createEmptyRegion() {
  const defTag = State.activeTag || '';
  const tagColor = State.activeTag && State.mapData.tags[State.activeTag] ? State.mapData.tags[State.activeTag].color : null;
  const defColor = tagColor || '#'+Math.floor(Math.random()*16777215).toString(16).padStart(6,'0');
  const name = prompt('区域名称:', '区域'+(Object.keys(State.mapData.provinces||{}).length+1));
  if (!name) return;
  const color = prompt('区域颜色:', defColor);
  if (!State.mapData.provinces) State.mapData.provinces = {};
  const desc = prompt('区域描述 (可选):', '');
  State.mapData.provinces[name] = {hexes:[], color:color||'#ff0000', tag:State.activeTag||'', description:desc||''};
  State.selectedProvince = name;
  showRegionDetail(name); showTagList(); render();
}

function showRegionList() {
  const body = document.getElementById('rightBody');
  const title = document.getElementById('rightTitle');
  const provs = State.mapData?.provinces || {};
  const names = Object.keys(provs);
  const tags = [...new Set(names.map(n => provs[n].tag).filter(Boolean))].sort();

  let html = `<div style="margin-bottom:6px;font-size:11px">
    标签过滤: <select onchange="filterByTag(this.value)" style="background:var(--bg);color:var(--text);border:1px solid var(--border);border-radius:2px;font-size:11px;padding:1px 4px;width:100px">
      <option value="">全部</option>
      ${tags.map(t => `<option value="${t}" ${State.activeTag===t?'selected':''}>${t}</option>`).join('')}
    </select>
    <button onclick="createEmptyRegion()" style="float:right;font-size:11px;background:var(--accent);border:none;color:#fff;border-radius:4px;padding:2px 8px;cursor:pointer">+ 新建</button>
    <a href="#" onclick="showTagList();return false" style="font-size:10px;color:var(--dim);margin-right:6px">标签管理</a>
  </div>`;

  title.textContent = `📐 区域 (${names.length})`;
  const filtered = State.activeTag ? names.filter(n => provs[n].tag === State.activeTag) : names;
  if (filtered.length === 0) {
    html += '<div style="color:var(--dim);font-size:11px;padding:4px">📐右键拖圈创建区域，或➕新建</div>';
  } else {
    html += filtered.map(name => {
      const p = provs[name];
      const sel = State.selectedProvince === name ? 'border:1px solid var(--accent);' : '';
      const annexed = p.annexedBy && p.annexedBy !== '';
      const style = annexed
        ? 'opacity:0.5;text-decoration:line-through;'
        : '';
      const annexedBadge = annexed
        ? `<span style="color:#e74c3c;font-size:9px;margin-left:2px" title="已被${p.annexedBy}吞并">⚔${p.annexedBy}</span>`
        : '';
      return `<div style="margin-bottom:4px;padding:6px;background:var(--bg);border-radius:4px;cursor:pointer;${sel}${style}"
        onclick="selectProvince('${name}')">
        <span style="display:inline-block;width:10px;height:10px;border-radius:2px;background:${p.color||'#888'};margin-right:6px"></span>
        <b>${name}</b> <span style="color:var(--dim);font-size:10px">${(p.hexes||[]).length}格</span>
        ${p.tag ? '<span style="background:#333;color:#aaa;font-size:9px;padding:1px 4px;border-radius:2px">'+p.tag+'</span>' : ''}
        ${annexedBadge}
      </div>`;
    }).join('');
  }
  body.innerHTML = html;
  document.getElementById('rightPanel').style.display = 'block';
}

function showRegionDetail(name) {
  const body = document.getElementById('leftBody');
  const title = document.getElementById('leftTitle');
  const p = State.mapData?.provinces?.[name];
  if (!p) return;

  const isAnnexed = p.annexedBy && p.annexedBy !== '';
  const keys = p.hexes || [];
  const hexCount = keys.length;
  const mapTotal = Object.keys(State.mapData?.hexes || {}).length;

  let sq = 0, sr = 0;
  for (const k of keys) { const [q,r] = k.split('_').map(Number); sq += q; sr += r; }
  const cq = Math.round(sq / Math.max(1, hexCount));
  const cr = Math.round(sr / Math.max(1, hexCount));

  const comp = {};
  for (const k of keys) {
    const cell = State.mapData.hexes?.[k];
    if (cell) comp[cell.terrain] = (comp[cell.terrain]||0) + 1;
  }

  const ownSet = new Set(keys);
  const adj = [];
  for (const [oname, op] of Object.entries(State.mapData.provinces || {})) {
    if (oname === name) continue;
    let edges = 0;
    for (const hk of keys) {
      const [q,r] = hk.split('_').map(Number);
      for (const [dq,dr] of DIR_VECTORS) {
        if ((op.hexes||[]).includes((q+dq)+'_'+(r+dr))) edges++;
      }
    }
    if (edges > 0) adj.push({name: oname, sharedEdges: edges, color: op.color, tag: op.tag});
  }
  adj.sort((a,b) => b.sharedEdges - a.sharedEdges);

  title.textContent = isAnnexed ? `📐 区域 (已吞并)` : `📐 区域`;
  document.getElementById('leftPanel').style.display = 'block';

  const annexedNote = isAnnexed
    ? `<div class="field" style="color:#e74c3c;font-weight:600">⚠ 已被「${p.annexedBy}」吞并 — 不在地图上显示</div>`
    : '';

  body.innerHTML = `<div class="field"><label>名称</label>
      <input value="${name}" onchange="renameProvince('${name}',this.value)" style="font-size:13px;font-weight:600;${isAnnexed?'text-decoration:line-through;color:var(--dim)':''}">
    </div>
    ${annexedNote}
    <div class="field">
      <span style="display:inline-block;width:12px;height:12px;border-radius:3px;background:${p.color||'#888'};margin-right:4px;vertical-align:middle"></span>
      <input value="${p.tag||''}" onchange="updateProvTag('${name}',this.value);showRegionDetail('${name}')" placeholder="tag" style="width:80px;font-size:11px">
      <span style="color:var(--dim);font-size:10px;margin-left:4px">${hexCount}格 / ${mapTotal}总</span>
    </div>
    <div class="field"><label>颜色</label>
      <input type="color" value="${p.color||'#ff0000'}" onchange="updateProvColor('${name}',this.value)" style="width:30px;height:20px">
    </div>
    <div class="field"><label>中心点</label>
      <span class="val">(${cq}, ${cr})</span>
    </div>
    <div class="field"><label>描述</label>
      <textarea rows="2" onchange="updateProvDesc('${name}',this.value)">${p.description||''}</textarea>
    </div>
    ${Object.keys(comp).length > 0 ? `<div class="field"><label>地形构成</label>
      <div>${Object.entries(comp).map(([t,c]) =>
        `<span class="terrain-chip" style="background:${getTerrainColor(t)}">${t} ×${c}</span>`
      ).join(' ')}</div>
    </div>` : ''}
    ${adj.length > 0 ? `<div class="field"><label>邻接区域 (${adj.length})</label>
      <div>${adj.map(a =>
        `<span class="adjacent-item" onclick="selectProvince('${a.name}')" title="${a.name} tag:${a.tag||'无'} | 共享边:${a.sharedEdges}">
          <span style="display:inline-block;width:6px;height:6px;border-radius:1px;background:${a.color};margin-right:2px"></span>${a.name} ${a.sharedEdges}
        </span>`
      ).join('')}</div>
    </div>` : '<div class="field" style="color:var(--dim)">无邻接区域</div>'}
    ${!isAnnexed ? `<button onclick="mergeIntoRegion('${name}')" style="margin-top:4px;background:#e67e22;border-color:#e67e22;color:#fff">⚔ 吞并</button>` : ''}
    <button onclick="relassoProvince('${name}')" style="margin-top:4px" title="右键拖动重新划定区域边界">🔄 重圈</button>
    <button onclick="deleteProvince('${name}')" style="color:#e74c3c;border-color:#e74c3c;margin-top:4px;margin-left:4px">🗑 删除</button>
    <button onclick="document.getElementById('leftPanel').style.display='none';showTagList()" style="margin-top:4px;margin-left:4px">← 关闭详情</button>`;
}

function filterByTag(tag) {
  State.activeTag = tag || null;
  if (State.activeTag) State.selectedProvince = null;
  showTagList();
  render();
}

function selectProvince(name) {
  State.activeTag = null;
  State.selectedProvince = name;
  State.canvas._boundaryCache = null;
  setTool('province');
  showRegionDetail(name);
  render();
}

function relassoProvince(name) {
  if (!State.mapData.provinces[name]) return;
  const p = State.mapData.provinces[name];
  p.hexes = [];
  State.canvas._boundaryCache = null;
  delete State.canvas['b_' + name];
  State.selectedProvince = name;
  State.provinceLasso = [];
  State.relassoTarget = name;
  setTool('province');
  showRegionDetail(name);
  render();
  setStatus(`重圈「${name}」: 右键拖动圈出新边界`);
}

function deleteProvince(name) {
  if (confirm(`删除区域「${name}」？`)) {
    delete State.mapData.provinces[name];
    if (State.selectedProvince === name) State.selectedProvince = null;
    State.relassoTarget = null;
    showTagList();
    render();
  }
}

// ── Province updates ───────────────────────────────────
function updateProvTag(name, val) {
  if (State.mapData.provinces[name]) State.mapData.provinces[name].tag = val;
}
function updateProvDesc(name, val) {
  if (State.mapData.provinces[name]) State.mapData.provinces[name].description = val;
}
async function renameProvince(oldName, newName) {
  if (!newName || newName === oldName || !State.mapData.provinces[oldName]) return;
  if (State.mapData.provinces[newName]) { showToast('名称已存在'); return; }

  try {
    const r = await fetch(`/api/map/${MapAPI.worldId}/rename-region`, {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({oldName, newName})
    });
    const data = await r.json();
    if (data.ok) {
      // Reload map to get updated data from server
      await loadMap();
      if (State.selectedProvince === oldName) State.selectedProvince = newName;
      showRegionDetail(newName);
      showTagList();
      render();
      showToast(`已改名: ${oldName} → ${newName}`);
    } else {
      showToast('改名失败: ' + (data.error || 'unknown'));
    }
  } catch(e) {
    showToast('改名失败: ' + e.message);
  }
}
function updateProvColor(name, val) {
  if (State.mapData.provinces[name]) State.mapData.provinces[name].color = val;
  render();
}

// ── Province Lasso ─────────────────────────────────────
function addProvincePoint(q, r) {
  if (State.provinceLasso.length > 0) {
    const last = State.provinceLasso[State.provinceLasso.length - 1];
    if (last.q === q && last.r === r) return;
    const line = hexLine(last.q, last.r, q, r);
    for (let i = 1; i < line.length; i++) State.provinceLasso.push(line[i]);
  } else { State.provinceLasso.push({q, r}); }
  renderProvincePreview();
}

function renderProvincePreview() {
  if (State.provinceLasso.length < 2) return;
  State.ctx.save(); State.ctx.translate(State.offX, State.offY); State.ctx.scale(State.zoom, State.zoom);
  State.ctx.strokeStyle = '#ff00ff'; State.ctx.lineWidth = 2/State.zoom;
  State.ctx.beginPath();
  for (const p of State.provinceLasso) { const {x,y}=hexToPixel(p.q,p.r); State.ctx.lineTo(x,y); }
  State.ctx.closePath(); State.ctx.stroke(); State.ctx.restore();
}

function finishProvinceLasso() {
  if (State.provinceLasso.length < 3) { State.provinceLasso=[]; render(); return; }
  const boundSet = new Set(State.provinceLasso.map(p => p.q+'_'+p.r));
  let sq=0,sr=0; for (const k of boundSet) { const [q,r]=k.split('_').map(Number); sq+=q; sr+=r; }
  const cq=Math.round(sq/boundSet.size), cr=Math.round(sr/boundSet.size);
  let seed = null;
  for (let rad=0; rad<200 && !seed; rad++) {
    if (rad===0) { const k=cq+'_'+cr; if(!boundSet.has(k)&&State.mapData.hexes[k]){seed={q:cq,r:cr};break;} continue; }
    let qq=cq+rad*DIR_VECTORS[4][0], rr=cr+rad*DIR_VECTORS[4][1];
    for (let d=0;d<6&&!seed;d++) for (let s=0;s<rad;s++) {
      const k=qq+'_'+rr; if(!boundSet.has(k)&&State.mapData.hexes[k]){seed={q:qq,r:rr};break;}
      qq+=DIR_VECTORS[d][0]; rr+=DIR_VECTORS[d][1];
    }
  }
  if (!seed) { State.provinceLasso=[]; render(); return; }
  const interior=new Set(), queue=[seed], visited=new Set();
  while (queue.length) {
    const {q,r}=queue.pop(), k=q+'_'+r;
    if(visited.has(k))continue; visited.add(k); interior.add(k);
    for (const [dq,dr] of DIR_VECTORS) {
      const nk=(q+dq)+'_'+(r+dr);
      if(!boundSet.has(nk)&&!visited.has(nk)&&State.mapData.hexes[nk]) queue.push({q:q+dq,r:r+dr});
    }
  }

  if (State.relassoTarget) {
    const targetName = State.relassoTarget;
    const p = State.mapData.provinces[targetName];
    if (p) { p.hexes = [...interior, ...boundSet]; }
    State.canvas._boundaryCache = null;
    delete State.canvas['b_' + targetName];
    State.provinceLasso = []; State.relassoTarget = null; State.selectedProvince = targetName;
    setStatus(`已重圈: ${targetName}`);
    showRegionDetail(targetName); showTagList(); render(); return;
  }

  const name = prompt('区域名称:', '区域'+(Object.keys(State.mapData.provinces||{}).length+1));
  const color = prompt('区域颜色 (hex):', '#'+Math.floor(Math.random()*16777215).toString(16).padStart(6,'0'));
  if (!name) { State.provinceLasso=[]; render(); return; }
  if (!State.mapData.provinces) State.mapData.provinces = {};
  const desc = prompt('区域描述 (可选):', '');
  State.mapData.provinces[name] = {hexes:[...interior,...boundSet], color:color||'#ff0000', tag:State.activeTag||'',description:desc||''};
  State.provinceLasso=[]; State.selectedProvince=name;
  showRegionDetail(name); showTagList(); render();
}

// ── Region Merge ────────────────────────────────────────
async function mergeIntoRegion(dominantName) {
  const provs = State.mapData?.provinces || {};
  const targets = Object.keys(provs).filter(n =>
    n !== dominantName && (!provs[n].annexedBy || provs[n].annexedBy === '')
  );
  if (targets.length === 0) { showToast('没有可吞并的区域'); return; }

  // Build selection modal content
  let listHtml = targets.map(name => {
    const p = provs[name];
    return `<div class="merge-option" onclick="confirmMerge('${dominantName}','${name}')"
      style="padding:6px 8px;margin:2px 0;background:var(--bg);border-radius:4px;cursor:pointer;display:flex;align-items:center;gap:6px">
      <span style="display:inline-block;width:10px;height:10px;border-radius:2px;background:${p.color||'#888'}"></span>
      <b>${name}</b>
      <span style="color:var(--dim);font-size:10px">${(p.hexes||[]).length}格</span>
      ${p.tag ? '<span style="background:#333;color:#aaa;font-size:9px;padding:1px 4px;border-radius:2px">'+p.tag+'</span>' : ''}
    </div>`;
  }).join('');

  const overlay = document.createElement('div');
  overlay.className = 'goat-modal-overlay';
  overlay.innerHTML = `
    <div class="goat-modal" style="max-width:360px">
      <h3>⚔ 吞并区域</h3>
      <p style="font-size:12px;color:var(--dim)">选择要吞并的目标区域，其领土将全部归入「${dominantName}」。</p>
      <div style="max-height:300px;overflow-y:auto">${listHtml}</div>
      <div class="goat-modal-buttons">
        <button class="goat-btn-cancel">取消</button>
      </div>
    </div>`;
  overlay.querySelector('.goat-btn-cancel').onclick = () => overlay.remove();
  overlay.onclick = (e) => { if (e.target === overlay) overlay.remove(); };
  document.body.appendChild(overlay);
}

async function confirmMerge(dominantName, annexedName) {
  // Remove the selection modal (find and remove all overlays)
  document.querySelectorAll('.goat-modal-overlay').forEach(o => o.remove());

  // Confirmation dialog
  const dominant = State.mapData.provinces[dominantName];
  const annexed = State.mapData.provinces[annexedName];
  const annexedHexCount = (annexed.hexes||[]).length;

  const overlay = document.createElement('div');
  overlay.className = 'goat-modal-overlay';
  overlay.innerHTML = `
    <div class="goat-modal" style="max-width:380px">
      <h3>⚔ 确认吞并</h3>
      <p style="font-size:13px;line-height:1.6">
        确定让 <b style="color:${dominant.color||'#fff'}">${dominantName}</b> 吞并
        <b style="color:${annexed.color||'#888'}">${annexedName}</b> ？
      </p>
      <p style="font-size:11px;color:var(--dim)">
        ${annexedName} 的 <b>${annexedHexCount}</b> 个领土格子将全部归入 ${dominantName}。<br>
        ${annexedName} 的数据将保留，标记为已被吞并，不再显示在地图上。
      </p>
      <div class="goat-modal-buttons">
        <button class="goat-btn-cancel">取消</button>
        <button class="goat-btn-confirm" style="background:#e67e22;border-color:#e67e22">⚔ 确认吞并</button>
      </div>
    </div>`;
  overlay.querySelector('.goat-btn-cancel').onclick = () => overlay.remove();
  overlay.querySelector('.goat-btn-confirm').onclick = async () => {
    overlay.remove();
    await doMerge(dominantName, annexedName);
  };
  overlay.onclick = (e) => { if (e.target === overlay) overlay.remove(); };
  document.body.appendChild(overlay);
}

async function doMerge(dominantName, annexedName) {
  try {
    const r = await fetch(`/api/map/${MapAPI.worldId}/merge-regions`, {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({dominantName, annexedName})
    });
    const data = await r.json();
    if (data.ok) {
      // Reload map to get updated data from server
      await loadMap();
      State.selectedProvince = dominantName;
      showRegionDetail(dominantName);
      showTagList();
      render();
      showToast(`吞并完成: ${annexedName} → ${dominantName} (${data.transferredHexes}格已转移)`);
      // Auto-save after merge
      await saveMap();
      setStatus(`已吞并并保存: ${annexedName} 并入 ${dominantName}`);
    } else {
      showToast('吞并失败: ' + (data.error || 'unknown'));
    }
  } catch(e) {
    showToast('吞并失败: ' + e.message);
  }
}
