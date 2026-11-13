// Item icons for the 14 fangsu PSD door blocks: rasterise the real .obj into a 64x64 sprite.
//
// Why not a block model (what the first cut did): a Minecraft block model can only describe
// axis-aligned cuboids, so the best it could do was "one cuboid per `o` group, with the group's
// whole uv rectangle on all six faces". That smears a chunk of the atlas over a box -- in the
// inventory a fangsu door looked like grey noise. fangsu itself ships a flat sprite for these
// items (`assets/fangsu/textures/item/screendoor_end.png` + `item/generated`), and so do the
// Xiamen PSD blocks in this mod (`mtrlte:item/apg_door`), so the door is drawn here as a flat
// front elevation: orthographic, looking at the face you see with `facing = north`.
//
// The rasteriser is deliberately tiny -- painter's algorithm by average depth, nearest-neighbour
// atlas sampling, 4x supersampling for antialiasing. Good enough for a 64px icon and no deps.
//
// Run through gen-resources.js (which owns the variant list), not on its own.
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const SIZE = 64;        // output sprite, like mtrlte:item/apg_door (64x64)
const MARGIN = 2;       // transparent border, px
const SS = 4;           // supersampling factor
const GLASS = { r: 0.78, g: 0.88, b: 0.95, a: 0.45 };   // alpha-0 atlas pixels -> frosted glass

// ---------------------------------------------------------------- png decode / encode

function decodePng(buf) {
  let pos = 8;
  let w = 0, h = 0, depth = 0, ctype = 0;
  const idat = [];
  while (pos + 8 <= buf.length) {
    const len = buf.readUInt32BE(pos);
    const type = buf.toString('ascii', pos + 4, pos + 8);
    const data = buf.subarray(pos + 8, pos + 8 + len);
    if (type === 'IHDR') {
      w = data.readUInt32BE(0);
      h = data.readUInt32BE(4);
      depth = data[8];
      ctype = data[9];
      if (data[12] !== 0) throw new Error('interlaced png not supported');
    } else if (type === 'IDAT') {
      idat.push(data);
    } else if (type === 'IEND') {
      break;
    }
    pos += 12 + len;
  }
  const channels = { 0: 1, 2: 3, 4: 2, 6: 4 }[ctype];
  if (!channels) throw new Error('unsupported png colour type ' + ctype);
  if (depth !== 8) throw new Error('unsupported png bit depth ' + depth);
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const stride = w * channels;
  const out = Buffer.alloc(stride * h);
  let p = 0;
  for (let y = 0; y < h; y++) {
    const filter = raw[p++];
    const row = raw.subarray(p, p + stride);
    p += stride;
    const cur = out.subarray(y * stride, (y + 1) * stride);
    const prev = y > 0 ? out.subarray((y - 1) * stride, y * stride) : null;
    for (let x = 0; x < stride; x++) {
      const a = x >= channels ? cur[x - channels] : 0;
      const b = prev ? prev[x] : 0;
      const c = prev && x >= channels ? prev[x - channels] : 0;
      let v = row[x];
      switch (filter) {
        case 0: break;
        case 1: v = (v + a) & 255; break;
        case 2: v = (v + b) & 255; break;
        case 3: v = (v + ((a + b) >> 1)) & 255; break;
        case 4: {
          const pa = Math.abs(b - c), pb = Math.abs(a - c), pc = Math.abs(a + b - 2 * c);
          v = (v + (pa <= pb && pa <= pc ? a : pb <= pc ? b : c)) & 255;
          break;
        }
        default: throw new Error('bad png filter ' + filter);
      }
      cur[x] = v;
    }
  }
  return { w, h, channels, data: out };
}

const CRC_TABLE = (() => {
  const table = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c;
  }
  return table;
})();

function crc32(bytes) {
  let c = -1;
  for (let i = 0; i < bytes.length; i++) c = CRC_TABLE[(c ^ bytes[i]) & 0xFF] ^ (c >>> 8);
  return (c ^ -1) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body));
  return Buffer.concat([len, body, crc]);
}

function encodePng(w, h, rgba) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0);
  ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8;    // bit depth
  ihdr[9] = 6;    // RGBA
  const raw = Buffer.alloc((w * 4 + 1) * h);
  for (let y = 0; y < h; y++) {
    raw[y * (w * 4 + 1)] = 0;                                    // filter: none
    rgba.copy(raw, y * (w * 4 + 1) + 1, y * w * 4, (y + 1) * w * 4);
  }
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

// ---------------------------------------------------------------- obj

/** material name -> png basename, from the sibling .mtl. */
function readMtl(file) {
  const map = new Map();
  let mat = null;
  for (const rawLine of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const line = rawLine.trim();
    if (line.startsWith('newmtl ')) mat = line.slice(7).trim();
    else if (line.startsWith('map_Kd ') && mat) map.set(mat, line.slice(7).trim());
  }
  return map;
}

// ---------------------------------------------------------------- raster

function generate(defs, resDir) {
  const assets = path.join(resDir, 'assets/mtrlte');
  const texDir = path.join(assets, 'textures/item/fangsu_psd');
  fs.mkdirSync(texDir, { recursive: true });
  const cache = new Map();
  const written = [];

  for (const def of defs) {
    const objPath = path.join(assets, def.obj);
    const mtlPath = objPath.replace(/\.obj$/, '.mtl');
    const pngDir = path.join(assets, 'textures', path.dirname(def.obj));
    const maps = readMtl(mtlPath);
    const tris = [];
    // A group can span several materials (brtduanmen layers dkg / ltg / blk), so the triangles
    // are grouped by `usemtl` and each batch samples its own png.
    const perMaterial = readObjByMaterial(objPath, def.flipV);
    for (const [mat, list] of perMaterial) {
      const png = maps.get(mat);
      if (!png) continue;
      const img = cache.get(png) || decodePng(fs.readFileSync(path.join(pngDir, png)));
      cache.set(png, img);
      for (const tri of list) {
        if (!def.groups.includes(tri.group)) continue;
        tris.push({ ...tri, img });
      }
    }
    if (!tris.length) {
      console.log('!! no geometry for ' + def.id);
      continue;
    }

    // bounds of the drawn geometry (y is up, x is sideways, camera looks along +z)
    let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
    for (const t of tris) for (const p of t.p) {
      minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]);
      minY = Math.min(minY, p[1]); maxY = Math.max(maxY, p[1]);
    }
    const scale = Math.min((SIZE - 2 * MARGIN) / Math.max(maxX - minX, 1e-6),
                           (SIZE - 2 * MARGIN) / Math.max(maxY - minY, 1e-6));
    if (process.env.PSD_DEBUG) {
      console.log(def.id, 'tris=' + tris.length, 'x[' + minX + '..' + maxX + ']',
        'y[' + minY + '..' + maxY + ']', 'scale=' + scale);
    }
    const offX = SIZE / 2 - ((minX + maxX) / 2) * scale;
    const offY = SIZE / 2 + ((minY + maxY) / 2) * scale;

    const W = SIZE * SS;
    const buf = new Float64Array(W * W * 4);    // premultiplied RGBA
    // the scale / offsets above are in final sprite pixels; the buffer is SS times bigger
    const s = scale * SS;
    const ox = offX * SS;
    const oy = offY * SS;
    // painter's algorithm: farthest (largest z) first
    const order = tris.slice().sort((a, b) => avgZ(b) - avgZ(a));
    for (const t of order) drawTri(buf, W, t, s, ox, oy);

    const out = Buffer.alloc(SIZE * SIZE * 4);
    for (let y = 0; y < SIZE; y++) {
      for (let x = 0; x < SIZE; x++) {
        let r = 0, g = 0, b = 0, a = 0;
        for (let sy = 0; sy < SS; sy++) {
          for (let sx = 0; sx < SS; sx++) {
            const i = ((y * SS + sy) * W + (x * SS + sx)) * 4;
            r += buf[i]; g += buf[i + 1]; b += buf[i + 2]; a += buf[i + 3];
          }
        }
        const n = SS * SS;
        r /= n; g /= n; b /= n; a /= n;
        const o = (y * SIZE + x) * 4;
        if (a > 0.004) {
          out[o] = Math.round(Math.min(1, r / a) * 255);
          out[o + 1] = Math.round(Math.min(1, g / a) * 255);
          out[o + 2] = Math.round(Math.min(1, b / a) * 255);
          out[o + 3] = Math.round(Math.min(1, a) * 255);
        }
      }
    }

    const short = def.id.replace(/^fangsu_psd_/, '');
    fs.writeFileSync(path.join(texDir, short + '.png'), encodePng(SIZE, SIZE, out));
    fs.writeFileSync(path.join(assets, 'models/item', def.id + '.json'),
      JSON.stringify({
        parent: 'item/generated',
        textures: { layer0: 'mtrlte:item/fangsu_psd/' + short },
      }, null, 2) + '\n');
    written.push('item/fangsu_psd/' + short + '.png');
  }
  return written;
}

function avgZ(t) {
  return (t.p[0][2] + t.p[1][2] + t.p[2][2]) / 3;
}

/** .obj -> Map(material -> triangles), uv already flipV'd. */
function readObjByMaterial(file, flipV) {
  const verts = [];
  const uvs = [];
  const byMat = new Map();
  let mat = '';
  let group = '';
  for (const rawLine of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith('#')) continue;
    const sp = line.indexOf(' ');
    if (sp < 0) continue;
    const kw = line.slice(0, sp);
    const rest = line.slice(sp + 1).trim();
    if (kw === 'v') {
      const p = rest.split(/\s+/).map(Number);
      verts.push([p[0] * 16 + 8, p[1] * 16, p[2] * 16 + 8]);
    } else if (kw === 'vt') {
      const p = rest.split(/\s+/).map(Number);
      uvs.push([p[0], flipV ? 1 - (p[1] || 0) : (p[1] || 0)]);
    } else if (kw === 'o' || kw === 'g') {
      group = rest;
    } else if (kw === 'usemtl') {
      mat = rest;
    } else if (kw === 'f') {
      const pos = [];
      const uv = [];
      for (const corner of rest.split(/\s+/)) {
        const parts = corner.split('/');
        pos.push(verts[parseInt(parts[0], 10) - 1]);
        uv.push(parts.length > 1 && parts[1] !== '' ? uvs[parseInt(parts[1], 10) - 1] : [0, 0]);
      }
      const list = byMat.get(mat) || [];
      for (let i = 1; i + 1 < pos.length; i++) {
        list.push({ group, p: [pos[0], pos[i], pos[i + 1]], uv: [uv[0], uv[i], uv[i + 1]] });
      }
      byMat.set(mat, list);
    }
  }
  return byMat;
}

/** One triangle into the supersampled premultiplied buffer ("over" compositing). */
function drawTri(buf, W, tri, scale, offX, offY) {
  const s = tri.p.map((p) => [p[0] * scale + offX, offY - p[1] * scale]);
  const [ax, ay] = s[0];
  const [bx, by] = s[1];
  const [cx, cy] = s[2];
  const den = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy);
  if (Math.abs(den) < 1e-9) return;
  const minSx = Math.max(0, Math.floor(Math.min(ax, bx, cx)));
  const maxSx = Math.min(W - 1, Math.ceil(Math.max(ax, bx, cx)));
  const minSy = Math.max(0, Math.floor(Math.min(ay, by, cy)));
  const maxSy = Math.min(W - 1, Math.ceil(Math.max(ay, by, cy)));
  const img = tri.img;
  for (let y = minSy; y <= maxSy; y++) {
    for (let x = minSx; x <= maxSx; x++) {
      const px = x + 0.5;
      const py = y + 0.5;
      const w0 = ((by - cy) * (px - cx) + (cx - bx) * (py - cy)) / den;
      const w1 = ((cy - ay) * (px - cx) + (ax - cx) * (py - cy)) / den;
      const w2 = 1 - w0 - w1;
      if (w0 < -0.001 || w1 < -0.001 || w2 < -0.001) continue;
      const u = w0 * tri.uv[0][0] + w1 * tri.uv[1][0] + w2 * tri.uv[2][0];
      const v = w0 * tri.uv[0][1] + w1 * tri.uv[1][1] + w2 * tri.uv[2][1];
      const tx = Math.max(0, Math.min(img.w - 1, Math.floor((u - Math.floor(u)) * img.w)));
      const ty = Math.max(0, Math.min(img.h - 1, Math.floor((v - Math.floor(v)) * img.h)));
      const o = (ty * img.w + tx) * img.channels;
      let r, g, b, a;
      if (img.channels === 4) {
        a = img.data[o + 3] / 255;
        r = img.data[o] / 255;
        g = img.data[o + 1] / 255;
        b = img.data[o + 2] / 255;
      } else {
        a = 1; r = img.data[o] / 255; g = img.data[o + 1] / 255; b = img.data[o + 2] / 255;
      }
      if (a < 0.1) { r = GLASS.r; g = GLASS.g; b = GLASS.b; a = GLASS.a; }
      const i = (y * W + x) * 4;
      const inv = 1 - a;
      buf[i] = r * a + buf[i] * inv;
      buf[i + 1] = g * a + buf[i + 1] * inv;
      buf[i + 2] = b * a + buf[i + 2] * inv;
      buf[i + 3] = a + buf[i + 3] * inv;
    }
  }
}

module.exports = { generate };

if (require.main === module) {
  console.log('run tools/psd/gen-resources.js <src/main/resources> instead -- it owns the variant list');
}
