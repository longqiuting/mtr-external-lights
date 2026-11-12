// 正交投影渲染 dep_on_wall 模型（从北面 / -Z 看），带 z-buffer，用来判断哪块几何挡在屏幕前面
const fs = require('fs');
const model = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));

const W = 512, H = 512; // 1 格 = 32px
const img = new Int32Array(W * H).fill(-1);       // 元素索引
const depth = new Float32Array(W * H).fill(Infinity);

function rot(p, r) {
  if (!r || !r.angle) return p;
  const a = r.angle * Math.PI / 180;
  const o = r.origin;
  let [x, y, z] = p.map((v, i) => v - o[i]);
  if (r.axis === 'y') {
    const nx = x * Math.cos(a) + z * Math.sin(a);
    const nz = -x * Math.sin(a) + z * Math.cos(a);
    x = nx; z = nz;
  } else if (r.axis === 'z') {
    const nx = x * Math.cos(a) - y * Math.sin(a);
    const ny = x * Math.sin(a) + y * Math.cos(a);
    x = nx; y = ny;
  } else if (r.axis === 'x') {
    const ny = y * Math.cos(a) - z * Math.sin(a);
    const nz = y * Math.sin(a) + z * Math.cos(a);
    y = ny; z = nz;
  }
  return [x + o[0], y + o[1], z + o[2]];
}

const faces = [];
model.elements.forEach((el, idx) => {
  let [x0, y0, z0] = el.from, [x1, y1, z1] = el.to;
  const cx = [x0, y0, z0], cy = [x1, y0, z0], cz = [x1, y1, z0], cw = [x0, y1, z0];
  const corners = [cx, cy, cz, cw].map(p => rot(p, el.rotation));
  // north face: at z = min z of the (rotated) quad; use the 4 corners as-is
  faces.push({ idx, name: el.name, corners, zmin: Math.min(...corners.map(p => p[2])), zmax: Math.max(...corners.map(p => p[2])) });
});

// 从 -Z 方向看：z 越小越近。光栅化 north 面（按角点顺序）
for (const f of faces) {
  const pts = f.corners;
  // 三角形扇形
  for (let i = 1; i < pts.length - 1; i++) {
    const tri = [pts[0], pts[i], pts[i + 1]];
    rasterTri(tri, f.idx);
  }
}

function rasterTri(tri, idx) {
  const px = tri.map(p => p[0] * 32);
  const py = tri.map(p => (16 - p[1]) * 32);
  const pz = tri.map(p => p[2]);
  const minX = Math.max(0, Math.floor(Math.min(...px))), maxX = Math.min(W - 1, Math.ceil(Math.max(...px)));
  const minY = Math.max(0, Math.floor(Math.min(...py))), maxY = Math.min(H - 1, Math.ceil(Math.max(...py)));
  const [x0, y0] = px, [x1, y1] = [px[1] - px[0], py[1] - py[0]], [x2, y2] = [px[2] - px[0], py[2] - py[0]];
  const den = x1 * y2 - x2 * y1;
  if (Math.abs(den) < 1e-6) return;
  for (let y = minY; y <= maxY; y++) {
    for (let x = minX; x <= maxX; x++) {
      const dx = x + 0.5 - x0, dy = y + 0.5 - y0;
      const u = (dx * y2 - x2 * dy) / den;
      const v = (x1 * dy - dx * y1) / den;
      if (u < 0 || v < 0 || u + v > 1) continue;
      const z = pz[0] + u * (pz[1] - pz[0]) + v * (pz[2] - pz[0]);
      const o = y * W + x;
      if (z < depth[o]) { depth[o] = z; img[o] = idx; }
    }
  }
}

// 输出：每个像素用元素索引配色
const colors = [[40,40,40],[90,90,90],[180,30,30],[120,120,120],[70,70,70],[150,150,150],[60,60,60],[100,200,100],[100,100,200],[200,180,60],[200,100,200],[80,200,200],[240,240,240],[160,80,40],[40,160,80],[80,40,160],[255,0,0],[0,0,255]];
function put(x, y, c) { const o = (y * W + x) * 4; buf[o] = c[0]; buf[o+1] = c[1]; buf[o+2] = c[2]; buf[o+3] = 255; }
const buf = Buffer.alloc(W * H * 4);
for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
  const i = img[y * W + x];
  put(x, y, i < 0 ? [255, 255, 255] : colors[i % colors.length]);
}
// 标注屏幕区域 (x 2..14, y 4..12, z 12.3) 的边框（红色虚线框）
for (let x = 2 * 32; x <= 14 * 32; x++) { put(x, (16 - 12) * 32, [255, 0, 255]); put(x, (16 - 4) * 32, [255, 0, 255]); }
for (let y = (16 - 12) * 32; y <= (16 - 4) * 32; y++) { put(2 * 32, y, [255, 0, 255]); put(14 * 32, y, [255, 0, 255]); }

// 简单 PNG 编码
const zlib = require('zlib');
function crc32(b) { let c, crc = 0xFFFFFFFF; for (let n = 0; n < b.length; n++) { c = (crc ^ b[n]) & 255; for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1; crc = (crc >>> 8) ^ c; } return (crc ^ 0xFFFFFFFF) >>> 0; }
function chunk(type, data) { const len = Buffer.alloc(4); len.writeUInt32BE(data.length); const td = Buffer.concat([Buffer.from(type, 'ascii'), data]); const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(td)); return Buffer.concat([len, td, crc]); }
const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(W, 0); ihdr.writeUInt32BE(H, 4); ihdr[8] = 8; ihdr[9] = 6;
const raw = Buffer.alloc((W * 4 + 1) * H);
for (let y = 0; y < H; y++) buf.copy(raw, y * (W * 4 + 1) + 1, y * W * 4, (y + 1) * W * 4);
fs.writeFileSync(process.argv[3], Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]));
console.log('faces', faces.map(f => `${f.idx}${f.name ? '(' + f.name + ')' : ''} z=[${f.zmin.toFixed(2)},${f.zmax.toFixed(2)}]`).join('\n'));
