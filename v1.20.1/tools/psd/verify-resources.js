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
  const [ns, p] = id.includes(':') ? id.split(':') : ['minecraft', id];
  if (ns === 'minecraft') return null;      // vanilla resource: 不校验（jar 里没有这份文件）
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

// 玻璃板：一个方块 = 一整格，格内左右各半个格（两半各 4 种形态，刷子切换）。
// 形态是否「全都在」是这套东西最容易静默失败的地方 —— 少一条 subModel 就少一种形态，
// 界面上看不出来，只能靠这里对账（素材 glass.left/right 的每一条 = 一种形态，
// id 是 door / doorend / glass / longend，auto 那条是规则、没有几何）。
const GLASS_FORM_KEYS = new Set(['auto']);   // auto = 下拉框第一项 / 刷子循环里的自动档，动作栏也会显示
for (const file of ['high']) {
  const p = path.join(ASSETS, 'fangsu_psd', 'screendoor', 'xiamen', file + '.json');
  if (!exists(p)) { fail('descriptor missing: ' + path.relative(RES, p)); continue; }
  const json = readJson(p);
  if (!json) continue;
  if (!json.glass) { fail('screendoor/' + file + ': no glass section (forms would be missing)'); continue; }
  const model = checkModel(json.model, 'screendoor/' + file + ' glass');
  const sides = {};
  for (const side of ['left', 'right']) {
    const forms = [];
    for (const e of json.glass[side] || []) {
      if (!e.subModel || !e.shape) continue;    // "auto" 规则条目：没有几何
      forms.push(e.id);
      GLASS_FORM_KEYS.add(e.id);
      if (model && !model.groups.has(e.subModel)) {
        fail('glass ' + file + '/' + side + ' form "' + e.id + '": o group "' + e.subModel + '" not found in ' + json.model);
      }
      if (!e.shape.length || !e.shape.every((b) => Array.isArray(b) && b.length === 6)) {
        fail('glass ' + file + '/' + side + ' form "' + e.id + '": shape must be [[x1,y1,z1,x2,y2,z2], ...]');
      }
    }
    if (forms.length < 2) fail('glass ' + file + '/' + side + ': only ' + forms.length + ' form(s), expected the 4 material forms');
    if (!forms.includes('glass')) fail('glass ' + file + '/' + side + ': no "glass" form (that is the default state)');
    sides[side] = forms;
  }
  // 邻居规则表 glass.auto[]：形状必须对得上 —— 规则里写的 left/right 必须是这一侧真有的形态 id，
  // 写错一个名字就会静默退回兜底形态（看起来「自动没生效」，很难查）。
  const rules = json.glass.auto;
  if (!Array.isArray(rules) || !rules.length) {
    fail('glass ' + file + ': no glass.auto[] rule table (neighbour auto-connect would do nothing)');
  } else {
    let fallback = false;
    for (const rule of rules) {
      if (!rule || rule.if === undefined) { fail('glass ' + file + ' auto: rule without "if"'); continue; }
      if (rule.if === 'TRUE') fallback = true;
      for (const side of ['left', 'right']) {
        const want = rule[side];
        if (want === undefined) continue;
        if (!sides[side].includes(want)) {
          fail('glass ' + file + ' auto: rule "' + JSON.stringify(rule.if) + '" sets ' + side
            + ' = "' + want + '", which is not one of ' + sides[side].join('/'));
        }
      }
    }
    if (!fallback) fail('glass ' + file + ' auto: no {"if": "TRUE"} fallback rule');
    if (!sides.left.includes('glass') || !sides.right.includes('glass')) {
      fail('glass ' + file + ' auto: fallback form "glass" missing');
    }
  }
  // 左半 x ∈ [8,16]、右半 x ∈ [0,8]：两半各 0.5 格拼成一整格。x 跑到另一半上就是「一格还是两格」搞错了。
  for (const side of ['left', 'right']) {
    for (const e of json.glass[side] || []) {
      if (!e.shape) continue;
      const xs = e.shape.map((b) => [b[0], b[3]]);
      if (side === 'left' && xs.some(([x1, x2]) => x2 <= 8)) {
        fail('glass ' + file + '/left form "' + e.id + '": shape x ' + JSON.stringify(xs) + ' does not reach into x 8..16 (left half)');
      }
      if (side === 'right' && xs.some(([x1]) => x1 >= 8)) {
        fail('glass ' + file + '/right form "' + e.id + '": shape x ' + JSON.stringify(xs) + ' does not stay in x 0..8 (right half)');
      }
    }
  }
  blocks.push({ id: 'fangsu_psd_screendoor_' + file + '_glass', kind: 'glass' });
}

// ---------------------------------------------------------------- 2. model json resolver
function modelFile(id) {
  const [ns, p] = id.includes(':') ? id.split(':') : ['minecraft', id];
  return path.join(NS_DIR, ns, 'models', p + '.json');
}
function checkModelJson(id, owner, seen = new Set()) {
  if (seen.has(id)) return;
  seen.add(id);
  // vanilla parents ("item/generated", "block/block", "minecraft:...")：jar 里没有，跳过不查
  if (!id.includes(':') || id.startsWith('minecraft:')) return;
  const file = modelFile(id);
  if (!exists(file)) { fail(owner + ': model json ' + id + ' missing'); return; }
  const json = readJson(file);
  if (!json) return;
  for (const tex of Object.values(json.textures || {})) {
    if (typeof tex !== 'string' || tex.startsWith('#')) continue;
    const texFile = fileOf(tex, 'texture');
    if (texFile && !exists(texFile)) fail(owner + ': model ' + id + ' texture ' + tex + ' missing');
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

// 玻璃板的形态名（刷子循环时动作栏显示的那个）：每种形态两个语言各要一条。
for (const key of GLASS_FORM_KEYS) {
  for (const [label, lang] of [['zh_cn', zh], ['en_us', en]]) {
    const k = 'block.mtrlte.fangsu_psd_glass_form.' + key;
    if (!lang[k]) fail('lang/' + label + ' missing ' + k);
  }
}

// ---------------------------------------------------------------- jar 交叉核对
if (JAR) {
  if (!exists(JAR) && !fs.existsSync(JAR)) {
    fail('jar not found: ' + JAR);
  } else {
    const out = require('child_process').execFileSync('unzip', ['-Z1', JAR], { encoding: 'utf8' });
    const entries = new Set(out.split(/\r?\n/).map((s) => s.trim()).filter(Boolean));
    // jar 里的类文件也算：方块类 + 方块实体渲染器 + 注册点，缺一个都会静默不生效
    for (const cls of ['FangsuPsdBlocks', 'FangsuPsdClient', 'FangsuPsdDef', 'FangsuPsdDoorBlock',
      'FangsuPsdDoorBlock$FangsuPsdDoorEntity', 'FangsuPsdRenderer',
      'FangsuPsdEndDoorBlock', 'FangsuPsdEndDoorBlock$FangsuPsdEndDoorEntity', 'FangsuPsdEndDoorRenderer',
      'FangsuPsdGlassDef', 'FangsuPsdGlassDef$Form', 'FangsuPsdGlassBlock', 'FangsuPsdGlassRenderer',
      'FangsuPsdDraw', 'FangsuPsdProgress', 'FangsuPsdPlacement', 'FangsuPsdInteract']) {
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
