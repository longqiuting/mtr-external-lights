// 采样 PNG 在「0..16 UV 空间」某矩形里的平均颜色（liquid_scanner 用）
const fs = require('fs'), zlib = require('zlib');
function decode(file) {
  const buf = fs.readFileSync(file); let pos = 8, w = 0, h = 0, ct = 0; const idat = [];
  while (pos < buf.length) {
    const len = buf.readUInt32BE(pos), type = buf.toString('ascii', pos + 4, pos + 8), data = buf.subarray(pos + 8, pos + 8 + len);
    if (type === 'IHDR') { w = data.readUInt32BE(0); h = data.readUInt32BE(4); ct = data[9]; }
    else if (type === 'IDAT') idat.push(data); else if (type === 'IEND') break;
    pos += 12 + len;
  }
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const ch = { 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 }[ct];
  const stride = w * ch, out = Buffer.alloc(w * h * ch); let prev = Buffer.alloc(stride);
  for (let y = 0; y < h; y++) {
    const f = raw[y * (stride + 1)];
    const cur = Buffer.from(raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1)));
    for (let i = 0; i < stride; i++) {
      const a = i >= ch ? cur[i - ch] : 0, b = prev[i], c = i >= ch ? prev[i - ch] : 0; let v = cur[i];
      if (f === 1) v = (v + a) & 255; else if (f === 2) v = (v + b) & 255; else if (f === 3) v = (v + ((a + b) >> 1)) & 255;
      else if (f === 4) { const p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c); v = (v + (pa <= pb && pa <= pc ? a : pb <= pc ? b : c)) & 255; }
      cur[i] = v;
    }
    cur.copy(out, y * stride); prev = cur;
  }
  return { w, h, ch, px: out };
}
const img = decode(process.argv[2]);
const { w, h, ch, px } = img;
console.log('texture', w + 'x' + h, 'channels', ch);
function sampleQuad(u1, v1, u2, v2) {
  const x1 = Math.min(u1, u2) / 16 * w, x2 = Math.max(u1, u2) / 16 * w;
  const y1 = Math.min(v1, v2) / 16 * h, y2 = Math.max(v1, v2) / 16 * h;
  let n = 0, r = 0, g = 0, b = 0, a = 0;
  for (let y = Math.floor(y1); y <= Math.ceil(y2) - 1; y++) {
    for (let x = Math.floor(x1); x <= Math.ceil(x2) - 1; x++) {
      if (x < 0 || y < 0 || x >= w || y >= h) continue;
      const i = (y * w + x) * ch;
      r += px[i]; g += px[i + 1]; b += px[i + 2]; a += ch === 4 ? px[i + 3] : 255; n++;
    }
  }
  if (!n) return 'empty';
  const hx = (v) => Math.round(v).toString(16).padStart(2, '0');
  return `#${hx(r / n)}${hx(g / n)}${hx(b / n)} a=${(a / n / 255).toFixed(2)}`;
}
const arg = process.argv.slice(3);
for (let i = 0; i < arg.length; i += 5) {
  console.log(arg[i].padEnd(26), sampleQuad(+arg[i + 1], +arg[i + 2], +arg[i + 3], +arg[i + 4]));
}
