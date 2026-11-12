#!/usr/bin/env node
// 校验栏杆的框选 / 碰撞盒（v1.20.1）：墙上扶手 11 个 + 玻璃扶手 11 个。
//
// 直接从 RailingWallBlocks.java / RailingGlassBlocks.java 里抠出 11 个 Variant 的 box(...)，
// 再从模型 JSON 里算出每个元素（含 Blockbench rotation）的真实位置，逐点核对：
//   1) 框选盒（= 碰撞盒的 y1 压到 0，见 RailingWallBlock#stretchToFloor）必须把**所有可见表面**包住
//      （玻璃模型画到 y=-5 的那几段属于下方方块，y<0 的采样点不计）；
//   2) 每个框选盒的 y1 必须是 0（否则那条「从地到顶」的贴墙薄片就断了，射线扫不进本格子 = 选不上）；
//   3) 11 个变体 / 11 个模型一一对应，盒子非空、非退化；
//   4) 玻璃扶手的碰撞顶面必须**与墙上扶手同一段同高**，且不低于 20（1.25 格）——
//      玻璃几何只到 17.5（1.09 格），低于玩家跳跃顶点 1.2522 格，会被一跳而过。
//
// 用法：node tools/check-railing-shapes.js [仓库根] [wall|glass|all]   退出码 0 = 全过

const fs = require('fs');
const path = require('path');

const SET_NAMES = ['wall', 'glass', 'all'];
// 参数可以只给目标（node tools/check-railing-shapes.js glass），也可以照旧给仓库根。
const arg2 = (process.argv[2] || '').toLowerCase();
const root = SET_NAMES.includes(arg2)
  ? path.resolve(__dirname, '..')
  : (process.argv[2] || path.resolve(__dirname, '..'));
const only = (SET_NAMES.includes(arg2) ? arg2 : (process.argv[3] || 'all')).toLowerCase();
const modelDir = path.join(root, 'v1.20.1/src/main/resources/assets/mtrlte/models/block');
const blockstateDir = path.join(root, 'v1.20.1/src/main/resources/assets/mtrlte/blockstates');
const EPS = 1e-6;

// 玻璃扶手不许低于这个顶面（1.25 格）：低于它 = 玩家跳跃顶点 1.2522 格在一跳之内越过。
const MIN_BARRIER_TOP = 20;

const SETS = {
  wall: {
    label: '墙上扶手',
    java: 'v1.20.1/src/main/java/dev/js/mtrheadlight/railing/RailingWallBlocks.java',
    // 墙上扶手贴墙侧在 x=0..3，容许 0.5 格误差（45° 杆尖会探出），与补注释里写的一致。
    wallSideTolerance: 0.5,
    // 45° 斜杆 / 斜段玻璃的杆尖会探出格子 0.36 / 1.06 格，盒子跟着放出去，容差按几何给。
    horizontalSlack: 0.5
  },
  glass: {
    label: '玻璃扶手',
    java: 'v1.20.1/src/main/java/dev/js/mtrheadlight/railing/RailingGlassBlocks.java',
    // 玻璃栏杆是落地款，整段玻璃退到 x=0.75..2.25（离墙 0.75 格），容差放大到 0.8。
    wallSideTolerance: 0.8,
    // 玻璃斜段画到 z=-5，旋转后杆尖探出 1.06 格（见 RailingGlassBlocks 类注释）。
    horizontalSlack: 1.2
  }
};

const EXPECTED = 11;

// ---------------------------------------------------------------- 解析 Java 表
function parseVariants(src) {
  const out = [];
  // 按 `new Variant(` 切块，块内所有 box(...) 都属于这个变体（避免正则去吃结尾的 `)))`）
  const chunks = src.split('new Variant(').slice(1);
  for (const chunk of chunks) {
    const head = /^\s*"([^"]+)"\s*,\s*"([^"]+)"\s*,\s*boxes\(/.exec(chunk);
    if (!head) continue;
    const boxes = [];
    const bre = /box\(\s*(-?[\d.]+)\s*,\s*(-?[\d.]+)\s*,\s*(-?[\d.]+)\s*,\s*(-?[\d.]+)\s*,\s*(-?[\d.]+)\s*,\s*(-?[\d.]+)\s*\)/g;
    let b;
    while ((b = bre.exec(chunk)) !== null) {
      boxes.push(b.slice(1, 7).map(Number));
    }
    out.push({ id: head[1], model: head[2], boxes });
  }
  return out;
}

// ---------------------------------------------------------------- 模型元素
function rotPoint(p, rot) {
  if (!rot) return p.slice();
  const a = rot.angle * Math.PI / 180, o = rot.origin;
  const x = p[0] - o[0], y = p[1] - o[1], z = p[2] - o[2];
  const c = Math.cos(a), s = Math.sin(a);
  let X = x, Y = y, Z = z;
  if (rot.axis === 'x') { Y = y * c - z * s; Z = y * s + z * c; }
  else if (rot.axis === 'y') { X = x * c + z * s; Z = -x * s + z * c; }
  else { X = x * c - y * s; Y = x * s + y * c; }
  return [X + o[0], Y + o[1], Z + o[2]];
}

// 元素 6 个面上的采样点（0.35 格分辨率够细：最薄的元素也有 1 格厚）
function surfaceSamples(el) {
  const [x1, y1, z1] = el.from, [x2, y2, z2] = el.to;
  const step = 0.35;
  const axis = (a, b) => {
    const n = Math.max(1, Math.ceil((b - a) / step));
    return Array.from({ length: n + 1 }, (_, i) => a + (b - a) * i / n);
  };
  const xs = axis(x1, x2), ys = axis(y1, y2), zs = axis(z1, z2);
  const pts = [];
  for (const x of xs) for (const y of ys) { pts.push([x, y, z1], [x, y, z2]); }
  for (const x of xs) for (const z of zs) { pts.push([x, y1, z], [x, y2, z]); }
  for (const y of ys) for (const z of zs) { pts.push([x1, y, z], [x2, y, z]); }
  return pts.map(p => rotPoint(p, el.rotation));
}

function insideAny(boxes, p) {
  return boxes.some(([x1, y1, z1, x2, y2, z2]) =>
    p[0] >= x1 - EPS && p[0] <= x2 + EPS &&
    p[1] >= y1 - EPS && p[1] <= y2 + EPS &&
    p[2] >= z1 - EPS && p[2] <= z2 + EPS);
}

// blockstate 的 `y` 与 Java rotate() 都是「绕 (8,8,8) 转」；y=90 时 (x,z)→(16-z,x)
// （与 rotate() 的 NORTH 分支逐字对应，见 RailingWallBlock 注释里的旋转表）。
const BLOCKSTATE_Y = {
  0: p => p,
  90: ([x, y, z]) => [16 - z, y, x],
  180: ([x, y, z]) => [16 - x, y, 16 - z],
  270: ([x, y, z]) => [z, y, 16 - x]
};

// 与 RailingWallBlock#rotate 完全一致的 Java 侧旋转（四条公式照抄）
function javaRotate(boxes, facing) {
  return boxes.map(([x1, y1, z1, x2, y2, z2]) => {
    switch (facing) {
      case 'north': return [16 - z2, y1, x1, 16 - z1, y2, x2];
      case 'east': return [16 - x2, y1, 16 - z2, 16 - x1, y2, 16 - z1];
      case 'south': return [z1, y1, 16 - x2, z2, y2, 16 - x1];
      default: return [x1, y1, z1, x2, y2, z2];
    }
  });
}

const FACING_SIDE = { // 该 facing 下扶手应该贴在哪一面：贴墙侧必须落在这一侧
  west: [0, 0], north: [2, 0], east: [0, 16], south: [2, 16]
};

// ---------------------------------------------------------------- 单套变体校验
function checkSet(setKey, wallTops) {
  const spec = SETS[setKey];
  const problems = [];
  const notes = [];

  const src = fs.readFileSync(path.join(root, spec.java), 'utf8');
  const variants = parseVariants(src);
  if (variants.length !== EXPECTED) problems.push(`变体数量 ${variants.length} != ${EXPECTED}`);

  const seenId = new Set(), seenModel = new Set();
  for (const v of variants) {
    if (seenId.has(v.id)) problems.push(`方块 id 重复: ${v.id}`);
    if (seenModel.has(v.model)) problems.push(`模型重复: ${v.model}`);
    seenId.add(v.id);
    seenModel.add(v.model);

    const modelPath = path.join(modelDir, v.model + '.json');
    if (!fs.existsSync(modelPath)) { problems.push(`${v.id}: 缺模型 ${v.model}.json`); continue; }
    if (!v.boxes.length) { problems.push(`${v.id}: 没有 box(...)`); continue; }

    // 几何层盒子（碰撞）→ 框选盒 = 同 footprint，y1 压到 0
    const collision = v.boxes;
    const outline = collision.map(([x1, , z1, x2, y2, z2]) => [x1, 0, z1, x2, y2, z2]);

    for (const b of collision) {
      const [x1, y1, z1, x2, y2, z2] = b;
      if (!(x2 > x1 && y2 > y1 && z2 > z1)) problems.push(`${v.id}: 退化盒子 [${b}]`);
      if (x1 < -spec.horizontalSlack || x2 > 16 + spec.horizontalSlack ||
          z1 < -spec.horizontalSlack || z2 > 16 + spec.horizontalSlack) {
        problems.push(`${v.id}: 盒子水平方向超出格子 [${b}]`);
      }
    }

    const model = JSON.parse(fs.readFileSync(modelPath, 'utf8'));
    let worst = null, unchecked = 0, totalPts = 0, below = 0;
    for (const el of model.elements || []) {
      for (const p of surfaceSamples(el)) {
        // 画到 y<0 的部分落在下方方块里（玻璃斜段扎进楼梯），不做碰撞也不进本格框选。
        if (p[1] < -EPS) { below++; continue; }
        totalPts++;
        if (insideAny(outline, p)) continue;
        unchecked++;
        if (worst === null) worst = p;
      }
    }

    for (const b of outline) {
      if (Math.abs(b[1]) > EPS) problems.push(`${v.id}: 框选盒底面没压到 y=0 [${b}]`);
    }
    if (unchecked > 0) {
      problems.push(`${v.id} (${v.model}): 有 ${unchecked}/${totalPts} 个可见表面采样点落在框选盒外，` +
        `例 ${worst.map(n => n.toFixed(2)).join(',')}`);
    }

    const geomTop = Math.max(...(model.elements || []).flatMap(e => surfaceSamples(e).map(p => p[1])));
    const boxTop = Math.max(...outline.map(b => b[4]));

    // 玻璃扶手：碰撞顶面必须与墙上扶手同一段同高，且不能低于 MIN_BARRIER_TOP。
    if (setKey === 'glass') {
      const suffix = v.id.replace(/^railing_glass_/, '');
      const wallTop = wallTops.get(suffix);
      if (wallTop === undefined) {
        problems.push(`${v.id}: 墙上扶手没有对应段 railing_wall_${suffix}，无法对齐高度`);
      } else if (Math.abs(boxTop - wallTop) > EPS) {
        problems.push(`${v.id}: 碰撞顶 ${boxTop} != 墙上扶手 railing_wall_${suffix} 的 ${wallTop}`);
      }
      if (boxTop < MIN_BARRIER_TOP - EPS) {
        problems.push(`${v.id}: 碰撞顶 ${boxTop} 低于 ${MIN_BARRIER_TOP}（1.25 格），玩家一跳就能过`);
      }
      if (boxTop < geomTop - EPS) {
        problems.push(`${v.id}: 碰撞顶 ${boxTop} 低于几何顶 ${geomTop.toFixed(2)}，会漏几何`);
      }
    }

    notes.push(`  ${v.id.padEnd(30)} ${v.model.padEnd(38)} 盒子 ${collision.length} 条  几何顶 ${geomTop.toFixed(2).padStart(6)}  框选顶 ${boxTop.toFixed(1).padStart(5)}  ${unchecked === 0 ? 'OK' : 'MISS'}`);

    // ---- 端到端：按 blockstate 的 `y` 转模型、按 Java rotate() 转盒子，四个朝向都得对得上
    const bsPath = path.join(blockstateDir, v.id + '.json');
    if (!fs.existsSync(bsPath)) { problems.push(`${v.id}: 缺 blockstate ${v.id}.json`); continue; }
    const bs = JSON.parse(fs.readFileSync(bsPath, 'utf8')).variants || {};
    const samples = (model.elements || []).flatMap(f => surfaceSamples(f));
    for (const facing of ['west', 'north', 'east', 'south']) {
      const entry = bs[`facing=${facing}`];
      if (!entry) { problems.push(`${v.id}: blockstate 缺 facing=${facing}`); continue; }
      if (entry.model !== 'mtrlte:block/' + v.model) {
        problems.push(`${v.id}: facing=${facing} 指向 ${entry.model}，应为 mtrlte:block/${v.model}`);
        continue;
      }
      const y = entry.y || 0;
      const turn = BLOCKSTATE_Y[y];
      if (!turn) { problems.push(`${v.id}: facing=${facing} 的 y=${y} 不认识`); continue; }

      const shaped = javaRotate(outline, facing);
      let miss = 0, first = null;
      for (const p of samples) {
        if (p[1] < -EPS) continue; // y<0 属于下方方块
        if (insideAny(shaped, turn(p))) continue;
        if (!miss++) first = turn(p);
      }
      if (miss) {
        problems.push(`${v.id} facing=${facing} (y=${y}): ${miss}/${samples.length} 个采样点落在旋转后的框选盒外，例 ` +
          first.map(n => n.toFixed(2)).join(','));
      }

      // 贴墙侧必须真的贴在那一面（旋转公式和 blockstate 的 y 一旦错位，这里会先炸）
      const [axis, want] = FACING_SIDE[facing];
      const lo = Math.min(...shaped.map(b => b[axis]));
      const hi = Math.max(...shaped.map(b => b[axis + 3]));
      const touch = want === 0 ? Math.abs(lo) : Math.abs(hi - 16);
      if (touch > spec.wallSideTolerance + EPS) {
        problems.push(`${v.id} facing=${facing}: 贴墙侧在 ${axis === 0 ? 'x' : 'z'} ${(want === 0 ? lo : hi).toFixed(2)}，` +
          `应贴 ${want === 0 ? '0' : '16'}（boxstate 的 y=${y} 与 rotate() 对不上）`);
      }
    }
  }

  return { label: spec.label, variants: variants.length, notes, problems };
}

// 墙上扶手的每段顶面（玻璃要照这个高度对齐）。
function wallTops() {
  const src = fs.readFileSync(path.join(root, SETS.wall.java), 'utf8');
  const tops = new Map();
  for (const v of parseVariants(src)) {
    const suffix = v.id.replace(/^railing_wall_/, '');
    tops.set(suffix, Math.max(...v.boxes.map(b => b[4])));
  }
  return tops;
}

// ---------------------------------------------------------------- 主流程
const targets = only === 'all' ? ['wall', 'glass'] : [only];
if (targets.some(t => !SETS[t])) {
  console.error(`未知目标 ${only}（可选 wall / glass / all）`);
  process.exit(2);
}

const walls = wallTops();
let failed = false;
for (const key of targets) {
  const res = checkSet(key, walls);
  console.log(`${res.label}框选校验：${res.variants}/${EXPECTED} 个变体`);
  console.log(res.notes.join('\n'));
  if (res.problems.length) {
    failed = true;
    console.log('\n问题:');
    res.problems.forEach(p => console.log('  ✗ ' + p));
  } else {
    console.log(key === 'glass'
      ? `\n全部通过：${EXPECTED} 个玻璃模型的可见几何都在框选盒内，框选盒从 y=0 起，顶面与墙上扶手同高。`
      : `\n全部通过：${EXPECTED} 个模型的可见几何都在框选盒内，框选盒均从 y=0 起。`);
  }
  console.log('');
}
process.exit(failed ? 1 : 0);
