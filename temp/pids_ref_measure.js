// 量参考图：粉色广告区 bbox、蓝色区 bbox、标语框
const fs = require('fs'), zlib = require('zlib');
function decode(file) {
  const buf = fs.readFileSync(file); let pos = 8, w = 0, h = 0, colorType = 0; const idat = [];
  while (pos < buf.length) {
    const len = buf.readUInt32BE(pos); const type = buf.toString('ascii', pos + 4, pos + 8);
    const data = buf.subarray(pos + 8, pos + 8 + len);
    if (type === 'IHDR') { w = data.readUInt32BE(0); h = data.readUInt32BE(4); colorType = data[9]; }
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
const img = decode(process.argv[2]); const { w, h, channels, px } = img;
const rgb = (x, y) => { const i = (y * w + x) * channels; return [px[i], px[i + 1], px[i + 2]]; };
function bbox(pred, label) {
  let x0 = 1e9, y0 = 1e9, x1 = -1, y1 = -1, n = 0;
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    if (pred(x, y)) { n++; if (x < x0) x0 = x; if (y < y0) y0 = y; if (x > x1) x1 = x; if (y > y1) y1 = y; }
  }
  console.log(label, `n=${n}`, n ? `bbox x ${x0}..${x1} y ${y0}..${y1}` : '');
}
const isPink = (x, y) => { const c = rgb(x, y); return c[0] > 220 && c[1] > 120 && c[1] < 210 && c[2] > 140 && c[2] < 220 && c[0] - c[1] > 40; };
const blue = (x, y) => { const c = rgb(x, y); return c[2] > 90 && c[2] > c[0] + 30 && c[2] > c[1] + 10 && c[1] < 110; };
bbox(isPink, 'pink');
bbox(blue, 'blue');
// 蓝色区逐行左右沿（每 10 行）
for (let y = 0; y < h; y += 10) {
  let l = -1, r = -1;
  for (let x = 0; x < w; x++) if (blue(x, y)) { if (l < 0) l = x; r = x; }
  console.log('row', y, 'blue', l, r);
}
// 粉色区逐行左右沿
for (let y = 0; y < h; y += 5) {
  let l = -1, r = -1, n = 0;
  for (let x = 0; x < w; x++) if (isPink(x, y)) { n++; if (l < 0) l = x; r = x; }
  if (n) console.log('pinkrow', y, l, r, n);
}
// 粉色区逐列上下沿
for (let x = 0; x < w; x += 20) {
  let t = -1, b = -1, n = 0;
  for (let y = 0; y < h; y++) if (isPink(x, y)) { n++; if (t < 0) t = y; b = y; }
  if (n) console.log('pinkcol', x, t, b, n);
}
// 右上角徽章条：一行一行的颜色
for (const y of [2, 8, 14, 20, 28, 33, 34, 40]) {
  let s = '';
  for (let x = 160; x < 520; x += 8) s += x + ':' + rgb(Math.min(x, w - 1), y).join(',') + ' ';
  console.log('line', y, s);
}
