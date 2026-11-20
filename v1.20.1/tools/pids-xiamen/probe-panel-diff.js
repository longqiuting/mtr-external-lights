'use strict';
// 临时探针：对比两张 512×235 底图，找出差异的包围盒（报告用）
const fs = require('fs');
const zlib = require('zlib');
function paeth(a, b, c) {
  const p = a + b - c;
  const pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
  return pa <= pb && pa <= pc ? a : (pb <= pc ? b : c);
}
function decodePng(file) {
  const buf = fs.readFileSync(file);
  let off = 8, width = 0, height = 0, colorType = 0;
  const idat = [];
  while (off < buf.length) {
    const len = buf.readUInt32BE(off);
    const type = buf.toString('ascii', off + 4, off + 8);
    const data = buf.subarray(off + 8, off + 8 + len);
    if (type === 'IHDR') { width = data.readUInt32BE(0); height = data.readUInt32BE(4); colorType = data[9]; }
    else if (type === 'IDAT') idat.push(data);
    else if (type === 'IEND') break;
    off += 12 + len;
  }
  const bpp = colorType === 6 ? 4 : 3;
  const stride = width * bpp;
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const pix = Buffer.alloc(height * stride);
  let prev = Buffer.alloc(stride);
  for (let y = 0; y < height; y++) {
    const rowOff = y * (stride + 1);
    const filter = raw[rowOff];
    const row = pix.subarray(y * stride, (y + 1) * stride);
    raw.copy(row, 0, rowOff + 1, rowOff + 1 + stride);
    for (let i = 0; i < stride; i++) {
      const a = i >= bpp ? row[i - bpp] : 0, b = prev[i], c = i >= bpp ? prev[i - bpp] : 0;
      switch (filter) {
        case 0: break; case 1: row[i] = (row[i] + a) & 0xff; break;
        case 2: row[i] = (row[i] + b) & 0xff; break;
        case 3: row[i] = (row[i] + ((a + b) >> 1)) & 0xff; break;
        case 4: row[i] = (row[i] + paeth(a, b, c)) & 0xff; break;
        default: throw new Error('bad filter');
      }
    }
    prev = row;
  }
  return { width, height, rgb(x, y) { const i = y * stride + x * bpp; return [pix[i], pix[i+1], pix[i+2]]; } };
}

const a = decodePng(process.argv[2]);
const b = decodePng(process.argv[3]);
console.log(`old ${a.width}×${a.height}   new ${b.width}×${b.height}`);
let x0 = 1e9, x1 = -1, y0 = 1e9, y1 = -1, n = 0;
const rows = new Map();
for (let y = 0; y < Math.min(a.height, b.height); y++) {
  for (let x = 0; x < Math.min(a.width, b.width); x++) {
    const p = a.rgb(x, y), q = b.rgb(x, y);
    if (Math.abs(p[0]-q[0]) + Math.abs(p[1]-q[1]) + Math.abs(p[2]-q[2]) > 12) {
      n++;
      if (x < x0) x0 = x; if (x > x1) x1 = x;
      if (y < y0) y0 = y; if (y > y1) y1 = y;
      rows.set(y, (rows.get(y) || 0) + 1);
    }
  }
}
console.log(`差异像素 ${n}，包围盒 x ${x0}..${x1} y ${y0}..${y1}`);
const ys = [...rows.keys()].sort((p, q) => p - q);
console.log(`有差异的行：${ys[0]}..${ys[ys.length-1]}（共 ${ys.length} 行）`);
// 按行段汇总
let segStart = ys[0], prev = ys[0];
const segs = [];
for (const y of ys.slice(1)) {
  if (y !== prev + 1) { segs.push([segStart, prev]); segStart = y; }
  prev = y;
}
segs.push([segStart, prev]);
console.log('行段：' + segs.map(s => `${s[0]}..${s[1]}`).join('  '));
