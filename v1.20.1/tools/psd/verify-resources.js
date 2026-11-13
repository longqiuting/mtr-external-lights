// Verify the fangsu PSD door resources: every descriptor's model/texture must exist in the jar
// resources, and every block needs a blockstate, an item model, a loot table and lang entries.
//
//   node verify-resources.js <src/main/resources>
//
// Exit code 0 = all good, 1 = problems (printed as "FAIL ...").
const fs = require('fs');
const path = require('path');

const RES = process.argv[2];
const JAR = process.argv[3];
if (!RES) {
  console.error('usage: node verify-resources.js <src/main/resources> [built.jar]');
  process.exit(2);
}
const ASSETS = path.join(RES, 'assets/mtrlte');
const NS_DIR = path.join(RES, 'assets');
const DATA = path.join(RES, 'data/mtrlte');

const problems = [];
const fail = (msg) => problems.push(msg);

// 每一个被检查过的资源路径都记下来；给了 jar 的话，最后再逐条确认它也真的在 jar 里。
const required = new Set();
const exists = (p) => {
  const rel = path.relative(RES, p).split(path.sep).join('/');
  required.add(rel);
  try { return fs.statSync(p).isFile(); } catch (e) { return false; }
};

function readJson(p) {
  try { return JSON.parse(fs.readFileSync(p, 'utf8')); } catch (e) { fail('bad JSON ' + path.relative(RES, p) + ': ' + e.message); return null; }
}

// resource id ("mtrlte:fangsu_psd/duanmen/x.obj") -> file on disk
function fileOf(id, kind) {
  const [ns, p] = id.split(':');
  if (kind === 'texture') return path.join(NS_DIR, ns, 'textures', p + '.png');
  return path.join(NS_DIR, ns, p + (path.extname(p) ? '' : '.json'));
}

// ---------------------------------------------------------------- 1. descriptors -> geometry
const blocks = [];
function mtlMaps(mtlFile) {
  const out = [];
  let mat = null;
  for (const raw of fs.readFileSync(mtlFile, 'utf8').split(/\r?\n/)) {
    const line = raw.trim();
    if (line.startsWith('newmtl ')) mat = line.slice(7).trim();
    else if (line.startsWith('map_Kd ') && mat) out.push({ mat, png: line.slice(7).trim() });
  }
  return out;
}

function checkModel(modelId, owner) {
  const obj = fileOf(modelId, 'model');
  if (!exists(obj)) { fail(owner + ': model ' + modelId + ' missing at ' + path.relative(RES, obj)); return null; }
  const text = fs.readFileSync(obj, 'utf8');
  const mtlName = (text.split(/\r?\n/).map((l) => l.trim()).find((l) => l.startsWith('mtllib ')) || '').slice(7).trim();
  if (!mtlName) { fail(owner + ': ' + modelId + ' has no mtllib'); return null; }
  const dir = path.dirname(modelId.split(':')[1]);
  const mtl = path.join(ASSETS, dir, mtlName);
  if (!exists(mtl)) { fail(owner + ': mtl ' + mtlName + ' missing next to ' + modelId); return null; }
  const maps = mtlMaps(mtl);
  if (!maps.length) fail(owner + ': ' + mtlName + ' has no map_Kd');
  for (const m of maps) {
    const png = path.join(ASSETS, 'textures', dir, m.png);
    if (!exists(png)) fail(owner + ': texture ' + m.png + ' missing at ' + path.relative(RES, png));
  }
  // 每个 usemtl 都要在 mtl 里有 map_Kd（否则那块geometry会被渲染器跳过 = 门少一层）
  const groups = new Set();
  const used = new Set();
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (line.startsWith('o ') || line.startsWith('g ')) groups.add(line.slice(2).trim());
    else if (line.startsWith('usemtl ')) used.add(line.slice(7).trim());
  }
  for (const mat of used) {
    if (!maps.some((m) => m.mat === mat)) fail(owner + ': usemtl ' + mat + ' has no map_Kd in ' + mtlName);
  }
  return { obj, maps, groups };
}

const DUANMEN = ['brtduanmen', 'mtrduanmen', 'drlduanmen', 'shlowduanmen', 'shhighduanmen'];
for (const file of DUANMEN) {
  const p = path.join(ASSETS, 'fangsu_psd', 'duanmen', file + '.json');
  if (!exists(p)) { fail('descriptor missing: ' + path.relative(RES, p)); continue; }
  const json = readJson(p);
  if (!json) continue;
  for (const e of json.content) {
    const id = 'fangsu_psd_' + e.id;
    const model = checkModel(e.model, id);
    if (!e.subModels || !e.subModels.main || !e.subModels.door) fail(id + ': subModels.main/door missing');
    else if (model) {
      for (const g of [e.subModels.main, e.subModels.door]) {
        if (!model.groups.has(g)) fail(id + ': o group "' + g + '" not found in ' + e.model);
      }
    }
    if (!e.shape || !e.shape.close || !e.shape.close.length) fail(id + ': shape.close missing');
    if (typeof e.doorAngle !== 'number') fail(id + ': doorAngle missing');
    blocks.push({ id, kind: 'rotating', angles: e.doorAngle });
  }
}
for (const file of ['low', 'high']) {
  const p = path.join(ASSETS, 'fangsu_psd', 'screendoor', 'xiamen', file + '.json');
  if (!exists(p)) { fail('descriptor missing: ' + path.relative(RES, p)); continue; }
  const json = readJson(p);
  if (!json) continue;
  const model = checkModel(json.model, 'screendoor/' + file);
  for (const side of ['left', 'right']) {
    let count = 0;
    for (const entry of json.door[side] || []) {
      for (const d of entry.doors || []) {
        count++;
        if (typeof d.step !== 'number') fail('screendoor/' + file + '/' + side + ': step missing');
        if (!d.shape || !d.shape.length) fail('screendoor/' + file + '/' + side + ': shape missing');
        if (model && !model.groups.has(d.subModel)) {
          fail('screendoor/' + file + '/' + side + ': o group "' + d.subModel + '" not found in ' + json.model);
        }
      }
    }
    if (!count) fail('screendoor/' + file + '/' + side + ': no doors');
    blocks.push({ id: 'fangsu_psd_screendoor_' + file + '_' + side, kind: 'sliding' });
  }
}

// ---------------------------------------------------------------- 2. model json resolver
function modelFile(id) {
  const [ns, p] = id.split(':');
  return path.join(NS_DIR, ns, 'models', p + '.json');
}
function checkModelJson(id, owner, seen = new Set()) {
  if (seen.has(id)) return;
  seen.add(id);
  const file = modelFile(id);
  if (!exists(file)) { fail(owner + ': model json ' + id + ' missing'); return; }
  const json = readJson(file);
  if (!json) return;
  for (const tex of Object.values(json.textures || {})) {
    if (typeof tex !== 'string' || tex.startsWith('#')) continue;
    if (!exists(fileOf(tex, 'texture'))) fail(owner + ': model ' + id + ' texture ' + tex + ' missing');
  }
  if (json.parent) checkModelJson(json.parent, owner, seen);
}

// ---------------------------------------------------------------- 3. per-block resources
const zh = readJson(path.join(ASSETS, 'lang', 'zh_cn.json')) || {};
const en = readJson(path.join(ASSETS, 'lang', 'en_us.json')) || {};
for (const block of blocks) {
  const bs = path.join(ASSETS, 'blockstates', block.id + '.json');
  if (!exists(bs)) { fail(block.id + ': blockstate missing'); }
  else {
    const json = readJson(bs);
    if (json) for (const [variant, v] of Object.entries(json.variants || {})) {
      checkModelJson(v.model, block.id + ' blockstate[' + variant + ']');
    }
  }
  const item = path.join(ASSETS, 'models', 'item', block.id + '.json');
  if (!exists(item)) fail(block.id + ': item model missing');
  else checkModelJson('mtrlte:item/' + block.id, block.id + ' item model');
  const loot = path.join(DATA, 'loot_tables', 'blocks', block.id + '.json');
  if (!exists(loot)) fail(block.id + ': loot table missing');
  else {
    const json = readJson(loot);
    const name = json && json.pools && json.pools[0] && json.pools[0].entries[0] && json.pools[0].entries[0].name;
    if (name !== 'mtrlte:' + block.id) fail(block.id + ': loot table drops ' + name);
  }
  for (const [label, lang] of [['zh_cn', zh], ['en_us', en]]) {
    if (!lang['block.mtrlte.' + block.id]) fail(block.id + ': lang/' + label + ' missing block.mtrlte.' + block.id);
  }
}

// ---------------------------------------------------------------- jar 交叉核对
if (JAR) {
  if (!exists(JAR) && !fs.existsSync(JAR)) {
    fail('jar not found: ' + JAR);
  } else {
    const out = require('child_process').execFileSync('unzip', ['-Z1', JAR], { encoding: 'utf8' });
    const entries = new Set(out.split(/\r?\n/).map((s) => s.trim()).filter(Boolean));
    // jar 里的类文件也算：14 个方块 = 方块类 + 方块实体渲染器 + 注册点，缺一个都会静默不生效
    for (const cls of ['FangsuPsdBlocks', 'FangsuPsdClient', 'FangsuPsdDef', 'FangsuPsdDoorBlock',
      'FangsuPsdDoorBlock$FangsuPsdDoorEntity', 'FangsuPsdRenderer']) {
      const entry = 'dev/js/mtrheadlight/xiamen/fangsu/' + cls + '.class';
      if (!entries.has(entry)) fail('jar: ' + entry + ' missing');
    }
    let missing = 0;
    for (const rel of required) {
      if (rel.startsWith('..')) continue;
      if (!entries.has(rel)) { fail('jar: ' + rel + ' missing'); missing++; }
    }
    console.log('jar check: ' + (required.size - missing) + '/' + required.size
      + ' referenced resource files present in ' + path.basename(JAR));
  }
}

// ---------------------------------------------------------------- report
const byKind = blocks.reduce((acc, b) => { acc[b.kind] = (acc[b.kind] || 0) + 1; return acc; }, {});
console.log('blocks: ' + blocks.length + ' (rotating ' + (byKind.rotating || 0) + ', sliding ' + (byKind.sliding || 0) + ')');
for (const b of blocks) console.log('  ok ' + b.id);
if (problems.length) {
  console.log('\n' + problems.length + ' problem(s):');
  for (const p of problems) console.log('  FAIL ' + p);
  process.exit(1);
}
console.log('\nall resources present: models, mtl textures, blockstates, item models, loot tables, lang');
