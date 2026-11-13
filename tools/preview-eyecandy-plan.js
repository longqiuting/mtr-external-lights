#!/usr/bin/env node
// 离线「俯视平面图」：把 eyecandy 的 .obj 按描述文件的变换摆好，从正上方画成 PNG。
//
// 为什么要它：BRT 栏杆（xiamen_brt_railing_*）的问题是「厚度方向压在格子中线上」，在 3D 等轴图里
// 很难一眼看出它到底贴不贴边 —— 俯视图里格子边线 / 中线都画出来，一眼就能判断。
//
// 变换与 Java 端 `RawObjParser.place` 完全同构：scale → rotation(XYZ 欧拉) → translation(格)
// → 模型空间（x/z * 16 + 8）。这个顺序与 NTE 的 BER 一致（见 docs 报告）：
//   NTE BlockEntityEyeCandyRenderer：PoseStack.translate(0.5,0,0.5) → translate(t) →
//   rotateX/Y/Z → rotateY(facing)，JOML 全是后乘 → 顶点先转、再平移。
// 所以描述文件里的 translation 是在**转过之后**的方块坐标轴上生效的（不是模型自己的轴）。
//
// 用法：node tools/preview-eyecandy-plan.js [key…] [--out DIR] [--descriptor first.json]
//        [--facing north,east]   （只画这几个 facing）
//        [--look]                （= 放置视角：四个「玩家朝向」各画一张，facing 取反 → 落点）
//        [--collision]           （再打印碰撞盒，转角栏杆是 L 形的那几只盒子）
// 输出：<out>/<key>.png（默认 temp/eyecandy-plan/），并打印每个 key 的 AABB（格）。
//
// 碰撞盒由 tools/verify-eyecandies.js 的 boxesFor / rotBox 算（与 Java 端
// EyecandyBlocks.boxesOf + EyecandyBlock.rotateBox 同构），所以这里打印出来的
// 「转过去的落点」和游戏里真正挡人的形状是同一份。
'use strict';

const fs = require('fs');
const path = require('path');
const { writePng } = require('./preview-rawmodels');
const { boxesFor, rotBox, rotPoints, boundsOf, FACINGS, OPPOSITE } =
  require('./verify-eyecandies.js');

const RES = path.resolve(__dirname, '..', 'v1.20.1', 'src', 'main', 'resources');
const EYECANDY = path.join(RES, 'assets', 'mtrlte', 'eyecandies');

const SIZE = 512;      // 画布边长（px）
const MARGIN = 40;     // 格子四周留白（px）
const SPAN = SIZE - MARGIN * 2; // 一格 = SPAN px
const PX_PER_UNIT = SPAN / 16;

// ------------------------------------------------------------------ 变换（与 Java 同构）

function rotate(p, a) {
  let [x, y, z] = p;
  if (a[0]) {
    const r = (a[0] * Math.PI) / 180;
    const c = Math.cos(r); const s = Math.sin(r);
    const ny = y * c - z * s; const nz = y * s + z * c;
    y = ny; z = nz;
  }
  if (a[1]) {
    const r = (a[1] * Math.PI) / 180;
    const c = Math.cos(r); const s = Math.sin(r);
    const nx = x * c + z * s; const nz = -x * s + z * c;
    x = nx; z = nz;
  }
  if (a[2]) {
    const r = (a[2] * Math.PI) / 180;
    const c = Math.cos(r); const s = Math.sin(r);
    const nx = x * c - y * s; const ny = x * s + y * c;
    x = nx; y = ny;
  }
  return [x, y, z];
}

/** obj 顶点（格）→ 模型空间 0..16（= RawObjParser.place）。 */
function place(verts, entry) {
  const s = entry.scale || [1, 1, 1];
  const a = entry.rotation || [0, 0, 0];
  const t = entry.translation || [0, 0, 0];
  const m = entry.mirror || [false, false, false];
  return verts.map((v) => {
    const p = rotate([v[0] * s[0] * (m[0] ? -1 : 1), v[1] * s[1] * (m[1] ? -1 : 1), v[2] * s[2] * (m[2] ? -1 : 1)], a);
    return [(p[0] + t[0]) * 16 + 8, (p[1] + t[1]) * 16, (p[2] + t[2]) * 16 + 8];
  });
}

/** 与 EyecandyBlocks.boxOf 同一套「抹零 + 防御下限」，单位 = 格。 */
const NUDGE = 2.5 / 16;
const MIN_SIZE = 1.0 / 16;
function boxOf(units) {
  const box = new Array(6);
  for (let axis = 0; axis < 3; axis++) {
    let min = units[axis] / 16;
    let max = units[axis + 3] / 16;
    if (min < 0 && min > -NUDGE) min = 0;
    if (max - min < MIN_SIZE) max = min + MIN_SIZE;
    box[axis] = min;
    box[axis + 3] = max;
  }
  return box;
}

function objGeometry(file) {
  const verts = [];
  const tris = [];
  const fix = (i, n) => (i < 0 ? n + i : i - 1);
  for (const raw of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line[0] === '#') continue;
    const parts = line.split(/\s+/);
    if (parts[0] === 'v') verts.push([parseFloat(parts[1]), parseFloat(parts[2]), parseFloat(parts[3])]);
    else if (parts[0] === 'f') {
      const idx = parts.slice(1).map((t) => fix(parseInt(t.split('/')[0], 10), verts.length));
      for (let i = 1; i + 1 < idx.length; i++) tris.push([idx[0], idx[i], idx[i + 1]]);
    }
  }
  return { verts, tris };
}

// ------------------------------------------------------------------ 2D 光栅化

function makeCanvas() {
  const buf = Buffer.alloc(SIZE * SIZE * 4);
  for (let i = 0; i < SIZE * SIZE; i++) {
    buf[i * 4] = 24; buf[i * 4 + 1] = 26; buf[i * 4 + 2] = 30; buf[i * 4 + 3] = 255;
  }
  return buf;
}

function blend(buf, px, py, r, g, b, a) {
  if (px < 0 || py < 0 || px >= SIZE || py >= SIZE) return;
  const i = (py * SIZE + px) * 4;
  buf[i] = Math.round(buf[i] * (1 - a) + r * a);
  buf[i + 1] = Math.round(buf[i + 1] * (1 - a) + g * a);
  buf[i + 2] = Math.round(buf[i + 2] * (1 - a) + b * a);
}

function fillTriangle(buf, p0, p1, p2, r, g, b, a) {
  const minX = Math.max(0, Math.floor(Math.min(p0[0], p1[0], p2[0])));
  const maxX = Math.min(SIZE - 1, Math.ceil(Math.max(p0[0], p1[0], p2[0])));
  const minY = Math.max(0, Math.floor(Math.min(p0[1], p1[1], p2[1])));
  const maxY = Math.min(SIZE - 1, Math.ceil(Math.max(p0[1], p1[1], p2[1])));
  const edge = (ax, ay, bx, by, px, py) => (px - ax) * (by - ay) - (py - ay) * (bx - ax);
  const area = edge(p0[0], p0[1], p1[0], p1[1], p2[0], p2[1]);
  if (Math.abs(area) < 1e-9) return;
  for (let y = minY; y <= maxY; y++) {
    for (let x = minX; x <= maxX; x++) {
      const cx = x + 0.5; const cy = y + 0.5;
      const w0 = edge(p1[0], p1[1], p2[0], p2[1], cx, cy);
      const w1 = edge(p2[0], p2[1], p0[0], p0[1], cx, cy);
      const w2 = edge(p0[0], p0[1], p1[0], p1[1], cx, cy);
      if ((w0 >= 0 && w1 >= 0 && w2 >= 0) || (w0 <= 0 && w1 <= 0 && w2 <= 0)) blend(buf, x, y, r, g, b, a);
    }
  }
}

/** x/z 单位 → 像素（x 向右、z 向下：方块北 = 画面上方）。 */
const proj = (p) => [MARGIN + p[0] * PX_PER_UNIT, MARGIN + p[2] * PX_PER_UNIT];

function hLine(buf, y, x0, x1, r, g, b, dash) {
  for (let x = x0; x <= x1; x++) {
    if (dash && x % (dash * 2) >= dash) continue;
    blend(buf, x, y, r, g, b, 1);
  }
}

function vLine(buf, x, y0, y1, r, g, b, dash) {
  for (let y = y0; y <= y1; y++) {
    if (dash && y % (dash * 2) >= dash) continue;
    blend(buf, x, y, r, g, b, 1);
  }
}

/** 画格子边框（0 / 16 单位 = 方块两条边）+ 中线（8 单位，虚线）+ 每 1 单位的浅网格。 */
function drawGrid(buf) {
  for (let u = 0; u <= 16; u++) {
    const p = Math.round(MARGIN + u * PX_PER_UNIT - 0.5);
    if (u === 0 || u === 16) {
      vLine(buf, p, Math.round(MARGIN - 0.5), Math.round(MARGIN + SPAN - 0.5), 120, 200, 255, 0);
      hLine(buf, p, Math.round(MARGIN - 0.5), Math.round(MARGIN + SPAN - 0.5), 120, 200, 255, 0);
    } else {
      const shade = u % 4 === 0 ? 70 : 44;
      vLine(buf, p, MARGIN, MARGIN + SPAN, shade, shade + 4, shade + 8, 0);
      hLine(buf, p, MARGIN, MARGIN + SPAN, shade, shade + 4, shade + 8, 0);
    }
  }
  // 中线（8 单位）：橙虚线 —— 栏杆压着它就是「一半在中间」那种症状
  const c = Math.round(MARGIN + 8 * PX_PER_UNIT - 0.5);
  vLine(buf, c, MARGIN, MARGIN + SPAN, 255, 170, 60, 4);
  hLine(buf, c, MARGIN, MARGIN + SPAN, 255, 170, 60, 4);
}

function drawBox(buf, box, r, g, b) {
  const [x0, x1] = [Math.round(MARGIN + box[0] * 16 * PX_PER_UNIT), Math.round(MARGIN + box[3] * 16 * PX_PER_UNIT)];
  const [z0, z1] = [Math.round(MARGIN + box[2] * 16 * PX_PER_UNIT), Math.round(MARGIN + box[5] * 16 * PX_PER_UNIT)];
  for (let x = x0; x <= x1; x++) { blend(buf, x, z0, r, g, b, 1); blend(buf, x, z1, r, g, b, 1); }
  for (let z = z0; z <= z1; z++) { blend(buf, x0, z, r, g, b, 1); blend(buf, x1, z, r, g, b, 1); }
}

// ------------------------------------------------------------------ 主流程

/** 与 EyecandyBlock.shapeOf / EyecandyRenderer 同一个角度：绕方块中心 (8,8) 每 90° 一步 (x, z) → (16 − z, x)。 */
function rotateFacing(placed, steps) {
  return placed.map((p) => {
    let x = p[0];
    let z = p[2];
    for (let i = 0; i < steps; i++) {
      const nx = 16 - z;
      const nz = x;
      x = nx;
      z = nz;
    }
    return [x, p[1], z];
  });
}

function main() {
  const args = process.argv.slice(2);
  const outIdx = args.indexOf('--out');
  const outDir = path.resolve(__dirname, '..', outIdx >= 0 ? args[outIdx + 1] : path.join('temp', 'eyecandy-plan'));
  const descIdx = args.indexOf('--descriptor');
  const descriptor = descIdx >= 0 ? args[descIdx + 1] : 'first.json';
  const facingIdx = args.indexOf('--facing');
  const facingList = facingIdx >= 0 ? args[facingIdx + 1].split(',') : null;
  const lookMode = args.includes('--look');
  const showCollision = args.includes('--collision');
  if (facingList) {
    const bad = facingList.filter((f) => !FACINGS.includes(f));
    if (bad.length) {
      console.error('unknown facing(s): ' + bad.join(', ') + ' (north|east|south|west)');
      process.exit(1);
    }
  }
  const valueIdx = new Set([outIdx, descIdx, facingIdx].filter((i) => i >= 0).map((i) => i + 1));
  const keys = args.filter((a, i) => !a.startsWith('--') && !valueIdx.has(i));

  const json = JSON.parse(fs.readFileSync(path.join(EYECANDY, descriptor), 'utf8'));
  fs.mkdirSync(outDir, { recursive: true });

  const targets = keys.length ? keys : Object.keys(json);
  const unknown = targets.filter((k) => !json[k]);
  if (unknown.length) {
    console.error('unknown key(s): ' + unknown.join(', '));
    process.exit(1);
  }

  console.log(`# ${descriptor}  —  顶视 AABB（单位：格；x/z = 左下角起算的方块内坐标）`);
  console.log('key                                  look  facing  x[min,max]        z[min,max]        厚轴(最薄)');
  let pngs = 0;
  const fmtBox = (b) => `[${b.map((v) => (Math.round(v * 100) / 100)).join(', ')}]`;
  for (const key of targets) {
    const entry = json[key];
    const objName = entry.model.replace(/^.*\//, '');
    const file = path.join(EYECANDY, objName);
    const { verts, tris } = objGeometry(file);
    const placed = place(verts, entry);
    // 碰撞盒（模型空间单位，北向那一份）：与 Java 注册期算的是同一组。
    const northBoxes = showCollision
      ? boxesFor(key, entry, file, boxOf(boundsOf(placed)).map((v) => v * 16))
      : null;
    // 方向见证点：北向时「最靠南（+z）的那个顶点」—— 转完之后它跑到哪，一眼能看出整件转了
    // （AABB 往往是对称的，光看包围盒分不出朝北还是朝南）。
    let witness = 0;
    for (let i = 1; i < placed.length; i++) {
      if (placed[i][2] > placed[witness][2] + 1e-9) witness = i;
    }
    const witnessNorth = placed[witness];

    // --look：四个「玩家朝向」各一份（facing = 玩家朝向的反方向 → 落点在玩家对面）；
    // 否则按 --facing；都没有就只画「模型原始朝向」（= facing=north，0°）。
    const views = lookMode
      ? FACINGS.map((look) => ({ look, facing: OPPOSITE[look], steps: FACINGS.indexOf(OPPOSITE[look]) }))
      : facingList
        ? facingList.map((f) => ({ look: null, facing: f, steps: FACINGS.indexOf(f) }))
        : [{ look: null, facing: null, steps: 0 }];

    for (const view of views) {
      const v = view.steps === 0 ? placed : rotateFacing(placed, view.steps);
      const box = boxOf(boundsOf(v));
      const size = [box[3] - box[0], box[4] - box[1], box[5] - box[2]];
      const thin = size[0] < size[2] ? 'x' : 'z';

      console.log(
        `${key.padEnd(36)} ${(view.look || '-').padEnd(5)} ${(view.facing || '-').padEnd(7)} ` +
        `[${box[0].toFixed(3).padStart(6)},${box[3].toFixed(3).padStart(6)}]  ` +
        `[${box[2].toFixed(3).padStart(6)},${box[5].toFixed(3).padStart(6)}]  ${thin} (${size[thin === 'x' ? 0 : 2].toFixed(3)})`);

      if (showCollision) {
        const boxes = northBoxes.map((b) => rotBox(b, view.steps));
        console.log(`    collision (1/16 格) ×${boxes.length}: ${boxes.map(fmtBox).join('  ')}`);
      }
      // 见证点（模型 +z 最外侧那个顶点）转完落在哪：证明「模型跟着 facing 转」，不只是碰撞盒。
      const w = rotPoints([witnessNorth], view.steps)[0];
      console.log(`    front vertex (1/16 格): (${witnessNorth[0].toFixed(1)}, ${witnessNorth[2].toFixed(1)}) `
        + `→ (${w[0].toFixed(1)}, ${w[2].toFixed(1)})`);

      const buf = makeCanvas();
      for (const tri of tris) {
        const p = tri.map((i) => proj(v[i]));
        fillTriangle(buf, p[0], p[1], p[2], 255, 90, 90, 0.55);
      }
      drawGrid(buf);
      drawBox(buf, box, 255, 230, 120);
      const suffix = view.look ? `look_${view.look}` : view.facing ? `facing_${view.facing}` : null;
      writePng(path.join(outDir, suffix ? `${key}_${suffix}.png` : `${key}.png`),
        SIZE, SIZE, buf);
      pngs++;
    }
  }
  console.log(`\nwrote ${pngs} png(s) to ${path.relative(process.cwd(), outDir)}`);
}

if (require.main === module) main();

module.exports = { place, boxOf, objGeometry, PARAMS: { SIZE, MARGIN, SPAN, PX_PER_UNIT } };
