// 校验贴墙标识（wall_sign_*）的静态一致性：模型几何 ↔ blockstate 旋转 ↔ Java 里的 VoxelShape 常量、
// 贴图引用、item 模型、战利品表、lang、pickaxe 标签。不启动游戏。
const fs = require("fs");
const path = require("path");

const VER = process.argv[2] || "v1.20.1";
const ROOT = path.join(VER, "src/main/resources");
const IDS = ["xiamen", "jiaomei", "lte"];
// 与 WallSignBlock 的常量一一对应（1/16 格）
const SHAPE = { minX: 0, maxX: 16, minY: 1, maxY: 15, minZ: 0, maxZ: 0.95 };

const read = (rel) => JSON.parse(fs.readFileSync(path.join(ROOT, rel), "utf8"));
const exists = (rel) => fs.existsSync(path.join(ROOT, rel));

let fails = 0;
const check = (ok, msg) => {
  console.log((ok ? "  ok   " : "  FAIL ") + msg);
  if (!ok) fails++;
};

const rotPoint = (x, z, deg) => {
  switch (((deg % 360) + 360) % 360) {
    case 90: return [16 - z, x];
    case 180: return [16 - x, 16 - z];
    case 270: return [z, 16 - x];
    default: return [x, z];
  }
};

const resolveModel = (ref, depth = 0) => {
  const [ns, name] = ref.split(":").length === 1 ? ["minecraft", ref] : ref.split(":");
  const file = `assets/${ns}/models/${name}.json`;
  if (!exists(file)) return { file, data: null };
  const data = read(file);
  if (data.elements) return { file, data };
  if (data.parent && depth < 8) {
    const parent = resolveModel(data.parent, depth + 1);
    return { file, data: { ...parent.data, ...data, textures: { ...(parent.data.textures || {}), ...(data.textures || {}) }, elements: parent.data.elements } };
  }
  return { file, data };
};

const collectTex = (textures) => new Set(Object.values(textures || {}));

for (const id of IDS) {
  const name = `wall_sign_${id}`;
  console.log(`\n[${name}]`);

  // ---- blockstate：4 个朝向
  const bs = read(`assets/mtrlte/blockstates/${name}.json`).variants;
  check(Object.keys(bs).length === 4, `blockstate 有 4 个 facing 变体 (${Object.keys(bs).join(", ")})`);

  for (const [variant, def] of Object.entries(bs)) {
    const y = def.y || 0;
    const model = resolveModel(def.model);
    check(!!model.data && !!model.data.elements, `${variant}: 模型 ${def.model} 可解析且有 elements (${model.file})`);

    // 旋转后的几何包围盒
    let box = { x1: 99, x2: -99, y1: 99, y2: -99, z1: 99, z2: -99 };
    for (const el of model.data.elements) {
      const [x1, z1] = rotPoint(el.from[0], el.from[2], y);
      const [x2, z2] = rotPoint(el.to[0], el.to[2], y);
      box = {
        x1: Math.min(box.x1, x1, x2), x2: Math.max(box.x2, x1, x2),
        y1: Math.min(box.y1, el.from[1], el.to[1]), y2: Math.max(box.y2, el.from[1], el.to[1]),
        z1: Math.min(box.z1, z1, z2), z2: Math.max(box.z2, z1, z2),
      };
    }
    // 期望：Java 常量按同一套旋转
    const [ex1, ez1] = rotPoint(SHAPE.minX, SHAPE.minZ, y);
    const [ex2, ez2] = rotPoint(SHAPE.maxX, SHAPE.maxZ, y);
    const exp = {
      x1: Math.min(ex1, ex2), x2: Math.max(ex1, ex2),
      y1: SHAPE.minY, y2: SHAPE.maxY,
      z1: Math.min(ez1, ez2), z2: Math.max(ez1, ez2),
    };
    const near = (a, b) => Math.abs(a - b) < 1e-6;
    const same = near(box.x1, exp.x1) && near(box.x2, exp.x2) && near(box.y1, exp.y1) &&
      near(box.y2, exp.y2) && near(box.z1, exp.z1) && near(box.z2, exp.z2);
    // 模型里多出来的 0.05 是 logo 层，允许 z 方向 1 个 0.05
    const near2 = (a, b) => Math.abs(a - b) < 0.051;
    const same2 = near2(box.x1, exp.x1) && near2(box.x2, exp.x2) && near2(box.y1, exp.y1) &&
      near2(box.y2, exp.y2) && near2(box.z1, exp.z1) && near2(box.z2, exp.z2);
    check(same || same2, `${variant}(y=${y}): 模型包围盒 [${box.x1},${box.y1},${box.z1}]→[${box.x2},${box.y2},${box.z2}] 与 VoxelShape [${exp.x1},${exp.y1},${exp.z1}]→[${exp.x2},${exp.y2},${exp.z2}] 一致`);
  }

  // ---- 贴图引用：全部是 mtrlte 自己的，且文件存在
  const anyModel = resolveModel(`mtrlte:block/${name}`);
  const texs = [...collectTex(anyModel.data.textures)];
  for (const tex of texs) {
    check(tex.startsWith("mtrlte:"), `贴图 ${tex} 没有跨模组引用`);
    check(exists(`assets/mtrlte/textures/${tex.split(":")[1]}.png`), `贴图文件存在: ${tex}`);
  }
  check(!JSON.stringify(anyModel.data).includes('"mtr:'), "模型里没有出现 mtr: 前缀");

  // ---- 正面 = +Z（south）面，且 north 面 cullface（贴墙）
  const plate = anyModel.data.elements[0];
  const logo = anyModel.data.elements[1];
  check(plate.faces.north && plate.faces.north.cullface === "north", "底板 north 面 cullface=north（贴墙不画）");
  check(Math.abs(logo.from[2] - logo.to[2]) < 1e-9 && logo.from[2] > plate.to[2], "logo 面是零厚度平面、在底板前面（不 z-fighting）");
  check(Object.keys(logo.faces).length === 1 && !!logo.faces.south, "logo 只画朝外的 south 面");
  // logo 是正方形：12×12，uv 0..16 不拉伸
  check(logo.to[0] - logo.from[0] === logo.to[1] - logo.from[1], "logo 面正方形（贴图不拉伸）");

  // ---- item 模型
  const item = read(`assets/mtrlte/models/item/${name}.json`);
  check(item.parent === `mtrlte:block/${name}`, `item 模型 parent=${item.parent}`);
  check(!!item.display && !!item.display.gui && !!item.display.thirdperson_righthand, "item 模型有 3D display（gui/thirdperson）");

  // ---- 战利品表 / lang / 标签
  check(exists(`data/mtrlte/loot_tables/blocks/${name}.json`), `战利品表存在: ${name}`);
  const zh = read("assets/mtrlte/lang/zh_cn.json");
  const en = read("assets/mtrlte/lang/en_us.json");
  check(!!zh[`block.mtrlte.${name}`] && !!zh[`item.mtrlte.${name}`], "zh_cn 有 block/item 两条翻译");
  check(!!en[`block.mtrlte.${name}`] && !!en[`item.mtrlte.${name}`], "en_us 有 block/item 两条翻译");
  const tag = read("data/minecraft/tags/blocks/mineable/pickaxe.json");
  check(tag.values.includes(`mtrlte:${name}`), "在 mineable/pickaxe 标签里");
}

console.log(fails === 0 ? "\nALL OK" : `\n${fails} FAILURE(S)`);
process.exit(fails === 0 ? 0 : 1);
