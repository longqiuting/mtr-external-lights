// 一次性生成：贴墙标识（wall_sign_*）的 blockstate / 3D 物品模型 / 战利品表。
// 三个方块几何完全一样、只有贴图不同（见 models/block/wall_sign*.json）。
const fs = require("fs");
const path = require("path");

const ROOT = "D:/MC-MTR-JS/mtr-headlight/v1.20.1/src/main/resources";
const IDS = ["xiamen", "jiaomei", "lte"];

const FACINGS = [
  ["north", 0],
  ["east", 90],
  ["south", 180],
  ["west", 270],
];

const write = (rel, data) => {
  const p = path.join(ROOT, rel);
  fs.mkdirSync(path.dirname(p), { recursive: true });
  // 数字数组压回一行（"rotation": [30, 45, 0]），跟仓库里其它模型 JSON 的排版一致。
  const text = JSON.stringify(data, null, 2).replace(
    /\[\s*\n\s*([^\[\]{}]*?)\s*\n\s*\]/g,
    (_m, inner) => "[" + inner.split(/,\s*/).join(", ") + "]",
  );
  fs.writeFileSync(p, text + "\n");
  console.log("write", rel);
};

// 3D 物品图标：parent 指向方块模型 + display（与站外标志的 item 模型同一套数值）。
// gui 那一项特殊：原版方块用 225°，那会把模型的 north 面（= 贴墙那一面）转向镜头，
// 我们的牌面在 south 面（+Z），所以用 45°，否则图标里只能看到光秃秃的背板。
const DISPLAY = {
  gui: { rotation: [30, 45, 0], translation: [0, 0, 0], scale: [0.625, 0.625, 0.625] },
  ground: { rotation: [0, 0, 0], translation: [0, 3, 0], scale: [0.25, 0.25, 0.25] },
  fixed: { rotation: [0, 0, 0], translation: [0, 0, 0], scale: [0.5, 0.5, 0.5] },
  thirdperson_righthand: { rotation: [75, 45, 0], translation: [0, 2.5, 0], scale: [0.375, 0.375, 0.375] },
  firstperson_righthand: { rotation: [0, 45, 0], translation: [0, 0, 0], scale: [0.4, 0.4, 0.4] },
  firstperson_lefthand: { rotation: [0, 225, 0], translation: [0, 0, 0], scale: [0.4, 0.4, 0.4] },
};

for (const id of IDS) {
  const name = `wall_sign_${id}`;
  const variants = {};
  for (const [facing, y] of FACINGS) {
    variants[`facing=${facing}`] = y === 0 ? { model: `mtrlte:block/${name}` } : { model: `mtrlte:block/${name}`, y };
  }
  write(`assets/mtrlte/blockstates/${name}.json`, { variants });

  write(`assets/mtrlte/models/item/${name}.json`, { parent: `mtrlte:block/${name}`, display: DISPLAY });

  write(`data/mtrlte/loot_tables/blocks/${name}.json`, {
    type: "minecraft:block",
    pools: [
      {
        rolls: 1,
        bonus_rolls: 0,
        entries: [{ type: "minecraft:item", name: `mtrlte:${name}` }],
        conditions: [{ condition: "minecraft:survives_explosion" }],
      },
    ],
  });
}
