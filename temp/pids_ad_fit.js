// 在底图上按 PidsXiamenLayout 的新广告矩形画一块半透明红，检查「贴边」效果
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
function encode(img, file) {
  const { w, h, channels, px } = img;
  const stride = w * channels;
  const raw = Buffer.alloc((stride + 1) * h);
  for (let y = 0; y < h; y++) { raw[y * (stride + 1)] = 0; px.copy(raw, y * (stride + 1) + 1, y * stride, (y + 1) * stride); }
  const idat = zlib.deflateSync(raw);
  const chunks = [];
  const chunk = (type, data) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
    const td = Buffer.concat([Buffer.from(type, 'ascii'), data]);
    const crcTable = [];
    for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1; crcTable[n] = c >>> 0; }
    let crc = 0xFFFFFFFF; for (const b of td) crc = crcTable[(crc ^ b) & 255] ^ (crc >>> 8);
    const crcB = Buffer.alloc(4); crcB.writeUInt32BE((crc ^ 0xFFFFFFFF) >>> 0);
    chunks.push(Buffer.concat([len, td, crcB]));
  };
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = channels === 4 ? 6 : 2;
  chunk('IHDR', ihdr); chunk('IDAT', idat); chunk('IEND', Buffer.alloc(0));
  fs.writeFileSync(file, Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), ...chunks]));
}
const [, , src, dst, x0, y0, x1, y1] = process.argv;
const img = decode(src);
const [ax0, ay0, ax1, ay1] = [x0, y0, x1, y1].map(Number);
for (let y = ay0; y < ay1; y++) for (let x = ax0; x < ax1; x++) {
  if (x < 0 || y < 0 || x >= img.w || y >= img.h) continue;
  const i = (y * img.w + x) * img.channels;
  img.px[i] = Math.round(img.px[i] * 0.35 + 255 * 0.65);
  img.px[i + 1] = Math.round(img.px[i + 1] * 0.35);
  img.px[i + 2] = Math.round(img.px[i + 2] * 0.35);
  if (img.channels === 4) img.px[i + 3] = 255;
}
encode(img, dst);
console.log('wrote', dst, ax0, ay0, ax1, ay1);
