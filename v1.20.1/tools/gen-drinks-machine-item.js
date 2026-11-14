// 饮料售卖机的物品图标：把**整台机器**（下半 + 上半）正交渲染成一张 128×128 的透明底 sprite。
//
// 为什么不用方块模型当图标：原版方块模型只能摆轴对齐的盒子，上下两半是两个方块，
// 物品栏里就只能是「两个半台」（也就是用户看到的那个 bug）。这里按
// tools/render-eyecandy-icons.js / tools/psd/gen-item-sprites.js 同一套思路离线光栅化：
//   Blockbench elements → 四边形（uv 0..1、绕序朝外）→ 相机投影（正交）→ z-buffer + 背面剔除
//   + cutout（alpha < 128 丢掉）→ 4× 超采样后盒式降采样。
//
// 用法：node tools/gen-drinks-machine-item.js
// 输出：src/main/resources/assets/mtrlte/textures/item/drinks_machine.png
//      （对应的物品模型由 tools/gen-drinks-machine.js 写成 item/generated + layer0）
'use strict';

const fs = require('fs');
const path = require('path');
const P = require(path.resolve(__dirname, '..', '..', 'tools', 'preview-rawmodels.js'));

const RES = path.resolve(__dirname, '..', 'src', 'main', 'resources');
const ASSETS = path.join(RES, 'assets', 'mtrlte');
const OUT = path.join(ASSETS, 'textures', 'item', 'drinks_machine.png');

const SIZE = Number(process.env.DRINKS_ICON_SIZE) || 128; // 输出尺寸（物品栏 16px 格子里 128 也够，缩下去边缘不发糊）
const SS = 4;          // 超采样倍数
const FILL = 0.92;     // 画面填充率（留一点透明边距）
const YAW = 0.62;      // 绕 Y：正面偏左 35°（正对 = 0，机器正面朝 +Z）
const PITCH = 0.30;    // 抬相机 17°：能看到顶盖 + 取货口前沿
const OUT_OVERRIDE = process.env.DRINKS_ICON_OUT || null; // 调试用：换个输出路径

/** 上下两块按「同一台的上下两段」拼起来：上半 +16（1 格）。 */
const PARTS = [
  { model: 'drinks_machine_bottom_closed', dy: 0 },
  { model: 'drinks_machine_top', dy: 16 },
];

// ------------------------------------------------------------------ 光栅化

/** 预乘 alpha 的「over」合成，透明底。 */
function rasterise(quads, tex, size, view) {
  const buf = new Float64Array(size * size * 4);
  const depth = new Float32Array(size * size).fill(-Infinity);

  for (const q of quads) {
    const sp = q.pos.map(view);
    // 背面剔除（与 getEntityCutout 一致）：投影只有一个 y 翻转，
    // 所以世界里的朝外绕序在屏幕上是**负**面积。
    const e1 = [sp[1].x - sp[0].x, sp[1].y - sp[0].y];
    const e2 = [sp[2].x - sp[0].x, sp[2].y - sp[0].y];
    if (e1[0] * e2[1] - e1[1] * e2[0] >= 0) continue;

    for (const [i0, i1, i2] of [[0, 1, 2], [0, 2, 3]]) {
      const p0 = sp[i0];
      const p1 = sp[i1];
      const p2 = sp[i2];
      const bx0 = Math.max(0, Math.floor(Math.min(p0.x, p1.x, p2.x)));
      const bx1 = Math.min(size - 1, Math.ceil(Math.max(p0.x, p1.x, p2.x)));
      const by0 = Math.max(0, Math.floor(Math.min(p0.y, p1.y, p2.y)));
      const by1 = Math.min(size - 1, Math.ceil(Math.max(p0.y, p1.y, p2.y)));
      const den = (p1.y - p2.y) * (p0.x - p2.x) + (p2.x - p1.x) * (p0.y - p2.y);
      if (Math.abs(den) < 1e-9) continue;

      for (let y = by0; y <= by1; y++) {
        for (let x = bx0; x <= bx1; x++) {
          const px = x + 0.5;
          const py = y + 0.5;
          const w0 = ((p1.y - p2.y) * (px - p2.x) + (p2.x - p1.x) * (py - p2.y)) / den;
          const w1 = ((p2.y - p0.y) * (px - p2.x) + (p0.x - p2.x) * (py - p2.y)) / den;
          const w2 = 1 - w0 - w1;
          if (w0 < -1e-6 || w1 < -1e-6 || w2 < -1e-6) continue;
          const z = w0 * p0.z + w1 * p1.z + w2 * p2.z;
          const idx = y * size + x;
          if (z <= depth[idx]) continue;
          const u = w0 * q.uv[i0][0] + w1 * q.uv[i1][0] + w2 * q.uv[i2][0];
          const v = w0 * q.uv[i0][1] + w1 * q.uv[i1][1] + w2 * q.uv[i2][1];
          // uv 夹在 0..1：游戏里超界会 REPEAT 绕到贴图另一边（那里的颜色是给别的面的），
          // 图标按 clamp 采样，和 Blockbench 预览一致。
          const tx = Math.min(tex.width - 1, Math.max(0, Math.floor(Math.min(1, Math.max(0, u)) * tex.width)));
          const ty = Math.min(tex.height - 1, Math.max(0, Math.floor(Math.min(1, Math.max(0, v)) * tex.height)));
          const t = (ty * tex.width + tx) * 4;
          const a = tex.data[t + 3] / 255;
          if (a < 0.5) continue; // cutout
          depth[idx] = z;
          const s = q.shade;
          const o = idx * 4;
          buf[o] = (tex.data[t] / 255) * s;
          buf[o + 1] = (tex.data[t + 1] / 255) * s;
          buf[o + 2] = (tex.data[t + 2] / 255) * s;
          buf[o + 3] = 1;
        }
      }
    }
  }
  return buf;
}

/** SS× 超采样 → alpha 加权盒式降采样（避免边缘发黑）。 */
function downsample(buf, big, small) {
  const out = Buffer.alloc(small * small * 4);
  const n = big / small;
  for (let y = 0; y < small; y++) {
    for (let x = 0; x < small; x++) {
      let r = 0;
      let g = 0;
      let b = 0;
      let a = 0;
      for (let sy = 0; sy < n; sy++) {
        for (let sx = 0; sx < n; sx++) {
          const o = ((y * n + sy) * big + (x * n + sx)) * 4;
          const alpha = buf[o + 3];
          r += buf[o] * alpha;
          g += buf[o + 1] * alpha;
          b += buf[o + 2] * alpha;
          a += alpha;
        }
      }
      const d = (y * small + x) * 4;
      if (a > 0) {
        out[d] = Math.round((r / a) * 255);
        out[d + 1] = Math.round((g / a) * 255);
        out[d + 2] = Math.round((b / a) * 255);
        out[d + 3] = Math.round((a / (n * n)) * 255);
      }
    }
  }
  return out;
}

// ------------------------------------------------------------------ main

const quads = [];
let tex = null;
for (const part of PARTS) {
  const m = P.mesh(path.join(P.ROOT, 'rawmodels', part.model + '.json'));
  tex = m.tex;
  for (const q of m.quads) {
    quads.push({ ...q, pos: q.pos.map((p) => [p[0], p[1] + part.dy, p[2]]) });
  }
}

// 取景：投影一遍求包围盒，居中 + 贴合画布。
const camera = P.camera(YAW, PITCH);
const probe = quads.map((q) => q.pos.map(camera(1)));
let minX = Infinity;
let maxX = -Infinity;
let minY = Infinity;
let maxY = -Infinity;
for (const sp of probe) {
  for (const s of sp) {
    minX = Math.min(minX, s.x);
    maxX = Math.max(maxX, s.x);
    minY = Math.min(minY, s.y);
    maxY = Math.max(maxY, s.y);
  }
}
const big = SIZE * SS;
const scale = (big * FILL) / Math.max(maxX - minX, maxY - minY, 1e-6);
const midX = (minX + maxX) / 2;
const midY = (minY + maxY) / 2;
const view = (p) => {
  const s = camera(1)(p);
  return { x: big / 2 + (s.x - midX) * scale, y: big / 2 + (s.y - midY) * scale, z: s.z };
};

const buf = rasterise(quads, tex, big, view);
let drawn = 0;
for (let i = 3; i < buf.length; i += 4) if (buf[i] > 0.5) drawn++;
const coverage = drawn / (big * big);
if (coverage < 0.02) throw new Error(`icon is empty (coverage=${coverage.toFixed(4)})`);

const sprite = downsample(buf, big, SIZE);
const outPath = OUT_OVERRIDE || OUT;
fs.mkdirSync(path.dirname(outPath), { recursive: true });
P.writePng(outPath, SIZE, SIZE, sprite);
console.log(`${PARTS.length} parts, ${quads.length} quads → ${path.relative(RES, outPath)} (${SIZE}x${SIZE}, coverage ${(coverage * 100).toFixed(1)}%)`);
