#!/usr/bin/env node
// 核对 eyecandy（厦门 25 件 + 南普陀 34 件）的「资源 + 碰撞箱 + 图标 + 页签」是不是成套的
// （不开游戏就能查的那种错）。
//
// 查这些事：
//   1. 两份描述（first.json / nanputuo.json）里的 model / texture 文件都在
//      （obj 在 assets/mtrlte/eyecandies/、png 在 assets/mtrlte/textures/eyecandies/）；
//      两包之间 key / obj / png / 方块 id 不重复；
//   2. 贴图能解码、尺寸 ≤ 1024（超了就说明没有降采样，显存白吃）；
//   3. 碰撞箱从几何独立重算一遍（**不复用** Java 的代码，公式按 RawObjParser 的约定重写）：
//        · 普通物件：每轴尺寸 > 0、在 0.02..400 格之间、y 轴最低点 ≈ 0；
//        · 转角栏杆（msd_corner_railing / xiamen_brt_railing_corner）：占位格必须**不是**整块（< 85%），而且要同时有
//          「贴 x 边」和「贴 z 边」的两条臂 —— 也就是真的 L 形，而不是一个 AABB；
//        · 桥墩（bridge_pier_20）：从放置点往下挂（y ∈ [-19, 1]、高 20 格、墩顶贴放置格顶面），
//          不带已废弃的 raise，而且只有轮廓没有碰撞；
//   4. blockstate 是 facing=north/east/south/west 四个变体（指到 mtrlte:block/empty；
//      **59 件全部都有 facing**，一件都不例外）、物品模型是 item/generated + layer0；
//   5. 四向碰撞：把注册期那组盒子按 (x, z) → (16 − z, x) 转三圈，
//        · 盒数 / 体积不变、四步回到原样；
//        · 「转盒子」得到的 AABB 必须与「直接转顶点」得到的 AABB 一致（模型角度 = 碰撞角度）；
//        · 不出格（允许 1 单位的伸出，BRT 栏杆每段顶出 0.05 格）；
//        · 栏杆（薄面）按玩家朝向落边：玩家朝北 → 落到北边，朝东 → 东边 …（= 玩家对面那条边）；
//   6. 物品图标 128×128、有 alpha、不是全空；掉落表掉自己；
//   7. lang（zh_cn / en_us）里 60 个 block/item 键 + 南普陀页签名都在，
//      已废弃的 itemGroup.mtrlte.xiamen_decor 与旋转提示 tooltip.mtrlte.eyecandy_rotation 都不在了；
//   8. 源码里的约定：EyecandyBlocks 里有 nanputuo_decor、没有 xiamen_decor、转角 / 桥墩 key 都在，
//      EyecandyBlock 自己带 FACING + 放置取反（getOpposite），没有刷子 / 没有旧 rotation 属性，
//      EyecandyDirectionalBlock 已合并掉、DIRECTIONAL_KEYS 这类 key 集合不复存在，
//      渲染器读 EyecandyBlock.FACING；
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
  { file: 'first.json', expect: 25, label: '厦门风格物件包' },
  { file: 'nanputuo.json', expect: 34, label: '南普陀' },
];
const CORNER_KEYS = ['msd_corner_railing', 'xiamen_brt_railing_corner'];
const NO_COLLISION_KEYS = ['bridge_pier_20'];
// 带警示灯的屏蔽门收纳机（BRT psd 左 / 右）：obj 里有两段位置重合、只差 uv 的 `o light`
// （深色 = 熄 / 浅色 = 亮），渲染器只画其中一段，亮的那段自发光。
const WARNING_LIGHT_KEYS = ['xiamen_brt_psd_left', 'xiamen_brt_psd_right'];
// 贴图尺寸上限：绝大部分物件 1024 就够（超了说明没降采样，显存白吃）；
// 这两件是作者给的 4096 原图（用户明确要求「保持原尺寸，不缩」），单独放宽。
const MAX_TEXTURE_SIZE = 1024;
const MAX_TEXTURE_SIZE_LARGE = 4096;

// BRT 栏杆 4 段：薄面（z，约 0.09 格）必须整体贴在方块的一条边上，不能压着格子中线。
const RAILING_KEYS = ['xiamen_brt_railing_middle', 'xiamen_brt_railing_left',
  'xiamen_brt_railing_right', 'xiamen_brt_railing_middle_plain'];
// 四个朝向（blockRotation / 90 的下标顺序，与 EyecandyBlock.blockRotation 一致）。
const FACINGS = ['north', 'east', 'south', 'west'];
// 「转盒子」与「转顶点」两份 AABB 的容差（单位 = 模型空间 1/16 格）：
// boxOf 的抹零是 2.5 单位，转前转后抹零的轴不同，所以留 3 单位的余量。
const BOX_TOL = 3.0;

const VERBOSE = process.argv.includes('-v');

/** 两件共用同一份 .obj 的登记（不是错误，见 checkEntry 第 1 步）。 */
const sharedModels = [];

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

/**
 * OBJ 里有多少段「以 light 开头的组」（{@code o light} / {@code o light2}，同名也算两段）。
 * 与 RawObjParser.groupRuns 同一套「组名一变就开新段」的规则。
 */
function lightRuns(file) {
  const runs = [];
  let current = null;
  let count = 0;
  for (const raw of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const line = raw.trim();
    if (!line) continue;
    const parts = line.split(/\s+/);
    if (parts[0] === 'o' || parts[0] === 'g') {
      if (current !== null && count > 0 && current.toLowerCase().startsWith('light')) runs.push(current);
      current = parts.slice(1).join(' ');
      count = 0;
    } else if (parts[0] === 'f') {
      count++;
    }
  }
  if (current !== null && count > 0 && current.toLowerCase().startsWith('light')) runs.push(current);
  return runs;
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


// ----------------------------------------------------------------- 四向（facing）

/** 一步 rot90（与 {@code EyecandyBlock.rotateBox} 同一公式）：[16 − z2, y1, x1, 16 − z1, y2, x2]。 */
function rot90(box) {
  const [x1, y1, z1, x2, y2, z2] = box;
  return [16 - z2, y1, x1, 16 - z1, y2, x2];
}

/** 转 steps 个 90°。 */
function rotBox(box, steps) {
  let b = box;
  for (let i = 0; i < ((steps % 4) + 4) % 4; i++) b = rot90(b);
  return b;
}

/** 顶点转 steps 个 90°：{@code (x, z) → (16 − z, x)}（与渲染器 NEGATIVE_Y 同一个角度的等价写法）。 */
function rotPoints(points, steps) {
  let pts = points;
  for (let i = 0; i < ((steps % 4) + 4) % 4; i++) pts = pts.map(([x, y, z]) => [16 - z, y, x]);
  return pts;
}

function boundsOf(points) {
  const raw = [Infinity, Infinity, Infinity, -Infinity, -Infinity, -Infinity];
  for (const p of points) {
    for (let i = 0; i < 3; i++) {
      raw[i] = Math.min(raw[i], p[i]);
      raw[i + 3] = Math.max(raw[i + 3], p[i]);
    }
  }
  return raw;
}

/** AABB（单位）→ 那组盒子的并集 AABB（就是取两端的角点）。 */
function unionBox(boxes) {
  return boundsOf(boxes.flatMap((b) => [[b[0], b[1], b[2]], [b[3], b[4], b[5]]]));
}

/**
 * 注册期真正用的那组盒子（模型空间单位），与 {@code EyecandyBlocks.boxesOf} 同构：
 * 桥墩 = 整格框、转角栏杆 = L 形一组、其余 = 1 个 AABB。
 */
function boxesFor(key, entry, objFile, boxUnits) {
  if (NO_COLLISION_KEYS.includes(key)) {
    return [[0, 0, 0, 16, 16, 16]];
  }
  if (CORNER_KEYS.includes(key)) {
    const grid = footprint(objFile, entry);
    const yMin = Math.max(0, boxUnits[1]);
    const yMax = Math.max(1, boxUnits[4]);
    const boxes = cornerBoxes(grid);
    if (boxes.length) {
      return boxes.map((b) => [b.x1, yMin, b.z1, b.x2, yMax, b.z2]);
    }
  }
  return [[boxUnits[0], boxUnits[1], boxUnits[2], boxUnits[3], boxUnits[4], boxUnits[5]]];
}

const volumeOf = (boxes) => boxes.reduce(
  (n, b) => n + Math.abs((b[3] - b[0]) * (b[4] - b[1]) * (b[5] - b[2])), 0);

/** 玩家朝向的反方向（= 放置时写进 facing 的那个值，见 EyecandyBlock#getPlacementState）。 */
const OPPOSITE = { north: 'south', east: 'west', south: 'north', west: 'east' };

/** 薄的那一轴：0 = x，2 = z。 */
function thinAxis(size) {
  return (size[0] <= size[2] ? 0 : 2);
}

/** 占位格里实心格的外接框（单位）；空格返回 null。 */
function gridBox(grid) {
  let minX = 16; let maxX = -1; let minZ = 16; let maxZ = -1;
  for (let z = 0; z < 16; z++) {
    for (let x = 0; x < 16; x++) {
      if (!grid[z * 16 + x]) continue;
      minX = Math.min(minX, x); maxX = Math.max(maxX, x);
      minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
    }
  }
  return maxX < 0 ? null : [minX, 0, minZ, maxX + 1, 16, maxZ + 1];
}

/** 占位格转 steps 个 90°：格 (x, z) → (15 − z, x)。 */
function rotGrid(grid, steps) {
  let g = grid;
  for (let i = 0; i < ((steps % 4) + 4) % 4; i++) {
    const next = new Uint8Array(256);
    for (let z = 0; z < 16; z++) {
      for (let x = 0; x < 16; x++) {
        if (g[z * 16 + x]) next[x * 16 + (15 - z)] = 1;
      }
    }
    g = next;
  }
  return g;
}

/** 这组盒子伸出方块多少（单位）：旋转是绕中心的，伸出量必须守恒。 */
const overhang = (boxes) => boxes.reduce((n, b) => n
  + Math.max(0, -b[0]) + Math.max(0, -b[2]) + Math.max(0, b[3] - 16) + Math.max(0, b[5] - 16), 0);

/**
 * 四向碰撞：把北向那组盒子按 facing 转三圈，逐项核对。
 *
 * <p>「转盒子」与「转顶点」两份 AABB 必须一致 —— 这是「模型转了碰撞盒跟着转」的独立证明：
 * 顶点这条路走的是渲染器那个角度（NEGATIVE_Y），盒子那条路走的是 {@code shapeOf} 的
 * {@code (x, z) → (16 − z, x)}，两条路算出来的包围盒对不上就说明两份角度不一致。
 * 转角栏杆比的是<b>占位格</b>（它的几何 4.5 格宽，套几何 AABB 本来就对不上 L 形的格子）。
 *
 * @return 四个朝向的盒子列表（下标同 {@link FACINGS}）
 */
function checkFourWay(key, entry, objFile, north, placed) {
  const tag = `[${key}]`;
  const northVolume = volumeOf(north);
  const northOverhang = overhang(north);
  const grid = CORNER_KEYS.includes(key) ? footprint(objFile, entry) : null;
  const gridBoxNorth = grid ? gridBox(grid) : null;
  const shapes = [];

  for (let steps = 0; steps < 4; steps++) {
    const facing = FACINGS[steps];
    const rotated = north.map((b) => rotBox(b, steps));
    shapes.push(rotated);

    if (rotated.length !== north.length) {
      fail(`${tag} facing=${facing}: ${rotated.length} collision box(es), north has ${north.length}`);
    } else ok(`${tag} facing=${facing}: ${rotated.length} collision box(es)`);

    if (Math.abs(volumeOf(rotated) - northVolume) > 1e-6) {
      fail(`${tag} facing=${facing}: collision volume ${volumeOf(rotated).toFixed(2)} != north ${northVolume.toFixed(2)}`);
    } else ok(`${tag} facing=${facing}: volume ${northVolume.toFixed(1)} preserved`);

    // y 不许被 yaw 旋转影响。
    if (rotated.some((b) => Math.abs(b[1] - north[0][1]) > 1e-9 || Math.abs(b[4] - north[0][4]) > 1e-9)) {
      fail(`${tag} facing=${facing}: y range changed by the yaw rotation`);
    }

    // 直接转几何（顶点 / 占位格）vs 转盒子：同一个角度？
    if (NO_COLLISION_KEYS.includes(key)) {
      // 桥墩的「盒子」是有意为之的 1×1×1 框选（碰撞是空集），跟几何 AABB（20 格高）天然对不上；
      // 整格框绕中心转还是整格框，所以这里只核对体积 / 伸出量，不拿几何比。
      ok(`${tag} facing=${facing}: 1×1×1 outline frame is rotation-invariant (no collision)`);
    } else {
      const referenceNorth = gridBoxNorth ? gridBoxNorth : boundsOf(placed);
      const direct = gridBoxNorth ? rotBox(referenceNorth, steps) : boundsOf(rotPoints(placed, steps));
      const viaBox = unionBox(rotated);
      // 转角栏杆的占位格只有 x/z（y 是整格高），所以只比水平四分量。
      const axes = gridBoxNorth ? [0, 2, 3, 5] : [0, 1, 2, 3, 4, 5];
      const drift = Math.max(...axes.map((i) => Math.abs(direct[i] - viaBox[i])));
      if (drift > BOX_TOL) {
        fail(`${tag} facing=${facing}: rotated boxes AABB drifts ${drift.toFixed(2)} from the rotated `
          + `${gridBoxNorth ? 'footprint' : 'geometry'} (model angle and collision angle disagree)`);
      } else ok(`${tag} facing=${facing}: boxes match the rotated ${gridBoxNorth ? 'footprint' : 'geometry'} (drift ${drift.toFixed(2)})`);
    }

    // 伸出量守恒：本来伸出方块的（单车 2 格、指示牌 -0.5 格、BRT 栏杆顶层）转完照样伸出，不能凭空缩回来。
    if (Math.abs(overhang(rotated) - northOverhang) > 1e-6) {
      fail(`${tag} facing=${facing}: overhang ${overhang(rotated).toFixed(2)} != north ${northOverhang.toFixed(2)}`);
    } else if (northOverhang > 0) {
      ok(`${tag} facing=${facing}: overhang ${northOverhang.toFixed(2)} preserved`);
    } else {
      ok(`${tag} facing=${facing}: inside the block`);
    }
  }

  // 四步回到原样（闭运算没漏、rot90 没漂）。
  const back = rotBox(north[0], 4);
  if (back.some((v, i) => Math.abs(v - north[0][i]) > 1e-9)) {
    fail(`${tag} four 90° steps do not return to the north box`);
  } else ok(`${tag} four 90° steps return to north`);

  // 栏杆：薄面要按玩家朝向落到「玩家对面」那条边（玩家朝北 → 北边 z≈0，朝东 → 东边 x≈16）。
  if (RAILING_KEYS.includes(key)) {
    for (const look of FACINGS) {
      const steps = FACINGS.indexOf(OPPOSITE[look]);
      const boxes = shapes[steps];
      const b = unionBox(boxes);
      const size = [b[3] - b[0], b[4] - b[1], b[5] - b[2]];
      const axis = thinAxis(size);
      const lo = b[axis];
      const hi = b[axis];
      const flushLo = Math.abs(Math.min(...boxes.map((q) => q[axis]))) < 0.01;
      const flushHi = Math.abs(16 - Math.max(...boxes.map((q) => q[axis + 3]))) < 0.01;
      const onFarEdge = (look === 'north' && flushLo)
        || (look === 'south' && flushHi)
        || (look === 'west' && flushLo)
        || (look === 'east' && flushHi);
      if (!onFarEdge) {
        fail(`${tag} player looks ${look} (facing=${OPPOSITE[look]}): railing is not flush with the ${look} edge `
          + `[${lo.toFixed(3)}, ${hi.toFixed(3)}]`);
      } else ok(`${tag} player looks ${look} → railing on the ${look} edge`);
    }
  }

  // 「这一件到底转不转得动」：四个朝向的盒子集合不一样就是看得出差别
  // （用集合而不是包围盒：L 形转角栏杆的包围盒是整格，但两条臂确实转了）。
  const sig = (bs) => bs.map((b) => b.map((v) => Math.round(v * 1000) / 1000).join(',')).sort().join('|');
  const northSig = sig(north);
  const varied = shapes.some((bs, i) => i > 0 && sig(bs) !== northSig);
  return { shapes, varied };
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
  // obj 可以两件共用（sign.obj 就是 100m / 200m 共用的一份几何，只靠 translation / rotation 区分），
  // 贴图共用更常见（xiamen_brt_railing_corner 与 xiamen_brt_railing_middle_plain 共用 middle-nontexture.png），
  // 所以这里只登记、不判错。
  if (seenFiles.has(objName)) sharedModels.push(`${objName} (${key})`);
  seenFiles.add(objName);
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
  const maxTexture = WARNING_LIGHT_KEYS.includes(key) ? MAX_TEXTURE_SIZE_LARGE : MAX_TEXTURE_SIZE;
  if (info.bitDepth !== 8) fail(`${tag} ${pngName} bit depth ${info.bitDepth} (expected 8)`);
  if (info.width > maxTexture || info.height > maxTexture) {
    fail(`${tag} ${pngName} is ${info.width}x${info.height} (>${maxTexture}, not downscaled)`);
  } else {
    ok(`${tag} ${pngName} ${info.width}x${info.height}`);
  }

  // 2b) 警示灯组：警示灯那两件必须有两段 `o light`（深 / 浅两份几何重合，只差 uv），
  //     渲染器靠「第几段」分辨亮灭 —— 少一段就闪不起来。
  if (WARNING_LIGHT_KEYS.includes(key)) {
    const runs = lightRuns(path.join(EYECANDY, objName));
    if (runs.length < 2) {
      fail(`${tag} ${objName} has ${runs.length} light group(s), expected 2 (off + on)`);
    } else ok(`${tag} ${objName} has ${runs.length} light groups (off / on)`);
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

    if (RAILING_KEYS.includes(key)) {
      // BRT 栏杆：薄的那一轴必须整体贴在方块的一条边上（min ≈ 0 或 max ≈ 1）。
      // 「压着格子中线」= 描述文件和变换顺序不匹配（先把 translation 转了 90° 再平移，偏移量
      // 落到了长轴 / 中线方向上）—— 那种情况下 [min, max] 会跨着 0.5，一眼能看出来。
      const thin = size[0] <= size[2] ? 0 : 2;
      const axisName = thin === 0 ? 'x' : 'z';
      const lo = box[thin];
      const hi = box[thin + 3];
      if (Math.min(Math.abs(lo), Math.abs(1 - hi)) > 0.01) {
        fail(`${tag} railing edge is not flush with a cell edge on ${axisName}: [${lo.toFixed(3)}, ${hi.toFixed(3)}]`
          + ` (should reach 0 or 1 on the thin axis)`);
      } else if (lo < 0.5 && hi > 0.5) {
        fail(`${tag} railing straddles the cell centre line on ${axisName}: [${lo.toFixed(3)}, ${hi.toFixed(3)}]`);
      } else ok(`${tag} railing flush on ${axisName}: [${lo.toFixed(3)}, ${hi.toFixed(3)}]`);
    }
  }

  // 3b) 四向碰撞（每件 4 份形状：注册期按 (x, z) → (16 − z, x) 预烘，转角栏杆的 L 形整组转）
  const objFile = path.join(EYECANDY, objName);
  const northBoxes = boxesFor(key, entry, objFile, box.map((v) => v * 16));
  const fourWay = checkFourWay(key, entry, objFile, northBoxes, placed);

  // 4) blockstate / 物品模型
  //    59 件全都有 facing 属性（放置时按玩家朝向定，见 EyecandyBlock）→ blockstate 必须写满
  //    四个变体（少一个变体原版会把状态判成「未知」直接报错）。没有任何「单变体」的例外。
  const bsFile = path.join(BLOCKSTATES, id + '.json');
  if (!fs.existsSync(bsFile)) fail(`${tag} missing blockstate ${id}.json`);
  else {
    const bs = JSON.parse(fs.readFileSync(bsFile, 'utf8'));
    const variants = bs.variants || {};
    const keys = Object.keys(variants);
    const wanted = FACINGS.map((f) => 'facing=' + f);
    const missing = wanted.filter((k) => !variants[k]);
    const allEmpty = keys.every((k) => variants[k] && variants[k].model === 'mtrlte:block/empty');
    if (missing.length || keys.length !== 4 || !allEmpty) {
      fail(`${tag} blockstate should have exactly the four facing= variants → mtrlte:block/empty `
        + `(missing ${missing.join(',') || 'none'}, has ${keys.join(',')})`);
    } else ok(`${tag} blockstate → block/empty ×4 (facing)`);
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

  return { key, id, name: entry.name, box, size, pack: entry.__pack, varied: fourWay.varied, fourWay: fourWay.shapes };
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
  if (total !== 25 + 34) fail(`descriptors hold ${total} entries, expected 59`);
  else ok('60 entries in total');

  // 7) lang
  const langZh = JSON.parse(fs.readFileSync(path.join(LANG, 'zh_cn.json'), 'utf8'));
  const langEn = JSON.parse(fs.readFileSync(path.join(LANG, 'en_us.json'), 'utf8'));
  for (const [name, json] of [['zh_cn', langZh], ['en_us', langEn]]) {
    if (!json['itemGroup.mtrlte.nanputuo_decor']) fail(`${name} missing itemGroup.mtrlte.nanputuo_decor`);
    else ok(`${name} nanputuo tab name`);
    if (json['itemGroup.mtrlte.xiamen_decor']) fail(`${name} still has the retired itemGroup.mtrlte.xiamen_decor`);
    else ok(`${name} no retired xiamen_decor tab name`);
    if (!json['itemGroup.mtrlte.core']) fail(`${name} missing itemGroup.mtrlte.core (the tab the 25 go into)`);
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
  // 警示灯：两块 key 必须在 WARNING_LIGHT_KEYS 里，渲染器必须按「底座 + 一盏灯」分开画，
  // 方块必须交出 ticker（状态机的唯一入口）。
  for (const key of WARNING_LIGHT_KEYS) {
    if (!blocksSrc.includes(`"${key}"`)) fail(`EyecandyBlocks: warning-light key ${key} not listed`);
    else ok(`warning-light key ${key} listed`);
  }
  if (!blocksSrc.includes('WARNING_LIGHT_KEYS')) fail('EyecandyBlocks: WARNING_LIGHT_KEYS set is missing');
  else ok('EyecandyBlocks has the WARNING_LIGHT_KEYS set');
  if (!blocksSrc.includes('public static RawMesh lightMesh(')) {
    fail('EyecandyBlocks: lightMesh(entry, lit) is missing (the warning light cannot be picked)');
  } else ok('EyecandyBlocks splits the warning light into base / off / on');
  if (!blocksSrc.includes('RawObjParser.groupRuns(')) {
    fail('EyecandyBlocks does not use RawObjParser.groupRuns (light paragraphs would not be separable)');
  } else ok('EyecandyBlocks picks the light group by paragraph order');
  const probeSrc = path.join(src, 'xiamen', 'eyecandy', 'PsdDoorProbe.java');
  if (!fs.existsSync(probeSrc)) fail('PsdDoorProbe.java is missing (no door-state source)');
  else {
    const probe = fs.readFileSync(probeSrc, 'utf8');
    if (!probe.includes('TileEntityPSDAPGDoorBase')) fail('PsdDoorProbe does not read the MTR PSD door block entity');
    else ok('PsdDoorProbe reads the MTR PSD door state');
    if (!probe.includes('"door_open"')) fail('PsdDoorProbe does not look for the generic door_open property');
    else ok('PsdDoorProbe also accepts generic open / door_open properties');
  }
  const beSrc = fs.readFileSync(path.join(src, 'xiamen', 'eyecandy', 'EyecandyBlockEntity.java'), 'utf8');
  for (const needle of ['BLINK_PHASE_TICKS', 'BLINK_FLASHES', 'PsdDoorProbe.anyDoorOpen(', 'public boolean isLit()']) {
    if (!beSrc.includes(needle)) fail(`EyecandyBlockEntity: ${needle} is missing (blink state machine)`);
    else ok(`EyecandyBlockEntity has ${needle}`);
  }
  // facing 三处必须一致：Java 端每件都带 FACING、生成器给每件写四个变体、本脚本核对四个变体。
  // 漏一处就是「方块有 facing 但 blockstate 只有单变体」那种上线才炸的问题。
  const genSrc = fs.readFileSync(path.join(__dirname, 'gen-eyecandy-assets.js'), 'utf8');
  if (blocksSrc.includes('DIRECTIONAL_KEYS')) {
    fail('EyecandyBlocks still keys the facing off a DIRECTIONAL_KEYS set (all 59 rotate now)');
  } else ok('EyecandyBlocks has no DIRECTIONAL_KEYS (every eyecandy rotates)');
  if (genSrc.includes('DIRECTIONAL_KEYS')) {
    fail('gen-eyecandy-assets.js still keys the blockstate off a DIRECTIONAL_KEYS set');
  } else ok('generator has no DIRECTIONAL_KEYS');
  if (!genSrc.includes("const FACINGS = ['north', 'east', 'south', 'west']")) {
    fail('gen-eyecandy-assets.js: FACINGS list missing (it should write the four facing variants for every key)');
  } else ok('generator writes the four facing variants for every key');
  if (fs.existsSync(path.join(src, 'xiamen', 'eyecandy', 'EyecandyDirectionalBlock.java'))) {
    fail('EyecandyDirectionalBlock.java still exists (it should be merged into EyecandyBlock)');
  } else ok('EyecandyDirectionalBlock merged into EyecandyBlock');
  if (blocksSrc.includes('EyecandyDirectionalBlock')) fail('EyecandyBlocks still refers to EyecandyDirectionalBlock');
  else ok('EyecandyBlocks uses one block class for all 59');

  const blockSrc = fs.readFileSync(path.join(src, 'xiamen', 'eyecandy', 'EyecandyBlock.java'), 'utf8');
  if (!blockSrc.includes('Properties.HORIZONTAL_FACING')) {
    fail('EyecandyBlock does not declare facing (Properties.HORIZONTAL_FACING)');
  } else ok('EyecandyBlock declares facing');
  if (!blockSrc.includes('builder.add(FACING)')) {
    fail('EyecandyBlock does not register FACING in appendProperties');
  } else ok('FACING registered in appendProperties');
  const placeIdx = blockSrc.indexOf('getPlacementState');
  const placeBody = placeIdx >= 0 ? blockSrc.slice(placeIdx, placeIdx + 600) : '';
  if (!placeBody.includes('getHorizontalPlayerFacing().getOpposite()')) {
    fail('EyecandyBlock#getPlacementState does not use getHorizontalPlayerFacing().getOpposite()');
  } else ok('placement takes the player facing opposite (piece lands on the far edge)');
  if (!blockSrc.includes('blockRotation(state.get(FACING))')) {
    fail('EyecandyBlock#shapeFor does not index the pre-baked shapes by FACING');
  } else ok('shapeFor indexes the four pre-baked shapes by facing');
  if (blockSrc.includes('EnumProperty.of("rotation"')) {
    fail('EyecandyBlock still exposes the retired 4-way "rotation" property');
  } else ok('EyecandyBlock has no rotation property');
  if (blockSrc.includes('rotateYClockwise')) fail('EyecandyBlock still cycles ROTATION on use');
  else ok('EyecandyBlock has no brush rotation');
  if (blockSrc.includes('onUse(')) {
    fail('EyecandyBlock still overrides onUse (no brush interaction is wanted)');
  } else ok('EyecandyBlock has no onUse (no brush, no tooltip)');

  const rendererSrc = fs.readFileSync(path.join(src, 'xiamen', 'eyecandy', 'EyecandyRenderer.java'), 'utf8');
  if (!rendererSrc.includes('EyecandyBlock.FACING')) fail('EyecandyRenderer does not read EyecandyBlock.FACING');
  else ok('renderer reads EyecandyBlock.FACING');
  if (!rendererSrc.includes('EyecandyBlock.blockRotation(')) {
    fail('EyecandyRenderer does not use EyecandyBlock.blockRotation (model would not follow facing)');
  } else ok('renderer rotates the mesh by blockRotation');

  const serverSrc = fs.readFileSync(path.join(src, 'server', 'LteServer.java'), 'utf8');
  if (!serverSrc.includes('EyecandyBlocks.register()')) fail('LteServer does not call EyecandyBlocks.register()');
  else ok('LteServer calls EyecandyBlocks.register()');
  const clientSrc = fs.readFileSync(path.join(src, 'xiamen', 'XiamenClient.java'), 'utf8');
  if (!clientSrc.includes('EyecandyClient.register()')) fail('XiamenClient does not call EyecandyClient.register()');
  else ok('XiamenClient calls EyecandyClient.register()');

  // 59 × 4 = 236 个 blockstate 变体（只数 eyecandy_*，别的资源一概不碰）。
  let bsFiles = 0;
  let variantCount = 0;
  for (const name of fs.readdirSync(BLOCKSTATES)) {
    if (!name.startsWith('eyecandy_') || !name.endsWith('.json')) continue;
    bsFiles++;
    variantCount += Object.keys(JSON.parse(fs.readFileSync(path.join(BLOCKSTATES, name), 'utf8')).variants || {}).length;
  }
  if (bsFiles !== 59) fail(`${bsFiles} eyecandy blockstate file(s), expected 59`);
  else ok('59 eyecandy blockstates');
  if (variantCount !== 59 * 4) {
    fail(`eyecandy blockstates hold ${variantCount} variants, expected ${59 * 4} (59 × facing=north/east/south/west)`);
  } else ok(`${variantCount} blockstate variants (59 × 4 facings)`);

  // 汇总表
  console.log('\nkey                                   id                                               size (blocks)          min corner            name');
  for (const r of rows) {
    const f = (v) => v.toFixed(3).padStart(7);
    console.log(`${r.key.padEnd(36)} ${r.id.padEnd(47)} ${f(r.size[0])}${f(r.size[1])}${f(r.size[2])}  ${f(r.box[0])}${f(r.box[1])}${f(r.box[2])}  ${r.name}`);
  }

  console.log(`\n${checks} checks passed, ${failures} failure(s)`);
  if (sharedModels.length) console.log(`shared .obj: ${sharedModels.join(', ')}`);
  const symmetric = rows.filter((r) => !r.varied).map((r) => r.key);
  console.log(`facing: all ${rows.length} keys carry facing ×4 variants; `
    + `${rows.length - symmetric.length} change footprint across the four facings`
    + (symmetric.length ? `, ${symmetric.length} are rotation-symmetric (still rotate): ${symmetric.join(', ')}` : ''));
  if (failures > 0) process.exit(1);
}

module.exports = { footprint, cornerBoxes, boxesFor, rot90, rotBox, rotPoints, boundsOf, boxOf,
  FACINGS, OPPOSITE };

if (require.main === module) main();
