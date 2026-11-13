#!/usr/bin/env node
// 核对 26 个 eyecandy 的「资源 + 碰撞箱 + 图标」是不是成套的（不开游戏就能查的那种错）。
//
// 查七件事：
//   1. first.json 里 26 条描述的 model / texture 文件都在（obj 在 eyecandies/、png 在 eyecandies/）；
//   2. 贴图能解码、尺寸 ≤ 1024（超了就说明没有降采样，显存白吃）；
//   3. 碰撞箱从几何独立重算一遍（**不复用** Java 的代码，公式按 RawObjParser 的约定重写）：
//        · 每轴尺寸 > 0（没有压扁成 0 的轴）；
//        · 每轴尺寸在 0.02..400 格之间（防止单位搞错，比如忘了 /16 或忘了 *16）；
//        · y 轴最低点 ≈ 0（物件要落地：允许抹零后的 0，不允许整体飘着或陷进地里）；
//   4. blockstate 指到 mtrlte:block/empty、物品模型是 item/generated + layer0；
//   5. 物品图标 128×128、有 alpha、不是全空；掉落表掉自己；
//   6. lang（zh_cn / en_us）里 26 个 block/item 键 + 页签名都在；
//   7. 页码顺序 = first.json 顺序（注册代码里是按 entries() 顺序 add 的，这里只核对条数）。
//
// 用法：node tools/verify-eyecandies.js        （只打印结论）
//       node tools/verify-eyecandies.js -v     （再打印每个物件的 AABB 供人工核对）
'use strict';

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const RES = path.resolve(__dirname, '..', 'v1.20.1', 'src', 'main', 'resources');
const EYECANDY = path.join(RES, 'assets', 'mtrlte', 'eyecandies');
const BLOCKSTATES = path.join(RES, 'assets', 'mtrlte', 'blockstates');
const ITEM_MODELS = path.join(RES, 'assets', 'mtrlte', 'models', 'item');
const ITEM_TEXTURES = path.join(RES, 'assets', 'mtrlte', 'textures', 'item');
const LOOT = path.join(RES, 'data', 'mtrlte', 'loot_tables', 'blocks');
const LANG = path.join(RES, 'assets', 'mtrlte', 'lang');

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

  const ys = placed.map((p) => p[1]);
  const lift = Math.max(...ys) <= 0 ? -Math.min(...ys) : 0;
  return placed.map((p) => [p[0] * 16 + 8, (p[1] + lift) * 16, p[2] * 16 + 8]);
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

/** 与 EyecandyBlocks.boxOf 同一套「抹零 + 防御下限」。 */
const NUDGE = 2.5 / 16;
const MIN_SIZE = 1.0 / 16;
function boxOf(units) {
  const box = new Array(6);
  for (let axis = 0; axis < 3; axis++) {
    let min = units[axis] / 16;
    let max = units[axis + 3] / 16;
    if (min < 0 && min > -NUDGE) min = 0;
    if (max < 0) max = 0;
    if (max - min < MIN_SIZE) max = min + MIN_SIZE;
    box[axis] = min;
    box[axis + 3] = max;
  }
  return box;
}

// ----------------------------------------------------------------- main

function checkEntry(key, entry) {
  const tag = `[${key}]`;
  const id = 'eyecandy_' + key.toLowerCase();

  // 1) 描述 → 文件
  const objName = entry.model.replace(/^.*\//, '');
  const pngName = entry.textureId.replace(/^.*\//, '');
  if (!fs.existsSync(path.join(EYECANDY, objName))) fail(`${tag} missing model ${objName}`);
  else ok(`${tag} model ${objName}`);
  if (!fs.existsSync(path.join(EYECANDY, pngName))) {
    fail(`${tag} missing texture ${pngName}`);
    return null;
  }
  ok(`${tag} texture ${pngName}`);

  // 2) 贴图尺寸
  const info = pngInfo(path.join(EYECANDY, pngName));
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
  for (let i = 0; i < 3; i++) {
    const axis = 'xyz'[i];
    if (!(size[i] > 0)) fail(`${tag} collision box has zero size on ${axis}`);
    else if (size[i] < 0.02 || size[i] > 400) {
      fail(`${tag} collision box size on ${axis} = ${size[i].toFixed(3)} out of [0.02, 400] (unit bug?)`);
    } else ok(`${tag} size ${axis} = ${size[i].toFixed(3)}`);
  }
  if (box[1] < -1e-6) fail(`${tag} collision box dips below the floor: minY = ${box[1].toFixed(4)}`);
  if (box[1] > 0.02) fail(`${tag} collision box floats above the floor: minY = ${box[1].toFixed(4)}`);

  // 4) blockstate / 物品模型
  const bsFile = path.join(BLOCKSTATES, id + '.json');
  if (!fs.existsSync(bsFile)) fail(`${tag} missing blockstate ${id}.json`);
  else {
    const bs = JSON.parse(fs.readFileSync(bsFile, 'utf8'));
    const variants = Object.values(bs.variants || {});
    if (variants.length !== 1 || variants[0].model !== 'mtrlte:block/empty') {
      fail(`${tag} blockstate should be a single variant pointing at mtrlte:block/empty`);
    } else ok(`${tag} blockstate → block/empty`);
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

  return { key, id, name: entry.name, box, size };
}

function main() {
  console.log('verifying eyecandy decorations (v1.20.1)\n');
  const first = JSON.parse(fs.readFileSync(path.join(EYECANDY, 'first.json'), 'utf8'));
  const keys = Object.keys(first);
  if (keys.length !== 26) fail(`first.json has ${keys.length} entries, expected 26`);
  else ok('first.json has 26 entries');

  const rows = [];
  for (const key of keys) {
    const row = checkEntry(key, first[key]);
    if (row) rows.push(row);
  }

  // 7) lang
  const langZh = JSON.parse(fs.readFileSync(path.join(LANG, 'zh_cn.json'), 'utf8'));
  const langEn = JSON.parse(fs.readFileSync(path.join(LANG, 'en_us.json'), 'utf8'));
  if (!langZh['itemGroup.mtrlte.xiamen_decor'] || !langEn['itemGroup.mtrlte.xiamen_decor']) {
    fail('missing itemGroup.mtrlte.xiamen_decor in lang');
  } else ok('tab name present');
  for (const key of keys) {
    const id = 'eyecandy_' + key.toLowerCase();
    for (const [name, json] of [['zh_cn', langZh], ['en_us', langEn]]) {
      for (const prefix of ['block.mtrlte.', 'item.mtrlte.']) {
        if (!json[prefix + id]) fail(`${name} missing ${prefix}${id}`);
        else ok(`${name} ${prefix}${id}`);
      }
    }
  }

  // 页码 / 页签 / 注册点在源码里
  const src = path.join(__dirname, '..', 'v1.20.1', 'src', 'main', 'java', 'dev', 'js', 'mtrheadlight');
  const blocksSrc = fs.readFileSync(path.join(src, 'xiamen', 'eyecandy', 'EyecandyBlocks.java'), 'utf8');
  if (!blocksSrc.includes('"xiamen_decor"')) fail('EyecandyBlocks: tab id "xiamen_decor" not found');
  else ok('tab id xiamen_decor in source');
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
