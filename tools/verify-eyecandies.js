#!/usr/bin/env node
// 核对 eyecandy（厦门 26 件 + 南普陀 34 件）的「资源 + 碰撞箱 + 图标 + 页签」是不是成套的
// （不开游戏就能查的那种错）。
//
// 查这些事：
//   1. 两份描述（first.json / nanputuo.json）里的 model / texture 文件都在
//      （obj 在 assets/mtrlte/eyecandies/、png 在 assets/mtrlte/textures/eyecandies/）；
//      两包之间 key / obj / png / 方块 id 不重复；
//   2. 贴图能解码、尺寸 ≤ 1024（超了就说明没有降采样，显存白吃）；
//   3. 碰撞箱从几何独立重算一遍（**不复用** Java 的代码，公式按 RawObjParser 的约定重写）：
//        · 普通物件：每轴尺寸 > 0、在 0.02..400 格之间、y 轴最低点 ≈ 0；
//        · 转角栏杆（key13 / key14）：占位格必须**不是**整块（< 85%），而且要同时有
//          「贴 x 边」和「贴 z 边」的两条臂 —— 也就是真的 L 形，而不是一个 AABB；
//        · 桥墩（qiaodun20）：从放置点往下挂（y ∈ [-19, 1]、高 20 格、墩顶贴放置格顶面），
//          不带已废弃的 raise，而且只有轮廓没有碰撞；
//   4. blockstate 是单变体 ""（指到 mtrlte:block/empty，方块没有朝向属性）、
//      物品模型是 item/generated + layer0；
//   5. 物品图标 128×128、有 alpha、不是全空；掉落表掉自己；
//   6. lang（zh_cn / en_us）里 60 个 block/item 键 + 南普陀页签名都在，
//      已废弃的 itemGroup.mtrlte.xiamen_decor 与旋转提示 tooltip.mtrlte.eyecandy_rotation 都不在了；
//   7. 源码里的约定：EyecandyBlocks 里有 nanputuo_decor、没有 xiamen_decor、转角 / 桥墩 key 都在，
//      LteServer 调 EyecandyBlocks.register()、XiamenClient 调 EyecandyClient.register()。
//
// 用法：node tools/verify-eyecandies.js        （只打印结论）
//       node tools/verify-eyecandies.js -v     （再打印每个物件的 AABB 供人工核对）
'use strict';

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const RES = path.resolve(__dirname, '..', 'v1.20.1', 'src', 'main', 'resources');
const EYECANDY = path.join(RES, 'assets', 'mtrlte', 'eyecandies');
const TEX = path.join(RES, 'assets', 'mtrlte', 'textures', 'eyecandies');
const BLOCKSTATES = path.join(RES, 'assets', 'mtrlte', 'blockstates');
const ITEM_MODELS = path.join(RES, 'assets', 'mtrlte', 'models', 'item');
const ITEM_TEXTURES = path.join(RES, 'assets', 'mtrlte', 'textures', 'item');
const LOOT = path.join(RES, 'data', 'mtrlte', 'loot_tables', 'blocks');
const LANG = path.join(RES, 'assets', 'mtrlte', 'lang');

const DESCRIPTORS = [
  { file: 'first.json', expect: 26, label: '厦门风格物件包' },
  { file: 'nanputuo.json', expect: 34, label: '南普陀' },
];
const CORNER_KEYS = ['key13', 'key14'];
const NO_COLLISION_KEYS = ['qiaodun20'];

const VERBOSE = process.argv.includes('-v');

let failures = 0;
let checks = 0;
const fail = (m) => { failures++; console.log('  FAIL ' + m); };
const ok = (m) => { checks++; if (VERBOSE) console.log('  ok   ' + m); };

// ----------------------------------------------------------------- PNG 头 / 像素

function pngInfo(file) {
  const b = fs.readFileSync(file);
  return { width: b.readUInt32BE(16), height: b.readUInt32BE(20), bitDepth: b[24], colorType: b[25] };
}

/** 只解出「alpha 是否全 0」用的极小 PNG 解码（8bit RGBA / RGB）。 */
function pngHasPixels(file) {
  const buf = fs.readFileSync(file);
  const info = pngInfo(file);
  const channels = info.colorType === 6 ? 4 : 3;
  let pos = 8;
  const idat = [];
  while (pos < buf.length) {
    const len = buf.readUInt32BE(pos);
    const type = buf.toString('ascii', pos + 4, pos + 8);
    if (type === 'IDAT') idat.push(buf.subarray(pos + 8, pos + 8 + len));
    if (type === 'IEND') break;
    pos += 12 + len;
  }
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const stride = info.width * channels;
  let prev = Buffer.alloc(stride);
  let opaque = 0;
  for (let y = 0; y < info.height; y++) {
    const filter = raw[y * (stride + 1)];
    const line = raw.subarray(y * (stride + 1) + 1, y * (stride + 1) + 1 + stride);
    const cur = Buffer.alloc(stride);
    for (let x = 0; x < stride; x++) {
      const a = x >= channels ? cur[x - channels] : 0;
      const b = prev[x];
      const c = x >= channels ? prev[x - channels] : 0;
      let v = line[x];
      if (filter === 1) v += a;
      else if (filter === 2) v += b;
      else if (filter === 3) v += (a + b) >> 1;
      else if (filter === 4) {
        const p = a + b - c;
        const pa = Math.abs(p - a); const pb = Math.abs(p - b); const pc = Math.abs(p - c);
        v += pa <= pb && pa <= pc ? a : (pb <= pc ? b : c);
      }
      cur[x] = v & 0xff;
    }
    for (let x = 0; x < info.width; x++) {
      if (channels === 3 || cur[x * channels + 3] > 8) opaque++;
    }
    prev = cur;
  }
  return opaque > 0;
}

// ----------------------------------------------------------------- 几何（独立重写）

/** scale → rotation(XYZ 欧拉) → translation → 模型空间（与 RawObjParser.place 等价，代码独立重写）。 */
function placeVertices(vertices, entry) {
  const s = entry.scale || [1, 1, 1];
  const a = (entry.rotation || [0, 0, 0]).map((d) => (d * Math.PI) / 180);
  const t = entry.translation || [0, 0, 0];
  const m = entry.mirror || [false, false, false];

  // 三个轴各一个旋转矩阵，按 X→Y→Z 乘（等价于 Rz·Ry·Rx）。
  const rx = [[1, 0, 0], [0, Math.cos(a[0]), -Math.sin(a[0])], [0, Math.sin(a[0]), Math.cos(a[0])]];
  const ry = [[Math.cos(a[1]), 0, Math.sin(a[1])], [0, 1, 0], [-Math.sin(a[1]), 0, Math.cos(a[1])]];
  const rz = [[Math.cos(a[2]), -Math.sin(a[2]), 0], [Math.sin(a[2]), Math.cos(a[2]), 0], [0, 0, 1]];
  const mul = (A, B) => A.map((row, i) => [0, 1, 2].map((j) => row[0] * B[0][j] + row[1] * B[1][j] + row[2] * B[2][j]));
  const R = mul(rz, mul(ry, rx));

  const placed = vertices.map((v) => {
    const p = [v[0] * s[0] * (m[0] ? -1 : 1), v[1] * s[1] * (m[1] ? -1 : 1), v[2] * s[2] * (m[2] ? -1 : 1)];
    return [
      R[0][0] * p[0] + R[0][1] * p[1] + R[0][2] * p[2] + t[0],
      R[1][0] * p[0] + R[1][1] * p[1] + R[1][2] * p[2] + t[1],
      R[2][0] * p[0] + R[2][1] * p[1] + R[2][2] * p[2] + t[2],
    ];
  });

  // 素材怎么摆就怎么画：没有「抬到地面」的兜底了（桥墩整段挂在 y<0 是正常的）。
  return placed.map((p) => [p[0] * 16 + 8, p[1] * 16, p[2] * 16 + 8]);
}

function objVertices(file) {
  const out = [];
  for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    if (line.startsWith('v ')) {
      const p = line.trim().split(/\s+/);
      out.push([parseFloat(p[1]), parseFloat(p[2]), parseFloat(p[3])]);
    }
  }
  return out;
}

/** 三角形（f 行按扇形拆），与 RawObjParser.footprint 的采样对象一致。 */
function objTriangles(file) {
  const verts = [];
  const tris = [];
  const fix = (i, n) => (i < 0 ? n + i : i - 1);
  for (const raw of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line[0] === '#') continue;
    const parts = line.split(/\s+/);
    if (parts[0] === 'v') verts.push([parseFloat(parts[1]), parseFloat(parts[2]), parseFloat(parts[3])]);
    else if (parts[0] === 'f') {
      const idx = parts.slice(1).map((t) => fix(parseInt(t.split('/')[0], 10), verts.length));
      for (let i = 1; i + 1 < idx.length; i++) tris.push([idx[0], idx[i], idx[i + 1]]);
    }
  }
  return { verts, tris };
}

/** 与 EyecandyBlocks.boxOf 同一套「抹零 + 防御下限」。 */
const NUDGE = 2.5 / 16;
const MIN_SIZE = 1.0 / 16;
function boxOf(units) {
  const box = new Array(6);
  for (let axis = 0; axis < 3; axis++) {
    let min = units[axis] / 16;
    let max = units[axis + 3] / 16;
    if (min < 0 && min > -NUDGE) min = 0;
    if (max - min < MIN_SIZE) max = min + MIN_SIZE;
    box[axis] = min;
    box[axis + 3] = max;
  }
  return box;
}

/**
 * 方块内 16×16 占位格（重心采样，与 RawObjParser.footprint 同一算法、独立实现）。
 */
function footprint(file, entry) {
  const { verts, tris } = objTriangles(file);
  const placed = placeVertices(verts, entry);
  const grid = new Uint8Array(256);
  for (const tri of tris) {
    const p = tri.map((i) => placed[i]);
    const edge = Math.max(
      Math.hypot(p[1][0] - p[0][0], p[1][2] - p[0][2]),
      Math.hypot(p[2][0] - p[1][0], p[2][2] - p[1][2]),
      Math.hypot(p[2][0] - p[0][0], p[2][2] - p[0][2]));
    const steps = Math.max(3, Math.min(48, Math.ceil(edge / 0.4)));
    for (let a = 0; a <= steps; a++) {
      for (let b = 0; a + b <= steps; b++) {
        const wa = a / steps, wb = b / steps, wc = 1 - wa - wb;
        const x = p[0][0] * wa + p[1][0] * wb + p[2][0] * wc;
        const z = p[0][2] * wa + p[1][2] * wb + p[2][2] * wc;
        if (x < 0 || z < 0 || x >= 16 || z >= 16) continue;
        grid[Math.floor(z) * 16 + Math.floor(x)] = 1;
      }
    }
  }
  return grid;
}

/**
 * 占位格 → 盒子（与 EyecandyBlocks.cornerBoxes 同一算法：闭运算 → 逐行 run（缝隙 ≤ 2）→ 纵向合并）。
 * 用来看「L 形到底被切成几个盒子、总共占多少格」。
 */
function cornerBoxes(grid, gap = 2, passes = 3) {
  const at = (g, x, z) => (x < 0 || z < 0 || x > 15 || z > 15 ? 0 : g[z * 16 + x]);
  let cur = Uint8Array.from(grid);
  for (let pass = 0; pass < passes; pass++) {
    const next = Uint8Array.from(cur);
    for (let z = 0; z < 16; z++) {
      for (let x = 0; x < 16; x++) {
        if (cur[z * 16 + x]) continue;
        if ((at(cur, x - 1, z) && at(cur, x + 1, z)) || (at(cur, x, z - 1) && at(cur, x, z + 1))) next[z * 16 + x] = 1;
      }
    }
    cur = next;
  }
  const rows = [];
  for (let z = 0; z < 16; z++) {
    const runs = [];
    let x = 0;
    while (x < 16) {
      if (!cur[z * 16 + x]) { x++; continue; }
      let end = x, scan = x;
      while (scan < 16) {
        if (cur[z * 16 + scan]) { end = scan; scan++; continue; }
        let look = scan;
        while (look < 16 && look - scan < gap && !cur[z * 16 + look]) look++;
        if (look < 16 && cur[z * 16 + look]) { scan = look; continue; }
        break;
      }
      runs.push([x, end + 1]);
      x = end + 1;
    }
    rows.push(runs);
  }
  const out = [];
  const key = (runs) => runs.map((r) => r.join(':')).join('|');
  let z0 = 0;
  while (z0 < 16) {
    if (!rows[z0].length) { z0++; continue; }
    let z1 = z0;
    while (z1 + 1 < 16 && key(rows[z1 + 1]) === key(rows[z0])) z1++;
    for (const r of rows[z0]) out.push({ x1: r[0], x2: r[1], z1: z0, z2: z1 + 1 });
    z0 = z1 + 1;
  }
  return out;
}


// ----------------------------------------------------------------- 单个条目

function checkEntry(key, entry, seenIds, seenFiles) {
  const tag = `[${key}]`;
  const id = 'eyecandy_' + key.toLowerCase();
  if (seenIds.has(id)) fail(`${tag} duplicate block id ${id} (two packs use the same key?)`);
  seenIds.add(id);

  // 1) 描述 → 文件
  const objName = entry.model.replace(/^.*\//, '');
  const pngName = entry.textureId.replace(/^.*\//, '');
  if (seenFiles.has(objName)) fail(`${tag} duplicate model file name ${objName}`);
  seenFiles.add(objName); // obj 一件一个；贴图可以两件共用（key14 与 key7 共用 middle-nontexture.png）
  if (!fs.existsSync(path.join(EYECANDY, objName))) {
    fail(`${tag} missing model ${objName}`);
    return null;
  }
  ok(`${tag} model ${objName}`);
  if (!fs.existsSync(path.join(TEX, pngName))) {
    fail(`${tag} missing texture textures/eyecandies/${pngName}`);
    return null;
  }
  ok(`${tag} texture ${pngName}`);

  // 2) 贴图尺寸
  const info = pngInfo(path.join(TEX, pngName));
  if (info.bitDepth !== 8) fail(`${tag} ${pngName} bit depth ${info.bitDepth} (expected 8)`);
  if (info.width > 1024 || info.height > 1024) {
    fail(`${tag} ${pngName} is ${info.width}x${info.height} (>1024, not downscaled)`);
  } else {
    ok(`${tag} ${pngName} ${info.width}x${info.height}`);
  }

  // 3) 碰撞箱
  const verts = objVertices(path.join(EYECANDY, objName));
  if (verts.length === 0) {
    fail(`${tag} ${objName} has no vertices`);
    return null;
  }
  const placed = placeVertices(verts, entry);
  const raw = [Infinity, Infinity, Infinity, -Infinity, -Infinity, -Infinity];
  for (const p of placed) {
    for (let i = 0; i < 3; i++) {
      raw[i] = Math.min(raw[i], p[i]);
      raw[i + 3] = Math.max(raw[i + 3], p[i]);
    }
  }
  const box = boxOf(raw);
  const size = [box[3] - box[0], box[4] - box[1], box[5] - box[2]];

  if (NO_COLLISION_KEYS.includes(key)) {
    // 桥墩：素材整段挂在放置点以下，描述只把它抬 2 格 —— 墩顶落在放置格的顶面（= 上方桥面底面），
    // 墩身往下挂 19 格。方块本身不给碰撞（源码里那条规则由第 7 步核对）。
    if (Object.prototype.hasOwnProperty.call(entry, 'raise')) {
      fail(`${tag} pier still carries the retired "raise" field (use translation)`);
    } else ok(`${tag} pier has no "raise"`);
    if (Math.abs(box[4] - 1) > 1e-3) {
      fail(`${tag} pier top should sit at the block's top face (y = 1), got ${box[4].toFixed(3)}`);
    } else ok(`${tag} pier top y = ${box[4].toFixed(3)} (block top face)`);
    if (Math.abs(box[1] + 19) > 1e-3) {
      fail(`${tag} pier bottom should hang 19 blocks below the placement point (y = -19), got ${box[1].toFixed(3)}`);
    } else ok(`${tag} pier bottom y = ${box[1].toFixed(3)}`);
    if (Math.abs(size[1] - 20) > 0.2) fail(`${tag} pier height is ${size[1].toFixed(2)} blocks, expected ~20`);
    else ok(`${tag} pier height ${size[1].toFixed(2)}`);
  } else if (CORNER_KEYS.includes(key)) {
    // 转角栏杆：占位格既不能是整块，也不能只剩一条线。
    const grid = footprint(path.join(EYECANDY, objName), entry);
    const filled = grid.reduce((n, v) => n + v, 0);
    const ratio = filled / 256;
    if (ratio >= 0.85) fail(`${tag} corner footprint fills ${(ratio * 100).toFixed(0)}% of the block (= an AABB)`);
    else ok(`${tag} corner footprint ${(ratio * 100).toFixed(0)}%`);
    if (ratio < 0.08) fail(`${tag} corner footprint is nearly empty (${(ratio * 100).toFixed(0)}%)`);
    // 两条臂：贴 x 边的那条（某一行横跨 ≥ 12 格）和贴 z 边的那条（某一列纵跨 ≥ 12 格）
    let widestRow = 0;
    for (let z = 0; z < 16; z++) {
      let run = 0;
      for (let x = 0; x < 16; x++) { run = grid[z * 16 + x] ? run + 1 : 0; widestRow = Math.max(widestRow, run); }
    }
    let tallestCol = 0;
    for (let x = 0; x < 16; x++) {
      let run = 0;
      for (let z = 0; z < 16; z++) { run = grid[z * 16 + x] ? run + 1 : 0; tallestCol = Math.max(tallestCol, run); }
    }
    if (widestRow < 8) fail(`${tag} corner has no arm along x (widest row ${widestRow}/16)`);
    else ok(`${tag} corner x-arm ${widestRow}/16`);
    if (tallestCol < 8) fail(`${tag} corner has no arm along z (tallest column ${tallestCol}/16)`);
    else ok(`${tag} corner z-arm ${tallestCol}/16`);
    if (box[1] < -1e-6) fail(`${tag} corner dips below the floor: minY = ${box[1].toFixed(4)}`);

    // 盒子数 / 覆盖面积：L 形应该被切成「几个」盒子，而不是一个整格（也不能碎成十几块）。
    const boxes = cornerBoxes(grid);
    const area = boxes.reduce((n, b) => n + (b.x2 - b.x1) * (b.z2 - b.z1), 0);
    const areaRatio = area / 256;
    if (boxes.length === 0) fail(`${tag} cornerBoxes produced nothing`);
    else if (boxes.length > 8) fail(`${tag} corner is cut into ${boxes.length} boxes (too fragmented)`);
    else ok(`${tag} corner boxes ${boxes.length}`);
    if (areaRatio > 0.6) fail(`${tag} corner boxes cover ${(areaRatio * 100).toFixed(0)}% of the block (≈ an AABB)`);
    else ok(`${tag} corner box area ${(areaRatio * 100).toFixed(0)}%`);
    const outside = boxes.filter((b) => b.x1 < 0 || b.z1 < 0 || b.x2 > 16 || b.z2 > 16);
    if (outside.length) fail(`${tag} ${outside.length} corner box(es) stick out of the block`);
    else ok(`${tag} corner boxes stay inside the block`);
  } else {
    for (let i = 0; i < 3; i++) {
      const axis = 'xyz'[i];
      if (!(size[i] > 0)) fail(`${tag} collision box has zero size on ${axis}`);
      else if (size[i] < 0.02 || size[i] > 400) {
        fail(`${tag} collision box size on ${axis} = ${size[i].toFixed(3)} out of [0.02, 400] (unit bug?)`);
      } else ok(`${tag} size ${axis} = ${size[i].toFixed(3)}`);
    }
    if (box[1] < -1e-6) fail(`${tag} collision box dips below the floor: minY = ${box[1].toFixed(4)}`);
    if (box[1] > 0.02) fail(`${tag} collision box floats above the floor: minY = ${box[1].toFixed(4)}`);
  }

  // 4) blockstate / 物品模型
  const bsFile = path.join(BLOCKSTATES, id + '.json');
  if (!fs.existsSync(bsFile)) fail(`${tag} missing blockstate ${id}.json`);
  else {
    const bs = JSON.parse(fs.readFileSync(bsFile, 'utf8'));
    const variants = bs.variants || {};
    const keys = Object.keys(variants);
    if (keys.length !== 1 || keys[0] !== '' || !variants[''] || variants[''].model !== 'mtrlte:block/empty') {
      fail(`${tag} blockstate should be the single "" variant → mtrlte:block/empty (has ${keys.length}: ${keys.join(',')})`);
    } else ok(`${tag} blockstate → block/empty ×1`);
  }

  const itemFile = path.join(ITEM_MODELS, id + '.json');
  if (!fs.existsSync(itemFile)) fail(`${tag} missing item model ${id}.json`);
  else {
    const model = JSON.parse(fs.readFileSync(itemFile, 'utf8'));
    if (model.parent !== 'item/generated') fail(`${tag} item model parent is ${model.parent}, expected item/generated`);
    else if (!model.textures || model.textures.layer0 !== 'mtrlte:item/' + id) {
      fail(`${tag} item model layer0 should be mtrlte:item/${id}`);
    } else ok(`${tag} item model → item/${id}`);
  }

  // 5) 图标
  const iconFile = path.join(ITEM_TEXTURES, id + '.png');
  if (!fs.existsSync(iconFile)) {
    fail(`${tag} missing item icon ${id}.png`);
  } else {
    const icon = pngInfo(iconFile);
    if (icon.width !== 128 || icon.height !== 128) fail(`${tag} icon is ${icon.width}x${icon.height}, expected 128x128`);
    else if (!pngHasPixels(iconFile)) fail(`${tag} icon is fully transparent`);
    else ok(`${tag} icon 128x128`);
  }

  // 6) 掉落表
  const lootFile = path.join(LOOT, id + '.json');
  if (!fs.existsSync(lootFile)) fail(`${tag} missing loot table ${id}.json`);
  else {
    const loot = JSON.parse(fs.readFileSync(lootFile, 'utf8'));
    const name = loot.pools?.[0]?.entries?.[0]?.name;
    if (name !== 'mtrlte:' + id) fail(`${tag} loot table drops ${name}, expected mtrlte:${id}`);
    else ok(`${tag} loot → itself`);
  }

  return { key, id, name: entry.name, box, size, pack: entry.__pack };
}

// ----------------------------------------------------------------- main

function main() {
  console.log('verifying eyecandy decorations (v1.20.1)\n');

  const seenIds = new Set();
  const seenFiles = new Set();
  const rows = [];
  let total = 0;

  for (const descriptor of DESCRIPTORS) {
    const file = path.join(EYECANDY, descriptor.file);
    if (!fs.existsSync(file)) {
      fail(`missing descriptor ${descriptor.file}`);
      continue;
    }
    const json = JSON.parse(fs.readFileSync(file, 'utf8'));
    const keys = Object.keys(json);
    total += keys.length;
    if (keys.length !== descriptor.expect) {
      fail(`${descriptor.file} has ${keys.length} entries, expected ${descriptor.expect}`);
    } else ok(`${descriptor.file} has ${descriptor.expect} entries`);
    for (const key of keys) {
      const entry = Object.assign({ __pack: descriptor.label }, json[key]);
      const row = checkEntry(key, entry, seenIds, seenFiles);
      if (row) rows.push(row);
    }
  }
  if (total !== 26 + 34) fail(`descriptors hold ${total} entries, expected 60`);
  else ok('60 entries in total');

  // 7) lang
  const langZh = JSON.parse(fs.readFileSync(path.join(LANG, 'zh_cn.json'), 'utf8'));
  const langEn = JSON.parse(fs.readFileSync(path.join(LANG, 'en_us.json'), 'utf8'));
  for (const [name, json] of [['zh_cn', langZh], ['en_us', langEn]]) {
    if (!json['itemGroup.mtrlte.nanputuo_decor']) fail(`${name} missing itemGroup.mtrlte.nanputuo_decor`);
    else ok(`${name} nanputuo tab name`);
    if (json['itemGroup.mtrlte.xiamen_decor']) fail(`${name} still has the retired itemGroup.mtrlte.xiamen_decor`);
    else ok(`${name} no retired xiamen_decor tab name`);
    if (!json['itemGroup.mtrlte.core']) fail(`${name} missing itemGroup.mtrlte.core (the tab the 26 go into)`);
    else ok(`${name} core tab name`);
    if (json['tooltip.mtrlte.eyecandy_rotation']) {
      fail(`${name} still has tooltip.mtrlte.eyecandy_rotation (the rotation property was removed)`);
    } else ok(`${name} no rotation tooltip`);
  }
  for (const row of rows) {
    for (const [name, json] of [['zh_cn', langZh], ['en_us', langEn]]) {
      for (const prefix of ['block.mtrlte.', 'item.mtrlte.']) {
        if (!json[prefix + row.id]) fail(`${name} missing ${prefix}${row.id}`);
        else ok(`${name} ${prefix}${row.id}`);
      }
    }
  }

  // 8) 源码里的约定
  const src = path.join(__dirname, '..', 'v1.20.1', 'src', 'main', 'java', 'dev', 'js', 'mtrheadlight');
  const blocksSrc = fs.readFileSync(path.join(src, 'xiamen', 'eyecandy', 'EyecandyBlocks.java'), 'utf8');
  if (blocksSrc.includes('"xiamen_decor"')) fail('EyecandyBlocks still registers the retired xiamen_decor tab');
  else ok('no xiamen_decor tab in EyecandyBlocks');
  if (!blocksSrc.includes('"nanputuo_decor"')) fail('EyecandyBlocks: tab id "nanputuo_decor" not found');
  else ok('nanputuo_decor tab in EyecandyBlocks');
  if (!blocksSrc.includes('XiamenObjects.CORE_TAB_ID')) fail('EyecandyBlocks does not add its items to mtrlte:core');
  else ok('items go into mtrlte:core');
  for (const key of CORNER_KEYS) {
    if (!blocksSrc.includes(`"${key}"`)) fail(`EyecandyBlocks: corner key ${key} not listed`);
    else ok(`corner key ${key} listed`);
  }
  for (const key of NO_COLLISION_KEYS) {
    if (!blocksSrc.includes(`"${key}"`)) fail(`EyecandyBlocks: no-collision key ${key} not listed`);
    else ok(`no-collision key ${key} listed`);
  }
  const blockSrc = fs.readFileSync(path.join(src, 'xiamen', 'eyecandy', 'EyecandyBlock.java'), 'utf8');
  if (blockSrc.includes('EnumProperty.of("rotation"')) {
    fail('EyecandyBlock still exposes the retired 4-way "rotation" property');
  } else ok('EyecandyBlock has no rotation property');
  if (blockSrc.includes('rotateYClockwise')) fail('EyecandyBlock still cycles ROTATION on use');
  else ok('EyecandyBlock has no brush rotation');
  const serverSrc = fs.readFileSync(path.join(src, 'server', 'LteServer.java'), 'utf8');
  if (!serverSrc.includes('EyecandyBlocks.register()')) fail('LteServer does not call EyecandyBlocks.register()');
  else ok('LteServer calls EyecandyBlocks.register()');
  const clientSrc = fs.readFileSync(path.join(src, 'xiamen', 'XiamenClient.java'), 'utf8');
  if (!clientSrc.includes('EyecandyClient.register()')) fail('XiamenClient does not call EyecandyClient.register()');
  else ok('XiamenClient calls EyecandyClient.register()');

  // 汇总表
  console.log('\nkey                    id                              size (blocks)          min corner            name');
  for (const r of rows) {
    const f = (v) => v.toFixed(3).padStart(7);
    console.log(`${r.key.padEnd(22)} ${r.id.padEnd(31)} ${f(r.size[0])}${f(r.size[1])}${f(r.size[2])}  ${f(r.box[0])}${f(r.box[1])}${f(r.box[2])}  ${r.name}`);
  }

  console.log(`\n${checks} checks passed, ${failures} failure(s)`);
  if (failures > 0) process.exit(1);
}

if (require.main === module) main();
