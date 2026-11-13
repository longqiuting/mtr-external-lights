// Block-atlas safety: the item icons + the "particle only" block model reference textures through the
// regular model pipeline, which stitches sprites into the block atlas (1 张 1024×1024 的贴图基本占满
// 整张图集，stitch 会失败 → 物品图标变紫黑). The BER binds textures directly (no atlas), so it can keep
// the full-resolution png.
//
//   → 给每张 >256 的贴图生成一份 4× 盒式降采样（256×256）的 `<name>_icon.png`,
//      并把图标模型 / 粒子模型里的引用换成它。
//
//   node downscale-icons.js <src/main/resources>
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const RES = process.argv[2];
const ASSETS = path.join(RES, 'assets/mtrlte');
const MAX = 256;

// ---------------------------------------------------------------- png codec (8-bit, non-interlaced)
const CRC_TABLE = (() => {
  const table = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c;
  }
  return table;
})();

function crc32(buf) {
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const out = Buffer.alloc(8 + data.length + 4);
  out.writeUInt32BE(data.length, 0);
  out.write(type, 4, 'ascii');
  data.copy(out, 8);
  out.writeUInt32BE(crc32(Buffer.concat([Buffer.from(type, 'ascii'), data])), 8 + data.length);
  return out;
}

function decode(buf) {
  let off = 8;
  let w = 0, h = 0, bpp = 0;
  const idat = [];
  while (off < buf.length) {
    const len = buf.readUInt32BE(off);
    const type = buf.toString('ascii', off + 4, off + 8);
    const data = buf.subarray(off + 8, off + 8 + len);
    if (type === 'IHDR') {
      w = data.readUInt32BE(0);
      h = data.readUInt32BE(4);
      const depth = data[8];
      const colorType = data[9];
      if (depth !== 8 || data[12] !== 0) throw new Error('unsupported png (depth ' + depth + ', interlace ' + data[12] + ')');
      bpp = colorType === 6 ? 4 : colorType === 2 ? 3 : 0;
      if (!bpp) throw new Error('unsupported color type ' + colorType);
    } else if (type === 'IDAT') {
      idat.push(data);
    } else if (type === 'IEND') {
      break;
    }
    off += 12 + len;
  }
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const stride = w * bpp;
  const out = Buffer.alloc(h * stride);
  let p = 0;
  for (let y = 0; y < h; y++) {
    const filter = raw[p++];
    const line = raw.subarray(p, p + stride);
    p += stride;
    const cur = out.subarray(y * stride, (y + 1) * stride);
    const prev = y > 0 ? out.subarray((y - 1) * stride, y * stride) : null;
    for (let x = 0; x < stride; x++) {
      const a = x >= bpp ? cur[x - bpp] : 0;
      const b = prev ? prev[x] : 0;
      const c = prev && x >= bpp ? prev[x - bpp] : 0;
      let v = line[x];
      switch (filter) {
        case 0: break;
        case 1: v = (v + a) & 255; break;
        case 2: v = (v + b) & 255; break;
        case 3: v = (v + ((a + b) >> 1)) & 255; break;
        case 4: {
          const q = a + b - c;
          const pa = Math.abs(q - a), pb = Math.abs(q - b), pc = Math.abs(q - c);
          v = (v + (pa <= pb && pa <= pc ? a : pb <= pc ? b : c)) & 255;
          break;
        }
        default: throw new Error('bad filter ' + filter);
      }
      cur[x] = v;
    }
  }
  return { w, h, bpp, data: out };
}

function encode({ w, h, bpp, data }) {
  const stride = w * 4;
  const raw = Buffer.alloc(h * (stride + 1));
  for (let y = 0; y < h; y++) {
    raw[y * (stride + 1)] = 0;
    for (let x = 0; x < w; x++) {
      const s = (y * w + x) * bpp;
      const d = y * (stride + 1) + 1 + x * 4;
      // 预乘无关：这里只做盒式平均，alpha 直接平均即可（素材没有边缘半透明渐变）
      raw[d] = data[s];
      raw[d + 1] = data[s + 1];
      raw[d + 2] = data[s + 2];
      raw[d + 3] = bpp === 4 ? data[s + 3] : 255;
    }
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0);
  ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8;
  ihdr[9] = 6;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

/** 盒式降采样到 ≤ MAX（取能整除的最小因子，1024 → 256 是 4×4 平均）。 */
function shrink(img) {
  let factor = 1;
  while (img.w / factor > MAX || img.h / factor > MAX) factor++;
  const w = Math.floor(img.w / factor);
  const h = Math.floor(img.h / factor);
  const data = Buffer.alloc(w * h * 4);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const sum = [0, 0, 0, 0];
      for (let dy = 0; dy < factor; dy++) {
        for (let dx = 0; dx < factor; dx++) {
          const s = ((y * factor + dy) * img.w + (x * factor + dx)) * img.bpp;
          for (let c = 0; c < 4; c++) sum[c] += img.bpp === 4 ? img.data[s + c] : c === 3 ? 255 : img.data[s + c];
        }
      }
      const d = (y * w + x) * 4;
      for (let c = 0; c < 4; c++) data[d + c] = Math.round(sum[c] / (factor * factor));
    }
  }
  return { w, h, bpp: 4, data };
}

function pngSize(file) {
  const b = fs.readFileSync(file);
  return { w: b.readUInt32BE(16), h: b.readUInt32BE(20) };
}

// ---------------------------------------------------------------- 1. 生成 _icon 副本
const MODEL_DIR = path.join(ASSETS, 'models/block');
const created = new Map(); // 原贴图 id（不带 textures/ 与 .png） -> 新贴图 id

for (const name of fs.readdirSync(MODEL_DIR)) {
  if (!name.startsWith('fangsu_psd_') || !name.endsWith('_icon.json')) continue;
  const file = path.join(MODEL_DIR, name);
  const json = JSON.parse(fs.readFileSync(file, 'utf8'));
  let changed = false;
  for (const [key, tex] of Object.entries(json.textures || {})) {
    if (!tex.startsWith('mtrlte:fangsu_psd/')) continue;
    const png = path.join(ASSETS, 'textures', tex.replace('mtrlte:', '') + '.png');
    if (!fs.existsSync(png)) continue;
    const { w, h } = pngSize(png);
    if (w <= MAX && h <= MAX) continue;
    const target = tex + '_icon';
    if (!created.has(tex)) {
      const img = decode(fs.readFileSync(png));
      const small = shrink(img);
      const outPng = path.join(ASSETS, 'textures', target.replace('mtrlte:', '') + '.png');
      fs.mkdirSync(path.dirname(outPng), { recursive: true });
      fs.writeFileSync(outPng, encode(small));
      console.log('downscaled ' + path.relative(RES, png) + ' ' + w + 'x' + h + ' -> ' + small.w + 'x' + small.h
        + '  (' + path.relative(RES, outPng) + ')');
      created.set(tex, target);
    }
    json.textures[key] = created.get(tex);
    changed = true;
  }
  if (changed) fs.writeFileSync(file, JSON.stringify(json, null, 2) + '\n');
}

// 粒子贴图也走图集：指向一张小图（门框 256 那张，够用）。
const particle = path.join(MODEL_DIR, 'fangsu_psd_particle_only.json');
if (fs.existsSync(particle)) {
  const json = JSON.parse(fs.readFileSync(particle, 'utf8'));
  if (json.textures && json.textures.particle) {
    const tex = json.textures.particle;
    json.textures.particle = created.get(tex) || tex;
    fs.writeFileSync(particle, JSON.stringify(json, null, 2) + '\n');
  }
}

console.log(created.size + ' texture(s) duplicated at <=' + MAX + 'px for the atlas');
