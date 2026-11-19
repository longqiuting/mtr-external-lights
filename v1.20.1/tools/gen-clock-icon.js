#!/usr/bin/env node
/*
 * 生成时钟方块的物品图标（16×16 PNG），不依赖任何第三方包：
 * 自己画一个表盘（奶白面 + 深色外圈 + 三根指针 + 中心轴），再自己写 PNG 编码
 * （zlib.deflateSync 压 IDAT，CRC32 手算）。
 *
 * 用法（仓库根或 v1.20.1 目录里都行）：
 *   node v1.20.1/tools/gen-clock-icon.js
 *
 * 输出：v1.20.1/src/main/resources/assets/mtrlte/textures/item/clock.png
 */

'use strict';

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const SIZE = 16;
/** 每个像素 3×3 超采样，边缘不至于全是锯齿。 */
const SS = 3;

const COLORS = {
  rim: [0x22, 0x26, 0x2e, 0xff],
  face: [0xf2, 0xea, 0xd8, 0xff],
  tick: [0x6b, 0x63, 0x57, 0xff],
  hour: [0x1b, 0x1b, 0x1b, 0xff],
  minute: [0x1b, 0x1b, 0x1b, 0xff],
  second: [0xc0, 0x39, 0x2b, 0xff],
  hub: [0x1b, 0x1b, 0x1b, 0xff],
};

const CENTER = SIZE / 2; // 8.0 —— 16px 的格心
const R_OUT = 7.3;       // 外圈半径
const R_FACE = 6.3;      // 钟面半径
const R_TICK_OUT = 5.9;
const R_TICK_IN = 5.0;

// 10:10 —— 钟表广告里的经典角度
const HOUR_FRACTION = (10 + 10 / 60) / 12;  // 0.8472
const MINUTE_FRACTION = 10 / 60;            // 0.1667
const SECOND_FRACTION = 33 / 60;            // 0.55（红秒针指向 6 点偏右）

function dist(ax, ay, bx, by) {
  return Math.hypot(ax - bx, ay - by);
}

/** 到线段的距离（用来画指针）。 */
function segDist(px, py, x0, y0, x1, y1) {
  const dx = x1 - x0;
  const dy = y1 - y0;
  const len2 = dx * dx + dy * dy;
  let t = len2 === 0 ? 0 : ((px - x0) * dx + (py - y0) * dy) / len2;
  t = Math.max(0, Math.min(1, t));
  return dist(px, py, x0 + dx * t, y0 + dy * t);
}

/** 指针端点：从 12 点方向顺时针，x = sin，y = -cos（屏幕坐标 y 向下）。 */
function handEnd(fraction, length) {
  const th = fraction * Math.PI * 2;
  return [CENTER + Math.sin(th) * length, CENTER - Math.cos(th) * length];
}

const HOUR_END = handEnd(HOUR_FRACTION, 3.1);
const MINUTE_END = handEnd(MINUTE_FRACTION, 4.9);
const SECOND_END = handEnd(SECOND_FRACTION, 5.6);

/** 某个采样点的颜色（返回 [r,g,b,a] 或 null）。从后往前画：先面，再刻度，再指针。 */
function sample(x, y) {
  const d = dist(x, y, CENTER, CENTER);

  let out = null;
  if (d <= R_OUT) {
    out = d <= R_FACE ? COLORS.face : COLORS.rim;
  } else {
    return null;
  }

  // 12 个刻度：只有 12/3/6/9 四个画出来（16px 塞不下 12 个）
  if (d >= R_TICK_IN && d <= R_TICK_OUT) {
    for (const k of [0, 3, 6, 9]) {
      const th = (k / 12) * Math.PI * 2;
      const ux = Math.sin(th);
      const uy = -Math.cos(th);
      const along = (x - CENTER) * ux + (y - CENTER) * uy; // 沿半径的投影
      const across = Math.abs((x - CENTER) * -uy + (y - CENTER) * ux);
      if (along > 0 && across < 0.45) {
        out = COLORS.tick;
      }
    }
  }

  // 指针（宽度按 16px 图标放大到能看清）
  if (segDist(x, y, CENTER, CENTER, SECOND_END[0], SECOND_END[1]) < 0.35) {
    out = COLORS.second;
  }
  if (segDist(x, y, CENTER, CENTER, MINUTE_END[0], MINUTE_END[1]) < 0.6) {
    out = COLORS.minute;
  }
  if (d < 4.2 && segDist(x, y, CENTER, CENTER, HOUR_END[0], HOUR_END[1]) < 0.85) {
    out = COLORS.hour;
  }
  // 中心轴
  if (d < 0.95) {
    out = COLORS.hub;
  }
  return out;
}

function renderRgba() {
  const buf = Buffer.alloc(SIZE * SIZE * 4, 0);
  for (let y = 0; y < SIZE; y++) {
    for (let x = 0; x < SIZE; x++) {
      let r = 0;
      let g = 0;
      let b = 0;
      let a = 0;
      for (let sy = 0; sy < SS; sy++) {
        for (let sx = 0; sx < SS; sx++) {
          const px = x + (sx + 0.5) / SS;
          const py = y + (sy + 0.5) / SS;
          const c = sample(px, py);
          if (c) {
            r += c[0];
            g += c[1];
            b += c[2];
            a += c[3];
          }
        }
      }
      const n = SS * SS;
      const o = (y * SIZE + x) * 4;
      if (a > 0) {
        // 预乘回普通 alpha（颜色按覆盖到的采样点平均）
        const covered = a / 255;
        buf[o] = Math.round(r / covered);
        buf[o + 1] = Math.round(g / covered);
        buf[o + 2] = Math.round(b / covered);
        buf[o + 3] = Math.round(a / n);
      }
    }
  }
  return buf;
}

// ---------------------------------------------------------------- PNG 编码

const CRC_TABLE = (() => {
  const table = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) {
      c = (c & 1) !== 0 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    }
    table[n] = c;
  }
  return table;
})();

function crc32(buf) {
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i++) {
    c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  }
  return (c ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const typeBuf = Buffer.from(type, 'ascii');
  const crcBuf = Buffer.alloc(4);
  crcBuf.writeUInt32BE(crc32(Buffer.concat([typeBuf, data])), 0);
  return Buffer.concat([len, typeBuf, data, crcBuf]);
}

function encodePng(rgba, width, height) {
  const signature = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;  // bit depth
  ihdr[9] = 6;  // colour type: RGBA
  ihdr[10] = 0; // deflate
  ihdr[11] = 0; // adaptive filtering
  ihdr[12] = 0; // no interlace

  // 每行前面加一个 filter 字节（0 = None）
  const raw = Buffer.alloc((width * 4 + 1) * height);
  for (let y = 0; y < height; y++) {
    raw[y * (width * 4 + 1)] = 0;
    rgba.copy(raw, y * (width * 4 + 1) + 1, y * width * 4, (y + 1) * width * 4);
  }
  const idat = zlib.deflateSync(raw, { level: 9 });

  return Buffer.concat([
    signature,
    chunk('IHDR', ihdr),
    chunk('IDAT', idat),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

// ---------------------------------------------------------------- main

const out = path.join(__dirname, '..', 'src', 'main', 'resources', 'assets', 'mtrlte',
  'textures', 'item', 'clock.png');
fs.mkdirSync(path.dirname(out), { recursive: true });

const rgba = renderRgba();
const png = encodePng(rgba, SIZE, SIZE);
fs.writeFileSync(out, png);

let opaque = 0;
for (let i = 3; i < rgba.length; i += 4) {
  if (rgba[i] > 0) {
    opaque++;
  }
}
console.log(`clock icon: ${SIZE}x${SIZE}, ${png.length} bytes, ${opaque}/${SIZE * SIZE} non-transparent px`);
console.log(`written: ${path.relative(process.cwd(), out)}`);
