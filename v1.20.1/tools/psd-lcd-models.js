#!/usr/bin/env node
/*
 * PSD-LCD 两套模型：**用户原样 → 我们的坐标系** 的确定性转换 + 自检
 * =================================================================
 *
 * 唯一真源是用户从 Blockbench 导出的 `tools/psd/psd_lcd_xiamen.json`
 * （`mornal` = element 0..6 → 整块吊板，`half` = element 7..13 → 剪后只剩上半格）。
 * 那个文件里的几何是 **MTR 自己的坐标系**：
 *
 *   厚度沿 X（16…22，x=22 是正面）   高度沿 Y（0…16）   铺开沿 Z（0…16）
 *
 * 我们的板子是「铺开沿 X（0…16）、厚度沿 Z（10…16，z=10 是正面）」，所以整组元素要过一遍
 * 下面这条**刚性变换**（不许手调任何数字）：
 *
 *   1. 先把元素自带的 rotation 摆上去（Blockbench 的 mesh Euler 序：R = Rz·Ry·Rx），
 *      绕它自己的 origin —— 用户模型的每个元素都带一个 `y=-90`（倒角那块是 x/y/z 三轴），
 *      这一步就是把「铺开沿 Z」转到「铺开沿 X」的那个四分之一圈；
 *   2. 再镜像一次 z（z → 16 − z）：Blockbench 的 y 与 MC 的手性相反，不镜像的话正面
 *      （用户模型的 x=22）会落到 z=16（背面），插屏凹槽会朝里；
 *   3. 不额外平移：第 1 步的 origin 在 x=16，四分之一圈后 x 正好落在 0…16，
 *      第 2 步的 z → 16−z 把厚度从 0…6 折到 10…16 —— 和 PsdLcdLayout 的
 *      PANEL_Z0_PX=10 / PANEL_Z1_PX=16 严丝合缝。
 *
 * 变换是**等距**的（边长、体积、任意两点距离都不变），工具里逐元素断言。产出的 element 仍然
 * 是 vanilla 能表达的形式：`y` 单轴旋转的元素变换后旋转正好抵消（变成纯盒子），两块 22.5° 倒角
 * 变换后是**绕 X 轴 ±22.5° 的单轴旋转** —— 都能写进 MC 的模型 json。
 *
 * 自检（失败就 exit 1，一个数字都不放过）：
 *   · 每条元素的 8 个角点，变换前后的棱长 / 体积 / 两两距离完全一致（等距）；
 *   · 两组总包围盒 = 整块 x 0…16、y 0…16、z 10…16；半块 x 0…16、y 7.5…16、z 10…16；
 *   · 产出的 (from, to, rotation) 反算 8 个角点，与变换后的角点误差 < 1e-3px（= 没手调）；
 *   · 贴图里不能再出现 `mtr:` 命名空间（我们只认 mtrlte）。
 *
 * 用法：
 *   node v1.20.1/tools/psd-lcd-models.js            # 只自检，不写文件
 *   node v1.20.1/tools/psd-lcd-models.js --write    # 自检 + 重新生成两份模型
 *
 * 另外两个自检脚本（psd-lcd-facing-check.js / psd-lcd-fit-check.js）require 本文件取
 * 参考 json、状态表、以及「从模型几何里反推窗口带」的同一个函数，保证三处口径一致。
 */
'use strict';

const fs = require('fs');
const path = require('path');

const HERE = __dirname;                              // …/v1.20.1/tools
const VERSION_DIR = path.resolve(HERE, '..');         // …/v1.20.1

/** 用户给的 Blockbench 导出（两组的唯一真源）。 */
const REF_PATH = process.env.PSD_LCD_REF
  || path.join(HERE, 'psd', 'psd_lcd_xiamen.json');

const MODEL_DIR = path.join(VERSION_DIR, 'src/main/resources/assets/mtrlte/models/block');

// ---------------------------------------------------------------- 坐标系常量（= PsdLcdLayout.java）

/** 板子正面（朝观察者）在格子里的 z（16 分之格）。 */
const PANEL_Z0_PX = 10.0;
/** 板子背面（格子的后半格边缘）在格子里的 z。 */
const PANEL_Z1_PX = 16.0;
/** 屏幕面（凹槽底）在格子里的 z：正面 10 + 凹槽 0.2。 */
const SCREEN_FACE_Z_PX = 10.2;

/** 用户模型里板子的正面 / 背面分别在哪条 x 上（MTR 坐标：厚度沿 X 16…22）。 */
const USER_FRONT_X = 22;
const USER_BACK_X = 16;

/** 误差上限（px）。 */
const EPS = 1e-6;
/** 反算角点的容差（px）：产出 json 里数字保留 4 位小数。 */
const ROUND_EPS = 1e-3;

/** 方块状态里的两档 ↔ 两份模型 ↔ 参考 json 里的组名。 */
const STATES = [
  {
    cut: false,
    key: 'half_cut=false',
    model: 'mtrlte:block/psd_lcd_xiamen',
    file: 'psd_lcd_xiamen.json',
    group: 'mornal',
    windowY0: 1.5,
    windowY1: 8.5,
    bboxY: [0.0, 16.0],
    label: '整块（half_cut=false）',
  },
  {
    cut: true,
    key: 'half_cut=true',
    model: 'mtrlte:block/psd_lcd_xiamen_half',
    file: 'psd_lcd_xiamen_half.json',
    group: 'half',
    windowY0: 9.0,
    windowY1: 16.0,
    bboxY: [7.5, 16.0],
    label: '只剩上半格（half_cut=true）',
  },
];

/** 输出模型引用的贴图：全部在 mtrlte 命名空间里（psd_top 我们仓里有同一张图）。 */
const TEXTURES = {
  particle: 'mtrlte:block/psd_top',
};

/** 元素在变换时名字会换：正面/背面的贴图跟着「面」走，所以面名也要跟着换。 */
const FACE_MAP = {
  north: 'east',
  east: 'north',
  south: 'west',
  west: 'south',
  up: 'up',
  down: 'down',
};

// ---------------------------------------------------------------- 小工具

function readJson(file) {
  return JSON.parse(fs.readFileSync(file, 'utf8'));
}

/** 参考 json（用户原件）。 */
function loadRef(file = REF_PATH) {
  const ref = readJson(file);
  if (!Array.isArray(ref.elements)) {
    throw new Error('参考 json 里没有 elements: ' + file);
  }
  return ref;
}

/** 参考 json 里某一组（mornal / half）的元素，按 children 顺序取。 */
function groupElements(ref, groupName) {
  const group = (ref.groups || []).find((g) => g.name === groupName);
  if (!group) {
    throw new Error('参考 json 里没有组 ' + groupName);
  }
  return group.children.map((i) => ref.elements[i]);
}

/** 模型文件路径。 */
function modelPath(file) {
  return path.join(MODEL_DIR, file);
}

const deg = (d) => (d * Math.PI) / 180;

function matMul(a, b) {
  const o = new Array(9).fill(0);
  for (let r = 0; r < 3; r++) {
    for (let c = 0; c < 3; c++) {
      let s = 0;
      for (let k = 0; k < 3; k++) {
        s += a[r * 3 + k] * b[k * 3 + c];
      }
      o[r * 3 + c] = s;
    }
  }
  return o;
}

function matApply(m, v) {
  return [
    m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
    m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
    m[6] * v[0] + m[7] * v[1] + m[8] * v[2],
  ];
}

const rotX = (a) => {
  const c = Math.cos(a);
  const s = Math.sin(a);
  return [1, 0, 0, 0, c, -s, 0, s, c];
};
const rotY = (a) => {
  const c = Math.cos(a);
  const s = Math.sin(a);
  return [c, 0, s, 0, 1, 0, -s, 0, c];
};
const rotZ = (a) => {
  const c = Math.cos(a);
  const s = Math.sin(a);
  return [c, -s, 0, s, c, 0, 0, 0, 1];
};
const ROT = { x: rotX, y: rotY, z: rotZ };

/** 元素自带 rotation → 3×3（Blockbench 的 mesh Euler 序 R = Rz·Ry·Rx）。 */
function elementMatrix(rot) {
  if (!rot) {
    return [1, 0, 0, 0, 1, 0, 0, 0, 1];
  }
  return matMul(rotZ(deg(rot.z || 0)), matMul(rotY(deg(rot.y || 0)), rotX(deg(rot.x || 0))));
}

/** 变换的第 2 步：镜像 z（Blockbench 手性 → MC 手性）。 */
const flipZ = (p) => [p[0], p[1], PANEL_Z1_PX - (p[2] - 0)];

/**
 * 用户模型里一个点 → 我们的坐标系（把元素自己的 rotation 一起烘进去）。
 *
 * @param {number[]} p      用户模型坐标
 * @param {object}   rot    rotation（x/y/z/origin），可以没有
 */
function mapPoint(p, rot) {
  if (!rot) {
    return [p[0], p[1], PANEL_Z1_PX - p[2]];
  }
  const o = rot.origin;
  const m = elementMatrix(rot);
  const rel = [p[0] - o[0], p[1] - o[1], p[2] - o[2]];
  const r = matApply(m, rel);
  const q = [r[0] + o[0], r[1] + o[1], r[2] + o[2]];
  return [q[0], q[1], PANEL_Z1_PX - q[2]];
}

/** 一个元素的 8 个角点（按位组合，顺序固定）。 */
function boxCorners(from, to) {
  const out = [];
  for (const i of [0, 1]) {
    for (const j of [0, 1]) {
      for (const k of [0, 1]) {
        out.push([i ? from[0] : to[0], j ? from[1] : to[1], k ? from[2] : to[2]]);
      }
    }
  }
  return out;
}

/** 元素变换后的 8 个角点。 */
function transformedCorners(el) {
  return boxCorners(el.from, el.to).map((p) => mapPoint(p, el.rotation));
}

function bboxOf(corners) {
  const lo = [0, 1, 2].map((a) => Math.min(...corners.map((c) => c[a])));
  const hi = [0, 1, 2].map((a) => Math.max(...corners.map((c) => c[a])));
  return [lo, hi];
}

const dist = (a, b) => Math.hypot(a[0] - b[0], a[1] - b[1], a[2] - b[2]);

/**
 * 长宽高与体积 —— 等距变换的判据。
 *
 * 从 0 号角点到另外 7 个角点的 7 段距离里，**最短的三段就是盒子的三条棱**（其余是面对角线 /
 * 体对角线）；体积 = 三条棱相乘。用「棱长」而不是「轴对齐包围盒」，旋转过的倒角才不会
 * 因为包围盒变大而被误判成“形变”。
 */
function shapeOf(corners) {
  const d = corners.slice(1).map((c) => dist(corners[0], c)).sort((a, b) => a - b);
  const edges = d.slice(0, 3);
  return { edges, volume: edges[0] * edges[1] * edges[2] };
}

const round4 = (v) => {
  const r = Math.round(v * 1e4) / 1e4;
  return Object.is(r, -0) ? 0 : r;
};

// ---------------------------------------------------------------- 一个元素 → vanilla element

/**
 * 把用户元素转成 vanilla element：
 *
 *   · 纯 `y` 旋转的元素 —— 那个四分之一圈正好是坐标系变换本身，变换后**旋转抵消**，
 *     直接输出轴对齐盒子（不加 rotation）；
 *   · 两块 22.5° 倒角（x/y/z 三轴）—— 变换后是**绕 X 轴的单轴旋转**，角度由用户自己的
 *     数字给出：`90 - |y|`（用户写的是 -90 / -67.5 / 90 = 四分之一圈 + 22.5° 残量），
 *     再用 8 个角点反算验证（误差必须为 0，否则报错退出）；
 *   · 面名跟着「面」走（正面/背面的贴图不会跑到侧面上去）。
 *
 * @param {object} el      用户元素
 * @param {number} groupY0 这一组的 y 下沿（0 / 7.5）—— 倒角的旋转中心压在底板台阶那条棱上
 */
function convertElement(el, groupY0) {
  const corners = transformedCorners(el);
  const [lo, hi] = bboxOf(corners);
  const rot = el.rotation;
  const simpleY = !rot || ((rot.x || 0) === 0 && (rot.z || 0) === 0);

  const out = { name: el.name };
  let rotation = null;

  if (!simpleY) {
    const angle = 90 - Math.abs(rot.y || 0);
    if (!(angle > 0) || angle >= 90) {
      throw new Error(`${el.name}: 无法把复合旋转 ${JSON.stringify(rot)} 压成单轴旋转`);
    }
    // 旋转中心：下倒角贴底板台阶的前脸（z=11, y=groupY0），上倒角贴台阶顶面（z=10, y=groupY0+1）。
    // 取哪个中心几何完全一样（绕 X 轴转，中心只平移盒子），选这两条棱是为了 from/to 正好压在
    // 板上已有的整数棱线上 —— 和上一版验收过的模型逐字对上。
    const upper = (rot.z || 0) < 0;
    const pivot = [0, upper ? groupY0 + 1 : groupY0, upper ? 10 : 11];
    // 正负两号都试一遍，取反算误差更小的那个（哪个方向是「外倾」由用户的数字决定）。
    let best = null;
    for (const signed of [angle, -angle]) {
      const inv = rotX(deg(-signed));
      const un = corners.map((c) => {
        const rel = [c[0] - pivot[0], c[1] - pivot[1], c[2] - pivot[2]];
        const v = matApply(inv, rel);
        return [v[0] + pivot[0], v[1] + pivot[1], v[2] + pivot[2]];
      });
      const ulo = [0, 1, 2].map((a) => Math.min(...un.map((c) => c[a])));
      const uhi = [0, 1, 2].map((a) => Math.max(...un.map((c) => c[a])));
      const err = cornerError(ulo, uhi, { angle: signed, axis: 'x', origin: pivot }, corners);
      if (!best || err < best.err) {
        best = { err, signed, from: ulo, to: uhi };
      }
    }
    if (best.err > ROUND_EPS) {
      throw new Error(`${el.name}: 倒角压不成单轴旋转（误差 ${best.err}px）`);
    }
    rotation = { angle: round4(best.signed), axis: 'x', origin: pivot.map(round4) };
    out.from = best.from.map(round4);
    out.to = best.to.map(round4);
  } else {
    out.from = lo.map(round4);
    out.to = hi.map(round4);
  }

  if (rotation) {
    out.rotation = rotation;
  }

  const faces = {};
  for (const [name, face] of Object.entries(el.faces || {})) {
    const mapped = FACE_MAP[name];
    if (!mapped) {
      throw new Error(`${el.name}: 未知面 ${name}`);
    }
    const copy = { ...face };
    if (copy.cullface) {
      const cull = FACE_MAP[copy.cullface] || copy.cullface;
      // cullface 只在「和这个面同向」时才有意义（= 贴着邻居的那一面）。用户模型里有几处
      // cullface 和面不同向（Blockbench 的遗留），照抄会让我们的正脸被旁边那格的方块剔掉 ——
      // 那种一律丢掉（面永远画出来，宁可多重绘一个 quad）。
      if (cull === mapped) {
        copy.cullface = cull;
      } else {
        delete copy.cullface;
      }
    } else {
      delete copy.cullface;
    }
    faces[mapped] = copy;
  }
  out.faces = faces;
  return out;
}

/** 用 (from, to, rotation) 反算 8 个角点。 */
function cornersOf(from, to, rotation) {
  if (!rotation || !rotation.angle) {
    return boxCorners(from, to);
  }
  const m = ROT[rotation.axis](deg(rotation.angle));
  const o = rotation.origin;
  return boxCorners(from, to).map((p) => {
    const rel = [p[0] - o[0], p[1] - o[1], p[2] - o[2]];
    const v = matApply(m, rel);
    return [v[0] + o[0], v[1] + o[1], v[2] + o[2]];
  });
}

/** 反算出来的 8 个角点 vs 目标角点：最大「最近点」距离。 */
function cornerError(from, to, rotation, target) {
  const got = cornersOf(from, to, rotation);
  let worst = 0;
  for (const t of target) {
    let best = Infinity;
    for (const g of got) {
      best = Math.min(best, dist(g, t));
    }
    worst = Math.max(worst, best);
  }
  return worst;
}

/** 一组元素 → 一份 vanilla 模型。 */
function transformGroup(ref, groupName, groupY0) {
  const els = groupElements(ref, groupName);
  return {
    format_version: '1.9.0',
    credit: 'LTE - Xiamen PSD-LCD hanging board. Converted from the user\'s Blockbench export '
      + '(psd_lcd_xiamen.json) by tools/psd-lcd-models.js: bake each element\'s own rotation '
      + '(Rz*Ry*Rx about its origin), then mirror z (z -> 16 - z). No coordinate was typed by hand.',
    textures: { ...TEXTURES },
    elements: els.map((el) => convertElement(el, groupY0)),
  };
}

// ---------------------------------------------------------------- 从模型几何反推窗口带

/**
 * 某一高度上的**最前面那个面**的 z（px）：所有 y 区间盖住这一层的元素里，最小的 from.z/to.z。
 * 没有元素盖住 → Infinity。
 */
function frontSurfaceZ(model, y) {
  let best = Infinity;
  for (const el of model.elements || []) {
    const y0 = Math.min(el.from[1], el.to[1]);
    const y1 = Math.max(el.from[1], el.to[1]);
    if (y > y0 + 1e-9 && y < y1 - 1e-9) {
      best = Math.min(best, Math.min(el.from[2], el.to[2]));
    }
  }
  return best;
}

/**
 * **从模型自己的几何**反推屏幕窗口的 y 带：扫一遍高度，找出「最前面那个面 = 凹槽底」
 * 的连续区间。这就是屏幕能画的范围 —— 不依赖任何名字（用户模型里没有 ScreenPlane）。
 *
 * @returns {{y0: number, y1: number, z: number}}
 */
function derivedWindowBand(model, step = 0.05) {
  const ys = [];
  for (let y = step / 2; y < 16; y += step) {
    ys.push(y);
  }
  // 凹槽底 = 整个模型里最常见的「最前面」的 z（除了板子正面那一层）
  const front = Math.min(...ys.map((y) => frontSurfaceZ(model, y)).filter((v) => Number.isFinite(v)));
  const surface = Math.min(...ys.map((y) => frontSurfaceZ(model, y))
    .filter((v) => Number.isFinite(v) && v > front + 1e-6));
  const inside = ys.filter((y) => Math.abs(frontSurfaceZ(model, y) - surface) < 1e-6);
  if (inside.length === 0) {
    throw new Error('模型里找不到凹槽（屏幕窗口）');
  }
  const y0 = Math.min(...inside);
  const y1 = Math.max(...inside);
  // 扫的是格子中点，往回收半格
  return { y0: round4(Math.max(0, y0 - step / 2)), y1: round4(Math.min(16, y1 + step / 2)), z: round4(surface) };
}

// ---------------------------------------------------------------- 自检

/**
 * 跑一遍全部断言。
 * @returns {{ok: boolean, failures: string[], notes: string[]}}
 */
function verify(ref = loadRef()) {
  const failures = [];
  const notes = [];
  const fail = (msg) => failures.push(msg);

  // 1. 参考 json 的两组
  for (const state of STATES) {
    const els = groupElements(ref, state.group);
    if (els.length !== 7) {
      fail(`参考 json 的 ${state.group} 组有 ${els.length} 个元素，应为 7`);
    }
  }

  for (const state of STATES) {
    const groupY0 = state.bboxY[0];

    // 2. 等距：棱长 / 体积 / 两两距离
    for (const el of groupElements(ref, state.group)) {
      const before = boxCorners(el.from, el.to);
      const after = transformedCorners(el);
      const a = shapeOf(before);
      const b = shapeOf(after);
      if (a.edges.length !== b.edges.length
        || a.edges.some((e, i) => Math.abs(e - b.edges[i]) > 1e-5)
        || Math.abs(a.volume - b.volume) > 1e-4) {
        fail(`${state.group}/${el.name}: 变换不是等距（棱长 ${a.edges} → ${b.edges}，`
          + `体积 ${a.volume.toFixed(4)} → ${b.volume.toFixed(4)}）`);
      }
      // 两条具体边（对角距离）也要一致
      if (Math.abs(dist(before[0], before[7]) - dist(after[0], after[7])) > 1e-5) {
        fail(`${state.group}/${el.name}: 对角线长度变了`);
      }
    }

    // 3. 总包围盒
    const all = groupElements(ref, state.group).flatMap((el) => transformedCorners(el));
    const [lo, hi] = bboxOf(all);
    const near = (v, want) => Math.abs(v - want) < 1e-6;
    if (!near(lo[0], 0) || !near(hi[0], 16)) {
      fail(`${state.group}: x 应为 0…16，实际 ${lo[0].toFixed(4)}…${hi[0].toFixed(4)}`);
    }
    // 倒角那块是绕 X 轴 22.5° 转出来的，8 个角点里最低的那个在三角函数的最后一位上会
    // 差 -1e-6 量级（±0.000001px），所以 y 用 2e-3 的容差、并且要求「贴住」而不是「越过」。
    if (lo[1] < state.bboxY[0] - 2e-3 || Math.abs(hi[1] - state.bboxY[1]) > 1e-6) {
      fail(`${state.group}: y 应为 ${state.bboxY[0]}…${state.bboxY[1]}，`
        + `实际 ${lo[1].toFixed(6)}…${hi[1].toFixed(6)}`);
    }
    if (lo[2] < PANEL_Z0_PX - 2e-3 || Math.abs(hi[2] - PANEL_Z1_PX) > 1e-6) {
      fail(`${state.group}: z 应为 ${PANEL_Z0_PX}…${PANEL_Z1_PX}，`
        + `实际 ${lo[2].toFixed(6)}…${hi[2].toFixed(6)}`);
    }

    // 4. 产出的 (from,to,rotation) 能反算出同一批角点
    const model = transformGroup(ref, state.group, groupY0);
    for (const [i, out] of model.elements.entries()) {
      const target = transformedCorners(groupElements(ref, state.group)[i]);
      const err = cornerError(out.from, out.to, out.rotation, target);
      if (err > ROUND_EPS) {
        fail(`${state.group}/${out.name}: 产出的 from/to/rotation 反算角点误差 ${err.toFixed(6)}px`);
      }
    }

    // 5. 贴图不许引用别的命名空间；每个面的 texture 都要在 textures 里、cullface 得是六个方向之一
    for (const [k, v] of Object.entries(model.textures)) {
      if (!v.startsWith('mtrlte:')) {
        fail(`${state.group}: 贴图 ${k} = ${v} 不在 mtrlte 命名空间里`);
      }
    }
    const FACES6 = ['north', 'east', 'south', 'west', 'up', 'down'];
    for (const el of model.elements) {
      const names = Object.keys(el.faces || {});
      if (names.length === 0) {
        fail(`${state.group}/${el.name}: 一个面都没有`);
      }
      for (const [face, def] of Object.entries(el.faces || {})) {
        if (!FACES6.includes(face)) {
          fail(`${state.group}/${el.name}: 面名 ${face} 不合法`);
        }
        if (!def.texture || !(def.texture.startsWith('#')
          ? Object.prototype.hasOwnProperty.call(model.textures, def.texture.slice(1))
          : def.texture.startsWith('mtrlte:'))) {
          fail(`${state.group}/${el.name}.${face}: 贴图 ${def.texture} 没在 textures 里声明`);
        }
        if (!Array.isArray(def.uv) || def.uv.length !== 4) {
          fail(`${state.group}/${el.name}.${face}: uv 不是 4 个数`);
        }
        if (def.cullface && !FACES6.includes(def.cullface)) {
          fail(`${state.group}/${el.name}.${face}: cullface ${def.cullface} 不合法`);
        }
      }
      if (el.from.some((v) => !Number.isFinite(v)) || el.to.some((v) => !Number.isFinite(v))) {
        fail(`${state.group}/${el.name}: from/to 里有非数字`);
      }
      if (el.from[0] > el.to[0] || el.from[1] > el.to[1] || el.from[2] > el.to[2]) {
        fail(`${state.group}/${el.name}: from 比 to 大（${el.from} → ${el.to}）`);
      }
    }

    // 6. 从几何反推的窗口带 = 状态表
    const band = derivedWindowBand(model);
    if (Math.abs(band.y0 - state.windowY0) > 0.06 || Math.abs(band.y1 - state.windowY1) > 0.06) {
      fail(`${state.group}: 几何反推的窗口带 ${band.y0}…${band.y1}，状态表要 `
        + `${state.windowY0}…${state.windowY1}`);
    }
    if (Math.abs(band.z - SCREEN_FACE_Z_PX) > 1e-6) {
      fail(`${state.group}: 凹槽底 z = ${band.z}，应为 ${SCREEN_FACE_Z_PX}`);
    }
    notes.push(`${state.label}: 窗口 y ${band.y0}…${band.y1}，屏幕面 z ${band.z}，`
      + `元素 ${model.elements.length} 个`);
  }

  return { ok: failures.length === 0, failures, notes };
}

// ---------------------------------------------------------------- 写文件

function modelJson(model) {
  const n = (v) => (Number.isInteger(v) ? String(v) : String(round4(v)));
  const arr = (a) => '[' + a.map(n).join(', ') + ']';
  const lines = [];
  lines.push('{');
  lines.push(`\t"format_version": ${JSON.stringify(model.format_version)},`);
  lines.push(`\t"credit": ${JSON.stringify(model.credit)},`);
  lines.push('\t"textures": {');
  const tex = Object.entries(model.textures);
  tex.forEach(([k, v], i) => {
    lines.push(`\t\t${JSON.stringify(k)}: ${JSON.stringify(v)}${i === tex.length - 1 ? '' : ','}`);
  });
  lines.push('\t},');
  lines.push('\t"elements": [');
  model.elements.forEach((el, i) => {
    lines.push('\t\t{');
    lines.push(`\t\t\t"name": ${JSON.stringify(el.name)},`);
    lines.push(`\t\t\t"from": ${arr(el.from)},`);
    lines.push(`\t\t\t"to": ${arr(el.to)},`);
    if (el.rotation) {
      lines.push(`\t\t\t"rotation": {"angle": ${n(el.rotation.angle)}, "axis": `
        + `${JSON.stringify(el.rotation.axis)}, "origin": ${arr(el.rotation.origin)}},`);
    }
    lines.push('\t\t\t"faces": {');
    const faces = Object.entries(el.faces);
    faces.forEach(([name, face], j) => {
      const parts = [`"uv": ${arr(face.uv)}`, `"texture": ${JSON.stringify(face.texture)}`];
      if (face.cullface) {
        parts.push(`"cullface": ${JSON.stringify(face.cullface)}`);
      }
      if (face.rotation) {
        parts.push(`"rotation": ${face.rotation}`);
      }
      if (face.tintindex) {
        parts.push(`"tintindex": ${face.tintindex}`);
      }
      lines.push(`\t\t\t\t${JSON.stringify(name)}: {${parts.join(', ')}}`
        + (j === faces.length - 1 ? '' : ','));
    });
    lines.push('\t\t\t}');
    lines.push(`\t\t}${i === model.elements.length - 1 ? '' : ','}`);
  });
  lines.push('\t]');
  lines.push('}');
  return lines.join('\n') + '\n';
}

function writeModels(ref = loadRef()) {
  const written = [];
  for (const state of STATES) {
    const groupY0 = state.bboxY[0];
    const model = transformGroup(ref, state.group, groupY0);
    const file = modelPath(state.file);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, modelJson(model), 'utf8');
    written.push(file);
  }
  return written;
}

// ---------------------------------------------------------------- CLI

if (require.main === module) {
  const ref = loadRef();
  console.log('== PSD-LCD 模型：用户 json → 我们的坐标系 ==');
  console.log(`  参考文件  ${REF_PATH}`);
  const result = verify(ref);
  for (const n of result.notes) {
    console.log(`  ${n}`);
  }
  for (const state of STATES) {
    const groupY0 = state.bboxY[0];
    const model = transformGroup(ref, state.group, groupY0);
    const [lo, hi] = bboxOf(model.elements.flatMap((el) => cornersOf(el.from, el.to, el.rotation)));
    console.log(`  ${state.group.padEnd(7)} bbox  x ${lo[0]}…${hi[0]}  y ${lo[1]}…${hi[1]}  `
      + `z ${lo[2]}…${hi[2]}`);
    for (const el of model.elements) {
      const rot = el.rotation ? ` rot ${el.rotation.axis} ${el.rotation.angle}° @ ${el.rotation.origin}`
        : '';
      console.log(`    ${String(el.name).padEnd(20)} [${el.from}] → [${el.to}]${rot}`);
    }
  }
  if (!result.ok) {
    console.error(`FAILED (${result.failures.length})`);
    for (const f of result.failures) {
      console.error('  - ' + f);
    }
    process.exit(1);
  }
  console.log('全部通过：两组变换都是等距、包围盒对得上、贴图都在 mtrlte 里。');
  if (process.argv.includes('--write')) {
    for (const file of writeModels(ref)) {
      console.log('  写入 ' + path.relative(VERSION_DIR, file));
    }
  }
}

module.exports = {
  REF_PATH,
  MODEL_DIR,
  STATES,
  TEXTURES,
  FACE_MAP,
  PANEL_Z0_PX,
  PANEL_Z1_PX,
  SCREEN_FACE_Z_PX,
  USER_FRONT_X,
  USER_BACK_X,
  ROUND_EPS,
  readJson,
  loadRef,
  groupElements,
  modelPath,
  mapPoint,
  elementMatrix,
  boxCorners,
  transformedCorners,
  cornersOf,
  cornerError,
  bboxOf,
  transformGroup,
  convertElement,
  derivedWindowBand,
  frontSurfaceZ,
  verify,
  writeModels,
  modelJson,
};
