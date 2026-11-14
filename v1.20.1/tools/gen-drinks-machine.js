// 生成饮料售卖机的资源文件（blockstate / 物品模型 / 原始模型 / 战利品表）。
// 用法：node tools/gen-drinks-machine.js "<素材目录>" "<resources 目录>"
const fs = require('fs');
const path = require('path');

const SRC = process.argv[2] || 'C:/Users/AD/Desktop/素材/饮料机';
const RES = process.argv[3] || path.join(__dirname, '..', 'src', 'main', 'resources');

const NS = 'mtrlte';
const MODELS = {
  top: { file: 'drinks_machine_top.json', out: 'drinks_machine_top' },
  bottomClosed: { file: 'drinks_machine_bottom_closed.json', out: 'drinks_machine_bottom_closed' },
  bottomOpened: { file: 'drinks_machine_bottom_opened.json', out: 'drinks_machine_bottom_opened' },
};

function writeJson(p, obj, tab) {
  fs.mkdirSync(path.dirname(p), { recursive: true });
  fs.writeFileSync(p, JSON.stringify(obj, null, tab ? '\t' : 2) + '\n', 'utf8');
}

// ---------------------------------------------------------------- 原始模型（BER 读）
for (const [key, m] of Object.entries(MODELS)) {
  const raw = fs.readFileSync(path.join(SRC, m.file), 'utf8');
  fs.mkdirSync(path.join(RES, 'assets', NS, 'rawmodels'), { recursive: true });
  fs.writeFileSync(path.join(RES, 'assets', NS, 'rawmodels', m.out + '.json'), raw, 'utf8');
  console.log('rawmodel', m.out, JSON.parse(raw).elements.length, 'elements');
}

// ---------------------------------------------------------------- 贴图
fs.mkdirSync(path.join(RES, 'assets', NS, 'textures', 'block'), { recursive: true });
fs.copyFileSync(path.join(SRC, 'drinks_machine.png'),
  path.join(RES, 'assets', NS, 'textures', 'block', 'drinks_machine.png'));
console.log('texture drinks_machine.png copied');

// ---------------------------------------------------------------- blockstate
const facings = ['north', 'east', 'south', 'west'];
const topVariants = {};
for (const f of facings) topVariants['facing=' + f] = { model: NS + ':block/empty' };
writeJson(path.join(RES, 'assets', NS, 'blockstates', 'drinks_machine_top.json'), { variants: topVariants });

const bottomVariants = {};
for (const f of facings) {
  for (const open of ['false', 'true']) {
    bottomVariants['facing=' + f + ',open=' + open] = { model: NS + ':block/empty' };
  }
}
writeJson(path.join(RES, 'assets', NS, 'blockstates', 'drinks_machine_bottom.json'), { variants: bottomVariants });
console.log('blockstates written (top 4 / bottom 8 variants)');

// ---------------------------------------------------------------- 物品的方块模型
// 素材是 Blockbench 导出的 elements，只能挂一个贴图：把每个面的 texture 改成 #tex。
// parent 用 block/block（= 不带任何旋转/缩放的方块基底）。
const itemDisplay = {
  gui: { rotation: [30, 225, 0], translation: [0, 2, 0], scale: [0.33, 0.33, 0.33] },
  ground: { rotation: [0, 0, 0], translation: [0, 3, 0], scale: [0.2, 0.2, 0.2] },
  fixed: { rotation: [0, 0, 0], translation: [0, 0, 0], scale: [0.4, 0.4, 0.4] },
  thirdperson_righthand: { rotation: [75, 45, 0], translation: [0, 2.5, 0], scale: [0.25, 0.25, 0.25] },
  thirdperson_lefthand: { rotation: [75, 135, 0], translation: [0, 2.5, 0], scale: [0.25, 0.25, 0.25] },
  firstperson_righthand: { rotation: [0, 45, 0], translation: [0, 0, 0], scale: [0.3, 0.3, 0.3] },
  firstperson_lefthand: { rotation: [0, 225, 0], translation: [0, 0, 0], scale: [0.3, 0.3, 0.3] },
};

function blockModelFrom(modelFile, outName) {
  const d = JSON.parse(fs.readFileSync(path.join(SRC, modelFile), 'utf8'));
  const elements = (d.elements || []).map((el) => {
    const faces = {};
    for (const [dir, face] of Object.entries(el.faces || {})) {
      faces[dir] = Object.assign({}, face, { texture: '#tex' });
    }
    const copy = Object.assign({}, el, { faces });
    return copy;
  });
  writeJson(path.join(RES, 'assets', NS, 'models', 'block', outName + '.json'), {
    parent: 'block/block',
    textures: { particle: NS + ':block/drinks_machine', tex: NS + ':block/drinks_machine' },
    elements,
  }, true);
  console.log('block model', outName, elements.length, 'elements');
}

blockModelFrom('drinks_machine_top.json', 'drinks_machine_top_item');
blockModelFrom('drinks_machine_bottom_closed.json', 'drinks_machine_bottom_item');

// ---------------------------------------------------------------- 物品模型
for (const id of ['drinks_machine_top', 'drinks_machine_bottom']) {
  writeJson(path.join(RES, 'assets', NS, 'models', 'item', id + '.json'), {
    parent: NS + ':block/' + id + '_item',
    display: itemDisplay,
  });
}
console.log('item models written');

// ---------------------------------------------------------------- 战利品表（掉自己）
for (const id of ['drinks_machine_top', 'drinks_machine_bottom']) {
  writeJson(path.join(RES, 'data', NS, 'loot_tables', 'blocks', id + '.json'), {
    type: 'minecraft:block',
    pools: [{
      rolls: 1,
      entries: [{ type: 'minecraft:item', name: NS + ':' + id }],
      conditions: [{ condition: 'minecraft:survives_explosion' }],
    }],
  });
}
console.log('loot tables written');
