// 生成贴墙标识（wall_sign_*）的资产：两个站名出入口模型（从 MTR 4.0.5 拷贝、改命名空间）、
// 三个 blockstate（multipart：2 形态 × 4 朝向）、三个 3D 物品模型、三张战利品表。
//
// 用法：node tools/gen-wall-sign-assets.js [MTR-4.0.5 的 assets/mtr 目录]
// 不给路径就用下面这个默认位置（素材机上的 Blockbench 导出目录）；目录不在就跳过模型拷贝，
// 仓库里那两份已经生成好的文件保持不动。
const fs = require("fs");
const path = require("path");

const ROOT = "D:/MC-MTR-JS/mtr-headlight/v1.20.1/src/main/resources";
const MTR_ASSETS =
  process.argv[2] ||
  "C:/Users/AD/Desktop/blockbench/[我的世界铁路] MTR-fabric-4.0.5+1.20.1/assets/mtr";
const IDS = ["xiamen", "jiaomei", "lte"];

const FACINGS = [
  ["north", 0],
  ["east", 90],
  ["south", 180],
  ["west", 270],
];

// 形态：0 = short（y 4..12）、1 = tall（y 0..16）。与 WallSignBlock.STYLE 对应。
const STYLES = [
  [0, "station_name_entrance_short", "y 4..12"],
  [1, "station_name_entrance_tall", "y 0..16"],
];

const write = (rel, data) => {
  const p = path.join(ROOT, rel);
  fs.mkdirSync(path.dirname(p), { recursive: true });
  // 数字数组压回一行（"from": [0, 4, 0.05]），跟仓库里其它模型 JSON 的排版一致。
  const text = JSON.stringify(data, null, 2).replace(
    /\[\s*\n\s*([^\[\]{}]*?)\s*\n\s*\]/g,
    (_m, inner) => "[" + inner.split(/,\s*/).join(", ") + "]",
  );
  fs.writeFileSync(p, text + "\n");
  console.log("write", rel);
};

// ---------------------------------------------------------------- 1) 模型：MTR 4.0.5 原样拷贝

const MODELS = [
  ["station_name_entrance_tall", "Light", [0, 0, 0.05], [16, 16, 0.05]],
  ["station_name_entrance_short", "Light", [0, 4, 0.05], [16, 12, 0.05]],
];

for (const [name, comment, from, to] of MODELS) {
  const src = path.join(MTR_ASSETS, "models/block", `${name}.json`);
  if (!fs.existsSync(src)) {
    console.log("skip (no source):", src);
    continue;
  }
  const model = JSON.parse(fs.readFileSync(src, "utf8"));
  // 命名空间改写：mtr:block/dark_blue → mtrlte:block/dark_blue（改完不许再出现 mtr:）。
  const textures = {};
  for (const [key, value] of Object.entries(model.textures || {})) {
    textures[key] = value.replace(/^mtr:/, "mtrlte:");
  }
  write(`assets/mtrlte/models/block/${name}.json`, {
    __comment: `MTR 4.0.5 的 mtr:block/${name} 原样拷贝（只把贴图命名空间改成 mtrlte）。零厚度发光板，站名由 WallSignRenderer 画。`,
    textures,
    elements: [
      {
        __comment: comment,
        from,
        to,
        faces: {
          north: { texture: "#particle", cullface: "north" },
          south: { texture: "#particle" },
        },
      },
    ],
  });

  // 贴图一起拷（MTR 自带的那张）
  for (const tex of Object.values(textures)) {
    const file = tex.split(":")[1] + ".png";
    const from2 = path.join(MTR_ASSETS, "textures", file);
    const to2 = path.join(ROOT, "assets/mtrlte/textures", file);
    if (fs.existsSync(from2) && !fs.existsSync(to2)) {
      fs.mkdirSync(path.dirname(to2), { recursive: true });
      fs.copyFileSync(from2, to2);
      console.log("write", path.relative(ROOT, to2));
    }
  }
}

// ---------------------------------------------------------------- 2) blockstate / item / 战利品表

// 3D 物品图标：parent 指向 tall 模型 + display（与站外标志的 item 模型同一套数值）。
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

  // multipart：8 条（style × facing）。link 位掩码不选变体，它只给渲染器算「这条牌子横跨几格」。
  const multipart = [];
  for (const [style, model] of STYLES) {
    for (const [facing, y] of FACINGS) {
      const apply = { model: `mtrlte:block/${model}` };
      if (y !== 0) apply.y = y;
      multipart.push({ when: { style: `${style}`, facing }, apply });
    }
  }
  write(`assets/mtrlte/blockstates/${name}.json`, {
    __comment:
      "站名出入口塔式模型（MTR 4.0.5）：style=0 用 short、style=1 用 tall；facing = 墙在哪一侧，" +
      "y 约定与 station_sign_* / dep_on_wall 一致。link 位掩码不选变体（零厚度的板本来就没有拼接件），" +
      "只给渲染器算「这条牌子横跨几格」。",
    multipart,
  });

  write(`assets/mtrlte/models/item/${name}.json`, {
    parent: "mtrlte:block/station_name_entrance_tall",
    display: DISPLAY,
  });

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
