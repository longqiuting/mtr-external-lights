#!/usr/bin/env node
// 把 26 个 eyecandy 的 .obj 各渲染成一张 128×128 的等轴测物品图标（透明底）。
//
// 为什么要离线渲染：物品栏图标必须「是真实模型画出来的」，不能用方块图标的截图凑
// （这些物件是 .obj，原版 blockstate 模型根本表达不了，也就没有可截的方块图标）。
// 这里用和 Java 端同一套变换（scale → rotation(XYZ 欧拉) → translation → 模型空间 0..16），
// 自己解 PNG、自己做 z-buffer 光栅化 + 背面剔除 + 双线性采样，输出 {@code textures/item/eyecandy_<key>.png}。
//
// 用法：node tools/render-eyecandy-icons.js [key…]   （不带参数 = 全部 26 个）
// 输出：v1.20.1/src/main/resources/assets/mtrlte/textures/item/eyecandy_<key>.png
'use strict';

const fs = require('fs');
const path = require('path');
const { decodePng, writePng } = require('./preview-rawmodels');

const ROOT = path.resolve(__dirname, '..', 'v1.20.1', 'src', 'main', 'resources', 'assets', 'mtrlte');
const SRC = path.join(ROOT, 'eyecandies');
const OUT = path.join(ROOT, 'textures', 'item');

// 输出尺寸与超采样倍数（3× 渲染后盒式降采样，边缘才不会毛刺）
const SIZE = 128;
const SS = 3;
const BG_ALPHA = 0; // 透明底

// ------------------------------------------------------------------ 与 Java 同构的变换

function rotate(p, a) {
  let [x, y, z] = p;
  if (a[0]) {
    const r = (a[0] * Math.PI) / 180;
    const [c, s] = [Math.cos(r), Math.sin(r)];
    [y, z] = [y * c - z * s, y * s + z * c];
  }
  if (a[1]) {
    const r = (a[1] * Math.PI) / 180;
    const [c, s] = [Math.cos(r), Math.sin(r)];
    [x, z] = [x * c + z * s, -x * s + z * c];
  }
  if (a[2]) {
    const r = (a[2] * Math.PI) / 180;
    const [c, s] = [Math.cos(r), Math.sin(r)];
    [x, y] = [x * c - y * s, x * s + y * c];
  }
  return [x, y, z];
}

/** OBJ 顶点（格）→ 模型空间 0..16；整体在 y<=0 时抬到贴地（对应 Java 端 RawObjParser.place）。 */
function place(verts, entry) {
  const s = entry.scale || [1, 1, 1];
  const a = entry.rotation || [0, 0, 0];
  const t = entry.translation || [0, 0, 0];
  const m = entry.mirror || [false, false, false];
  const out = verts.map((v) => {
    let p = [v[0] * s[0] * (m[0] ? -1 : 1), v[1] * s[1] * (m[1] ? -1 : 1), v[2] * s[2] * (m[2] ? -1 : 1)];
    p = rotate(p, a);
    return [p[0] + t[0], p[1] + t[1], p[2] + t[2]];
  });
  let minY = Infinity;
  let maxY = -Infinity;
  for (const p of out) {
    minY = Math.min(minY, p[1]);
    maxY = Math.max(maxY, p[1]);
  }
  const lift = maxY <= 0 ? -minY : 0;
  return out.map((p) => [p[0] * 16 + 8, (p[1] + lift) * 16, p[2] * 16 + 8]);
}

// ------------------------------------------------------------------ OBJ 解析（只取渲染要用的）

function parseObj(text, flipV) {
  const verts = [];
  const uvs = [];
  const tris = [];
  const wrap = (v) => {
    const w = v - Math.floor(v);
    return w >= 1 ? 0 : w;
  };
  const fix = (idx, count) => (idx < 0 ? count + idx : idx - 1);
  for (const rawLine of text.split('\n')) {
    const line = rawLine.trim();
    if (!line || line[0] === '#') continue;
    const parts = line.split(/\s+/);
    if (parts[0] === 'v') {
      verts.push([parseFloat(parts[1]), parseFloat(parts[2]), parseFloat(parts[3])]);
    } else if (parts[0] === 'vt') {
      const u = parseFloat(parts[1]);
      const v = parts.length > 2 ? parseFloat(parts[2]) : 0;
      uvs.push([wrap(u), wrap(flipV ? 1 - v : v)]);
    } else if (parts[0] === 'f') {
      const corners = parts.slice(1).map((tok) => {
        const f = tok.split('/');
        return {
          v: fix(parseInt(f[0], 10), verts.length),
          t: f.length > 1 && f[1] !== '' ? fix(parseInt(f[1], 10), uvs.length) : -1,
        };
      });
      for (let i = 1; i + 1 < corners.length; i++) {
        tris.push([corners[0], corners[i], corners[i + 1]]);
      }
    }
  }
  return { verts, uvs, tris };
}

// ------------------------------------------------------------------ 等轴测相机（正交）

const YAW = Math.PI / 4; // 绕 Y 45°
const PITCH = Math.atan(1 / Math.SQRT2); // 真等轴测，35.264°

function isometric(p, cx, cy, cz) {
  const x0 = p[0] - cx;
  const y0 = p[1] - cy;
  const z0 = p[2] - cz;
  const xr = x0 * Math.cos(YAW) + z0 * Math.sin(YAW);
  const zr = -x0 * Math.sin(YAW) + z0 * Math.cos(YAW);
  const sy = y0 * Math.cos(PITCH) - zr * Math.sin(PITCH);
  const depth = y0 * Math.sin(PITCH) + zr * Math.cos(PITCH);
  return { x: xr, y: -sy, z: depth };
}

function shadeOf(n) {
  const [ax, ay, az] = [Math.abs(n[0]), Math.abs(n[1]), Math.abs(n[2])];
  if (ay >= ax && ay >= az) return n[1] >= 0 ? 1.0 : 0.5;
  if (ax >= az) return 0.6;
  return 0.8;
}

function normalOf(p0, p1, p2) {
  const u = [p1[0] - p0[0], p1[1] - p0[1], p1[2] - p0[2]];
  const v = [p2[0] - p0[0], p2[1] - p0[1], p2[2] - p0[2]];
  const n = [u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0]];
  const len = Math.hypot(n[0], n[1], n[2]);
  return len < 1e-9 ? null : [n[0] / len, n[1] / len, n[2] / len];
}

/** 双线性采样（贴图是 1024 级的，128px 图标用最近邻会有明显锯齿）。 */
function sample(tex, u, v) {
  let x = u * tex.width - 0.5;
  let y = v * tex.height - 0.5;
  const x0 = Math.floor(x);
  const y0 = Math.floor(y);
  const fx = x - x0;
  const fy = y - y0;
  const at = (ix, iy) => {
    const cx = Math.min(tex.width - 1, Math.max(0, ix));
    const cy = Math.min(tex.height - 1, Math.max(0, iy));
    const o = (cy * tex.width + cx) * 4;
    return [tex.data[o], tex.data[o + 1], tex.data[o + 2], tex.data[o + 3]];
  };
  const c00 = at(x0, y0);
  const c10 = at(x0 + 1, y0);
  const c01 = at(x0, y0 + 1);
  const c11 = at(x0 + 1, y0 + 1);
  const out = [0, 0, 0, 0];
  for (let i = 0; i < 4; i++) {
    const top = c00[i] * (1 - fx) + c10[i] * fx;
    const bottom = c01[i] * (1 - fx) + c11[i] * fx;
    out[i] = top * (1 - fy) + bottom * fy;
  }
  return out;
}

// ------------------------------------------------------------------ 光栅化

function renderIcon(quads, tex, size) {
  const buf = Buffer.alloc(size * size * 4); // 透明底
  const depth = new Float32Array(size * size).fill(-Infinity);

  // 自动取景：先用 scale=1 投影求包围盒，再贴合到画布 88%
  let minX = Infinity; let maxX = -Infinity; let minY = Infinity; let maxY = -Infinity;
  const probe = quads.map((q) => q.pos.map((p) => isometric(p, q.cx, q.cy, q.cz)));
  for (const sp of probe) {
    for (const s of sp) {
      minX = Math.min(minX, s.x); maxX = Math.max(maxX, s.x);
      minY = Math.min(minY, s.y); maxY = Math.max(maxY, s.y);
    }
  }
  const scale = (size * 0.88) / Math.max(maxX - minX, maxY - minY, 1e-6);
  const midX = (minX + maxX) / 2;
  const midY = (minY + maxY) / 2;

  quads.forEach((q, qi) => {
    const sp = probe[qi].map((s) => ({
      x: size / 2 + (s.x - midX) * scale,
      y: size / 2 + (s.y - midY) * scale,
      z: s.z,
    }));
    // 背面剔除（与 getEntityCutout 一致）：投影里只有一个镜像（y 翻转），
    // 所以世界里的朝外绕序在屏幕上是负面积。
    const e1 = [sp[1].x - sp[0].x, sp[1].y - sp[0].y];
    const e2 = [sp[2].x - sp[0].x, sp[2].y - sp[0].y];
    if (e1[0] * e2[1] - e1[1] * e2[0] >= 0) return;

    const p0 = sp[0]; const p1 = sp[1]; const p2 = sp[2];
    const bx0 = Math.max(0, Math.floor(Math.min(p0.x, p1.x, p2.x)));
    const bx1 = Math.min(size - 1, Math.ceil(Math.max(p0.x, p1.x, p2.x)));
    const by0 = Math.max(0, Math.floor(Math.min(p0.y, p1.y, p2.y)));
    const by1 = Math.min(size - 1, Math.ceil(Math.max(p0.y, p1.y, p2.y)));
    const denom = (p1.y - p2.y) * (p0.x - p2.x) + (p2.x - p1.x) * (p0.y - p2.y);
    if (Math.abs(denom) < 1e-9) return;
    for (let y = by0; y <= by1; y++) {
      for (let x = bx0; x <= bx1; x++) {
        const px = x + 0.5;
        const py = y + 0.5;
        const w0 = ((p1.y - p2.y) * (px - p2.x) + (p2.x - p1.x) * (py - p2.y)) / denom;
        const w1 = ((p2.y - p0.y) * (px - p2.x) + (p0.x - p2.x) * (py - p2.y)) / denom;
        const w2 = 1 - w0 - w1;
        if (w0 < -1e-6 || w1 < -1e-6 || w2 < -1e-6) continue;
        const z = w0 * p0.z + w1 * p1.z + w2 * p2.z;
        const idx = y * size + x;
        if (z <= depth[idx]) continue;
        const u = w0 * q.uv[0][0] + w1 * q.uv[1][0] + w2 * q.uv[2][0];
        const v = w0 * q.uv[0][1] + w1 * q.uv[1][1] + w2 * q.uv[2][1];
        const c = sample(tex, u, v);
        if (c[3] < 128) continue; // cutout
        depth[idx] = z;
        const s = q.shade;
        buf[idx * 4] = Math.min(255, c[0] * s);
        buf[idx * 4 + 1] = Math.min(255, c[1] * s);
        buf[idx * 4 + 2] = Math.min(255, c[2] * s);
        buf[idx * 4 + 3] = 255;
      }
    }
  });
  return buf;
}

/** SS× 超采样 → 盒式降采样（alpha 加权，避免边缘出现黑边）。 */
function downsample(buf, big, small) {
  const out = Buffer.alloc(small * small * 4);
  const n = big / small;
  for (let y = 0; y < small; y++) {
    for (let x = 0; x < small; x++) {
      let r = 0; let g = 0; let b = 0; let a = 0; let count = 0;
      for (let sy = 0; sy < n; sy++) {
        for (let sx = 0; sx < n; sx++) {
          const o = ((y * n + sy) * big + (x * n + sx)) * 4;
          const alpha = buf[o + 3] / 255;
          r += buf[o] * alpha;
          g += buf[o + 1] * alpha;
          b += buf[o + 2] * alpha;
          a += alpha;
          count++;
        }
      }
      const d = (y * small + x) * 4;
      if (a > 0) {
        out[d] = Math.round(r / a);
        out[d + 1] = Math.round(g / a);
        out[d + 2] = Math.round(b / a);
        out[d + 3] = Math.round((a / count) * 255);
      } else {
        out[d] = 255; out[d + 1] = 255; out[d + 2] = 255; out[d + 3] = BG_ALPHA;
      }
    }
  }
  return out;
}

// ------------------------------------------------------------------ main

function build(key, entry, report) {
  const objFile = path.join(SRC, entry.model.replace(/^.*\//, ''));
  const pngFile = path.join(SRC, entry.textureId.replace(/^.*\//, ''));
  const tex = decodePng(pngFile);
  const { verts, uvs, tris } = parseObj(fs.readFileSync(objFile, 'utf8'), entry.flipV !== false);
  const placed = place(verts, entry);

  // 包围盒中心作为取景中心
  const min = [Infinity, Infinity, Infinity];
  const max = [-Infinity, -Infinity, -Infinity];
  for (const p of placed) {
    for (let i = 0; i < 3; i++) {
      min[i] = Math.min(min[i], p[i]);
      max[i] = Math.max(max[i], p[i]);
    }
  }
  const cx = (min[0] + max[0]) / 2;
  const cy = (min[1] + max[1]) / 2;
  const cz = (min[2] + max[2]) / 2;

  const quads = [];
  for (const tri of tris) {
    const pos = tri.map((c) => placed[c.v]);
    const uv = tri.map((c) => (c.t >= 0 ? uvs[c.t] : [0, 0]));
    const n = normalOf(pos[0], pos[1], pos[2]);
    if (!n) continue;
    quads.push({ pos, uv, cx, cy, cz, shade: shadeOf(n) });
  }
  if (!quads.length) throw new Error(`${key}: 没有可画的面`);

  const big = renderIcon(quads, tex, SIZE * SS);
  let drawn = 0;
  for (let i = 0; i < big.length; i += 4) if (big[i + 3] > 8) drawn++;
  const coverage = drawn / ((SIZE * SS) * (SIZE * SS));
  if (coverage < 0.01) throw new Error(`${key}: 渲染结果是空的（coverage=${coverage.toFixed(4)}）`);

  const small = downsample(big, SIZE * SS, SIZE);
  writePng(path.join(OUT, `eyecandy_${key.toLowerCase()}.png`), SIZE, SIZE, small);
  report.push({ key, tris: quads.length, coverage, tex: `${tex.width}x${tex.height}` });
}

if (require.main === module) {
  const first = JSON.parse(fs.readFileSync(path.join(SRC, 'first.json'), 'utf8'));
  const only = process.argv.slice(2).map((s) => s.toLowerCase());
  const report = [];
  for (const [key, entry] of Object.entries(first)) {
    if (only.length && !only.includes(key.toLowerCase())) continue;
    build(key, entry, report);
    const last = report[report.length - 1];
    console.log(`${key.padEnd(22)} tris=${String(last.tris).padStart(5)} cover=${(last.coverage * 100).toFixed(1)}% tex=${last.tex}`);
  }
  console.log(`\nwrote ${report.length} icons to ${path.relative(process.cwd(), OUT)}`);
}

module.exports = { parseObj, place, renderIcon, downsample, isometric };
