// 读 PNG → 灰度 ASCII 图 + 关键行列的亮度剖面。用法: node pngmap.js <file> [cols]
const fs = require('fs');
const zlib = require('zlib');

function decode(file) {
  const buf = fs.readFileSync(file);
  let pos = 8, w = 0, h = 0, bitDepth = 0, colorType = 0, idat = [];
  while (pos < buf.length) {
    const len = buf.readUInt32BE(pos); const type = buf.toString('ascii', pos + 4, pos + 8);
    const data = buf.subarray(pos + 8, pos + 8 + len);
    if (type === 'IHDR') { w = data.readUInt32BE(0); h = data.readUInt32BE(4); bitDepth = data[8]; colorType = data[9]; }
    else if (type === 'IDAT') idat.push(data);
    else if (type === 'IEND') break;
    pos += 12 + len;
  }
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const channels = { 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 }[colorType];
  if (bitDepth !== 8) throw new Error('bitDepth ' + bitDepth + ' unsupported');
  const stride = w * channels;
  const out = Buffer.alloc(w * h * channels);
  let prev = Buffer.alloc(stride);
  for (let y = 0; y < h; y++) {
    const filter = raw[y * (stride + 1)];
    const line = raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1));
    const cur = Buffer.from(line);
    for (let i = 0; i < stride; i++) {
      const a = i >= channels ? cur[i - channels] : 0;
      const b = prev[i];
      const c = i >= channels ? prev[i - channels] : 0;
      let v = cur[i];
      if (filter === 1) v = (v + a) & 255;
      else if (filter === 2) v = (v + b) & 255;
      else if (filter === 3) v = (v + ((a + b) >> 1)) & 255;
      else if (filter === 4) {
        const p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
        v = (v + (pa <= pb && pa <= pc ? a : pb <= pc ? b : c)) & 255;
      }
      cur[i] = v;
    }
    cur.copy(out, y * stride);
    prev = cur;
  }
  return { w, h, channels, px: out };
}

const file = process.argv[2];
const cols = parseInt(process.argv[3] || '100', 10);
const img = decode(file);
const { w, h, channels, px } = img;
console.log('size', w, 'x', h, 'ch', channels);
function lum(x, y) {
  const i = (y * w + x) * channels;
  if (channels >= 3) return Math.round(0.299 * px[i] + 0.587 * px[i + 1] + 0.114 * px[i + 2]);
  return px[i];
}
const colsN = cols, rowsN = Math.max(8, Math.round(cols * h / w / 2.1));
const cw = w / colsN, ch = h / rowsN;
const ramp = ' .:-=+*#%@';
for (let r = 0; r < rowsN; r++) {
  let line = '';
  for (let c = 0; c < colsN; c++) {
    let sum = 0, n = 0;
    for (let y = Math.floor(r * ch); y < Math.min(h, (r + 1) * ch); y += 2)
      for (let x = Math.floor(c * cw); x < Math.min(w, (c + 1) * cw); x += 2) { sum += lum(x, y); n++; }
    line += ramp[Math.min(9, Math.floor((sum / Math.max(1, n)) / 25.6))];
  }
  console.log(String(Math.round(r * ch)).padStart(4) + '|' + line);
}
// 亮像素（>200）的列/行直方图，用来找框线
const colHits = new Array(w).fill(0), rowHits = new Array(h).fill(0);
for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) if (lum(x, y) > 200) { colHits[x]++; rowHits[y]++; }
function peaks(arr, thr) { const out = []; for (let i = 0; i < arr.length; i++) if (arr[i] > thr) out.push(i + ':' + arr[i]); return out; }
console.log('col peaks(>40%):', peaks(colHits, h * 0.4).join(' '));
console.log('row peaks(>25%):', peaks(rowHits, w * 0.25).join(' '));
