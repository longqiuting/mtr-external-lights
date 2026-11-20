'use strict';
// 临时探针：consist_band.png / consist_card.png / consist_flake.png 的列剖面
const fs = require('fs');
const zlib = require('zlib');
function paeth(a, b, c) {
  const p = a + b - c;
  const pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
  return pa <= pb && pa <= pc ? a : (pb <= pc ? b : c);
}
function decodePng(file) {
  const buf = fs.readFileSync(file);
  let off = 8, width = 0, height = 0, depth = 0, colorType = 0, interlace = 0;
  const idat = [];
  while (off < buf.length) {
    const len = buf.readUInt32BE(off);
    const type = buf.toString('ascii', off + 4, off + 8);
    const data = buf.subarray(off + 8, off + 8 + len);
    if (type === 'IHDR') { width = data.readUInt32BE(0); height = data.readUInt32BE(4); depth = data[8]; colorType = data[9]; interlace = data[12]; }
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
  return { width, height, bpp, pix, stride,
    rgb(x, y) { const i = y * stride + x * bpp; return [pix[i], pix[i+1], pix[i+2], bpp === 4 ? pix[i+3] : 255]; } };
}

const band = decodePng(process.argv[2]);
console.log(`band ${band.width}×${band.height} bpp=${band.bpp}`);
const isCyan = ([r,g,b]) => r < 110 && g > 150 && b > 150;
const isNavy = ([r,g,b]) => b > 150 && r < 60 && g < 60;

// 逐列：y=16 与 y=2 / y=31 的颜色
console.log('\ncol  y=2            y=16            y=31');
for (let x = 0; x < band.width; x++) {
  if (x < 40 || x > band.width - 30 || (x % 20 === 0)) {
    const a = band.rgb(x, 2).slice(0,3).join(',');
    const b = band.rgb(x, 16).slice(0,3).join(',');
    const c = band.rgb(x, 31).slice(0,3).join(',');
    console.log(`  x=${String(x).padStart(3)} ${a.padEnd(15)} ${b.padEnd(15)} ${c}`);
  }
}
// 哪些列「整列都是 cyan」
let cyanCols = [], navyCols = [];
for (let x = 0; x < band.width; x++) {
  let allCyan = true, hasNavy = false;
  for (let y = 0; y < band.height; y++) {
    const c = band.rgb(x, y);
    if (!isCyan(c)) allCyan = false;
    if (isNavy(c)) hasNavy = true;
  }
  if (allCyan) cyanCols.push(x);
  if (hasNavy) navyCols.push(x);
}
console.log('\nall-cyan columns:', cyanCols.length, cyanCols.slice(0,50).join(','));
console.log('navy columns:', navyCols.length, 'first', navyCols[0], 'last', navyCols[navyCols.length-1]);
console.log('navy gaps:', (() => { const g=[]; for (let i=1;i<navyCols.length;i++) if (navyCols[i]-navyCols[i-1]>1) g.push(`${navyCols[i-1]}→${navyCols[i]}`); return g.join(' '); })());
// 每列「不是纯背景」的最左/最右
let maxFirst = 0, minLast = band.width - 1;
for (let y = 2; y < band.height - 2; y++) {
  for (let x = 0; x < band.width; x++) if (!isCyan(band.rgb(x, y))) { maxFirst = Math.max(maxFirst, x); break; }
  for (let x = band.width - 1; x >= 0; x--) if (!isCyan(band.rgb(x, y))) { minLast = Math.min(minLast, x); break; }
}
console.log(`maxFirst=${maxFirst} leftW=${Math.min(band.width-2, maxFirst+3)} minLast=${minLast} cyanRightW=${band.width-minLast}`);
// 中段颜色统计
const hist = new Map();
for (let y = 0; y < band.height; y++) for (let x = 60; x < 200; x++) {
  const k = band.rgb(x,y).slice(0,3).join(',');
  hist.set(k, (hist.get(k)||0)+1);
}
console.log('mid-section colors:', [...hist.entries()].sort((a,b)=>b[1]-a[1]).slice(0,8).map(e=>`${e[0]}×${e[1]}`).join(' | '));
// alpha
let minA = 255;
for (let y = 0; y < band.height; y++) for (let x = 0; x < band.width; x++) minA = Math.min(minA, band.rgb(x,y)[3]);
console.log('min alpha =', minA);
