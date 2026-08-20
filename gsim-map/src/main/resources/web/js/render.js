// ── Rendering ───────────────────────────────────────────
function render() {
  State.ctx.clearRect(0, 0, State.canvas.width, State.canvas.height);
  State.ctx.save();
  State.ctx.translate(State.offX, State.offY);
  State.ctx.scale(State.zoom, State.zoom);

  const invZoom = 1 / State.zoom;
  const vpLeft   = -State.offX * invZoom - GRID;
  const vpTop    = -State.offY * invZoom - GRID;
  const vpRight  = (-State.offX + State.canvas.width)  * invZoom + GRID;
  const vpBottom = (-State.offY + State.canvas.height) * invZoom + GRID;

  const entries = Object.entries(State.mapData.hexes || {});
  const hexCount = entries.length;

  if (hexCount === 0 && (!State.mapData.terrainBlocks || State.mapData.terrainBlocks.length === 0)) {
    State.ctx.strokeStyle = '#ffffff22';
    State.ctx.lineWidth = 1 / State.zoom;
    State.ctx.beginPath(); State.ctx.moveTo(-200, 0); State.ctx.lineTo(200, 0); State.ctx.stroke();
    State.ctx.beginPath(); State.ctx.moveTo(0, -200); State.ctx.lineTo(0, 200); State.ctx.stroke();
    State.ctx.beginPath(); State.ctx.arc(0, 0, 50, 0, Math.PI * 2); State.ctx.stroke();
    State.ctx.restore();
    State.ctx.fillStyle = '#ffffff44';
    State.ctx.font = '16px sans-serif';
    State.ctx.textAlign = 'center';
    State.ctx.fillText('空画布 — 右键拖动圈出地形，选地形填充', State.canvas.width/2, State.canvas.height/2 + 70);
    return;
  }
  let drawn = 0;

  // ── Helper: draw a batch of hexes ──
  function drawHexBatch(hexesByColor) {
    for (const [color, hexes] of Object.entries(hexesByColor)) {
      State.ctx.fillStyle = color;
      for (const {x, y} of hexes) {
        State.ctx.beginPath();
        const corners = hexCorners(x, y, GRID - 1);
        State.ctx.moveTo(corners[0][0], corners[0][1]);
        for (let i = 1; i < 6; i++) State.ctx.lineTo(corners[i][0], corners[i][1]);
        State.ctx.closePath();
        State.ctx.fill();
        State.ctx.stroke();
      }
    }
  }

  State.ctx.strokeStyle = '#ffffff18';
  State.ctx.lineWidth = 0.5 / State.zoom;

  // ── Pass 1: Compressed regions as filled polygons ──
  // Sort by size ascending: small CRs drawn first (bottom), large CRs last (top).
  // Uses evenodd fill so that hole rings (inner boundaries) properly carve out
  // other-terrain regions enclosed by a CR.
  const sortedCRs = (State.mapData.compressedRegions || [])
    .filter(cr => {
      const outer = cr.boundary;
      return outer && outer.length >= 3;
    })
    .sort((a, b) => {
      const sa = a.size || a.hexKeys?.length || 0;
      const sb = b.size || b.hexKeys?.length || 0;
      return sa - sb;
    });
  for (const cr of sortedCRs) {
    const color = cr.color || getTerrainColor(cr.terrain);
    State.ctx.fillStyle = color;
    State.ctx.beginPath();
    // Draw all boundary rings: outer ring + hole rings
    const rings = (cr.boundaries && cr.boundaries.length > 0)
      ? cr.boundaries
      : [cr.boundary];
    for (const ring of rings) {
      if (!ring || ring.length < 3) continue;
      State.ctx.moveTo(ring[0].x, ring[0].y);
      for (let i = 1; i < ring.length; i++) {
        State.ctx.lineTo(ring[i].x, ring[i].y);
      }
    }
    State.ctx.closePath();
    State.ctx.fill('evenodd');
    drawn += cr.size || cr.hexKeys?.length || 0;
  }

  // ── Pass 2: Individual hexes (on top, override compressed) ──
  // Skip hexes already covered by a compressed region with matching terrain
  const indByColor = {};
  for (const [key, cell] of entries) {
    const crMeta = State.mapData._compressedMeta?.get(key);
    if (crMeta && cell.terrain === crMeta.terrain) continue;
    const [q, r] = key.split('_').map(Number);
    const {x, y} = hexToPixel(q, r);
    if (x < vpLeft || x > vpRight || y < vpTop || y > vpBottom) continue;
    const color = resolveTerrainColor(q, r, cell);
    if (!indByColor[color]) indByColor[color] = [];
    indByColor[color].push({x, y});
    drawn++;
  }

  const blocks = (State.mapData.terrainBlocks || []).filter(b => b.boundary && b.boundary.length >= 3);
  if (blocks.length > 0) {
    const tl = pixelToHex(vpLeft, vpTop);
    const br = pixelToHex(vpRight, vpBottom);
    for (let q = tl.q - 1; q <= br.q + 1; q++) {
      for (let r = Math.min(tl.r, br.r) - 1; r <= Math.max(tl.r, br.r) + 1; r++) {
        const key = q + '_' + r;
        if (State.mapData.hexes && State.mapData.hexes[key]) continue;
        const {x: hx, y: hy} = hexToPixel(q, r);
        if (hx < vpLeft || hx > vpRight || hy < vpTop || hy > vpBottom) continue;
        for (let i = blocks.length - 1; i >= 0; i--) {
          if (pointInPolygon(hx, hy, blocks[i].boundary)) {
            const color = getTerrainColor(blocks[i].terrain);
            if (!indByColor[color]) indByColor[color] = [];
            indByColor[color].push({x: hx, y: hy});
            drawn++;
            break;
          }
        }
      }
    }
  }
  drawHexBatch(indByColor);

  if (hexCount > 1000) {
    setStatus(`渲染: ${drawn}/${hexCount} hex (${(100*drawn/hexCount)|0}%)`);
  }

  // ── Pass 3: Hex icon tags (on top, from cell.tags["图标显示"] / cell.symbol) ──
  State.ctx.font = 'bold ' + (14 / State.zoom) + 'px sans-serif';
  State.ctx.textAlign = 'center';
  State.ctx.textBaseline = 'middle';
  State.ctx.lineWidth = 3 / State.zoom;
  State.ctx.strokeStyle = '#000000';
  State.ctx.fillStyle = '#ffffff';
  for (const [key, cell] of entries) {
    const icon = (cell.tags && cell.tags['图标显示']) || cell.symbol || '';
    if (!icon) continue;
    const [q, r] = key.split('_').map(Number);
    const {x, y} = hexToPixel(q, r);
    if (x < vpLeft || x > vpRight || y < vpTop || y > vpBottom) continue;
    State.ctx.strokeText(icon, x, y);
    State.ctx.fillText(icon, x, y);
  }

  State.ctx.restore();
  renderCompressedRegionBorder();
  renderPathways();
  renderProvinceHighlight();
}

function renderCompressedRegionBorder() {
  if (!State.selectedCompressedRegion || !State.mapData._compressedRegions) return;
  // Find the compressed region by id
  const crs = State.mapData.compressedRegions;
  const cr = crs.find(c => c.id === State.selectedCompressedRegion);
  if (!cr || !cr.boundary || cr.boundary.length < 3) return;

  State.ctx.save();
  State.ctx.translate(State.offX, State.offY);
  State.ctx.scale(State.zoom, State.zoom);

  State.ctx.strokeStyle = (cr.color || '#FFD700') + 'cc';
  State.ctx.lineWidth = 4 / State.zoom;
  State.ctx.setLineDash([8 / State.zoom, 4 / State.zoom]);
  State.ctx.beginPath();
  State.ctx.moveTo(cr.boundary[0].x, cr.boundary[0].y);
  for (let i = 1; i < cr.boundary.length; i++) {
    State.ctx.lineTo(cr.boundary[i].x, cr.boundary[i].y);
  }
  State.ctx.closePath();
  State.ctx.stroke();
  State.ctx.setLineDash([]);

  State.ctx.restore();
}

function resizeCanvas() {
  State.canvas.width = State.wrap.clientWidth;
  State.canvas.height = State.wrap.clientHeight;
  render();
}

function fitView() {
  let minX=Infinity,maxX=-Infinity,minY=Infinity,maxY=-Infinity;
  let anyHex = false;

  // Check individual hexes
  const hexes = State.mapData?.hexes;
  if (hexes) {
    Object.keys(hexes).forEach(key => {
      const [q,r] = key.split('_').map(Number);
      const {x,y} = hexToPixel(q,r);
      minX=Math.min(minX,x-GRID); maxX=Math.max(maxX,x+GRID);
      minY=Math.min(minY,y-GRID); maxY=Math.max(maxY,y+GRID);
      anyHex = true;
    });
  }

  // Also check compressed region hexKeys
  const compressed = State.mapData?.compressedRegions || [];
  for (const cr of compressed) {
    if (!cr.hexKeys || cr.hexKeys.length === 0) continue;
    for (const key of cr.hexKeys) {
      const [q,r] = key.split('_').map(Number);
      const {x,y} = hexToPixel(q,r);
      minX=Math.min(minX,x-GRID); maxX=Math.max(maxX,x+GRID);
      minY=Math.min(minY,y-GRID); maxY=Math.max(maxY,y+GRID);
      anyHex = true;
      break; // just need one hex from each region for bounds
    }
  }

  if (!anyHex) {
    State.zoom = 1; State.offX = State.canvas.width/2; State.offY = State.canvas.height/2;
    render(); return;
  }
  const w=maxX-minX, h=maxY-minY;
  State.zoom = Math.min(State.canvas.width*0.85/w, State.canvas.height*0.85/h, 3);
  State.offX = State.canvas.width/2 - (minX+maxX)/2*State.zoom;
  State.offY = State.canvas.height/2 - (minY+maxY)/2*State.zoom;
  render();
}

// ── Province Highlight ──────────────────────────────────
function renderProvinceHighlight() {
  if (State.tool !== 'province') { State.canvas._boundaryHexes = null; return; }
  if (!State.mapData?.provinces) return;
  const provs = State.mapData.provinces;

  let toRender = [];
  const highlightSet = new Set();
  if (State.activeTag) {
    for (const [name, p] of Object.entries(provs)) {
      // Skip annexed regions — they are no longer displayed on the map
      if (p.annexedBy && p.annexedBy !== '') continue;
      if (p.tag === State.activeTag && p.hexes?.length) {
        toRender.push({name, ...p});
        for (const k of p.hexes) highlightSet.add(k);
      }
    }
  } else if (State.selectedProvince && provs[State.selectedProvince]?.hexes?.length) {
    toRender = [{name: State.selectedProvince, ...provs[State.selectedProvince]}];
    for (const k of provs[State.selectedProvince].hexes) highlightSet.add(k);
    State.canvas._boundaryHexes = computeBoundaryHexes(State.selectedProvince);
  }
  if (!toRender.length) { State.canvas._boundaryHexes = null; return; }

  State.ctx.save();
  State.ctx.translate(State.offX, State.offY);
  State.ctx.scale(State.zoom, State.zoom);
  const invZ = 1/State.zoom;
  const vl = -State.offX * invZ, vt = -State.offY * invZ;
  const vr = vl + State.canvas.width * invZ, vb = vt + State.canvas.height * invZ;
  State.ctx.fillStyle = 'rgba(0,0,0,0.55)';
  State.ctx.fillRect(vl, vt, vr - vl, vb - vt);

  // Redraw compressed regions dimmed (show terrain through the dark overlay)
  // Same size-ascending sort + evenodd fill as Pass 1
  const crs = (State.mapData.compressedRegions || [])
    .filter(cr => cr.boundary && cr.boundary.length >= 3)
    .sort((a, b) => {
      const sa = a.size || a.hexKeys?.length || 0;
      const sb = b.size || b.hexKeys?.length || 0;
      return sa - sb;
    });
  if (crs.length > 0) {
    State.ctx.globalAlpha = 0.45;
    for (const cr of crs) {
      State.ctx.fillStyle = cr.color || getTerrainColor(cr.terrain);
      State.ctx.beginPath();
      const rings = (cr.boundaries && cr.boundaries.length > 0)
        ? cr.boundaries
        : [cr.boundary];
      for (const ring of rings) {
        if (!ring || ring.length < 3) continue;
        State.ctx.moveTo(ring[0].x, ring[0].y);
        for (let i = 1; i < ring.length; i++) {
          State.ctx.lineTo(ring[i].x, ring[i].y);
        }
      }
      State.ctx.closePath();
      State.ctx.fill();
    }
    State.ctx.globalAlpha = 1;
  }

  // Redraw individual hexes not covered by matching CR (non-CR + edited CR hexes)
  const entries = Object.entries(State.mapData.hexes || {});
  for (const [key, cell] of entries) {
    if (highlightSet.has(key)) continue; // drawn later as province hex
    const crMeta = State.mapData._compressedMeta?.get(key);
    if (crMeta && cell.terrain === crMeta.terrain) continue; // CR covers this
    const [q, r] = key.split('_').map(Number);
    const {x, y} = hexToPixel(q, r);
    if (x < vl || x > vr || y < vt || y > vb) continue;
    State.ctx.fillStyle = (cell.color || getTerrainColor(cell.terrain)) + '99';
    State.ctx.beginPath();
    const corners = hexCorners(x, y, GRID - 1);
    State.ctx.moveTo(corners[0][0], corners[0][1]);
    for (let i = 1; i < 6; i++) State.ctx.lineTo(corners[i][0], corners[i][1]);
    State.ctx.closePath();
    State.ctx.fill();
  }

  for (const region of toRender) {
    const phexes = region.hexes || [];
    if (!phexes.length) continue;

    State.ctx.fillStyle = (region.color || '#FF0000') + '66';
    State.ctx.strokeStyle = (region.color || '#FF0000') + 'aa';
    State.ctx.lineWidth = 0.8 / State.zoom;
    State.ctx.beginPath();
    let drawn = 0;
    for (const key of phexes) {
      const [q, r] = key.split('_').map(Number);
      const {x, y} = hexToPixel(q, r);
      if (x < vl || x > vr || y < vt || y > vb) continue;
      const corners = hexCorners(x, y, GRID - 1);
      State.ctx.moveTo(corners[0][0], corners[0][1]);
      for (let i = 1; i < 6; i++) State.ctx.lineTo(corners[i][0], corners[i][1]);
      State.ctx.closePath();
      drawn++;
      if (drawn > 2000) break;
    }
    State.ctx.fill();
    State.ctx.stroke();

    const cacheName = 'b_' + region.name;
    if (!State.canvas[cacheName]) {
      State.canvas[cacheName] = computeBoundaryHexes(region.name);
    }
    if (region.name === State.selectedProvince) {
      State.canvas._boundaryHexes = State.canvas[cacheName];
    }
    // 圆点仅对选中编辑中的区域渲染（activeTag 浏览模式不画，避免缩小缩放时圆点叠成噪点）
    const isEditing = State.selectedProvince === region.name;
    if (isEditing) {
      State.ctx.fillStyle = region.color;
      State.ctx.globalAlpha = 0.8;
      for (const b of State.canvas[cacheName]) {
        const {x, y} = hexToPixel(b.q, b.r);
        if (x < vl || x > vr || y < vt || y > vb) continue;
        State.ctx.beginPath(); State.ctx.arc(x, y, 5/State.zoom, 0, Math.PI*2); State.ctx.fill();
      }
      State.ctx.globalAlpha = 1;
    }

    // ── Region name label ──
    if (State.showRegionNames && phexes.length > 0) {
      let sq = 0, sr = 0;
      for (const k of phexes) { const [q,r] = k.split('_').map(Number); sq += q; sr += r; }
      const cx = sq / phexes.length, cy = sr / phexes.length;
      const center = hexToPixel(Math.round(cx), Math.round(cy));
      const fontSize = Math.max(8, Math.min(40, Math.sqrt(phexes.length) * 1.8)) / State.zoom;
      State.ctx.font = `bold ${fontSize}px sans-serif`;
      State.ctx.textAlign = 'center';
      State.ctx.textBaseline = 'middle';
      // Outline for readability
      State.ctx.strokeStyle = '#000000cc';
      State.ctx.lineWidth = 3 / State.zoom;
      State.ctx.strokeText(region.name, center.x, center.y);
      State.ctx.fillStyle = '#ffffff';
      State.ctx.fillText(region.name, center.x, center.y);
    }
  }

  State.ctx.restore();
}

function computeBoundaryHexes(provinceName) {
  const p = State.mapData?.provinces?.[provinceName];
  if (!p?.hexes) return [];
  const hs = new Set(p.hexes), bnd = [];
  for (const key of p.hexes) {
    const [q,r]=key.split('_').map(Number);
    for (const [dq,dr] of DIR_VECTORS) if(!hs.has((q+dq)+'_'+(r+dr))){bnd.push({q,r,key});break;}
  }
  return bnd;
}
