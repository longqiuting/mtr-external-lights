// Generate the block/item/loot/lang resources for the 14 fangsu PSD door blocks.
// Ids and names must stay in sync with FangsuPsdDef.java (same derivation rules).
const fs = require('fs');
const path = require('path');

const RES = process.argv[2]; // .../src/main/resources
const ASSETS = path.join(RES, 'assets/mtrlte');
const PSD = path.join(ASSETS, 'fangsu_psd');
const DATA = path.join(RES, 'data/mtrlte');

// ---------------------------------------------------------------- definitions
const DEFS = [];

function readJson(p) { return JSON.parse(fs.readFileSync(p, 'utf8')); }

function duanmenNames(file, right) {
  const m = {
    brtduanmen: ['方速端门 BRT', 'Fangsu end door BRT'],
    mtrduanmen: ['方速端门 MTR', 'Fangsu end door MTR'],
    drlduanmen: ['方速端门 DRL', 'Fangsu end door DRL'],
    shlowduanmen: ['方速端门 SH 低', 'Fangsu end door SH low'],
    shhighduanmen: ['方速端门 SH 高', 'Fangsu end door SH high'],
  }[file];
  return [m[0] + (right ? '（右）' : '（左）'), m[1] + (right ? ' (right)' : ' (left)')];
}

for (const file of ['brtduanmen', 'mtrduanmen', 'drlduanmen', 'shlowduanmen', 'shhighduanmen']) {
  const json = readJson(path.join(PSD, 'duanmen', file + '.json'));
  for (const entry of json.content) {
    const right = entry.id.endsWith('_right');
    const [zh, en] = duanmenNames(file, right);
    DEFS.push({
      id: 'fangsu_psd_' + entry.id,
      zh, en,
      obj: entry.model.replace('mtrlte:', ''),
      groups: [entry.subModels.main, entry.subModels.door],
      flipV: entry.flipV !== false,
    });
  }
}

for (const file of ['low', 'high']) {
  const json = readJson(path.join(PSD, 'screendoor/xiamen', file + '.json'));
  const label = file === 'low' ? '低' : '高';
  const labelEn = file === 'low' ? 'low' : 'high';
  for (const side of ['left', 'right']) {
    const groups = [];
    for (const entry of json.door[side]) {
      for (const door of entry.doors || []) groups.push(door.subModel);
    }
    if (!groups.length) continue;
    DEFS.push({
      id: 'fangsu_psd_screendoor_' + file + '_' + side,
      zh: '方速屏蔽门 厦门' + label + '（' + (side === 'left' ? '左' : '右') + '）',
      en: 'Fangsu platform screen door Xiamen ' + labelEn + ' (' + side + ')',
      obj: json.model.replace('mtrlte:', ''),
      groups,
      flipV: json.flipV !== false,
    });
  }
}

// ---------------------------------------------------------------- write blockstates
const BS_DIR = path.join(ASSETS, 'blockstates');
const MODEL_DIR = path.join(ASSETS, 'models/block');
const ITEM_DIR = path.join(ASSETS, 'models/item');
const LOOT_DIR = path.join(DATA, 'loot_tables/blocks');
for (const d of [BS_DIR, MODEL_DIR, ITEM_DIR, LOOT_DIR]) fs.mkdirSync(d, { recursive: true });

// shared particle-only block model (geometry is drawn by the BER, like MTR's own PSD doors)
fs.writeFileSync(path.join(MODEL_DIR, 'fangsu_psd_particle_only.json'),
  JSON.stringify({ textures: { particle: 'mtrlte:fangsu_psd/duanmen/mtrduanmen' } }, null, 2) + '\n');

for (const def of DEFS) {
  fs.writeFileSync(path.join(BS_DIR, def.id + '.json'),
    JSON.stringify({ variants: { '': { model: 'mtrlte:block/fangsu_psd_particle_only' } } }, null, 2) + '\n');

  // Item icon: a 64x64 sprite rasterised from the real .obj — written by gen-item-sprites.js
  // at the bottom of this script (it owns both textures/item/fangsu_psd/<name>.png and
  // models/item/<id>.json). 不做「一个 o 组一个立方体」的方块模型图标了：方块模型只能描述
  // 轴对齐盒子，把一整个 uv 矩形贴到 6 个面上，物品栏里就是一片糊掉的图集。

  // loot: only the lower half drops an item
  fs.writeFileSync(path.join(LOOT_DIR, def.id + '.json'), JSON.stringify({
    type: 'minecraft:block',
    pools: [{
      rolls: 1,
      entries: [{
        type: 'minecraft:item',
        name: 'mtrlte:' + def.id,
        conditions: [{ condition: 'minecraft:block_state_property', block: 'mtrlte:' + def.id, properties: { half: 'lower' } }],
      }],
      conditions: [{ condition: 'minecraft:survives_explosion' }],
    }],
  }, null, 2) + '\n');
}

// ---------------------------------------------------------------- item icons
const sprites = require('./gen-item-sprites').generate(DEFS, RES);

// ---------------------------------------------------------------- lang
function addLang(file, pick) {
  const p = path.join(ASSETS, 'lang', file);
  if (!fs.existsSync(p)) { console.log('missing lang ' + file); return; }
  const json = JSON.parse(fs.readFileSync(p, 'utf8'));
  for (const def of DEFS) json['block.mtrlte.' + def.id] = pick(def);
  const sorted = {};
  for (const key of Object.keys(json).sort()) sorted[key] = json[key];
  fs.writeFileSync(p, JSON.stringify(sorted, null, 2) + '\n');
}
addLang('zh_cn.json', (d) => d.zh);
addLang('en_us.json', (d) => d.en);

console.log(DEFS.length + ' blocks generated (' + sprites.length + ' item sprites):');
for (const d of DEFS) console.log('  ' + d.id + '  ' + d.zh + '  <- ' + d.obj);
