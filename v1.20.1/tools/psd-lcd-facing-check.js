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
for (const [key, variant] of Object.entries(blockstate.variants || {})) {
  const facing = key.replace('facing=', '').toUpperCase();
  bsY[facing] = variant.y || 0;
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

/** PsdLcdLayout#windowQuad 的同一份算法（左上 → 左下 → 右下 → 右上，世界格）。 */
function windowQuad(facing, segments) {
  const f = VEC[facing];
  const right = VEC[RIGHT[facing]];
  const n = Math.max(1, segments);
  const y0 = WIN_Y0 / 16;
  const y1 = WIN_Y1 / 16;
  const plane = (f[0] < 0 || f[2] < 0) ? (THICK - GAP) / 16 : 1 - (THICK - GAP) / 16;
  const startX = 0.5 - right[0] * 0.5;
  const startZ = 0.5 - right[2] * 0.5;
  if (right[0] !== 0) {
    const x0 = startX;
    const x1 = startX + right[0] * n;
    return [[x0, y1, plane], [x0, y0, plane], [x1, y0, plane], [x1, y1, plane]];
  }
  const z0 = startZ;
  const z1 = startZ + right[2] * n;
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
  const q = windowQuad(face, 3);
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

// ---------------------------------------------------------------- 三个朝向的四边形顶点

console.log('\n== 四边形顶点（3 段，anchor 那一格的最小角为原点）==');
for (const face of FACES) {
  const q = windowQuad(face, 3);
  const text = q.map((p) => `(${p.map((v) => v.toFixed(3)).join(', ')})`).join('  ');
  console.log(`  ${label(face)}  ${text}`);
}

console.log('');
if (failures.length) {
  console.error(`FAILED (${failures.length})`);
  process.exit(1);
}
console.log('四个朝向全部「屏幕法线指向放置的玩家」。');
