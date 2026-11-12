#!/usr/bin/env node
// 扶手包围盒 / 资源接线自检（1.20.1）。
//
// 校验三件事：
//   1. Java 里 RailingWallBlocks / RailingGlassBlocks 声明的每个 Variant 都有对应的模型文件；
//   2. 模型里<b>每个元素的真实包围盒</b>（含 rotation 旋转后）都被该段的粗包围盒裹住 ——
//      漏出去一小块就会变成「选不上的黑块」，比富余难看得多；
//   3. blockstate / 物品模型 / 掉落表 / 语言键 / 配方 都在（缺了方块就没法从创造页签拿到或拆了不掉）。
//
// 用法：node tools/check-railing-shapes.js      （在 v1.20.1 目录下）
const fs = require('fs');
const path = require('path');

const SRC = 'src/main/resources/assets/mtrlte';
const DATA = 'src/main/resources/data/mtrlte';
const EPS = 0.1;

const FAMILIES = [
  { name: '墙上扶手', java: 'src/main/java/dev/js/mtrheadlight/railing/RailingWallBlocks.java' },
  // 玻璃段素材把玻璃画到 y=-5（顺着楼梯扎进下一格），碰撞 / 框选盒有意从 y=0 起，校验时按 0 处理。
  { name: '玻璃扶手', java: 'src/main/java/dev/js/mtrheadlight/railing/RailingGlassBlocks.java', clampBelowFloor: true },
];

/** Blockbench 元素 → 旋转后的 8 个角点（rotation 的 axis/origin 与 Blockbench 一致）。 */
function corners(el) {
  const [x1, y1, z1] = el.from;
  const [x2, y2, z2] = el.to;
  const pts = [];
  for (const x of [x1, x2]) for (const y of [y1, y2]) for (const z of [z1, z2]) pts.push([x, y, z]);
  const r = el.rotation;
  if (!r || !r.angle) return pts;
  const rad = (r.angle * Math.PI) / 180;
  const c = Math.cos(rad), s = Math.sin(rad);
  const [ox, oy, oz] = r.origin;
  return pts.map(([x, y, z]) => {
    let dx = x - ox, dy = y - oy, dz = z - oz;
    if (r.axis === 'x') { const ny = dy * c - dz * s, nz = dy * s + dz * c; dy = ny; dz = nz; }
    else if (r.axis === 'y') { const nx = dx * c + dz * s, nz = -dx * s + dz * c; dx = nx; dz = nz; }
    else { const nx = dx * c - dy * s, ny = dx * s + dy * c; dx = nx; dy = ny; }
    return [ox + dx, oy + dy, oz + dz];
  });
}

function aabb(pts) {
  return [
    Math.min(...pts.map((p) => p[0])), Math.min(...pts.map((p) => p[1])), Math.min(...pts.map((p) => p[2])),
    Math.max(...pts.map((p) => p[0])), Math.max(...pts.map((p) => p[1])), Math.max(...pts.map((p) => p[2])),
  ];
}

function inside(inner, outer) {
  return inner[0] >= outer[0] - EPS && inner[1] >= outer[1] - EPS && inner[2] >= outer[2] - EPS
    && inner[3] <= outer[3] + EPS && inner[4] <= outer[4] + EPS && inner[5] <= outer[5] + EPS;
}

function fmt(b) {
  const r = (v) => (Math.round(v * 1000) / 1000).toFixed(2);
  return `x=${r(b[0])}..${r(b[3])} y=${r(b[1])}..${r(b[4])} z=${r(b[2])}..${r(b[5])}`;
}

/** 从 Java 源码里抽出 `new Variant("id", "model", boxes(box(..), box(..)))`。 */
function variants(javaFile) {
  const src = fs.readFileSync(javaFile, 'utf8');
  const out = [];
  const re = /new Variant\("([^"]+)",\s*"([^"]+)",\s*boxes\(((?:[^()]|\([^()]*\))*)\)/g;
  let m;
  while ((m = re.exec(src)) !== null) {
    const boxes = [];
    const bre = /box\(([^)]*)\)/g;
    let b;
    while ((b = bre.exec(m[3])) !== null) {
      const n = b[1].split(',').map((v) => Number(v.trim()));
      if (n.length !== 6 || n.some((v) => Number.isNaN(v))) throw new Error(`box 解析失败: ${b[0]}`);
      boxes.push(n);
    }
    out.push({ id: m[1], model: m[2], boxes });
  }
  return out;
}

const problems = [];
let checked = 0;

for (const family of FAMILIES) {
  const list = variants(family.java);
  if (list.length === 0) problems.push(`${family.name}: 没从 ${family.java} 解析出任何 Variant`);

  for (const v of list) {
    const modelPath = path.join(SRC, 'models/block', v.model + '.json');
    const bsPath = path.join(SRC, 'blockstates', v.id + '.json');
    const itemPath = path.join(SRC, 'models/item', v.id + '.json');
    const lootPath = path.join(DATA, 'loot_tables/blocks', v.id + '.json');

    for (const p of [modelPath, bsPath, itemPath, lootPath]) {
      if (!fs.existsSync(p)) problems.push(`${v.id}: 缺少 ${p}`);
    }
    if (!fs.existsSync(modelPath)) continue;

    const model = JSON.parse(fs.readFileSync(modelPath, 'utf8'));
    for (const [key, tex] of Object.entries(model.textures || {})) {
      if (typeof tex !== 'string' || !tex.includes(':')) continue;
      const [ns, p] = tex.split(':');
      const texPath = path.join('src/main/resources/assets', ns, 'textures', p + '.png');
      if (!fs.existsSync(texPath)) problems.push(`${v.id}: 贴图 #${key}=${tex} 不存在（${texPath}）`);
    }

    const bs = JSON.parse(fs.readFileSync(bsPath, 'utf8'));
    for (const [state, def] of Object.entries(bs.variants || {})) {
      if (def.model !== `mtrlte:block/${v.model}`) problems.push(`${v.id}: blockstate ${state} 指向 ${def.model}`);
    }
    for (const f of ['west', 'north', 'east', 'south']) {
      if (!bs.variants?.[`facing=${f}`]) problems.push(`${v.id}: blockstate 缺 facing=${f}`);
    }

    (model.elements || []).forEach((el, i) => {
      const box = aabb(corners(el));
      if (family.clampBelowFloor && box[1] < 0) box[1] = 0;
      checked++;
      if (!v.boxes.some((b) => inside(box, b))) {
        problems.push(`${v.id} (${v.model}) 元素 #${i} 超出包围盒: 元素 ${fmt(box)} / 盒子 ${v.boxes.map(fmt).join(' | ')}`);
      }
    });
  }

  // 语言键
  for (const lang of ['zh_cn', 'en_us']) {
    const l = JSON.parse(fs.readFileSync(path.join(SRC, `lang/${lang}.json`), 'utf8'));
    for (const v of list) {
      if (!l[`block.mtrlte.${v.id}`]) problems.push(`${v.id}: ${lang}.json 缺 block.mtrlte.${v.id}`);
    }
  }
}

if (problems.length) {
  console.error(`✗ ${problems.length} 个问题：`);
  for (const p of problems) console.error('  - ' + p);
  process.exit(1);
}
console.log(`✓ 扶手自检通过（${checked} 个模型元素全部落在包围盒内，方块 / 物品 / 掉落表 / blockstate / 语言键齐全）`);
