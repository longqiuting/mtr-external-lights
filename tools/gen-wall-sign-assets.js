// 生成贴墙标识（wall_sign_*）的全部静态资产 —— 不读任何 MTR 的文件，自己写：
//   · 两个基础模型 station_name_entrance_{tall,short}：灯板（自有底图）+ 品牌 logo quad
//   · 每个品牌一份子模型 wall_sign_<id>_{tall,short}：只覆盖 logo 贴图
//   · 三个 blockstate（multipart：2 形态 × 4 朝向，属性名照 MTR 的 propagate_property）
//   · 三个 3D 物品模型、三张战利品表
//   · 底图 textures/block/station_name_background.png（只在缺失时生成）
//
// 用法：node tools/gen-wall-sign-assets.js
// 校验：node tools/verify-wall-signs.js v1.20.1
const fs = require("fs");
const path = require("path");
const zlib = require("zlib");

const VER = process.argv[2] || "v1.20.1";
const ROOT = path.join(VER, "src/main/resources");
const IDS = ["xiamen", "jiaomei", "lte"];

const FACINGS = [
  ["north", 0],
  ["east", 90],
  ["south", 180],
  ["west", 270],
];

// 形态：0 = short（灯板 y 4..12）、1 = tall（灯板 y 0..16）。与 BlockStationNameEntrance.STYLE 对应。
const STYLES = [
  [0, "short", [0, 4, 0.05], [16, 12, 0.05], 6],
  [1, "tall", [0, 0, 0.05], [16, 16, 0.05], 12],
];

// 灯板：z = 0.05 px 的零厚度板；正面（south / +Z）是牌面，背面 north 带 cullface 贴墙。
// logo：z = 0.1 px（板前面、站名平面 0.15 后面），右侧 5x5 px，竖直居中（y 5.5..10.5，中心 y=8）。
// 站名带由 WallSignRenderer 画在 x -0.46875..0.125（0.5..10.0 px），所以 logo 从 10.5 px 起、不重叠。
const BOARD_TEX = "mtrlte:block/station_name_background";
const PLATE_Z = 0.05;
const LOGO_Z = 0.1;
const LOGO_FROM = [10.5, 5.5, LOGO_Z];
const LOGO_TO = [15.5, 10.5, LOGO_Z];

// 每个品牌的 blockstate 注释（沿用仓库里已有的措辞，重跑本脚本不会产生无意义 diff）。
const BRAND_LABEL = { xiamen: "厦门地铁", jiaomei: "角美地铁", lte: "LTE" };
const BLOCKSTATE_COMMENT = {
  xiamen:
    "照 MTR 3.6.4.2 的 blockstates/station_name_entrance.json：属性名就是它的 propagate_property（0=矮板 / 1=高板），facing = 墙在哪一侧。2..5 是 MTR 的「无灯板」形态（只有字），照 MTR 一样不挂模型。模型换成带厦门地铁 logo 的那一份。",
  jiaomei:
    "与 wall_sign_xiamen.json 同一套（MTR 的 station_name_entrance：propagate_property 0=矮 / 1=高，2..5 无灯板），模型换成带角美地铁 logo 的那一份。",
  lte: "与 wall_sign_xiamen.json 同一套（MTR 的 station_name_entrance：propagate_property 0=矮 / 1=高，2..5 无灯板），模型换成带 LTE logo 的那一份。",
};

const write = (rel, data) => {
  const p = path.join(ROOT, rel);
  fs.mkdirSync(path.dirname(p), { recursive: true });
  // 数字数组压回一行（"from": [0, 4, 0.05]），跟仓库里其它模型 JSON 的排版一致。
  const text = JSON.stringify(data, null, 2).replace(
    /\[\s*\n\s*([^[\]{}]*?)\s*\n\s*\]/g,
    (_m, inner) => "[" + inner.split(/,\s*/).join(", ") + "]",
  );
  fs.writeFileSync(p, text + "\n");
  console.log("write", rel);
};

// ---------------------------------------------------------------- 1) 基础模型

for (const [, form, from, to, textPx] of STYLES) {
  const name = `station_name_entrance_${form}`;
  const tall = form === "tall";
  write(`assets/mtrlte/models/block/${name}.json`, {
    __comment: tall
      ? "站名出入口「高板」的几何：一块 16x16 的零厚度灯板 + 一块品牌 logo。贴图全部是自己的（station_name_background / *_logo），没有任何 MTR 命名空间的引用。站名本身不在这里 —— 由 WallSignRenderer 按 STYLE 在灯板正面 z≈0.15 px 处画，只占左边 0.5..10.0 px 那条，右边 10.5..15.5 px 留给 logo。"
      : "站名出入口「矮板」的几何：一块 16x8 的零厚度灯板（y 4..12）+ 一块品牌 logo。贴图全部是自己的（station_name_background / *_logo），没有任何 MTR 命名空间的引用。站名由 WallSignRenderer 按 STYLE 画在灯板正面 z≈0.15 px 处，只占左边 0.5..10.0 px，右边 10.5..15.5 px 留给 logo。",
    textures: {
      particle: BOARD_TEX,
      board: BOARD_TEX,
      logo: "mtrlte:block/xiamen_logo", // 默认值，三个品牌的子模型各自覆盖
    },
    elements: [
      {
        __comment: tall
          ? "灯板：z=0.05 px 两面（正面 south=+z 是牌面；背面 north 贴墙，带 cullface 让墙挡住时省一层）。"
          : "灯板：y 4..12、z=0.05 px 两面（正面 south=+z；背面 north 带 cullface）。",
        from,
        to,
        faces: {
          north: { texture: "#board", cullface: "north" },
          south: { texture: "#board" },
        },
      },
      {
        __comment: tall
          ? "品牌 logo：z=0.1 px（在灯板 0.05 前面、站名 0.15 后面，且 x 不与站名那条重叠），右侧 5x5 px 竖直居中。挖空 PNG → 方块走 cutout。"
          : "品牌 logo：z=0.1 px，右侧 5x5 px 竖直居中（矮板 8 px 高，5 px 的 logo 上下各留 1.5 px）。",
        from: LOGO_FROM,
        to: LOGO_TO,
        faces: {
          south: { texture: "#logo", shade: false },
        },
      },
    ],
  });
  void textPx;
  void PLATE_Z;
}

// ---------------------------------------------------------------- 2) 每个品牌

// 3D 物品图标：parent 指向自己那份 tall 模型 + display（与站外标志的 item 模型同一套数值）。
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

  // 子模型：几何全在父模型里，这里只覆盖 logo 贴图。
  for (const [, form] of STYLES) {
    const label = form === "tall" ? "高板" : "矮板";
    write(`assets/mtrlte/models/block/${name}_${form}.json`, {
      __comment: `站名出入口「${label}」+ ${BRAND_LABEL[id]} logo。几何在父模型里（照 MTR 的 station_name_entrance_${form}），这里只换 logo 贴图。`,
      parent: `mtrlte:block/station_name_entrance_${form}`,
      textures: { logo: `mtrlte:block/${id}_logo` },
    });
  }

  // blockstate：multipart 8 条（2 形态 × 4 朝向）。2..5 是 MTR 的「无灯板」形态，照 MTR 一样不挂模型。
  // 排版手写（when/apply 压成一行），跟仓库里其它 blockstate 一致。
  const rows = [];
  for (const [style, form] of STYLES) {
    for (const [facing, y] of FACINGS) {
      const when = `{ "facing": "${facing}", "propagate_property": "${style}" }`;
      const apply =
        y === 0
          ? `{ "model": "mtrlte:block/${name}_${form}" }`
          : `{ "model": "mtrlte:block/${name}_${form}", "y": ${y} }`;
      rows.push(`    {\n      "when": ${when},\n      "apply": ${apply}\n    }`);
    }
  }
  const p = path.join(ROOT, `assets/mtrlte/blockstates/${name}.json`);
  fs.mkdirSync(path.dirname(p), { recursive: true });
  fs.writeFileSync(p, `{\n  "__comment": "${BLOCKSTATE_COMMENT[id]}",\n  "multipart": [\n${rows.join(",\n")}\n  ]\n}\n`);
  console.log("write", `assets/mtrlte/blockstates/${name}.json`);

  write(`assets/mtrlte/models/item/${name}.json`, {
    parent: `mtrlte:block/${name}_tall`,
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

// ---------------------------------------------------------------- 3) 底图（只在缺失时生成）

const bgPath = path.join(ROOT, "assets/mtrlte/textures/block/station_name_background.png");
if (fs.existsSync(bgPath)) {
  console.log("skip (exists) assets/mtrlte/textures/block/station_name_background.png");
} else {
  fs.mkdirSync(path.dirname(bgPath), { recursive: true });
  fs.writeFileSync(bgPath, stationNameBackgroundPng(16));
  console.log("write assets/mtrlte/textures/block/station_name_background.png");
}

/** 16x16 的站名牌底：深藏青填充 + 顶面亮一档 / 底面暗一档 + 1 px 内框（多格并排时看得出是一块块面板）。 */
function stationNameBackgroundPng(size) {
  const base = [0x16, 0x23, 0x3a, 0xff];
  const topLine = [0x2b, 0x40, 0x66, 0xff];
  const bottomLine = [0x0c, 0x15, 0x24, 0xff];
  const frame = [0x21, 0x34, 0x54, 0xff];
  const raw = Buffer.alloc((size * 4 + 1) * size);
  for (let y = 0; y < size; y++) {
    raw[y * (size * 4 + 1)] = 0; // filter: none
    for (let x = 0; x < size; x++) {
      let c = base;
      if (y === 0) c = topLine;
      if (y === size - 1) c = bottomLine;
      if (x === 0 || x === size - 1) c = frame;
      if (y === 0 && (x === 0 || x === size - 1)) c = frame;
      c.copy(raw, y * (size * 4 + 1) + 1 + x * 4);
    }
  }
  const chunk = (type, data) => {
    const len = Buffer.alloc(4);
    len.writeUInt32BE(data.length);
    const body = Buffer.concat([Buffer.from(type, "ascii"), data]);
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(crc32(body) >>> 0);
    return Buffer.concat([len, body, crc]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0);
  ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 6; // RGBA
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk("IHDR", ihdr),
    chunk("IDAT", zlib.deflateSync(raw, { level: 9 })),
    chunk("IEND", Buffer.alloc(0)),
  ]);
}

let crcTable = null;
function crc32(buf) {
  if (!crcTable) {
    crcTable = new Int32Array(256);
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      crcTable[n] = c;
    }
  }
  let c = -1;
  for (const b of buf) c = crcTable[(c ^ b) & 0xff] ^ (c >>> 8);
  return c ^ -1;
}
