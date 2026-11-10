// Pixel statistics for candidate PSD door textures.
// Prints size, opaque ratio (a >= 128), and the colour spread of the box-UV "front face"
// region (u = d..d+w of 2(d+w), v = d..d+h of d+h) which is what MTR samples for the door body.
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

function decode(file) {
  const buf = fs.readFileSync(file);
  let pos = 8, w = 0, h = 0, bitDepth = 0, colorType = 0;
  const idat = [];
  while (pos < buf.length) {
    const len = buf.readUInt32BE(pos); pos += 4;
    const type = buf.toString('ascii', pos, pos + 4); pos += 4;
    const data = buf.subarray(pos, pos + len); pos += len + 4;
    if (type === 'IHDR') { w = data.readUInt32BE(0); h = data.readUInt32BE(4); bitDepth = data[8]; colorType = data[9]; }
    else if (type === 'IDAT') idat.push(data);
    else if (type === 'IEND') break;
  }
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const channels = { 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 }[colorType];
  const bpp = channels * (bitDepth / 8);
  const stride = w * bpp;
  const out = Buffer.alloc(h * stride);
  let p = 0;
  for (let y = 0; y < h; y++) {
    const filter = raw[p++];
    const line = raw.subarray(p, p + stride); p += stride;
    for (let x = 0; x < stride; x++) {
      const a = x >= bpp ? out[y * stride + x - bpp] : 0;
      const b = y > 0 ? out[(y - 1) * stride + x] : 0;
      const c = (x >= bpp && y > 0) ? out[(y - 1) * stride + x - bpp] : 0;
      let v = line[x];
      if (filter === 1) v += a;
      else if (filter === 2) v += b;
      else if (filter === 3) v += (a + b) >> 1;
      else if (filter === 4) {
        const pp = a + b - c, pa = Math.abs(pp - a), pb = Math.abs(pp - b), pc = Math.abs(pp - c);
        v += (pa <= pb && pa <= pc) ? a : (pb <= pc ? b : c);
      }
      out[y * stride + x] = v & 0xff;
    }
  }
  return { w, h, bitDepth, colorType, channels, bpp, stride, out };
}

function px(img, x, y) {
  const o = y * img.stride + x * img.bpp;
  if (img.channels === 4) return [img.out[o], img.out[o + 1], img.out[o + 2], img.out[o + 3]];
  if (img.channels === 3) return [img.out[o], img.out[o + 1], img.out[o + 2], 255];
  if (img.channels === 2) return [img.out[o], img.out[o], img.out[o], img.out[o + 1]];
  return [img.out[o], img.out[o], img.out[o], 255];
}

// box-UV front-face region for a w x h x d box drawn into a W x H atlas (in atlas units)
function faceStats(img, atlasW, atlasH, bx, by, bw, bh, bd) {
  const ax = img.w / atlasW, ay = img.h / atlasH; // pixels per atlas unit
  const x0 = Math.round((bx + bd) * ax), x1 = Math.round((bx + bd + bw) * ax);
  const y0 = Math.round((by + bd) * ay), y1 = Math.round((by + bd + bh) * ay);
  let n = 0, opaque = 0, sumR = 0, sumG = 0, sumB = 0;
  const buckets = new Map();
  for (let y = y0; y < Math.min(y1, img.h); y++) {
    for (let x = x0; x < Math.min(x1, img.w); x++) {
      const [r, g, b, a] = px(img, x, y);
      n++;
      if (a >= 128) { opaque++; sumR += r; sumG += g; sumB += b; }
      const k = `${r >> 5},${g >> 5},${b >> 5},${a >= 128 ? 'O' : 'T'}`;
      buckets.set(k, (buckets.get(k) || 0) + 1);
    }
  }
  const top = [...buckets.entries()].sort((p, q) => q[1] - p[1]).slice(0, 4)
    .map(([k, v]) => `${k} ${(100 * v / n).toFixed(0)}%`).join(' | ');
  return { x0, y0, x1, y1, n, opaque, ratio: opaque / n,
    mean: opaque ? [sumR / opaque | 0, sumG / opaque | 0, sumB / opaque | 0] : null, top };
}

function overall(img) {
  let n = 0, opaque = 0, zero = 0, semi = 0;
  for (let y = 0; y < img.h; y++) for (let x = 0; x < img.w; x++) {
    const a = px(img, x, y)[3];
    n++; if (a >= 128) opaque++; else if (a === 0) zero++; else semi++;
  }
  return { n, opaque, zero, semi, ratio: opaque / n };
}

const files = process.argv.slice(2);
for (const f of files) {
  if (!fs.existsSync(f)) { console.log(`MISSING ${f}`); continue; }
  let img;
  try { img = decode(f); } catch (e) { console.log(`${path.basename(f)}: decode error ${e.message}`); continue; }
  const o = overall(img);
  // atlas unit grid: 36 x 18 units for door sheets, 20 x 18 for end sheets
  const isEnd = /_end_/.test(path.basename(f));
  const aw = isEnd ? 20 : 36, ah = 18;
  const bw = isEnd ? 8 : 16, bd = 2, bh = 16;
  const fs1 = faceStats(img, aw, ah, 0, 0, bw, bh, bd);
  console.log(`${path.basename(f).padEnd(34)} ${String(img.w).padStart(4)}x${String(img.h).padStart(3)} ct=${img.colorType} ` +
    `opaque=${(100 * o.ratio).toFixed(1)}% alpha0=${(100 * o.zero / o.n).toFixed(1)}% ` +
    `| front(u${fs1.x0}-${fs1.x1},v${fs1.y0}-${fs1.y1}) opaque=${(100 * fs1.ratio).toFixed(1)}% ` +
    `mean=${fs1.mean ? fs1.mean.join(',') : '-'} | ${fs1.top}`);
}
