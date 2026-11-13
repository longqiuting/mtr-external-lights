#!/usr/bin/env node
// 挂墙 / 挂顶发车倒数器：牌身（rawmodels）和碰撞箱、屏幕朝向到底对不对得上。
//
// 为什么需要这个：这两块牌子的几何现在由 BER 在运行时画（原始 Blockbench JSON），
// 碰撞箱 / 屏幕位置写在 DepOnWallBlock / DepOnRoofBlock / *Renderer 里。两边只要有一套
// 角度约定写错，就会回到「碰撞箱对、模型转了 90°」那个老毛病，而这种错在离线看不出来。
// 这里用纯几何复算：把 rawmodels 的顶点按「BER 的矩阵链」摆到方块空间，再和 Java 里
// 那几组 VoxelShape 的盒子、以及渲染器算出来的屏幕法线逐轴比。
//
// 用法：node tools/verify-dep-orient.js
'use strict';

const fs = require('fs');
const path = require('path');

const RES = path.resolve(__dirname, '..', 'v1.20.1', 'src', 'main', 'resources', 'assets', 'mtrlte', 'rawmodels');
const EPS = 1.0 / 16.0 + 1e-6; // 允许 1/16 格（模型是浮点数，碰撞箱写的是整数 1/16）

let failures = 0;
function check(ok, message) {
  if (ok) {
    console.log('  ok   ' + message);
  } else {
    failures++;
    console.log('  FAIL ' + message);
  }
}

// ----------------------------------------------------------------- 和 RawModelJson 同一套旋转

const rad = (d) => (d * Math.PI) / 180;

function anglesOf(rotation) {
  if (!rotation) return [0, 0, 0];
  if (rotation.axis) {
    const a = [0, 0, 0];
    a[{ x: 0, y: 1, z: 2 }[rotation.axis]] = rotation.angle || 0;
    return a;
  }
  return [rotation.x || 0, rotation.y || 0, rotation.z || 0];
}

/**
 * v' = Rz·Ry·Rx·(v - origin) + origin（XYZ 欧拉序，右手定则）。
 * <p>和 {@code RawModelJson#rotate} 逐行一致；也和数据里的元素旋转一致：
 * {@code BakedQuadFactory#rotateVertex} 用的是 {@code Quaternionf.rotationAxis(angle, axis)}，
 * 没有取反（从 1.20.1 的字节码核过）。
 */
function rotateXyz(p, origin, a) {
  let [x, y, z] = [p[0] - origin[0], p[1] - origin[1], p[2] - origin[2]];
  if (a[0]) {
    const [c, s] = [Math.cos(rad(a[0])), Math.sin(rad(a[0]))];
    [y, z] = [y * c - z * s, y * s + z * c];
  }
  if (a[1]) {
    const [c, s] = [Math.cos(rad(a[1])), Math.sin(rad(a[1]))];
    [x, z] = [x * c + z * s, -x * s + z * c];
  }
  if (a[2]) {
    const [c, s] = [Math.cos(rad(a[2])), Math.sin(rad(a[2]))];
    [x, y] = [x * c - y * s, x * s + y * c];
  }
  return [x + origin[0], y + origin[1], z + origin[2]];
}

/** blockstate 的 {@code "y": n} = 绕方块中心（模型空间 8, 8）转 -n 度（= 俯视顺时针）。 */
const Y_OF = { north: 0, east: 90, south: 180, west: 270 };

/** 点：绕方块中心按 blockstate 的 y 转。 */
function rotatePoint(p, facing) {
  const a = rad(-Y_OF[facing]);
  const [c, s] = [Math.cos(a), Math.sin(a)];
  const [dx, dz] = [p[0] - 8, p[2] - 8];
  return [8 + dx * c + dz * s, p[1], 8 + (-dx * s + dz * c)];
}

/** 方向：同一个旋转，但不带平移（拿点变换去转法线是错的）。 */
function rotateDir(v, facing) {
  const a = rad(-Y_OF[facing]);
  const [c, s] = [Math.cos(a), Math.sin(a)];
  return [v[0] * c + v[2] * s, v[1], -v[0] * s + v[2] * c];
}

/** 一份 rawmodel 的每个元素盒子的 8 个角，摆到方块空间（1/16 格）。 */
function cornersOf(model, facing) {
  const out = [];
  for (const e of model.elements) {
    const origin = e.rotation && e.rotation.origin ? e.rotation.origin : [0, 0, 0];
    const angles = anglesOf(e.rotation);
    for (const x of [e.from[0], e.to[0]]) {
      for (const y of [e.from[1], e.to[1]]) {
        for (const z of [e.from[2], e.to[2]]) {
          out.push(rotatePoint(rotateXyz([x, y, z], origin, angles), facing));
        }
      }
    }
  }
  return out;
}

function aabb(points) {
  const lo = [Infinity, Infinity, Infinity];
  const hi = [-Infinity, -Infinity, -Infinity];
  for (const p of points) {
    for (let i = 0; i < 3; i++) {
      lo[i] = Math.min(lo[i], p[i]);
      hi[i] = Math.max(hi[i], p[i]);
    }
  }
  return { lo, hi };
}

const load = (name) => JSON.parse(fs.readFileSync(path.join(RES, name + '.json'), 'utf8'));
const VEC = { north: [0, 0, -1], south: [0, 0, 1], east: [1, 0, 0], west: [-1, 0, 0] };
const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];

/** 发光板（uv 3..6 × 0..2 那一面）在模型空间里的『朝外』方向。 */
function plateNormal(model, index) {
  const e = model.elements[index];
  const angles = anglesOf(e.rotation);
  return rotateXyz([0, 0, -1], [0, 0, 0], angles);
}

// ----------------------------------------------------------------- 挂墙版

/** {@code DepOnWallBlock} 的四个碰撞箱（{@code rotatedBox}，1/16 格，x1/x2/y1/y2/z1/z2）。 */
const WALL_SHAPES = {
  north: [1, 15, 3, 13, 12, 16],
  east: [0, 4, 3, 13, 1, 15],
  south: [1, 15, 3, 13, 0, 4],
  west: [12, 16, 3, 13, 1, 15],
};

console.log('== 挂墙版 rawmodels/dep_on_wall.json');
{
  const model = load('dep_on_wall');
  check(model.elements.length === 6, `元素 6 个（背板/发光板/左右边柱/上下横梁），实测 ${model.elements.length}`);

  for (const facing of ['north', 'east', 'south', 'west']) {
    const { lo, hi } = aabb(cornersOf(model, facing));
    const want = WALL_SHAPES[facing];
    const same = [lo[0], hi[0], lo[1], hi[1], lo[2], hi[2]]
      .every((v, i) => Math.abs(v - want[i]) <= EPS);
    check(same, `facing=${facing}：模型包围盒 ${lo.map((v) => v.toFixed(2))}..${hi.map((v) => v.toFixed(2))}`
      + ` 与碰撞箱 x${want[0]}..${want[1]} y${want[2]}..${want[3]} z${want[4]}..${want[5]} 一致`);
  }

  const normal = plateNormal(model, 1);
  check(Math.abs(normal[1]) < 1e-6,
    `发光板法线 ${normal.map((v) => v.toFixed(4))} 是水平的（y = 0 → 模型不会立起来 / 躺下去）`);
  for (const facing of ['north', 'east', 'south', 'west']) {
    const n = rotateDir(normal, facing);
    const want = VEC[facing];
    check(Math.abs(dot(n, want) - 1) < 1e-6,
      `facing=${facing}：发光板朝 ${n.map((v) => v.toFixed(3))} = facing（屏幕正对 ${facing}，和碰撞箱 / 数字同一个朝向）`);
  }
}

// ----------------------------------------------------------------- 挂顶版

/**
 * 下面这几个数一一对应 Java 那边的常量，改那边就要改这里（名字都留着它们的出处）：
 * <ul>
 *   <li>{@code ROOF_YAW_FIX} = {@code DepOnRoofRenderer#MODEL_YAW_FIX_DEGREES}；</li>
 *   <li>{@code ROOF_MODEL_YAW} = {@code DepOnRoofBlock#MODEL_YAW_DEGREES}；</li>
 *   <li>{@code ROOF_PIVOT} = {@code DepOnRoofBlock#MODEL_PIVOT_X / MODEL_PIVOT_Z}；</li>
 *   <li>{@code ROOF_SCREEN_CENTER / ROOF_SCREEN_NORMAL} = {@code DepOnRoofRenderer} 的静态初始化；</li>
 *   <li>{@code ROOF_FRAME} = {@code DepOnRoofBlock#FRAME}（牌框四根，偏转前的模型空间）。</li>
 * </ul>
 */
const ROOF_YAW_FIX = 90;
const ROOF_MODEL_YAW = -45;
const ROOF_PIVOT = [8, 7.15];
const ROOF_PLATE = [8, 5, 9.34];
const ROOF_FRAME = [
  [1, 0, 5.3, 15, 1, 9.3],
  [1, 9, 5.3, 15, 10, 9.3],
  [1, 1, 5.3, 2, 9, 9.3],
  [14, 1, 5.3, 15, 9, 9.3],
];
const ROOF_SLICE = 2.5;

const ROOF_SCREEN_CENTER = (() => {
  const r = rad(ROOF_MODEL_YAW);
  const [c, s] = [Math.cos(r), Math.sin(r)];
  const dx = ROOF_PLATE[0] - ROOF_PIVOT[0];
  const dz = ROOF_PLATE[2] - ROOF_PIVOT[1];
  return [
    (ROOF_PIVOT[0] + dx * c - dz * s) / 16,
    ROOF_PLATE[1] / 16,
    (ROOF_PIVOT[1] + dx * s + dz * c) / 16,
  ];
})();

const ROOF_SCREEN_NORMAL = (() => {
  const r = rad(ROOF_MODEL_YAW);
  return [-Math.sin(r), 0, Math.cos(r)];
})();

/** 渲染器补的那一步：模型空间里绕<b>模型 pivot</b>转 {@code ROOF_YAW_FIX}（正 = 俯视逆时针）。 */
function rotateModelSpace(p, degree) {
  const r = rad(degree);
  const [c, s] = [Math.cos(r), Math.sin(r)];
  const [dx, dz] = [p[0] - ROOF_PIVOT[0], p[2] - ROOF_PIVOT[1]];
  return [ROOF_PIVOT[0] + dx * c + dz * s, p[1], ROOF_PIVOT[1] + (-dx * s + dz * c)];
}

/** 同上，但只转方向（法线不能拿带平移的点变换去转）。 */
function rotateModelDir(v, degree) {
  const r = rad(degree);
  const [c, s] = [Math.cos(r), Math.sin(r)];
  return [v[0] * c + v[2] * s, v[1], -v[0] * s + v[2] * c];
}

/** {@code DepOnRoofBlock#frameBoxes()} 的复刻：四根切段 + 按它那套约定偏转后的轴对齐盒。 */
function roofFrameBoxes() {
  const r = rad(ROOF_MODEL_YAW);
  const [c, s] = [Math.cos(r), Math.sin(r)];
  const out = [];
  for (const b of ROOF_FRAME) {
    const width = b[3] - b[0];
    const slices = Math.max(1, Math.ceil(width / ROOF_SLICE));
    for (let i = 0; i < slices; i++) {
      const x1 = b[0] + (width * i) / slices;
      const x2 = b[0] + (width * (i + 1)) / slices;
      const xs = [];
      const zs = [];
      for (const x of [x1, x2]) {
        for (const z of [b[2], b[5]]) {
          const dx = x - ROOF_PIVOT[0];
          const dz = z - ROOF_PIVOT[1];
          xs.push(ROOF_PIVOT[0] + dx * c - dz * s);
          zs.push(ROOF_PIVOT[1] + dx * s + dz * c);
        }
      }
      out.push([Math.min(...xs), b[1], Math.min(...zs), Math.max(...xs), b[4], Math.max(...zs)]);
    }
  }
  return out.flatMap((b) => [[b[0], b[1], b[2]], [b[3], b[4], b[5]]]);
}

console.log('\n== 挂顶版 rawmodels/dep_on_roof.json');
{
  const model = load('dep_on_roof');
  check(model.elements.length === 16, `元素 16 个（牌框 6 + 挂杆 10），实测 ${model.elements.length}`);

  // 挂杆：y 10..16 的十节，转过方块旋转后要仍然是一根竖杆、顶到 y=16（天花板）。
  const hanger = model.elements.slice(5, 15);
  for (const facing of ['north', 'east', 'south', 'west']) {
    const pts = [];
    for (const e of hanger) {
      const origin = e.rotation && e.rotation.origin ? e.rotation.origin : [0, 0, 0];
      const angles = anglesOf(e.rotation);
      for (const x of [e.from[0], e.to[0]]) {
        for (const y of [e.from[1], e.to[1]]) {
          for (const z of [e.from[2], e.to[2]]) {
            pts.push(rotatePoint(rotateXyz([x, y, z], origin, angles), facing));
          }
        }
      }
    }
    const { lo, hi } = aabb(pts);
    check(Math.abs(hi[1] - 16) < 1e-6, `facing=${facing}：挂杆顶端 y=${hi[1].toFixed(2)} 顶到本格上边界（16）`);
    check(hi[1] - lo[1] > 5.5, `facing=${facing}：挂杆高 ${(hi[1] - lo[1]).toFixed(2)}/16 格（十节都在，没被丢掉）`);
    check(hi[0] - lo[0] < 4.5 && hi[2] - lo[2] < 4.5,
      `facing=${facing}：挂杆包围盒 ${(hi[0] - lo[0]).toFixed(2)} × ${(hi[2] - lo[2]).toFixed(2)}（拧成一根杆，不是散开的碎片）`);
  }

  // 素材原样：牌框的旋转必须还是 135°（被吸附过就会变成 ±45/±22.5）。
  const angles = anglesOf(model.elements[0].rotation);
  check(Math.abs(angles[1] - 135) < 1e-6, `牌框旋转 y=${angles[1]}（素材原样 135，不是吸附后的 -45）`);
  const snapped = [];
  for (const e of model.elements) {
    const a = anglesOf(e.rotation);
    for (const v of a) {
      if (v !== 0 && Math.abs(v / 22.5 - Math.round(v / 22.5)) > 1e-6) snapped.push(v);
    }
  }
  check(snapped.length > 0,
    `挂杆十节保留了 ${snapped.length} 个非 22.5° 整数倍的角度（原版方块模型存不下这些值）`);

  // 牌面朝向：模型自己的正面是 -Z（发光板 north 面），素材自带 135°。
  // 渲染器会给它再补一个 MODEL_YAW_FIX_DEGREES（+90°）把素材的「数学正角」拧到代码这套
  // 「俯视顺时针」，转完之后必须和数字层 / 碰撞箱假定的那一面重合 —— 这就是本次修的 90°。
  const FIX = ROOF_YAW_FIX; // = DepOnRoofRenderer#MODEL_YAW_FIX_DEGREES
  const plateNormalFixed = rotateModelDir(plateNormal(model, 1), FIX);
  const assumed = ROOF_SCREEN_NORMAL; // DepOnRoofRenderer#SCREEN_NORMAL
  const angle = Math.acos(Math.max(-1, Math.min(1, dot(plateNormalFixed, assumed)))) * 180 / Math.PI;
  check(angle < 0.5,
    `牌面法线 ${plateNormalFixed.map((v) => v.toFixed(3))} = 数字层的 SCREEN_NORMAL ${assumed.map((v) => v.toFixed(3))}`
    + `（素材 135° + 补偿 ${FIX}°，差 ${angle.toFixed(1)}°）`);

  // 屏幕正中：渲染器是 PLATE(8, 5, 9.34) 绕 (8, 7.15) 按它那套约定转出来的，转完要和
  // 模型那块发光板（元素 1，z = 5.3，往观察者方向再挪 0.28）重合。
  // 注意变换顺序：先元素自带的 135°，再渲染器补的 +90°（矩阵链就是这个次序）。
  const plate = model.elements[1];
  const plateOrigin = plate.rotation.origin;
  const plateAngles = anglesOf(plate.rotation);
  const plateCenter = rotateModelSpace(
    rotateXyz([8, 5, 5.3 - 0.28], plateOrigin, plateAngles), FIX);
  const screenX = ROOF_SCREEN_CENTER[0] * 16;
  const screenZ = ROOF_SCREEN_CENTER[2] * 16;
  const off = Math.hypot(plateCenter[0] - screenX, plateCenter[2] - screenZ);
  check(off <= 0.5,
    `屏幕正中 (${screenX.toFixed(2)}, ${screenZ.toFixed(2)}) 落在发光板上`
    + `（模型板中心转完 = ${plateCenter[0].toFixed(2)}, ${plateCenter[2].toFixed(2)}，差 ${off.toFixed(2)}/16 格）`);

  // 碰撞箱：DepOnRoofBlock#frameBoxes 把牌框四根切段、按它那套约定偏转；转完的模型牌框
  // 必须落进同一片区域（否则玩家会撞到空气或者穿模）。
  const frameModel = [];
  for (const i of [2, 3, 4, 15]) {
    const e = model.elements[i];
    const origin = e.rotation.origin;
    const angles = anglesOf(e.rotation);
    const pts = [];
    for (const x of [e.from[0], e.to[0]]) {
      for (const y of [e.from[1], e.to[1]]) {
        for (const z of [e.from[2], e.to[2]]) {
          pts.push(rotateModelSpace(rotateXyz([x, y, z], origin, angles), FIX));
        }
      }
    }
    frameModel.push(...pts);
  }
  const mBox = aabb(frameModel);
  const cBox = aabb(roofFrameBoxes());
  const same = [mBox.lo[0], mBox.hi[0], mBox.lo[1], mBox.hi[1], mBox.lo[2], mBox.hi[2]]
    .every((v, i) => Math.abs(v - [cBox.lo[0], cBox.hi[0], cBox.lo[1], cBox.hi[1], cBox.lo[2], cBox.hi[2]][i]) <= 0.5);
  check(same, `牌框包围盒 x${mBox.lo[0].toFixed(2)}..${mBox.hi[0].toFixed(2)}`
    + ` z${mBox.lo[2].toFixed(2)}..${mBox.hi[2].toFixed(2)} 与 DepOnRoofBlock#frameBoxes`
    + ` x${cBox.lo[0].toFixed(2)}..${cBox.hi[0].toFixed(2)} z${cBox.lo[2].toFixed(2)}..${cBox.hi[2].toFixed(2)} 一致`);
}

console.log('\n' + (failures === 0 ? '全部通过' : `${failures} 项失败`));
process.exit(failures === 0 ? 0 : 1);
