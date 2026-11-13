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

// ---------------------------------------------------------------------------------------------
// 还查一件事：`parent` 指向<b>本模组</b>却不存在的模型。这正是闸机侧壳物品「紫黑块」的原因 ——
// `models/item/ticket_gate_brt_in_side.json` 的 parent 写成 `mtrlte:block/ticket_gate_brt_in_item`，
// 那份 json 根本不存在，原版就直接画 MissingModel（紫黑）。上面那段因为文件缺失被 `continue` 跳过，
// 所以单独补一条：只查显式写了本模组命名空间（或 assets 下有同名目录）的 parent，
// `block/block` 这种原版 id 依旧放过。
const dangling = [];
function checkParents(dir) {
  const d = path.join(A, 'mtrlte', dir);
  if (!fs.existsSync(d)) return;
  for (const f of fs.readdirSync(d)) {
    if (!f.endsWith('.json')) continue;
    const j = readJson(path.join(d, f));
    if (!j || !j.parent) continue;
    const i = j.parent.indexOf(':');
    if (i < 0) continue; // 无命名空间 = 原版
    const ns = j.parent.slice(0, i);
    if (ns === 'minecraft') continue;
    const target = path.join(A, ns, 'models', j.parent.slice(i + 1) + '.json');
    if (!fs.existsSync(target)) dangling.push(`${dir}/${f}: parent ${j.parent} MISSING`);
  }
}
for (const dir of ['models/block', 'models/item']) checkParents(dir);
console.log(`[${ver}] missing parent models: ${dangling.length}`);
for (const m of [...new Set(dangling)].slice(0, 40)) console.log('  - ' + m);
