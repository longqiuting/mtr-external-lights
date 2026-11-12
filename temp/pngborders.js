// 找框线：输出每一行/每一列的「亮像素连续段」(>=minLen)
const fs = require('fs'); const zlib = require('zlib');
function decode(file) {
  const buf = fs.readFileSync(file); let pos = 8, w = 0, h = 0, bitDepth = 0, colorType = 0; const idat = [];
  while (pos < buf.length) {
    const len = buf.readUInt32BE(pos); const type = buf.toString('ascii', pos + 4, pos + 8);
    const data = buf.subarray(pos + 8, pos + 8 + len);
    if (type === 'IHDR') { w = data.readUInt32BE(0); h = data.readUInt32BE(4); bitDepth = data[8]; colorType = data[9]; }
    else if (type === 'IDAT') idat.push(data); else if (type === 'IEND') break;
    pos += 12 + len;
  }
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const channels = { 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 }[colorType];
  const stride = w * channels; const out = Buffer.alloc(w * h * channels);
  let prev = Buffer.alloc(stride);
  for (let y = 0; y < h; y++) {
    const filter = raw[y * (stride + 1)]; const cur = Buffer.from(raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1)));
    for (let i = 0; i < stride; i++) {
      const a = i >= channels ? cur[i - channels] : 0, b = prev[i], c = i >= channels ? prev[i - channels] : 0; let v = cur[i];
      if (filter === 1) v = (v + a) & 255; else if (filter === 2) v = (v + b) & 255; else if (filter === 3) v = (v + ((a + b) >> 1)) & 255;
      else if (filter === 4) { const p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c); v = (v + (pa <= pb && pa <= pc ? a : pb <= pc ? b : c)) & 255; }
      cur[i] = v;
    }
    cur.copy(out, y * stride); prev = cur;
  }
  return { w, h, channels, px: out };
}
const file = process.argv[2], thr = parseInt(process.argv[3] || '190', 10), minLen = parseInt(process.argv[4] || '25', 10);
const img = decode(file); const { w, h, channels, px } = img;
const lum = (x, y) => { const i = (y * w + x) * channels; return channels >= 3 ? 0.299 * px[i] + 0.587 * px[i + 1] + 0.114 * px[i + 2] : px[i]; };
console.log('size', w, 'x', h);
const rows = [], cols = [];
for (let y = 0; y < h; y++) {
  let start = -1;
  for (let x = 0; x <= w; x++) {
    const on = x < w && lum(x, y) > thr;
    if (on && start < 0) start = x;
    if (!on && start >= 0) { if (x - start >= minLen) rows.push([y, start, x - 1]); start = -1; }
  }
}
for (let x = 0; x < w; x++) {
  let start = -1;
  for (let y = 0; y <= h; y++) {
    const on = y < h && lum(x, y) > thr;
    if (on && start < 0) start = y;
    if (!on && start >= 0) { if (y - start >= minLen) cols.push([x, start, y - 1]); start = -1; }
  }
}
console.log('--- horizontal segments (y, x0..x1)');
console.log(rows.map(r => `${r[0]}: ${r[1]}..${r[2]} (${r[2] - r[1] + 1})`).join('\n'));
console.log('--- vertical segments (x, y0..y1)');
console.log(cols.map(c => `${c[0]}: ${c[1]}..${c[2]} (${c[2] - c[1] + 1})`).join('\n'));
