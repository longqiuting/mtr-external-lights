// Find model faces that reference an undefined #texture variable (MC renders MissingSprite = purple/black).
const fs = require('fs');
const path = require('path');

const ver = process.argv[2];
const A = path.join(ver, 'src/main/resources/assets');
const missing = [];

function readJson(p) { try { return JSON.parse(fs.readFileSync(p, 'utf8')); } catch (e) { return null; } }

function loadModel(id, seen = new Set()) {
  const i = id.indexOf(':');
  const ns = i < 0 ? 'minecraft' : id.slice(0, i);
  const p = i < 0 ? id : id.slice(i + 1);
  const file = path.join(A, ns, 'models', p + '.json');
  if (!fs.existsSync(file)) return null;
  if (seen.has(file)) return { textures: {}, elements: [] };
  seen.add(file);
  const j = readJson(file);
  if (!j) return null;
  const parent = j.parent ? loadModel(j.parent, seen) : { textures: {}, elements: [] };
  return {
    textures: Object.assign({}, parent ? parent.textures : {}, j.textures || {}),
    elements: (j.elements && j.elements.length ? j.elements : (parent ? parent.elements : [])) || [],
  };
}

for (const dir of ['models/block', 'models/item']) {
  const d = path.join(A, 'mtrlte', dir);
  if (!fs.existsSync(d)) continue;
  for (const f of fs.readdirSync(d)) {
    if (!f.endsWith('.json')) continue;
    const id = 'mtrlte:' + dir.split('/')[1] + '/' + f.replace(/\.json$/, '');
    const m = loadModel(id);
    if (!m) continue;
    for (const el of m.elements) {
      for (const [face, def] of Object.entries(el.faces || {})) {
        const t = def.texture;
        if (typeof t === 'string' && t.startsWith('#') && !(t.slice(1) in m.textures)) {
          missing.push(`${dir}/${f}: face ${face} -> ${t} UNDEFINED`);
        }
      }
    }
  }
}

console.log(`[${ver}] undefined face texture vars: ${missing.length}`);
for (const m of [...new Set(missing)].slice(0, 40)) console.log('  - ' + m);
