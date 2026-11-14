// 生成饮料售卖机的资源文件（原始模型 / blockstate / 物品模型 / 战利品表）。
// 用法：node tools/gen-drinks-machine.js ["<素材目录>"] ["<resources 目录>"] [--force-texture]
//
// 这一版的三条硬规则（都由本脚本保证，改素材后重跑即可，不要手改产物）：
//   ① UV 消毒：uv 必须是 0..16。素材里 drinks_machine_top 的「顶盖前沿」写了 u2 = 16.5、
//      drinks_machine_bottom_* 的「底座前沿」写了 u2 = 16.25 —— 游戏里实体贴图是 REPEAT 采样，
//      超界的那一条会**绕到贴图左边缘**，而这张 atlas 的左上角正好是一块纯黑（floor / 底座色），
//      于是机器正上方的前沿被染出一道黑边（Blockbench 预览是 clamp，所以看不出来）。
//      这里统一夹到 16 并打印夹了哪几个面。
//   ② 只有一个物品：整台机器只发一个 BlockItem，挂在**下半**（drinks_machine_bottom）。
//      上半照样注册成方块（不放物品），所以顶盖的方块模型只给 BER 用，没有 item 模型。
//   ③ 图标 = 正交渲染出来的整机贴图（tools/gen-drinks-machine-item.js 产出的
//      textures/item/drinks_machine.png），物品模型是 item/generated，不是方块模型 ——
//      方块模型只能摆轴对齐盒子，半台半台地摆进物品栏就是两张半截机器。
const fs = require('fs');
const path = require('path');

const argv = process.argv.slice(2).filter((a) => !a.startsWith('--'));
const SRC = argv[0] || 'C:/Users/AD/Desktop/素材/饮料机';
const RES = argv[1] || path.join(__dirname, '..', 'src', 'main', 'resources');
const FORCE_TEXTURE = process.argv.includes('--force-texture');

const NS = 'mtrlte';
const MODELS = {
  top: { file: 'drinks_machine_top.json', out: 'drinks_machine_top' },
  bottomClosed: { file: 'drinks_machine_bottom_closed.json', out: 'drinks_machine_bottom_closed' },
  bottomOpened: { file: 'drinks_machine_bottom_opened.json', out: 'drinks_machine_bottom_opened' },
};

/** 整台机器唯一的物品（挂在下半）。 */
const ITEM_ID = NS + ':drinks_machine_bottom';

const A = path.join(RES, 'assets', NS);
const D = path.join(RES, 'data', NS);

function writeJson(p, obj, tab) {
  fs.mkdirSync(path.dirname(p), { recursive: true });
  fs.writeFileSync(p, JSON.stringify(obj, null, tab ? '\t' : 2) + '\n', 'utf8');
}

// ---------------------------------------------------------------- ① UV 消毒
let clamped = 0;

/** uv 是 0..16（16 = 整张贴图），越界的夹回来并报出来。 */
function sanitiseUvs(name, model) {
  const notes = [];
  (model.elements || []).forEach((el, i) => {
    const faces = el.faces || {};
    for (const [dir, face] of Object.entries(faces)) {
      if (!Array.isArray(face.uv)) continue;
      const before = face.uv.slice();
      face.uv = face.uv.map((v) => Math.min(16, Math.max(0, v)));
      if (face.uv.some((v, k) => v !== before[k])) {
        clamped++;
        notes.push(`  el#${i} ${dir}: [${before}] → [${face.uv}]`);
      }
    }
  });
  if (notes.length) {
    console.log(`UV 消毒 ${name}:`);
    for (const n of notes) console.log(n);
  }
  return notes.length;
}

// ---------------------------------------------------------------- 原始模型（BER 读）
fs.mkdirSync(path.join(A, 'rawmodels'), { recursive: true });
for (const [key, m] of Object.entries(MODELS)) {
  const model = JSON.parse(fs.readFileSync(path.join(SRC, m.file), 'utf8'));
  const fixed = sanitiseUvs(m.out, model);
  writeJson(path.join(A, 'rawmodels', m.out + '.json'), model);
  console.log(`rawmodel ${m.out.padEnd(28)} ${model.elements.length} elements`
    + (fixed ? `，夹了 ${fixed} 个面` : '，UV 全部合规'));
}

// ---------------------------------------------------------------- 贴图
const texOut = path.join(A, 'textures', 'block', 'drinks_machine.png');
fs.mkdirSync(path.dirname(texOut), { recursive: true });
if (!fs.existsSync(texOut) || FORCE_TEXTURE) {
  fs.copyFileSync(path.join(SRC, 'drinks_machine.png'), texOut);
  console.log('texture drinks_machine.png copied');
} else {
  // 仓库里那份是压过的（420KB → 289KB），别用素材原图盖掉。
  console.log('texture drinks_machine.png already present (use --force-texture to overwrite)');
}

// ---------------------------------------------------------------- blockstate
// 世界里两块都由 BER 画，blockstate 只负责把状态挂上 mtrlte:block/empty。
const facings = ['north', 'east', 'south', 'west'];
const topVariants = {};
for (const f of facings) topVariants['facing=' + f] = { model: NS + ':block/empty' };
writeJson(path.join(A, 'blockstates', 'drinks_machine_top.json'), { variants: topVariants });

const bottomVariants = {};
for (const f of facings) {
  for (const open of ['false', 'true']) {
    bottomVariants['facing=' + f + ',open=' + open] = { model: NS + ':block/empty' };
  }
}
writeJson(path.join(A, 'blockstates', 'drinks_machine_bottom.json'), { variants: bottomVariants });
console.log('blockstates written (top 4 / bottom 8 variants)');

// ---------------------------------------------------------------- 物品模型（一个）
// 图标是整台机器的正交渲染图（tools/gen-drinks-machine-item.js），所以用 item/generated。
writeJson(path.join(A, 'models', 'item', 'drinks_machine_bottom.json'), {
  parent: 'item/generated',
  textures: { layer0: NS + ':item/drinks_machine' },
});
console.log('item model written: models/item/drinks_machine_bottom.json → item/drinks_machine');

// 旧的两个「方块模型当图标」文件已经没人引用，删掉免得下次又被当成模板。
const stale = [
  path.join(A, 'models', 'item', 'drinks_machine_top.json'),
  path.join(A, 'models', 'block', 'drinks_machine_top_item.json'),
  path.join(A, 'models', 'block', 'drinks_machine_bottom_item.json'),
];
for (const p of stale) {
  if (fs.existsSync(p)) {
    fs.unlinkSync(p);
    console.log('removed stale ' + path.relative(RES, p));
  }
}

// ---------------------------------------------------------------- 战利品表
// 整台只有一个物品：拆上半掉的是**下半那个物品**（拆下半同理，另一半被邻居更新收走不掉落）。
for (const id of ['drinks_machine_top', 'drinks_machine_bottom']) {
  writeJson(path.join(D, 'loot_tables', 'blocks', id + '.json'), {
    type: 'minecraft:block',
    pools: [{
      rolls: 1,
      entries: [{ type: 'minecraft:item', name: ITEM_ID }],
      conditions: [{ condition: 'minecraft:survives_explosion' }],
    }],
  });
}
console.log(`loot tables written (both halves drop ${ITEM_ID})`);

if (clamped) {
  console.log(`\n共夹了 ${clamped} 个面的 UV（超界 → 16）。`);
}
