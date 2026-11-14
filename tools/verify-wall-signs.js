// 校验贴墙标识（wall_sign_*）的静态一致性：MTR 4.0.5 站名出入口模型 ↔ blockstate 的
// style × facing 变体 ↔ Java 里的 VoxelShape 常量 / 属性定义、贴图引用、item 模型、战利品表、
// lang、pickaxe 标签。不启动游戏。
const fs = require("fs");
const path = require("path");

const VER = process.argv[2] || "v1.20.1";
const ROOT = path.join(VER, "src/main/resources");
const JAVA = path.join(VER, "src/main/java/dev/js/mtrheadlight/xiamen/sign");
const IDS = ["xiamen", "jiaomei", "lte"];

// 与 WallSignBlock 的常量一一对应（1/16 格）：z 取 0..1（Java 给了一格厚的可选中薄板，
// 模型是零厚度的 0.05，所以 z 方向放 1 格容差）。
const SHAPE = {
  short: { minX: 0, maxX: 16, minY: 4, maxY: 12, minZ: 0, maxZ: 1 },
  tall: { minX: 0, maxX: 16, minY: 0, maxY: 16, minZ: 0, maxZ: 1 },
};
const MODELS = { 0: "station_name_entrance_short", 1: "station_name_entrance_tall" };

const read = (rel) => JSON.parse(fs.readFileSync(path.join(ROOT, rel), "utf8"));
const exists = (rel) => fs.existsSync(path.join(ROOT, rel));
const readText = (p) => fs.readFileSync(p, "utf8");

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
    return {
      file,
      data: {
        ...parent.data,
        ...data,
        textures: { ...(parent.data.textures || {}), ...(data.textures || {}) },
        elements: parent.data.elements,
      },
    };
  }
  return { file, data };
};

const near = (a, b, tol) => Math.abs(a - b) < tol;

// ---------------------------------------------------------------- Java 源头

const blockSrc = readText(path.join(JAVA, "WallSignBlock.java"));
console.log("\n[Java: WallSignBlock]");
check(/IntProperty\.of\("style",\s*0,\s*1\)/.test(blockSrc), '属性 style = IntProperty.of("style", 0, 1)（0 矮 / 1 高）');
check(/IntProperty\.of\("link",\s*0,\s*63\)/.test(blockSrc), '属性 link = IntProperty.of("link", 0, 63)（6 位掩码）');
check(/1 << direction\.getId\(\)/.test(blockSrc), "位 = 1 << Direction#getId()");
check(/checkHoldingBrush\(world, player/.test(blockSrc), "刷子 = IBlock.checkHoldingBrush（MTR 的画笔，自带服务端守卫）");
check(/\(state\.get\(STYLE\) \+ 1\) % 2/.test(blockSrc), "刷子循环 = style 0 → 1 → 0");
check(/rotateYClockwise\(\)/.test(blockSrc) && /rotateYCounterclockwise\(\)/.test(blockSrc), "切换后沿墙向左右两侧传播");
check(/computeLink\(/.test(blockSrc) && /getStateForNeighborUpdate/.test(blockSrc), "邻居更新里重算连接掩码");
check(/VoxelShapes\.empty\(\)/.test(blockSrc), "碰撞形状为空（薄牌面不挡人，与 MTR 一致）");

const rendererSrc = readText(path.join(JAVA, "WallSignRenderer.java"));
console.log("\n[Java: WallSignRenderer]");
check(/RailwayData\.getStation\(ClientData\.STATIONS,\s*ClientData\.DATA_CACHE,\s*pos\)/.test(rendererSrc),
  "站名 = RailwayData.getStation(ClientData.STATIONS, ClientData.DATA_CACHE, pos)");
check(/gui\.mtr\.untitled/.test(rendererSrc), "没车站时用 MTR 的 gui.mtr.untitled");
check(/textRenderer\.draw\(/.test(rendererSrc), "站名落笔走 TextRenderer.draw");
check(/run\.anchor\(\)/.test(rendererSrc), "只有连接组里左下角那一格落笔（站名不重复）");

// ---------------------------------------------------------------- 每个方块

for (const id of IDS) {
  const name = `wall_sign_${id}`;
  console.log(`\n[${name}]`);

  // ---- blockstate：multipart，8 条（2 形态 × 4 朝向）
  const bs = read(`assets/mtrlte/blockstates/${name}.json`);
  const parts = bs.multipart || [];
  check(Array.isArray(parts) && parts.length === 8, `blockstate 是 multipart 且有 8 条（2 形态 × 4 朝向），实际 ${parts.length}`);

  for (const part of parts) {
    const when = part.when || {};
    const style = Number(when.style);
    const facing = when.facing;
    const y = (part.apply || {}).y || 0;
    const expectedModel = `mtrlte:block/${MODELS[style]}`;
    check((part.apply || {}).model === expectedModel,
      `style=${when.style}/${facing}: 指向 ${expectedModel}`);

    const model = resolveModel(expectedModel);
    check(!!model.data && !!model.data.elements, `  模型 ${expectedModel} 可解析且有 elements`);

    // 旋转后的几何包围盒
    const shape = style === 0 ? SHAPE.short : SHAPE.tall;
    let box = { x1: 99, x2: -99, y1: 99, y2: -99, z1: 99, z2: -99 };
    for (const el of model.data.elements) {
      for (const corner of [el.from, el.to]) {
        const [x, z] = rotPoint(corner[0], corner[2], y);
        box = {
          x1: Math.min(box.x1, x), x2: Math.max(box.x2, x),
          y1: Math.min(box.y1, corner[1]), y2: Math.max(box.y2, corner[1]),
          z1: Math.min(box.z1, z), z2: Math.max(box.z2, z),
        };
      }
    }
    // 模型是零厚度的一张纸，旋转 90/180/270 之后在那个轴上会退化成一条线（0.05 或 15.95），
    // 所以 x / z 只要求「落在 Java 形状里、容差 1 格」；y 必须精确相等（高矮形态就靠它区分）。
    // Java 的形状常量写在**模型空间**（未旋转），这里用同一套 rotPoint 把它转到当前变体的朝向上再比。
    const [sx1, sz1] = rotPoint(shape.minX, shape.minZ, y);
    const [sx2, sz2] = rotPoint(shape.maxX, shape.maxZ, y);
    const exp = {
      x1: Math.min(sx1, sx2), x2: Math.max(sx1, sx2),
      z1: Math.min(sz1, sz2), z2: Math.max(sz1, sz2),
    };
    const okX = box.x1 >= exp.x1 - 1 && box.x2 <= exp.x2 + 1;
    const okY = near(box.y1, shape.minY, 1e-6) && near(box.y2, shape.maxY, 1e-6);
    const okZ = box.z1 >= exp.z1 - 1 && box.z2 <= exp.z2 + 1;
    check(okX && okY && okZ,
      `  y=${y}: 包围盒 [${box.x1},${box.y1},${box.z1}]→[${box.x2},${box.y2},${box.z2}] 落在 VoxelShape 常量内（y 精确）`);
  }

  // ---- 贴图引用：全部是 mtrlte 自己的，且文件存在；模型里不许有 mtr: 前缀
  for (const style of [0, 1]) {
    const model = resolveModel(`mtrlte:block/${MODELS[style]}`);
    check(!JSON.stringify(model.data).includes('"mtr:'), `${MODELS[style]}: 模型里没有 mtr: 前缀`);
    for (const tex of Object.values(model.data.textures || {})) {
      check(tex.startsWith("mtrlte:"), `${MODELS[style]}: 贴图 ${tex} 没有跨模组引用`);
      check(exists(`assets/mtrlte/textures/${tex.split(":")[1]}.png`), `${MODELS[style]}: 贴图文件存在 ${tex}`);
    }
    const plate = model.data.elements[0];
    check(plate.faces.north && plate.faces.north.cullface === "north", `${MODELS[style]}: north 面 cullface=north（贴墙不画）`);
    check(Math.abs(plate.from[2] - plate.to[2]) < 1e-9, `${MODELS[style]}: 零厚度（与 MTR 原模型一致）`);
  }

  // ---- item 模型
  const item = read(`assets/mtrlte/models/item/${name}.json`);
  check(item.parent === "mtrlte:block/station_name_entrance_tall", `item 模型 parent=${item.parent}`);
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
