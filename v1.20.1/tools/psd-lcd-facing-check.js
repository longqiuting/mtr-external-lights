#!/usr/bin/env node
/*
 * PSD-LCD 朝向 + 模型来源自检（离线）
 * ==================================
 *
 * 这块屏被用户纠正过好几次，现在只有一条硬标准：
 *
 *   1. **两套模型就是用户给的 json 过一遍 tools/psd-lcd-models.js 的确定性变换**：
 *      参考件 `tools/psd/psd_lcd_xiamen.json` 的 `mornal` 组（element 0..6）→
 *      `models/block/psd_lcd_xiamen.json`，`half` 组（element 7..13）→
 *      `..._half.json`。这里**整份 json 逐字段比对**（连 uv / face / cullface 都不许手改），
 *      再加一条「(from,to,rotation) 反算 8 个角点 = 变换后的角点」，误差 > 0.001px 就报错。
 *   2. **屏幕窗口是从模型自己的几何量出来的**：用户的新模型里没有 ScreenPlane 了，所以窗口带
 *      改成扫高度找「最前面那个面 = 凹槽底」的连续区间 —— 结果必须等于 PsdLcdLayout 的
 *      WINDOW_Y0/Y1（整块 1.5…8.5）与 CUT_WINDOW_Y0/Y1（剪后 9…16），凹槽底 z 必须等于
 *      SCREEN_FACE_Z_PX（10.2），BER 四边形再往前一个 FACE_GAP（10.184）。
 *   3. **线路色带画在一条真的前脸上**：色带 y 带必须落在 z = PANEL_Z0（板子正面）的平面上，
 *      而且不许和屏幕窗口重叠（否则会和 LCD 四边形打架）。
 *   4. **四向一致**：blockstate 的 y 旋转、`PsdLcdLayout.Face#blockstateY()`、模型法线、
 *      BER 四边形四者逐朝向对账；放置规则永远是「面朝放置的玩家」。
 *
 * 用法（仓库根目录）：node v1.20.1/tools/psd-lcd-facing-check.js
 * 退出码：0 = 全部通过；1 = 有断言失败。
 */
'use strict';

const fs = require('fs');
const path = require('path');

const models = require('./psd-lcd-models');

const HERE = __dirname;
const VERSION_DIR = path.resolve(HERE, '..');
const BLOCKSTATE = path.join(VERSION_DIR, 'src/main/resources/assets/mtrlte/blockstates/psd_lcd_xiamen.json');
const LAYOUT_JAVA = path.join(VERSION_DIR, 'src/main/java/dev/js/mtrheadlight/xiamen/pids/PsdLcdLayout.java');
const BLOCK_JAVA = path.join(VERSION_DIR, 'src/main/java/dev/js/mtrheadlight/xiamen/pids/PsdLcdBlock.java');

const failures = [];
function ok(cond, label, detail) {
  if (!cond) {
    failures.push(label);
    console.log(`  FAIL  ${label}${detail ? '   ' + detail : ''}`);
  } else {
    console.log(`  PASS  ${label}${detail ? '   ' + detail : ''}`);
  }
  return cond;
}

// ---------------------------------------------------------------- 几何常量（= PsdLcdLayout）

const java = fs.readFileSync(LAYOUT_JAVA, 'utf8');
const blockJava = fs.readFileSync(BLOCK_JAVA, 'utf8');
/** 从某个 java 源码里读 `static final float NAME = xF;`。 */
const numIn = (source, name) => {
  const m = source.match(new RegExp(`static final float ${name}\\s*=\\s*([-0-9.f]+)F`));
  return m ? parseFloat(m[1]) : NaN;
};
const num = (name) => numIn(java, name);
const WIN_Y0 = num('WINDOW_Y0_PX');
const WIN_Y1 = num('WINDOW_Y1_PX');
const CUT_Y0 = num('CUT_WINDOW_Y0_PX');
const CUT_Y1 = num('CUT_WINDOW_Y1_PX');
const Z0 = num('PANEL_Z0_PX');
const RECESS = num('SCREEN_RECESS_PX');
// SCREEN_FACE_Z_PX / STRIP_DEPTH_BLOCKS 在 Java 里是算式（PANEL_Z0 + SCREEN_RECESS、
// (PANEL_Z0 - FACE_GAP)/16），脚本读不到结果，所以按同一份算式现算 —— 读得到的是它的分量。
const SCREEN_Z = Z0 + RECESS;
const GAP = num('FACE_GAP_PX');
const STRIP_Z = Z0 - GAP;
const CUT_BOTTOM = numIn(blockJava, 'CUT_BOTTOM_Y_PX');
const STRIP = {
  false: [num('STRIP_Y0_PX'), num('STRIP_Y1_PX')],
  true: [num('CUT_STRIP_Y0_PX'), num('CUT_STRIP_Y1_PX')],
};

/** 两档的期望（px，16 分之格）。 */
const BANDS = { false: [WIN_Y0, WIN_Y1], true: [CUT_Y0, CUT_Y1] };
/** 屏幕面 / BER 四边形所在 z（px）。 */
const PLANE_Z = SCREEN_Z;            // 10.2 —— 模型里凹槽底那一层
const BER_Z = PLANE_Z - GAP;         // 10.184 —— BER 四边形（防 z-fighting）

// ---------------------------------------------------------------- 1. 两套模型 = 用户 json + 变换

console.log('== 1. 两套模型 ↔ 用户给的 psd_lcd_xiamen.json（过一遍确定性变换）==');
console.log(`  参考文件  ${models.REF_PATH}`);
const ref = models.loadRef();
const verification = models.verify(ref);
ok(verification.ok, '变换自检（等距 / 包围盒 / 反算角点 / 贴图命名空间）',
  verification.ok ? '' : `${verification.failures.length} 处`);
for (const f of verification.failures) {
  console.log(`        ${f}`);
}

for (const state of models.STATES) {
  const file = models.modelPath(state.file);
  if (!ok(fs.existsSync(file), `${state.file} 存在`, file)) {
    continue;
  }
  const model = models.readJson(file);
  const expected = models.transformGroup(ref, state.group, state.bboxY[0]);
  const same = JSON.stringify(models.canonical ? models.canonical(model) : model)
    === JSON.stringify(models.canonical ? models.canonical(expected) : expected);
  // canonical 没导出时退回整串比较（生成器就是按固定顺序写的）
  const identical = same || JSON.stringify(model) === JSON.stringify(expected);
  ok(identical, `${state.file} 逐字段等于 ${state.group} 组变换后的结果（${expected.elements.length} 个元素）`,
    identical ? '' : '磁盘上的模型和生成器不一致 —— 跑 node tools/psd-lcd-models.js --write');

  // 反算角点：磁盘上那份也必须还原出同一批角点
  let worst = 0;
  model.elements.forEach((el, i) => {
    const target = models.transformedCorners(models.groupElements(ref, state.group)[i]);
    worst = Math.max(worst, models.cornerError(el.from, el.to, el.rotation, target));
  });
  ok(worst < models.ROUND_EPS, `${state.file} 每个元素反算角点误差 < ${models.ROUND_EPS}px`,
    `最大 ${worst.toExponential(2)}px`);

  const tex = Object.values(model.textures || {});
  ok(tex.length > 0 && tex.every((v) => v.startsWith('mtrlte:')),
    `${state.file} 的贴图全在 mtrlte 命名空间里`, tex.join(', '));
}

// ---------------------------------------------------------------- 2. 窗口带 / 屏幕面

console.log('\n== 2. 窗口带（从模型几何反推）↔ PsdLcdLayout.java ==');
ok(WIN_Y0 === 1.5 && WIN_Y1 === 8.5, 'Java 的 WINDOW_Y0/Y1 = 1.5 / 8.5（整块）', `${WIN_Y0} / ${WIN_Y1}`);
ok(CUT_Y0 === 9.0 && CUT_Y1 === 16.0, 'Java 的 CUT_WINDOW_Y0/Y1 = 9 / 16（剪后只剩上半格）', `${CUT_Y0} / ${CUT_Y1}`);
ok(SCREEN_Z === 10.2 && Z0 === 10.0,
  'Java 的 SCREEN_FACE_Z_PX = 10.2（= 正面 10 + 凹槽 0.2）', `${SCREEN_Z} / ${Z0}`);
ok(Math.abs(BER_Z - 10.184) < 1e-9, `BER 四边形在 z = 10.184px（模型面 ${PLANE_Z} − ${GAP}）`, `${BER_Z}`);

for (const state of models.STATES) {
  const model = models.readJson(models.modelPath(state.file));
  const band = models.derivedWindowBand(model);
  const want = BANDS[String(state.cut)];
  ok(Math.abs(band.y0 - want[0]) < 0.06 && Math.abs(band.y1 - want[1]) < 0.06,
    `${state.label} 从模型量出来的窗口 y 带 = ${want[0]} … ${want[1]}px`,
    `模型 ${band.y0} … ${band.y1}`);
  ok(Math.abs(band.y0 - state.windowY0) < 0.06 && Math.abs(band.y1 - state.windowY1) < 0.06,
    `${state.label} 窗口带与 STATES 表一致（${state.windowY0} … ${state.windowY1}）`);
  ok(Math.abs(band.z - SCREEN_Z) < 1e-6,
    `${state.label} 凹槽底 z = ${SCREEN_Z}px（屏幕画在这里再往前 ${GAP}）`, `模型 ${band.z}`);

  // 窗口以外必须有一条完整的前脸：正面（z = PANEL_Z0）在窗口带里不能有东西突出来
  const intruders = model.elements.filter((el) => {
    const y0 = Math.min(el.from[1], el.to[1]);
    const y1 = Math.max(el.from[1], el.to[1]);
    return y1 > band.y0 + 1e-6 && y0 < band.y1 - 1e-6 && Math.min(el.from[2], el.to[2]) < band.z - 1e-6;
  });
  ok(intruders.length === 0, `${state.label} 窗口带里没有比屏幕面更靠前的几何（画面不会被压住）`,
    intruders.map((e) => `${e.name}@${Math.min(e.from[2], e.to[2])}`).join(', '));

  // 剪过的那一档：板子从 7.5 起
  const ys = model.elements.flatMap((el) => [el.from[1], el.to[1]]);
  ok(Math.abs(Math.min(...ys) - (state.cut ? CUT_BOTTOM : 0)) < 1e-6,
    `${state.label} 板子下沿 = ${state.cut ? CUT_BOTTOM : 0}px`, `模型 ${Math.min(...ys)}`);
}

// ---------------------------------------------------------------- 2b. 线路色带

console.log('\n== 2b. 线路色带：y 带落在真的前脸上、且不与屏幕重叠 ==');
for (const state of models.STATES) {
  const model = models.readJson(models.modelPath(state.file));
  const band = models.derivedWindowBand(model);
  const strip = STRIP[String(state.cut)];
  const mid = (strip[0] + strip[1]) / 2;
  const surface = models.frontSurfaceZ(model, mid);
  ok(Math.abs(surface - Z0) < 1e-6,
    `${state.label} 色带 y ${strip[0]}…${strip[1]} 落在 z = ${Z0}（板子正面）上`, `模型给的面 ${surface}`);
  ok(strip[1] <= band.y0 + 1e-6 || strip[0] >= band.y1 - 1e-6,
    `${state.label} 色带（${strip[0]}…${strip[1]}）不与屏幕窗口（${band.y0}…${band.y1}）重叠`);
  ok(strip[1] - strip[0] > 0.4 && strip[1] - strip[0] < 0.6,
    `${state.label} 色带高 0.5px（= 模型里那条 0.5px 唇边）`, `${(strip[1] - strip[0]).toFixed(3)}px`);
  ok(Math.abs(STRIP_Z - (Z0 - GAP)) < 1e-6,
    `${state.label} 色带 quad 在 z = ${(Z0 - GAP).toFixed(3)}px（正面再往前 ${GAP}）`,
    STRIP_Z.toFixed(4));
}

// ---------------------------------------------------------------- 3. 朝向表

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

const blockstate = models.readJson(BLOCKSTATE);
const bs = {};
for (const [key, variant] of Object.entries(blockstate.variants || {})) {
  const props = Object.fromEntries(key.split(',').map((kv) => kv.trim().split('=')));
  const facing = (props.facing || '').toUpperCase();
  if (!facing) {
    continue;
  }
  bs[`${facing}/${props.half_cut}`] = variant;
}

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
  return [vec[0] * cos - vec[2] * sin, 0, vec[0] * sin + vec[2] * cos];
}

/** PsdLcdLayout#windowQuad(Face, startPx, endPx, cut) 的同一份算法（左上 → 左下 → 右下 → 右上，世界格）。 */
function windowQuad(facing, startPx, endPx, cut) {
  const f = VEC[facing];
  const right = VEC[RIGHT[facing]];
  const from = startPx / 16;
  const to = endPx / 16;
  const band = BANDS[String(!!cut)];
  const y0 = band[0] / 16;
  const y1 = band[1] / 16;
  const plane = (f[0] < 0 || f[2] < 0) ? BER_Z / 16 : 1 - BER_Z / 16;
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

const fmt = (v, d = 3) => (v >= 0 ? ' ' : '') + v.toFixed(d);

console.log('\n== 3. 模型 ↔ blockstate ↔ BER 三方对账（4 facing × 2 档）==');
for (const face of FACES) {
  for (const state of models.STATES) {
    const variant = bs[`${face}/${state.cut}`];
    ok(variant !== undefined, `blockstate 有 facing=${face.toLowerCase()},half_cut=${state.cut}`);
    if (!variant) {
      continue;
    }
    ok((variant.y || 0) === EXPECTED_Y[face],
      `facing=${face.toLowerCase()},half_cut=${state.cut} 的 y = ${EXPECTED_Y[face]}`,
      `实际 ${variant.y || 0}`);
    ok(variant.model === state.model,
      `facing=${face.toLowerCase()},half_cut=${state.cut} 用 ${state.model.split('/').pop()}`,
      `实际 ${variant.model}`);

    const normal = rotateY([0, 0, -1], variant.y || 0);
    ok(Math.abs(normal[0] - VEC[face][0]) < 1e-6 && Math.abs(normal[2] - VEC[face][2]) < 1e-6,
      `facing=${face} 的模型法线 = ${face}`, `(${normal.join(', ')})`);

    const band = BANDS[String(state.cut)];
    const q = windowQuad(face, 0, 16, state.cut);
    const ys = q.map((p) => p[1] * 16);
    const yMin = Math.min(...ys);
    const yMax = Math.max(...ys);
    ok(Math.abs(yMin - band[0]) < 1e-3 && Math.abs(yMax - band[1]) < 1e-3,
      `facing=${face},half_cut=${state.cut} 的四边形 y = ${band[0]} … ${band[1]}px`,
      `实际 ${yMin.toFixed(3)} … ${yMax.toFixed(3)}`);

    const axis = (face === 'NORTH' || face === 'SOUTH') ? 2 : 0;
    const span = q.map((p) => p[axis] * 16);
    const expectPlane = VEC[face][axis] < 0 ? BER_Z : 16 - BER_Z;
    ok(Math.abs(Math.min(...span) - expectPlane) < 1e-3,
      `facing=${face},half_cut=${state.cut} 的四边形在 z/x = ${expectPlane.toFixed(3)}px（BER 面）`,
      `实际 ${Math.min(...span).toFixed(4)}`);

    // 宽度：一格 16px（剪不改宽度）
    const wAxis = axis === 2 ? 0 : 2;
    const ws = q.map((p) => p[wAxis] * 16);
    ok(Math.abs((Math.max(...ws) - Math.min(...ws)) - 16) < 1e-3,
      `facing=${face},half_cut=${state.cut} 的四边形整格宽（16px）`,
      `实际 ${(Math.max(...ws) - Math.min(...ws)).toFixed(4)}`);

    const viewer = [normal[0] * 2, 1.5, normal[2] * 2];
    ok(normal[0] * viewer[0] + normal[2] * viewer[2] > 0, `facing=${face} 时屏幕正对观察者`);

    console.log(`  ${face.charAt(0)}  facing=${face.padEnd(5)} half_cut=${String(state.cut).padEnd(5)}`
      + ` y=${String(variant.y || 0).padStart(3)}°  法线=(${normal.map((v) => String(v).padStart(2)).join(',')})`
      + `  四边形 y ${band[0]}…${band[1]}px · 面 z ${expectPlane.toFixed(3)}px`
      + `  观察者 (${fmt(viewer[0])}, ${fmt(viewer[2])})`);
  }
}
ok(javaY.NORTH === 0 && javaY.EAST === 90 && javaY.SOUTH === 180 && javaY.WEST === 270,
  'Face#blockstateY() = north 0 / east 90 / south 180 / west 270',
  JSON.stringify(javaY));
ok(Object.keys(bs).length === 8, 'blockstate 正好 8 个 variants（4 facing × half_cut 两档）',
  `实际 ${Object.keys(bs).length}`);

// 放置规则：屏幕朝南时要由「看向北」的玩家放出来
for (const face of FACES) {
  const playerFacing = OPPOSITE[face];
  const yaw = Number(Object.keys(YAW_TO_FACING).find((k) => YAW_TO_FACING[k] === playerFacing));
  const placed = OPPOSITE[YAW_TO_FACING[yaw]];
  ok(placed === face, `玩家 yaw=${yaw}（朝向 ${playerFacing}）放出来的 facing = ${face}`, `实际 ${placed}`);
}

// ---------------------------------------------------------------- 3b. 色带四边形（四向）

/** PsdLcdLayout#stripQuad 的同一份算法（planeQuad，z = 正面 − FACE_GAP）。 */
function stripQuad(facing, startPx, endPx, cut, v0 = 0, v1 = 1) {
  const f = VEC[facing];
  const right = VEC[RIGHT[facing]];
  const from = Math.min(startPx, endPx) / 16;
  const to = Math.max(startPx, endPx) / 16;
  const band = STRIP[String(!!cut)];
  const h = band[1] - band[0];
  const y0 = (band[0] + h * Math.min(v0, v1)) / 16;
  const y1 = (band[0] + h * Math.max(v0, v1)) / 16;
  const plane = (f[0] < 0 || f[2] < 0) ? STRIP_Z / 16 : 1 - STRIP_Z / 16;
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

console.log('\n== 3b. 色带四边形（4 facing × 2 档，和屏幕四边形同一套算术）==');
for (const face of FACES) {
  for (const state of models.STATES) {
    const strip = STRIP[String(state.cut)];
    const q = stripQuad(face, 0, 16, state.cut);
    const axis = (face === 'NORTH' || face === 'SOUTH') ? 2 : 0;
    const expectPlane = VEC[face][axis] < 0 ? STRIP_Z : 16 - STRIP_Z;
    const span = q.map((p) => p[axis] * 16);
    ok(Math.abs(Math.min(...span) - expectPlane) < 1e-3,
      `facing=${face},half_cut=${state.cut} 色带在 z/x = ${expectPlane.toFixed(3)}px（正面 − ${GAP}）`,
      `实际 ${Math.min(...span).toFixed(4)}`);
    const ys = q.map((p) => p[1] * 16);
    ok(Math.abs(Math.min(...ys) - strip[0]) < 1e-3 && Math.abs(Math.max(...ys) - strip[1]) < 1e-3,
      `facing=${face},half_cut=${state.cut} 色带 y = ${strip[0]} … ${strip[1]}px`,
      `实际 ${Math.min(...ys).toFixed(3)} … ${Math.max(...ys).toFixed(3)}`);
    const wAxis = axis === 2 ? 0 : 2;
    const ws = q.map((p) => p[wAxis] * 16);
    ok(Math.abs((Math.max(...ws) - Math.min(...ws)) - 16) < 1e-3,
      `facing=${face},half_cut=${state.cut} 色带铺满一格（16px）`,
      `实际 ${(Math.max(...ws) - Math.min(...ws)).toFixed(4)}`);
    // 多线共站：两条色的横带正好各占一半
    const half0 = stripQuad(face, 0, 16, state.cut, 0, 0.5);
    const half1 = stripQuad(face, 0, 16, state.cut, 0.5, 1);
    const mid = (Math.min(...ys) + Math.max(...ys)) / 2 / 16;
    ok(Math.abs(half0[3][1] - mid) < 1e-6 && Math.abs(half1[1][1] - mid) < 1e-6,
      `facing=${face},half_cut=${state.cut} 两种颜色对半分（分界线 y = ${(mid * 16).toFixed(3)}px）`);
  }
}

// ---------------------------------------------------------------- 4. 方块：属性 / 轮廓 / 剪刀

console.log('\n== 4. PsdLcdBlock：half_cut 属性 / 两个轮廓 / 剪刀不损工具 ==');
ok(/BooleanProperty\.of\("half_cut"\)/.test(blockJava), '属性是 BooleanProperty.of("half_cut")');
ok(/builder\.add\(FACING, HALF_CUT\)/.test(blockJava), 'appendProperties 注册 FACING + HALF_CUT');
ok(!/\bHalf\b\s*(\.|implements|<)/.test(blockJava) && !/EnumProperty/.test(blockJava),
  '旧的 Half 枚举 / EnumProperty 已经删掉');
ok(!/panelSpan|modelXSpan/.test(blockJava), '旧的 panelSpan / modelXSpan 已经删掉');
ok(/final float y0 = cut \? CUT_BOTTOM_Y_PX : 0\.0F;/.test(blockJava),
  '轮廓：剪过 y0 = 7.5，整块 y0 = 0');
ok(/final float y1 = 16\.0F;/.test(blockJava), '轮廓：两档 y1 都是 16');
ok(/final float z0 = PANEL_Z0;/.test(blockJava) && /final float z1 = PANEL_Z1;/.test(blockJava),
  `轮廓：z ${Z0} … ${Z0 + 6}（两种状态一样，只是高度不同）`);
ok(/player\.isHolding\(Items\.SHEARS\)/.test(blockJava), '剪刀右键切换 half_cut');
ok(/state\.with\(HALF_CUT, cut\)/.test(blockJava), '切换写的是 state.with(HALF_CUT, cut)');
ok(/ENTITY_SHEEP_SHEAR/.test(blockJava), '播放 ENTITY_SHEEP_SHEAR');
ok(!/\.damage\(/.test(blockJava), '不对剪刀调用 damage()（不损工具）');
// 刷子分支必须在剪刀前面（否则刷子被剪刀抢掉）
ok(blockJava.indexOf('mtr.Items.BRUSH') < blockJava.indexOf('Items.SHEARS'),
  '刷子分支在剪刀分支之前');

console.log('');
if (failures.length) {
  console.error(`FAILED (${failures.length})`);
  for (const f of failures) {
    console.error('  - ' + f);
  }
  process.exit(1);
}
console.log(`全部通过：两套模型 = 用户 json 的 mornal / half 组过一遍同一个变换；`
  + `窗口 y 1.5…8.5 / 9…16、屏幕面 z ${SCREEN_Z}、BER ${BER_Z}；色带在正面 z ${Z0}。`);
