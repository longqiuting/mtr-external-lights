// Inspect fangsu PSD objs: groups, material usage, bounds (OBJ units, Y up, centered xz).
const fs = require('fs');
const path = require('path');

const root = process.argv[2];
const files = process.argv.slice(3);

for (const rel of files) {
  const text = fs.readFileSync(path.join(root, rel), 'utf8');
  const verts = [];
  const uvs = [];
  let group = '<none>';
  let mat = '<none>';
  const groups = new Map(); // group -> {mats:Set, min:[], max:[], faces:0, uvMin, uvMax}
  const mtls = new Set();
  let mtllib = null;
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const sp = line.indexOf(' ');
    const kw = sp < 0 ? line : line.slice(0, sp);
    const rest = sp < 0 ? '' : line.slice(sp + 1).trim();
    if (kw === 'v') verts.push(rest.split(/\s+/).map(Number));
    else if (kw === 'vt') uvs.push(rest.split(/\s+/).map(Number));
    else if (kw === 'mtllib') mtllib = rest;
    else if (kw === 'usemtl') { mat = rest; mtls.add(rest); }
    else if (kw === 'o' || kw === 'g') group = rest;
    else if (kw === 'f') {
      let g = groups.get(group);
      if (!g) { g = { mats: new Set(), min: [1e9, 1e9, 1e9], max: [-1e9, -1e9, -1e9], faces: 0, uvMin: [1e9, 1e9], uvMax: [-1e9, -1e9] }; groups.set(group, g); }
      g.mats.add(mat);
      g.faces++;
      for (const corner of rest.split(/\s+/)) {
        const [vi, ti] = corner.split('/').map((s) => (s === '' || s === undefined ? NaN : parseInt(s, 10)));
        const v = verts[vi < 0 ? verts.length + vi : vi - 1];
        if (v) for (let a = 0; a < 3; a++) { g.min[a] = Math.min(g.min[a], v[a]); g.max[a] = Math.max(g.max[a], v[a]); }
        const uv = uvs[(ti < 0 ? uvs.length + ti : ti - 1)];
        if (uv) { g.uvMin[0] = Math.min(g.uvMin[0], uv[0]); g.uvMin[1] = Math.min(g.uvMin[1], uv[1]); g.uvMax[0] = Math.max(g.uvMax[0], uv[0]); g.uvMax[1] = Math.max(g.uvMax[1], uv[1]); }
      }
    }
  }
  console.log('=== ' + rel + ' (mtllib ' + mtllib + ') ===');
  for (const [name, g] of groups) {
    const f = (n) => n.toFixed(3);
    console.log(`  ${name.padEnd(22)} faces=${String(g.faces).padStart(4)} mats=[${[...g.mats].map((m) => m.slice(0, 12)).join(', ')}]`
      + ` x[${f(g.min[0])},${f(g.max[0])}] y[${f(g.min[1])},${f(g.max[1])}] z[${f(g.min[2])},${f(g.max[2])}]`
      + ` u[${f(g.uvMin[0])},${f(g.uvMax[0])}] v[${f(g.uvMin[1])},${f(g.uvMax[1])}]`);
  }
}
