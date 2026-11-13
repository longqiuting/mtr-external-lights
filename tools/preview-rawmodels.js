#!/usr/bin/env node
// 离线预览：把 rawmodels/*.json 按「和 Java 端同一套公式」画成 PNG，用来看几何 / UV / 绕序对不对。
//
// 为什么要它：这次改的是运行时 BER，开游戏才能看见；而历史问题（角度吸附、三轴旋转、
// uv 除以 64、贴图降采样）都是「不开游戏看不出来」的类型。这里用纯 JS 复刻同一条数学路径，
// 自己解码 PNG 贴图、自己做 z-buffer 光栅化，输出图片给人眼核对。
//
// 用法：node tools/preview-rawmodels.js
// 输出：temp/preview/*.png（front / iso 两个角度）
'use strict';

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const ROOT = path.resolve(__dirname, '..', 'v1.20.1', 'src', 'main', 'resources', 'assets', 'mtrlte');
const OUT = path.resolve(__dirname, '..', 'temp', 'rawmodel-preview');
fs.mkdirSync(OUT, { recursive: true });

// ----------------------------------------------------------------- PNG 解码

const CRC_TABLE = (() => {
  const t = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) {
      c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    }
    t[n] = c;
  }
  return t;
})();

function crc32(buf) {
  let c = 0xffffffff;
  for (const b of buf) {
    c = CRC_TABLE[(c ^ b) & 0xff] ^ (c >>> 8);
  }
  return (c ^ 0xffffffff) >>> 0;
}

function decodePng(file) {
  const buf = fs.readFileSync(file);
  let pos = 8;
  let width = 0;
  let height = 0;
  let bitDepth = 0;
  let colorType = 0;
  const idat = [];
  while (pos < buf.length) {
    const len = buf.readUInt32BE(pos);
    const type = buf.toString('ascii', pos + 4, pos + 8);
    const data = buf.subarray(pos + 8, pos + 8 + len);
    if (type === 'IHDR') {
      width = data.readUInt32BE(0);
      height = data.readUInt32BE(4);
      bitDepth = data[8];
      colorType = data[9];
    } else if (type === 'IDAT') {
      idat.push(data);
    } else if (type === 'IEND') {
      break;
    }
    pos += 12 + len;
  }
  if (bitDepth !== 8 || (colorType !== 6 && colorType !== 2)) {
    throw new Error(`${file}: 只支持 8bit RGBA/RGB（bitDepth=${bitDepth} colorType=${colorType}）`);
  }
  const channels = colorType === 6 ? 4 : 3;
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const stride = width * channels;
  const out = Buffer.alloc(width * height * 4);
  let prev = Buffer.alloc(stride);
  for (let y = 0; y < height; y++) {
    const filter = raw[y * (stride + 1)];
    const line = raw.subarray(y * (stride + 1) + 1, y * (stride + 1) + 1 + stride);
    const cur = Buffer.alloc(stride);
    for (let x = 0; x < stride; x++) {
      const a = x >= channels ? cur[x - channels] : 0;
      const b = prev[x];
      const c = x >= channels ? prev[x - channels] : 0;
      let v = line[x];
      if (filter === 1) v += a;
      else if (filter === 2) v += b;
      else if (filter === 3) v += (a + b) >> 1;
      else if (filter === 4) {
        const p = a + b - c;
        const pa = Math.abs(p - a);
        const pb = Math.abs(p - b);
        const pc = Math.abs(p - c);
        v += pa <= pb && pa <= pc ? a : (pb <= pc ? b : c);
      }
      cur[x] = v & 0xff;
    }
    for (let x = 0; x < width; x++) {
      const s = x * channels;
      const d = (y * width + x) * 4;
      out[d] = cur[s];
      out[d + 1] = cur[s + 1];
      out[d + 2] = cur[s + 2];
      out[d + 3] = channels === 4 ? cur[s + 3] : 255;
    }
    prev = cur;
  }
  return { width, height, data: out };
}

function writePng(file, width, height, rgba) {
  const stride = width * 4;
  const raw = Buffer.alloc((stride + 1) * height);
  for (let y = 0; y < height; y++) {
    raw[y * (stride + 1)] = 0;
    rgba.copy(raw, y * (stride + 1) + 1, y * stride, y * stride + stride);
  }
  const idat = zlib.deflateSync(raw, { level: 9 });
  const chunk = (type, data) => {
    const out = Buffer.alloc(12 + data.length);
    out.writeUInt32BE(data.length, 0);
    out.write(type, 4, 'ascii');
    data.copy(out, 8);
    out.writeUInt32BE(crc32(Buffer.concat([Buffer.from(type, 'ascii'), data])), 8 + data.length);
    return out;
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;
  ihdr[9] = 6;
  fs.writeFileSync(file, Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', idat),
    chunk('IEND', Buffer.alloc(0)),
  ]));
}

// ----------------------------------------------------------------- 与 Java 同构的解析

const UV_TO_UNIT = 1 / 16;
const FACE = {
  north: { right: [-1, 0, 0], down: [0, -1, 0], normal: [0, 0, -1] },
  south: { right: [1, 0, 0], down: [0, -1, 0], normal: [0, 0, 1] },
  east: { right: [0, 0, -1], down: [0, -1, 0], normal: [1, 0, 0] },
  west: { right: [0, 0, 1], down: [0, -1, 0], normal: [-1, 0, 0] },
  up: { right: [1, 0, 0], down: [0, 0, 1], normal: [0, 1, 0] },
  down: { right: [1, 0, 0], down: [0, 0, -1], normal: [0, -1, 0] },
};
const SHADE = { north: 0.8, south: 0.8, east: 0.6, west: 0.6, up: 1.0, down: 0.5 };

function rotate(p, origin, a) {
  let [x, y, z] = [p[0] - origin[0], p[1] - origin[1], p[2] - origin[2]];
  if (a[0]) {
    const r = (Math.PI * a[0]) / 180;
    const [c, s] = [Math.cos(r), Math.sin(r)];
    [y, z] = [y * c - z * s, y * s + z * c];
  }
  if (a[1]) {
    const r = (Math.PI * a[1]) / 180;
    const [c, s] = [Math.cos(r), Math.sin(r)];
    [x, z] = [x * c + z * s, -x * s + z * c];
  }
  if (a[2]) {
    const r = (Math.PI * a[2]) / 180;
    const [c, s] = [Math.cos(r), Math.sin(r)];
    [x, y] = [x * c - y * s, x * s + y * c];
  }
  return [x + origin[0], y + origin[1], z + origin[2]];
}

function anglesOf(rotation) {
  if (!rotation) {
    return [0, 0, 0];
  }
  if (rotation.axis) {
    const a = [0, 0, 0];
    a[{ x: 0, y: 1, z: 2 }[rotation.axis]] = rotation.angle || 0;
    return a;
  }
  return [rotation.x || 0, rotation.y || 0, rotation.z || 0];
}

function mesh(file) {
  const model = JSON.parse(fs.readFileSync(file, 'utf8'));
  const texName = Object.entries(model.textures || {}).find(([k]) => k !== 'particle')[1];
  const tex = decodePng(path.join(ROOT, 'textures', 'block', texName.replace(/^.*:/, '') + '.png'));
  const quads = [];
  for (const e of model.elements) {
    const [x1, y1, z1] = e.from.map((v, i) => Math.min(v, e.to[i]));
    const [x2, y2, z2] = e.from.map((v, i) => Math.max(v, e.to[i]));
    const origin = e.rotation && e.rotation.origin ? e.rotation.origin : [0, 0, 0];
    const angles = anglesOf(e.rotation);
    const rotated = angles.some((a) => a !== 0);
    for (const [face, def] of Object.entries(FACE)) {
      const fj = e.faces[face];
      if (!fj) continue;
      const center = {
        north: [(x1 + x2) / 2, (y1 + y2) / 2, z1], south: [(x1 + x2) / 2, (y1 + y2) / 2, z2],
        east: [x2, (y1 + y2) / 2, (z1 + z2) / 2], west: [x1, (y1 + y2) / 2, (z1 + z2) / 2],
        up: [(x1 + x2) / 2, y2, (z1 + z2) / 2], down: [(x1 + x2) / 2, y1, (z1 + z2) / 2],
      }[face];
      const w = { north: x2 - x1, south: x2 - x1, east: z2 - z1, west: z2 - z1, up: x2 - x1, down: x2 - x1 }[face];
      const h = { north: y2 - y1, south: y2 - y1, east: y2 - y1, west: y2 - y1, up: z2 - z1, down: z2 - z1 }[face];
      const uv = fj.uv;
      const corners = [[-1, -1], [-1, 1], [1, 1], [1, -1]];
      const us = [uv[0], uv[0], uv[2], uv[2]];
      const vs = [uv[1], uv[3], uv[3], uv[1]];
      const pos = corners.map(([sx, sy]) => {
        const p = [
          center[0] + (sx * w * def.right[0]) / 2 + (sy * h * def.down[0]) / 2,
          center[1] + (sx * w * def.right[1]) / 2 + (sy * h * def.down[1]) / 2,
          center[2] + (sx * w * def.right[2]) / 2 + (sy * h * def.down[2]) / 2,
        ];
        return rotated ? rotate(p, origin, angles) : p;
      });
      const uvOut = pos.map((_, i) => [us[i] * UV_TO_UNIT, vs[i] * UV_TO_UNIT]);
      quads.push({
        pos,
        uv: uvOut,
        face,
        shade: e.shade === false ? 1 : SHADE[face],
        normal: rotated ? rotate(def.normal, [0, 0, 0], angles) : def.normal,
      });
    }
  }
  return { tex, quads };
}

// ----------------------------------------------------------------- 光栅化

function render(quads, tex, viewFactory, size, file) {
  const buf = Buffer.alloc(size * size * 4);
  const depth = new Float32Array(size * size).fill(-Infinity); // 越大越近

  // 自动取景：先用 scale=1 投影一遍求包围盒，再贴合到画布 90%
  const probe = viewFactory(1);
  let minX = Infinity; let maxX = -Infinity; let minY = Infinity; let maxY = -Infinity;
  for (const q of quads) {
    for (const p of q.pos) {
      const s = probe(p);
      minX = Math.min(minX, s.x); maxX = Math.max(maxX, s.x);
      minY = Math.min(minY, s.y); maxY = Math.max(maxY, s.y);
    }
  }
  const scale = (size * 0.9) / Math.max(maxX - minX, maxY - minY);
  const cx = (minX + maxX) / 2;
  const cy = (minY + maxY) / 2;
  const view = (p) => {
    const s = probe(p);
    return { x: size / 2 + (s.x - cx) * scale, y: size / 2 + (s.y - cy) * scale, z: s.z };
  };

  // 背景：浅灰
  for (let i = 0; i < size * size; i++) {
    buf[i * 4] = 235;
    buf[i * 4 + 1] = 235;
    buf[i * 4 + 2] = 240;
    buf[i * 4 + 3] = 255;
  }

  for (const q of quads) {
    const sp = q.pos.map(view);
    // 背面剔除（和 getEntityCutout 一致）
    const e1 = [sp[1].x - sp[0].x, sp[1].y - sp[0].y];
    const e2 = [sp[2].x - sp[0].x, sp[2].y - sp[0].y];
    const area = e1[0] * e2[1] - e1[1] * e2[0];
    // 投影里只有 y 翻转（一个镜像）→ 世界里的朝外绕序在屏幕上是**负**面积。
    if (!process.env.NO_CULL && area >= 0) {
      continue;
    }
    for (const tri of [[0, 1, 2], [0, 2, 3]]) {
      const [i0, i1, i2] = tri;
      const p0 = sp[i0];
      const p1 = sp[i1];
      const p2 = sp[i2];
      const minX = Math.max(0, Math.floor(Math.min(p0.x, p1.x, p2.x)));
      const maxX = Math.min(size - 1, Math.ceil(Math.max(p0.x, p1.x, p2.x)));
      const minY = Math.max(0, Math.floor(Math.min(p0.y, p1.y, p2.y)));
      const maxY = Math.min(size - 1, Math.ceil(Math.max(p0.y, p1.y, p2.y)));
      const denom = (p1.y - p2.y) * (p0.x - p2.x) + (p2.x - p1.x) * (p0.y - p2.y);
      if (Math.abs(denom) < 1e-9) continue;
      for (let y = minY; y <= maxY; y++) {
        for (let x = minX; x <= maxX; x++) {
          const px = x + 0.5;
          const py = y + 0.5;
          const w0 = ((p1.y - p2.y) * (px - p2.x) + (p2.x - p1.x) * (py - p2.y)) / denom;
          const w1 = ((p2.y - p0.y) * (px - p2.x) + (p0.x - p2.x) * (py - p2.y)) / denom;
          const w2 = 1 - w0 - w1;
          if (w0 < -1e-6 || w1 < -1e-6 || w2 < -1e-6) continue;
          const z = w0 * p0.z + w1 * p1.z + w2 * p2.z;
          const idx = y * size + x;
          if (z <= depth[idx]) continue;
          depth[idx] = z;
          // uv 插值（三角形内用三个角的 uv 线性插值）
          const u = w0 * q.uv[i0][0] + w1 * q.uv[i1][0] + w2 * q.uv[i2][0];
          const v = w0 * q.uv[i0][1] + w1 * q.uv[i1][1] + w2 * q.uv[i2][1];
          const tx = Math.min(tex.width - 1, Math.max(0, Math.floor(u * tex.width)));
          const ty = Math.min(tex.height - 1, Math.max(0, Math.floor(v * tex.height)));
          const t = (ty * tex.width + tx) * 4;
          if (tex.data[t + 3] < 128) {
            continue; // cutout
          }
          const s = q.shade;
          buf[idx * 4] = Math.min(255, tex.data[t] * s);
          buf[idx * 4 + 1] = Math.min(255, tex.data[t + 1] * s);
          buf[idx * 4 + 2] = Math.min(255, tex.data[t + 2] * s);
          buf[idx * 4 + 3] = 255;
        }
      }
    }
  }
  writePng(file, size, size, buf);
  console.log('wrote ' + path.relative(process.cwd(), file));
}

// 视角：相机绕 Y 方位角 a、仰角 e，永远看向方块中心。
// 约定：屏幕右 = +X（a=0 时）、屏幕上 = +Y、{@code z} = 越靠近相机越大。
function camera(a, e) {
  return () => (p) => {
    const x = p[0] - 8;
    const y = p[1] - 8;
    const z = p[2] - 8;
    // 绕 Y 转 -a（相机绕物体转）
    const x1 = x * Math.cos(a) - z * Math.sin(a);
    const z1 = x * Math.sin(a) + z * Math.cos(a);
    // 仰角：抬高相机 = 让高处的点变远
    const sy = y * Math.cos(e) + z1 * Math.sin(e);
    const depth = z1 * Math.cos(e) - y * Math.sin(e);
    return { x: x1, y: -sy, z: depth };
  };
}

if (require.main === module) {
  const targets = [
    { name: 'gate_metro_closed', files: ['ticket_barrier_closed'], views: [[Math.PI, 0.05, 'front'], [0.6, 0.28, 'iso']] },
    { name: 'gate_metro_open', files: ['ticket_barrier_open'], views: [[0.6, 0.28, 'iso']] },
    { name: 'gate_brt_closed', files: ['brt_barrier_closed'], views: [[Math.PI, 0.05, 'front'], [0.6, 0.28, 'iso']] },
    { name: 'gate_brt_open', files: ['brt_barrier_open'], views: [[0.6, 0.28, 'iso']] },
    { name: 'balance_machine', files: ['balance_machine_bottom', 'balance_machine_top'], views: [[0, 0.05, 'front'], [0.6, 0.28, 'iso']] },
  ];

  for (const target of targets) {
    const quads = [];
    let tex = null;
    target.files.forEach((f, index) => {
      const m = mesh(path.join(ROOT, 'rawmodels', f + '.json'));
      tex = m.tex;
      const dy = index === 0 ? 0 : 16; // 第二份（上段）抬高一格
      for (const q of m.quads) {
        quads.push({ ...q, pos: q.pos.map((p) => [p[0], p[1] + dy, p[2]]) });
      }
    });
    for (const [rotY, rotX, label] of target.views) {
      render(quads, tex, camera(rotY, rotX), 640, path.join(OUT, `${target.name}_${label}.png`));
    }
  }
}

module.exports = { mesh, render, camera, decodePng, ROOT, OUT };
