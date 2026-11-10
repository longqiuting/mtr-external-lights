// Minimal PNG decode for the tiny spray textures: dump size, color type, and a pixel/alpha map.
const fs = require('fs');
const zlib = require('zlib');

function decode(file) {
  const buf = fs.readFileSync(file);
  let pos = 8;
  let w = 0, h = 0, bitDepth = 0, colorType = 0;
  const idat = [];
  while (pos < buf.length) {
    const len = buf.readUInt32BE(pos); pos += 4;
    const type = buf.toString('ascii', pos, pos + 4); pos += 4;
    const data = buf.subarray(pos, pos + len); pos += len + 4;
    if (type === 'IHDR') {
      w = data.readUInt32BE(0); h = data.readUInt32BE(4);
      bitDepth = data[8]; colorType = data[9];
    } else if (type === 'IDAT') idat.push(data);
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

const files = process.argv.slice(2);
for (const f of files) {
  const img = decode(f);
  console.log(`\n== ${f}  ${img.w}x${img.h} bitDepth=${img.bitDepth} colorType=${img.colorType} channels=${img.channels}`);
  const colors = new Map();
  for (let y = 0; y < img.h; y++) {
    for (let x = 0; x < img.w; x++) {
      const o = y * img.stride + x * img.bpp;
      let r, g, b, a = 255;
      if (img.channels === 4) { r = img.out[o]; g = img.out[o+1]; b = img.out[o+2]; a = img.out[o+3]; }
      else if (img.channels === 3) { r = img.out[o]; g = img.out[o+1]; b = img.out[o+2]; }
      else { r = g = b = img.out[o]; }
      const k = `${r},${g},${b},${a}`;
      colors.set(k, (colors.get(k) || 0) + 1);
    }
  }
  const sorted = [...colors.entries()].sort((a, b) => b[1] - a[1]);
  console.log('unique colors:', sorted.length);
  for (const [k, n] of sorted.slice(0, 12)) console.log(`   rgba(${k}) x${n}`);
  // alpha map ASCII
  console.log('alpha map (# = opaque, . = 0):');
  for (let y = 0; y < img.h; y++) {
    let row = '';
    for (let x = 0; x < img.w; x++) {
      const o = y * img.stride + x * img.bpp;
      const a = img.channels === 4 ? img.out[o + 3] : (img.channels === 2 ? img.out[o+1] : 255);
      row += a === 0 ? '.' : (a < 64 ? ':' : (a < 160 ? '+' : '#'));
    }
    console.log('   ' + row);
  }
}
