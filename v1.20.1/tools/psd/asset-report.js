// PNG header dimensions + mtl map_Kd listing for the fangsu PSD assets.
const fs = require('fs');
const path = require('path');

function pngSize(file) {
  const b = fs.readFileSync(file);
  if (b.length < 24 || b.readUInt32BE(12) !== 0x49484452) return null; // 'IHDR'
  return { w: b.readUInt32BE(16), h: b.readUInt32BE(20) };
}

const root = process.argv[2];
function walk(dir) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) { walk(p); continue; }
    if (e.name.endsWith('.png')) {
      const s = pngSize(p);
      console.log(`PNG  ${path.relative(root, p).padEnd(42)} ${s ? s.w + 'x' + s.h : '??'}  ${fs.statSync(p).size}B`);
    } else if (e.name.endsWith('.mtl')) {
      const txt = fs.readFileSync(p, 'utf8');
      const maps = [...txt.matchAll(/newmtl\s+(\S+)[\s\S]*?map_Kd\s+(\S+)/g)].map((m) => m[1].slice(0, 10) + '→' + m[2]);
      console.log(`MTL  ${path.relative(root, p).padEnd(42)} ${maps.join('  ')}`);
    } else if (e.name.endsWith('.obj')) {
      console.log(`OBJ  ${path.relative(root, p).padEnd(42)} ${fs.statSync(p).size}B`);
    } else if (e.name.endsWith('.json')) {
      console.log(`JSON ${path.relative(root, p).padEnd(42)} ${fs.statSync(p).size}B`);
    }
  }
}
walk(root);
