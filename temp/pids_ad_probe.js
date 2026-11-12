// 量广告区可用的像素范围：BRT 卡片边框 / 地铁右侧空白区
const fs = require('fs'); const zlib = require('zlib');
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
const lum = (x, y) => { const c = rgb(x, y); return 0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2]; };
console.log('size', w, 'x', h, 'ch', channels);
// 扫描一条水平线，打印颜色变化点
function scanRow(y) {
  let out = []; let last = null;
  for (let x = 0; x < w; x++) { const c = rgb(x, y).join(','); if (c !== last) { out.push(`${x}:${c}`); last = c; } }
  console.log(`row ${y}:`, out.length > 40 ? out.slice(0, 40).join(' ') + ' ...' : out.join(' '));
}
function scanCol(x) {
  let out = []; let last = null;
  for (let y = 0; y < h; y++) { const c = rgb(x, y).join(','); if (c !== last) { out.push(`${y}:${c}`); last = c; } }
  console.log(`col ${x}:`, out.length > 40 ? out.slice(0, 40).join(' ') + ' ...' : out.join(' '));
}
scanRow(Math.round(h * 0.4));
scanCol(Math.round(w * 0.7));
