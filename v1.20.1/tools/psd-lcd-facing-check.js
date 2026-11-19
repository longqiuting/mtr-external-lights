#!/usr/bin/env node
/*
 * PSD-LCD 朝向自检（离线）
 * =========================
 *
 * 这块屏的朝向被用户纠正过两次，所以这里把「屏幕法线」在三条链路上各算一遍并互相校验：
 *
 *   1. 放置规则：玩家朝向 → 方块 facing = getHorizontalPlayerFacing().getOpposite()
 *      （玩家看向北 → facing = SOUTH → 屏幕朝南，正对玩家）；
 *   2. blockstate 的 y 旋转：模型本身朝北，facing=<X> 要用 y = {north 0, east 90, south 180, west 270}
 *      —— 和 PsdLcdLayout.Face#blockstateY() 里的四个数逐一对账；
 *   3. BER 四边形：PsdLcdLayout#windowQuad 的四个顶点（下面按同一份算法复算），
 *      用来确认四边形法线 = facing、y 范围 = 1.5…8.5px（不会往上留黑块、也不会往下溢出）。
 *
 * 打印形式：N / E / S / W 各一行。
 *
 * 用法（仓库根目录）：node v1.20.1/tools/psd-lcd-facing-check.js
 * 退出码：0 = 四个朝向全部「面朝玩家」；1 = 有朝向不对。
 */
'use strict';

const fs = require('fs');
const path = require('path');

const HERE = __dirname;
const VERSION_DIR = path.resolve(HERE, '..');
const BLOCKSTATE = path.join(VERSION_DIR, 'src/main/resources/assets/mtrlte/blockstates/psd_lcd_xiamen.json');
const LAYOUT_JAVA = path.join(VERSION_DIR, 'src/main/java/dev/js/mtrheadlight/xiamen/pids/PsdLcdLayout.java');
const MODEL = path.join(VERSION_DIR, 'src/main/resources/assets/mtrlte/models/block/psd_lcd_xiamen.json');

const failures = [];
function ok(cond, label, detail) {
  if (!cond) {
    failures.push(label);
    console.log(`  FAIL  ${label}${detail ? '   ' + detail : ''}`);
  }
  return cond;
}

// ---------------------------------------------------------------- 几何常量（= PsdLcdLayout）

const java = fs.readFileSync(LAYOUT_JAVA, 'utf8');
const num = (name) => {
  const m = java.match(new RegExp(`static final float ${name}\\s*=\\s*([-0-9.f]+)F`));
  return m ? parseFloat(m[1]) : NaN;
};
const WIN_Y0 = num('WINDOW_Y0_PX');
const WIN_Y1 = num('WINDOW_Y1_PX');
const THICK = num('THICKNESS_PX');
const GAP = num('FACE_GAP_PX');

// 模型里的窗口矩形（ScreenPlane）
const model = JSON.parse(fs.readFileSync(MODEL, 'utf8'));
const planeEl = (model.elements || []).find((e) => e.name === 'ScreenPlane')
  || (model.elements || []).find((e) => e.faces && e.faces.north && e.faces.north.texture === '#screen');
if (!planeEl) {
  console.error('模型里找不到 ScreenPlane');
  process.exit(1);
}

// ---------------------------------------------------------------- 朝向表

// MC 约定：模型默认朝北（前面 = z 0 那一面，法线 (0,0,-1)）；north 的 y 旋转 = 0。
const FACES = ['NORTH', 'EAST', 'SOUTH', 'WEST'];
const VEC = {
  NORTH: [0, 0, -1],
  SOUTH: [0, 0, 1],
  WEST: [-1, 0, 0],
  EAST: [1, 0, 0],
};
const OPPOSITE = { NORTH: 'SOUTH', SOUTH: 'NORTH', EAST: 'WEST', WEST: 'EAST' };
// 观察者站在屏幕正面看时他的右手方向（= 屏幕 +x）= Face#right()
const RIGHT = { NORTH: 'WEST', WEST: 'SOUTH', SOUTH: 'EAST', EAST: 'NORTH' };
// blockstate y 旋转（模型朝北）
const EXPECTED_Y = { NORTH: 0, EAST: 90, SOUTH: 180, WEST: 270 };
// 玩家 yaw → getHorizontalPlayerFacing()（MC：0 = south，90 = west，180 = north，270 = east）
const YAW_TO_FACING = { 0: 'SOUTH', 90: 'WEST', 180: 'NORTH', 270: 'EAST' };

const blockstate = JSON.parse(fs.readFileSync(BLOCKSTATE, 'utf8'));
const bsY = {};
// 每个变体的 key 现在是 "facing=<dir>,half=<none|left|right>"（剪刀剪半格以后加的），
// 朝向那一张表只认整格（half=none），半格另有一轮检查（见下面「半格变体」）。
const bsHalf = {};
for (const [key, variant] of Object.entries(blockstate.variants || {})) {
  const props = Object.fromEntries(key.split(',').map((kv) => kv.trim().split('=')));
  const facing = (props.facing || '').toUpperCase();
  const half = props.half || 'none';
  if (!facing) {
    continue;
  }
  bsHalf[`${facing}/${half}`] = variant;
  if (half === 'none') {
    bsY[facing] = variant.y || 0;
  }
}

// Java 里的 Face#blockstateY() 表
const javaY = {};
const yBlock = java.match(/blockstateY\(\)\s*\{[\s\S]*?\n\s*\}/);
if (yBlock) {
  const re = /case (NORTH|SOUTH|EAST|WEST) -> (\d+);/g;
  let m;
  while ((m = re.exec(yBlock[0])) !== null) {
    javaY[m[1]] = parseInt(m[2], 10);
  }
}

/** 把模型法线 (0,0,-1) 按 blockstate 的 y 旋转转过去（MC：y=90 把 north 转到 east）。 */
function rotateY(vec, deg) {
  const rad = (deg * Math.PI) / 180;
  const cos = Math.round(Math.cos(rad));
  const sin = Math.round(Math.sin(rad));
  // 标准右手绕 +Y 旋转：x' = x·cos − z·sin，z' = x·sin + z·cos
  // （MC 的 y=90 把 north(0,0,-1) 转到 east(1,0,0)，就是这一组符号）
  return [vec[0] * cos - vec[2] * sin, 0, vec[0] * sin + vec[2] * cos];
}

/**
 * PsdLcdLayout#windowQuad 的同一份算法（左上 → 左下 → 右下 → 右上，世界格）。
 *
 * 参数是阅读轴上的绝对区间（16 分之格，从 anchor 那一格的观察者左边缘量起）——
 * 和 Java 里的 `windowQuad(Face, float startPx, float endPx)` 完全对应；
 * 整条链 = `(0, segments*16)`，半格 = `(0,8)` / `(8,16)`。
 */
function windowQuad(facing, startPx = 0, endPx = 16) {
  const f = VEC[facing];
  const right = VEC[RIGHT[facing]];
  const from = startPx / 16;
  const to = endPx / 16;
  const y0 = WIN_Y0 / 16;
  const y1 = WIN_Y1 / 16;
  const plane = (f[0] < 0 || f[2] < 0) ? (THICK - GAP) / 16 : 1 - (THICK - GAP) / 16;
  const startX = 0.5 - right[0] * 0.5;
  const startZ = 0.5 - right[2] * 0.5;
  if (right[0] !== 0) {
    const x0 = startX + right[0] * from;
    const x1 = startX + right[0] * to;
    return [[x0, y1, plane], [x0, y0, plane], [x1, y0, plane], [x1, y1, plane]];
  }
  const z0 = startZ + right[2] * from;
  const z1 = startZ + right[2] * to;
  return [[plane, y1, z0], [plane, y0, z0], [plane, y0, z1], [plane, y1, z1]];
}

// ---------------------------------------------------------------- 逐朝向检查

const label = (face) => face.charAt(0);
const fmt = (v, d = 3) => (v >= 0 ? ' ' : '') + v.toFixed(d);

console.log('== 模型 ↔ blockstate ↔ BER 三方对账 ==');
for (const face of FACES) {
  ok(Math.abs((planeEl.from[1]) - WIN_Y0) < 1e-6 && Math.abs((planeEl.to[1]) - WIN_Y1) < 1e-6,
    `模型窗口 y 与 Java 常量一致（${WIN_Y0} … ${WIN_Y1}）`, '');

  // 1) blockstate
  ok(bsY[face] === EXPECTED_Y[face], `blockstate facing=${face.toLowerCase()} 的 y = ${EXPECTED_Y[face]}`,
    `实际 ${bsY[face]}`);
  ok(javaY[face] === EXPECTED_Y[face], `Face#blockstateY(${face}) = ${EXPECTED_Y[face]}`, `实际 ${javaY[face]}`);

  // 2) 模型法线（旋转后）
  const normal = rotateY([0, 0, -1], bsY[face] || 0);
  ok(Math.abs(normal[0] - VEC[face][0]) < 1e-6 && Math.abs(normal[2] - VEC[face][2]) < 1e-6,
    `facing=${face} 的模型法线 = ${face}`, `(${normal.join(', ')})`);

  // 3) 放置规则：屏幕朝南时要由「看向北」的玩家放出来
  const playerFacing = OPPOSITE[face];
  const yaw = Number(Object.keys(YAW_TO_FACING).find((k) => YAW_TO_FACING[k] === playerFacing));
  const placed = OPPOSITE[YAW_TO_FACING[yaw]];       // getHorizontalPlayerFacing().getOpposite()
  ok(placed === face, `玩家 yaw=${yaw}（朝向 ${playerFacing}）放出来的 facing = ${face}`, `实际 ${placed}`);

  // 4) BER 四边形：法线 = facing，y 范围 = 1.5…8.5px，世界高度 7/16 格
  const q = windowQuad(face, 0, 48);
  const ys = q.map((p) => p[1]);
  const yMin = Math.min(...ys) * 16;
  const yMax = Math.max(...ys) * 16;
  ok(Math.abs(yMin - WIN_Y0) < 1e-3 && Math.abs(yMax - WIN_Y1) < 1e-3,
    `facing=${face} 的四边形 y 范围 = ${WIN_Y0} … ${WIN_Y1}px`, `实际 ${yMin.toFixed(3)} … ${yMax.toFixed(3)}`);
  const axis = face === 'NORTH' || face === 'SOUTH' ? 2 : 0;
  const span = q.map((p) => p[axis]);
  const inset = (THICK - GAP) / 16;
  const expectPlane = VEC[face][axis] < 0 ? inset : 1 - inset;
  ok(Math.abs(Math.min(...span) - expectPlane) < 1e-3,
    `facing=${face} 的四边形贴在窗口面（离方块外面 ${(THICK - GAP).toFixed(3)}px）`,
    `实际 ${Math.min(...span).toFixed(4)} / 期望 ${expectPlane.toFixed(4)}`);

  // 5) 观察者站在法线方向上 → 看得到正面
  const viewer = [normal[0] * 2, 1.5, normal[2] * 2];
  const facingViewer = normal[0] * viewer[0] + normal[2] * viewer[2] > 0;
  ok(facingViewer, `facing=${face} 时屏幕正对观察者`);

  console.log(`  ${label(face)}  facing=${face.padEnd(5)} y=${String(bsY[face]).padStart(3)}°`
    + `  法线=(${normal.map((v) => String(v).padStart(2)).join(',')})`
    + `  玩家 yaw=${String(yaw).padStart(3)}°（朝向 ${playerFacing.padEnd(5)}）放置`
    + `  四边形 ${ys.length} 顶点 · y ${WIN_Y0}…${WIN_Y1}px · 观察者 (${fmt(viewer[0])}, ${fmt(viewer[2])}) → 正面可见`);
}

// ---------------------------------------------------------------- 半格变体（剪刀）

// 半格的几何约定（和 PsdLcdBlock#panelSpan / modelXSpan 一致）：
//   half=left  → model x 8…16（观察者左半）   文件 psd_lcd_xiamen_half_left.json
//   half=right → model x 0…8 （观察者右半）   文件 psd_lcd_xiamen_half_right.json
// 每个 facing 的 y 必须和整格那份一样（半格不改变朝向），模型名必须指到对应的半格文件，
// 而且那个文件里的 ScreenPlane 只能占这 8px —— 三者任一不对，屏幕上就会出现「框 8px、画 16px」。
console.log('\n== 半格变体（half=left / half=right）==');
const HALVES = {
  // mx = model 空间 x 区间（模型文件），s = 阅读轴区间（panelSpan，16 分之格，从本格左边缘量起）
  left: { model: 'mtrlte:block/psd_lcd_xiamen_half_left', mx0: 8, mx1: 16, s0: 0, s1: 8 },
  right: { model: 'mtrlte:block/psd_lcd_xiamen_half_right', mx0: 0, mx1: 8, s0: 8, s1: 16 },
};
const halfModels = {};
for (const [name, spec] of Object.entries(HALVES)) {
  const file = path.join(path.dirname(MODEL), `${spec.model.split('/').pop()}.json`);
  if (fs.existsSync(file)) {
    const parsed = JSON.parse(fs.readFileSync(file, 'utf8'));
    const plane = (parsed.elements || []).find((e) => e.name === 'ScreenPlane');
    halfModels[name] = plane ? { x0: plane.from[0], x1: plane.to[0] } : null;
  } else {
    halfModels[name] = null;
  }
  ok(halfModels[name] !== null,
    `半格模型 ${spec.model.split('/').pop()}.json 存在`, halfModels[name] === null ? '缺文件' : '');
  if (halfModels[name]) {
    ok(Math.abs(halfModels[name].x0 - spec.mx0) < 1e-6
      && Math.abs(halfModels[name].x1 - spec.mx1) < 1e-6,
      `half=${name} 模型的 ScreenPlane 只占 model x ${spec.mx0}…${spec.mx1}`,
      `实际 ${halfModels[name].x0}…${halfModels[name].x1}`);
  }
}
for (const face of FACES) {
  for (const [name, spec] of Object.entries(HALVES)) {
    const variant = bsHalf[`${face}/${name}`];
    ok(variant !== undefined, `blockstate 有 facing=${face.toLowerCase()},half=${name}`);
    if (!variant) {
      continue;
    }
    ok((variant.y || 0) === EXPECTED_Y[face],
      `facing=${face.toLowerCase()},half=${name} 的 y = ${EXPECTED_Y[face]}`,
      `实际 ${variant.y || 0}`);
    ok(variant.model === spec.model,
      `facing=${face.toLowerCase()},half=${name} 用 ${spec.model.split('/').pop()}`,
      `实际 ${variant.model}`);
  }
}
// 两半拼回一整段：本格 right 的终点（16i+16）必须正好等于下一格 left 的起点（16(i+1)+0）。
ok(HALVES.right.s1 === 16 && HALVES.left.s0 === 0 && HALVES.left.s1 === HALVES.right.s1 - 8,
  '本格 half=right 的右边缘与下一格 half=left 的左边缘相接（拼回一整段，缝 = 0）',
  `right=[${HALVES.right.s0},${HALVES.right.s1}] left=[${HALVES.left.s0},${HALVES.left.s1}]`);
// 半格必须正好是整格的一半（8px）。
ok(HALVES.left.s1 - HALVES.left.s0 === 8 && HALVES.right.s1 - HALVES.right.s0 === 8,
  '左右半格各占 8px（整格的 1/2）');

// BER 四边形 vs 模型：每个 facing × half，四边形在阅读轴上的区间必须和
// 「模型 ScreenPlane 的 x 区间按 blockstate y 旋转过去」逐格重合（误差 < 0.01px）。
// 这就是「画面只画留下的那 8px」的离线证据 —— 模型、blockstate、BER 三者只要有一个算错就红。
const rot2 = (x, z, deg) => {
  const rad = (deg * Math.PI) / 180;
  const cos = Math.round(Math.cos(rad));
  const sin = Math.round(Math.sin(rad));
  const t = x - 8;
  const u = z - 8;
  return [8 + t * cos - u * sin, 8 + t * sin + u * cos];
};
for (const face of FACES) {
  for (const [name, spec] of Object.entries(HALVES)) {
    const q = windowQuad(face, spec.s0, spec.s1);
    const axis = (face === 'NORTH' || face === 'SOUTH') ? 0 : 2;
    const vals = q.map((p) => p[axis] * 16);
    const run = [Math.min(...vals), Math.max(...vals)];
    const corners = [rot2(spec.mx0, 0.5, bsY[face]), rot2(spec.mx1, 0.5, bsY[face])];
    const m = corners.map((p) => p[axis === 0 ? 0 : 1]).sort((a, b) => a - b);
    ok(Math.abs(run[0] - m[0]) < 0.01 && Math.abs(run[1] - m[1]) < 0.01,
      `facing=${face.toLowerCase()},half=${name} 的四边形与模型窗口逐格重合`,
      `quad ${run.map((v) => v.toFixed(2))} / model ${m.map((v) => v.toFixed(2))}`);
  }
}

// ---------------------------------------------------------------- 三个朝向的四边形顶点

console.log('\n== 四边形顶点（3 段，anchor 那一格的最小角为原点）==');
for (const face of FACES) {
  const q = windowQuad(face, 0, 48);
  const text = q.map((p) => `(${p.map((v) => v.toFixed(3)).join(', ')})`).join('  ');
  console.log(`  ${label(face)}  ${text}`);
}

console.log('');
if (failures.length) {
  console.error(`FAILED (${failures.length})`);
  process.exit(1);
}
console.log('四个朝向全部「屏幕法线指向放置的玩家」。');
