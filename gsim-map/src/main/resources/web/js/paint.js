// ── Paint Tools ─────────────────────────────────────────
function applyTool(q, r) {
  const key = q + '_' + r;
  if (!State.mapData.hexes) State.mapData.hexes = {};

  if (State.tool === 'eraser') {
    delete State.mapData.hexes[key];
  } else if (State.tool === 'fill') {
    floodFill(key, State.activeTerrain);
  } else {
    const cell = State.mapData.hexes[key];
    if (!cell) {
      State.mapData.hexes[key] = {color: getTerrainColor(State.activeTerrain), terrain: State.activeTerrain, riverMask: 0};
    } else {
      cell.color = getTerrainColor(State.activeTerrain);
      cell.terrain = State.activeTerrain;
    }
  }
  render();
}

function floodFill(seedKey, terrain) {
  if (!State.mapData.hexes[seedKey]) return;
  const targetTerrain = State.mapData.hexes[seedKey].terrain;
  if (targetTerrain === terrain) return;
  const color = getTerrainColor(terrain);
  const visited = new Set();
  const stack = [seedKey];
  const MAX_FILL = 5000;
  while (stack.length && visited.size < MAX_FILL) {
    const key = stack.pop();
    if (visited.has(key)) continue;
    visited.add(key);
    const cell = State.mapData.hexes[key];
    if (!cell || cell.terrain !== targetTerrain) continue;
    cell.color = color;
    cell.terrain = terrain;
    const [q, r] = key.split('_').map(Number);
    for (const [dq, dr] of DIR_VECTORS) {
      stack.push((q+dq) + '_' + (r+dr));
    }
  }
}

// ── Terrain Lasso ──────────────────────────────────────
function applyTerrainLasso(q, r) {
  if (State.lassoPts.length > 2 && q === State.lassoPts[0].q && r === State.lassoPts[0].r) {
    finishTerrainLasso();
    return;
  }
  if (State.lassoPts.length > 0) {
    const last = State.lassoPts[State.lassoPts.length - 1];
    const line = hexLine(last.q, last.r, q, r);
    for (let i = 1; i < line.length; i++) State.lassoPts.push(line[i]);
  } else {
    State.lassoPts.push({q, r});
  }
  setStatus(`地形套索: ${State.lassoPts.length} 点 — 点起点闭合`);
  render();
  renderLassoPreview();
}

function finishTerrainLasso() {
  const terrain = State.activeTerrain;
  const rawKeys = State.lassoPts.map(p => `${p.q}_${p.r}`);
  State.lassoPts = [];
  fetch(`/api/map/${MapAPI.worldId}/blocks`, {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({terrain, lassoKeys: rawKeys, seedKey: rawKeys[0]})
  }).then(r => r.json()).then(async data => {
    if (data.ok) {
      showToast(`${terrain} 区域已添加`);
      await loadMap();
      render();
    } else {
      showToast('添加失败: ' + (data.reason || 'unknown'));
    }
  }).catch(e => showToast('添加失败: '+e.message));
}

// ── RDP Boundary Simplification ────────────────────────
function simplifyBoundary(pts, epsilon) {
  if (pts.length < 4) return pts.slice();
  const result = rdp(pts, 0, pts.length - 1, epsilon);
  return result;
}

function rdp(pts, start, end, eps) {
  let maxDist = 0, maxIdx = start;
  const a = pts[start], b = pts[end];
  for (let i = start + 1; i < end; i++) {
    const d = perpDist(pts[i], a, b);
    if (d > maxDist) { maxDist = d; maxIdx = i; }
  }
  if (maxDist > eps) {
    const left = rdp(pts, start, maxIdx, eps);
    const right = rdp(pts, maxIdx, end, eps);
    left.pop();
    return left.concat(right);
  }
  return [{x:a.x, y:a.y}, {x:b.x, y:b.y}];
}

function perpDist(p, a, b) {
  const dx = b.x - a.x, dy = b.y - a.y;
  if (Math.abs(dx) < 0.001 && Math.abs(dy) < 0.001)
    return Math.hypot(p.x - a.x, p.y - a.y);
  let t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / (dx * dx + dy * dy);
  t = Math.max(0, Math.min(1, t));
  const px = a.x + t * dx, py = a.y + t * dy;
  return Math.hypot(p.x - px, p.y - py);
}

function renderLassoPreview() {
  State.ctx.save();
  State.ctx.translate(State.offX, State.offY);
  State.ctx.scale(State.zoom, State.zoom);
  State.ctx.strokeStyle = '#FFD700';
  State.ctx.lineWidth = 2 / State.zoom;
  State.ctx.setLineDash([4/State.zoom, 4/State.zoom]);
  State.ctx.beginPath();
  for (let i = 0; i < State.lassoPts.length; i++) {
    const {x, y} = hexToPixel(State.lassoPts[i].q, State.lassoPts[i].r);
    if (i === 0) State.ctx.moveTo(x, y);
    else State.ctx.lineTo(x, y);
  }
  if (State.lassoPts.length > 2) State.ctx.closePath();
  State.ctx.stroke();
  State.ctx.setLineDash([]);
  State.ctx.restore();
}
