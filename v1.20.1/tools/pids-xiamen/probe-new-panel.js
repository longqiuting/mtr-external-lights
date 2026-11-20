'use strict';
// 临时探针：量新底图 pids_xiamen_metro.png 的顶部条区（无烘死卡片版）
const fs = require('fs');
const path = require('path');
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
    if (type === 'IHDR') {
      width = data.readUInt32BE(0); height = data.readUInt32BE(4);
      depth = data[8]; colorType = data[9]; interlace = data[12];
    } else if (type === 'IDAT') idat.push(data);
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
      const a = i >= bpp ? row[i - bpp] : 0;
      const b = prev[i];
      const c = i >= bpp ? prev[i - bpp] : 0;
      switch (filter) {
        case 0: break;
        case 1: row[i] = (row[i] + a) & 0xff; break;
        case 2: row[i] = (row[i] + b) & 0xff; break;
        case 3: row[i] = (row[i] + ((a + b) >> 1)) & 0xff; break;
        case 4: row[i] = (row[i] + paeth(a, b, c)) & 0xff; break;
        default: throw new Error('bad filter ' + filter);
      }
    }
    prev = row;
  }
  return { width, height, bpp, pix, stride,
    rgb(x, y) { const i = y * stride + x * bpp; return [pix[i], pix[i+1], pix[i+2], bpp === 4 ? pix[i+3] : 255]; } };
}

const png = decodePng(process.argv[2]);
const W = png.width, H = png.height;
console.log(`panel ${W}×${H} bpp=${png.bpp}`);
console.log('corner(3,H-3) =', png.rgb(3, H - 3).join(','));
const panelBg = png.rgb(3, H - 3);
const dist = (a, b) => Math.abs(a[0]-b[0]) + Math.abs(a[1]-b[1]) + Math.abs(a[2]-b[2]);

// 顶部 0..40 行的颜色直方图
const hist = new Map();
for (let y = 0; y < 40; y++) for (let x = 0; x < W; x++) {
  const k = png.rgb(x, y).slice(0,3).join(',');
  hist.set(k, (hist.get(k) || 0) + 1);
}
console.log('top-40 rows top colors:', [...hist.entries()].sort((a,b)=>b[1]-a[1]).slice(0,10).map(e=>`${e[0]}×${e[1]}`).join('  |  '));

// 每行：非 panelBg 的 x 范围
console.log('\nrow profile (y: xmin..xmax  of != panelBg, count):');
for (let y = 0; y < 40; y++) {
  let xmin = 1e9, xmax = -1, n = 0;
  for (let x = 0; x < W; x++) if (dist(png.rgb(x,y), panelBg) > 40) { if (x<xmin) xmin=x; if (x>xmax) xmax=x; n++; }
  console.log(`  y=${String(y).padStart(2)} ${xmax<0?'-':xmin+'..'+xmax} n=${n}`);
}

// 顶部条区：找「青色」像素范围 (b>150,g>140,r<100)
function isCyan([r,g,b]) { return b > 150 && g > 140 && r < 100; }
let cx0=1e9, cx1=-1, cy0=1e9, cy1=-1;
for (let y = 0; y < 45; y++) for (let x = 0; x < W; x++) {
  if (isCyan(png.rgb(x,y))) { if(x<cx0)cx0=x; if(x>cx1)cx1=x; if(y<cy0)cy0=y; if(y>cy1)cy1=y; }
}
console.log(`\ncyan bbox x ${cx0}..${cx1} y ${cy0}..${cy1}`);
console.log('sample colors in cyan zone:', [0,5,10,20,40,60,100,150].map(dy => png.rgb(cx0+10, Math.min(cy1, cyber(cy0,dy))).slice(0,3).join(',')).join(' | '));
function cyber(base, dy) { return base + dy; }
// 竖直线采样 x=300
console.log('\ncolumn x=300, y 0..40 colors:');
for (let y = 0; y < 40; y += 1) {
  const c = png.rgb(300, y);
  if (y < 36) console.log(`  y=${String(y).padStart(2)} ${c.slice(0,3).join(',')}`);
}
// 左端 x=179 附近
console.log('\nrow y=16 colors x 480..511:');
for (let x = 480; x <= 511; x++) console.log(`  x=${x} ${png.rgb(x,16).slice(0,3).join(',')}`);
console.log('\ncol x=505 y 0..32:');
for (let y = 0; y <= 32; y+=4) console.log(`  y=${y} ${png.rgb(505,y).slice(0,3).join(',')}`);

console.log('\nrow y=16 colors x 170..200:');
for (let x = 170; x <= 200; x++) console.log(`  x=${x} ${png.rgb(x,16).slice(0,3).join(',')}`);
