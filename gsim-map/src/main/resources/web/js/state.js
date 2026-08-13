// ── Constants ──────────────────────────────────────────
const GRID = 30;
const DIR_VECTORS = [[1,0],[0,1],[-1,1],[-1,0],[0,-1],[1,-1]];
const OPPOSITE_DIR = [3,4,5,0,1,2];

const DEFAULT_TERRAINS = {
  water:    {name:"water",    color:"#3295D2", food:1, gold:0, stone:0, moveCost:99, description:"水域"},
  lowland:  {name:"lowland",  color:"#5B8C3E", food:3, gold:1, stone:1, moveCost:1,  description:"沿海低地"},
  hills:    {name:"hills",    color:"#A0522D", food:2, gold:1, stone:3, moveCost:2,  description:"丘陵过渡带"},
  plains:   {name:"plains",   color:"#B8A88A", food:2, gold:2, stone:1, moveCost:1,  description:"内陆山区高原"},
  mountain: {name:"mountain", color:"#6B6B6B", food:0, gold:2, stone:5, moveCost:3,  description:"高山峰簇"},
  forest:   {name:"forest",   color:"#228B22", food:2, gold:1, stone:3, moveCost:2,  description:"森林"},
  desert:   {name:"desert",   color:"#DDC88D", food:0, gold:1, stone:2, moveCost:2,  description:"沙漠"},
  swamp:    {name:"swamp",    color:"#556B2F", food:2, gold:0, stone:1, moveCost:3,  description:"海岸沼泽"},
  tundra:   {name:"tundra",   color:"#B0C4DE", food:1, gold:0, stone:1, moveCost:2,  description:"冻土"}
};

// ── Application State ──────────────────────────────────
const State = {
  // Data
  mapData: null,
  terrainTypes: DEFAULT_TERRAINS,

  // Tool
  activeTerrain: 'plains',
  tool: 'pen',

  // Selection
  selectedProvince: null,
  activeTag: null,
  showRegionNames: true,
  selectedCompressedRegion: null,

  // Pathway
  activePathwayGroup: 'river',
  pathwayStart: null,
  pathwayHighlightMode: false,

  // Viewport
  zoom: 1,
  offX: 0,
  offY: 0,

  // Interaction
  mouseDown: false,
  clickHex: null,
  panStart: null,
  panOff: null,
  mouseButton: 0,
  lastHex: null,

  // Province lasso
  provinceLasso: [],
  relassoTarget: null,

  // Paint lasso (was in paint.js)
  lassoPts: [],

  // Province drag (was in province.js)
  dragPoint: null,

  // Map expansion direction (was in expand.js)
  expandDirection: null,

  // DOM refs (set once by init)
  canvas: null,
  ctx: null,
  wrap: null,
  tooltip: null,
};

// ── DOM Refs ───────────────────────────────────────────
State.canvas = document.getElementById('mapCanvas');
State.ctx = State.canvas.getContext('2d');
State.wrap = document.getElementById('canvasWrap');
State.tooltip = document.getElementById('tooltip');
