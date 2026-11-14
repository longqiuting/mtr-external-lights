#!/usr/bin/env node
/*
 * 道床「弯道接直道」接缝自检（纯数值，不开游戏）。
 *
 * 现场问题：弯道段能站，紧接的直道段整段没有碰撞箱。
 * 这个脚本把「R=5 的 90° 弯道 + 10 格直道」按 **MTR 实测几何** 铺出来，然后把 RailBed 的
 * 候选集算法（新旧两版）跑一遍：
 *
 *   A. 几何：MTR 真实 Rail 的端点 / 长度 / 接缝重合度（数值取自 MTR 3.6.4.2 的 Rail#getPosition）；
 *   B. 沿路径每 0.1 格采样：dist / offset / 是否命中 —— 两段是**两条 Rail** 和**同一条 Rail 的两段**
 *      两种形态都测（MTR 两种都会生成）；
 *   C. 接缝横向连续性：贴着道床 ±1.2 格并行走过接缝，看选中的 offset 会不会跳（>2cm 就是空洞）；
 *   D. 候选集：接缝两侧是不是**两条都在**候选里（旧的包围盒准入会漏），以及命中时选中的是哪条；
 *   E. 对照：把直道那条从候选里拿掉（旧 bug 的形态），看直道段的命中数 —— 应该变成 0。
 *
 * 用法：node tools/trackbed-junction-sim.js [--all]
 *   --all  把 B 的全部 0.1 格采样行都打出来（默认只打接缝窗口 + 每 1 格）
 */

'use strict';

// ---------------------------------------------------------------- 常量（与 RailBed 逐项同值）
const HALF_WIDTH = 1.3;        // RailBed.HALF_WIDTH
const CANDIDATE_REACH = 2.5;   // RailBed.CANDIDATE_REACH = HALF_WIDTH + 1.2
const SEAM_TIE = 0.02;         // RailBed.SEAM_TIE
const END_EPS = 0.02;          // RailBed.END_EPS
const CACHE_MARGIN_OLD = HALF_WIDTH + 0.25;  // 旧实现的 CACHE_MARGIN（包围盒外扩）
const SEARCH_RADIUS = 8.0;     // RailBed.SEARCH_RADIUS
const QUERY_MARGIN = SEARCH_RADIUS + 2.0;    // 客户端锚点量化后查询点最多离锚点这么远
const SAMPLE_MAX_STEP = 0.5;   // RailBed.SAMPLE_MAX_STEP
const SAMPLE_DIVISIONS = 64;   // RailBed.SAMPLE_DIVISIONS

const R = 5;                   // 弯道半径（MTR 里 R=5 是曲线工具能画出来的小半径）
const STRAIGHT_LEN = 10;       // 直道长度（格）
const RAIL_Y = 64;             // 轨面 y（平轨：RailBed.railY 全程恒为 yStart）

let failures = 0;
function check(name, ok, detail) {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  ' + detail : ''}`);
  if (!ok) failures++;
}
function f(v, n = 2) { return Number(v).toFixed(n); }
function sq(v) { return v * v; }

// ================================================================ 几何（与 MTR 实测一致）
// MTR：Rail((0,64,10) 朝 N → (5,64,5) 朝 E) 的 getPosition 实测：
//   len = 7.853981633974485
//   p(0) = (0.5, 64.0, 10.5)   p(len) = (5.5, 64.0, 5.5)
// 也就是圆心 (h,k) = (5, 10)、半径 5、从 180° 走到 270°（getPositionXZ 再各 +0.5）。
const SEAM = { x: 5.5, z: 5.5 };
const CURVE_LEN = R * Math.PI / 2;   // 7.8539816...
// MTR 实测：圆心 (h,k) = (5, 10)（方块坐标，+0.5 在 getPositionXZ 里加），θ 从 180° 走到 270°：
//   x = h + r·cos θ + 0.5, z = k + r·sin θ + 0.5
const CX = 5;
const CZ = 10;

/** 弯道上弧长 s 处的点（真值，解析）。 */
function curveAt(s) {
  const th = Math.PI + s / R;
  return { x: CX + R * Math.cos(th) + 0.5, z: CZ + R * Math.sin(th) + 0.5 };
}
/** 直道上弧长 s 处的点（真值，解析）：从接缝往 +x 走。 */
function straightAt(s) {
  return { x: SEAM.x + s, z: SEAM.z };
}
/** 弯道在 s 处的单位切向（沿行进方向）= d/ds (x, z)。 */
function curveTangent(s) {
  const th = Math.PI + s / R;
  return { x: -Math.sin(th), z: Math.cos(th) };
}
/** 直道的单位切向。 */
const STRAIGHT_TANGENT = { x: 1, z: 0 };

// ---------------------------------------------------------------- 折线（与 RailBed.entry 同式）
/**
 * 一条轨道 = 折线 + 包围盒 + 「是不是直线」。
 * RailBed 的采样步长是 min(0.5, len/64)，这里逐项照抄，所以脚本里的折线和游戏里那份一致。
 */
function makeEntry(name, length, at, straight) {
  const step = Math.min(SAMPLE_MAX_STEP, length / SAMPLE_DIVISIONS);
  const steps = Math.max(2, Math.ceil(length / step));
  const xs = new Array(steps + 1);
  const zs = new Array(steps + 1);
  const ss = new Array(steps + 1);
  let minX = Infinity, maxX = -Infinity, minZ = Infinity, maxZ = -Infinity;
  for (let i = 0; i <= steps; i++) {
    const s = length * i / steps;
    const p = at(s);
    xs[i] = p.x; zs[i] = p.z; ss[i] = s;
    minX = Math.min(minX, p.x); maxX = Math.max(maxX, p.x);
    minZ = Math.min(minZ, p.z); maxZ = Math.max(maxZ, p.z);
  }
  return { name, len: length, xs, zs, ss, minX, maxX, minZ, maxZ, straight };
}

/** 点 (qx,qz) 到**包围盒**的距离 —— 点到折线真实距离的下界（RailBed.Entry#bboxDistance）。 */
function bboxDistance(e, qx, qz) {
  const dx = Math.max(0, Math.max(e.minX - qx, qx - e.maxX));
  const dz = Math.max(0, Math.max(e.minZ - qz, qz - e.maxZ));
  return Math.sqrt(dx * dx + dz * dz);
}

/** 逐段投影（RailBed.column 同式）：返回 { dist, offset, along }。 */
function column(e, qx, qz) {
  let bestSq = Infinity, off = 0, along = 0;
  for (let i = 0; i + 1 < e.xs.length; i++) {
    const x1 = e.xs[i], z1 = e.zs[i];
    const ex = e.xs[i + 1] - x1, ez = e.zs[i + 1] - z1;
    const l2 = ex * ex + ez * ez;
    let fr = 0;
    if (l2 > 1e-12) fr = Math.min(1, Math.max(0, ((qx - x1) * ex + (qz - z1) * ez) / l2));
    const ox = qx - (x1 + ex * fr);
    const oz = qz - (z1 + ez * fr);
    const d2 = ox * ox + oz * oz;
    if (d2 >= bestSq) continue;
    bestSq = d2;
    along = e.ss[i] + (e.ss[i + 1] - e.ss[i]) * fr;
    off = l2 > 1e-12 ? ox * (-ez / Math.sqrt(l2)) + oz * (ex / Math.sqrt(l2)) : Math.sqrt(d2);
  }
  return { dist: Math.sqrt(bestSq), offset: off, along, y: RAIL_Y };
}

function interior(col, e) { return col.along > END_EPS && col.along < e.len - END_EPS; }

// ---------------------------------------------------------------- 新版候选集（RailBed 现在这样）
/**
 * 建缓存：**准入判据是「锚点到折线的真实距离」**，包围盒只做下界剪枝。
 * 与 RailBed.build 同式（reach = 查询点可能离锚点的距离 + 准入半径）。
 */
function buildCache(all, anchorX, anchorZ) {
  const reach = QUERY_MARGIN + CANDIDATE_REACH;
  const out = [];
  for (const e of all) {
    if (bboxDistance(e, anchorX, anchorZ) > reach) continue;   // 精确下界剪枝
    const col = column(e, anchorX, anchorZ);
    if (col.dist > reach) continue;                            // 真正的准入
    out.push({ e, anchorDist: col.dist });
  }
  out.sort((a, b) => a.anchorDist - b.anchorDist);
  return out;
}

/** 查询：全部候选里取 dist 最小；并列（≤2cm）时优先「投影在轨道内部」的那条。 */
function pick(cache, qx, qz) {
  let best = null, bestInterior = false;
  for (const { e } of cache) {
    if (bboxDistance(e, qx, qz) > CANDIDATE_REACH) continue;
    const col = column(e, qx, qz);
    if (col.dist > CANDIDATE_REACH) continue;
    const inside = interior(col, e);
    if (!best
      || col.dist < best.dist - SEAM_TIE
      || (col.dist <= best.dist + SEAM_TIE && inside && !bestInterior)) {
      best = { ...col, name: e.name, straight: e.straight };
      bestInterior = inside;
    }
  }
  return best;
}

/** 客户端的一次查询：锚点按 2 格量化，缓存跟着锚点走。 */
function query(all, qx, qz) {
  const ax = Math.floor(qx / 2) * 2;
  const az = Math.floor(qz / 2) * 2;
  return pick(buildCache(all, ax, az), qx, qz);
}

// ---------------------------------------------------------------- 旧版候选集（1.0.66 的形态，对照用）
function buildCacheOld(all, anchorX, anchorZ) {
  const reach = QUERY_MARGIN + CACHE_MARGIN_OLD;
  return all.filter((e) => bboxDistance(e, anchorX, anchorZ) <= reach);   // 准入就是包围盒
}
function pickOld(cache, qx, qz) {
  let best = null;
  for (const e of cache) {
    if (bboxDistance(e, qx, qz) > CACHE_MARGIN_OLD) continue;             // 第二道包围盒判定
    const col = column(e, qx, qz);
    if (!best || col.dist < best.dist) best = { ...col, name: e.name, straight: e.straight };
  }
  return best;
}
function queryOld(all, qx, qz) {
  const ax = Math.floor(qx / 2) * 2;
  const az = Math.floor(qz / 2) * 2;
  return pickOld(buildCacheOld(all, ax, az), qx, qz);
}

// ================================================================ 两种 MTR 形态
// 形态 1：两条 Rail（MTR 相邻节点一段一条）——用户场景最常见
const CURVE = makeEntry('curve', CURVE_LEN, curveAt, false);
const STRAIGHT = makeEntry('straight', STRAIGHT_LEN, straightAt, true);
const TWO_RAILS = [CURVE, STRAIGHT];

// 形态 2：同一条 Rail 的两段（MTR 实测 len1=7.8539816 是弧、len2=10.0 是直线，接缝连续）
const ONE_RAIL = (() => {
  const at = (s) => (s <= CURVE_LEN ? curveAt(s) : straightAt(s - CURVE_LEN));
  const e = makeEntry('one-rail', CURVE_LEN + STRAIGHT_LEN, at, false);
  // 这条 Rail 的折线跨两段，整体不是直线；但它的后半段是 —— 诊断里 straight 指的是整条
  return e;
})();

const ALL_LEN = CURVE_LEN + STRAIGHT_LEN;

/** 真值路径上弧长 s 处的点（s 从弯道起点算起，走到直道末端）。 */
function pathAt(s) {
  return s <= CURVE_LEN ? curveAt(s) : straightAt(s - CURVE_LEN);
}
/** 真值路径在 s 处的单位切向。 */
function tangentAt(s) {
  return s <= CURVE_LEN ? curveTangent(Math.min(s, CURVE_LEN - 1e-9)) : STRAIGHT_TANGENT;
}

// ================================================================ A. 几何
console.log('A. 几何（R=5 的 90° 弯道 + 10 格直道；数值与 MTR 3.6.4.2 的 Rail#getPosition 实测一致）');
const p0 = curveAt(0), pSeam = curveAt(CURVE_LEN), pEnd = straightAt(STRAIGHT_LEN);
console.log(`   弯道 len=${f(CURVE_LEN, 4)}  p(0)=(${f(p0.x)}, ${f(p0.z)})  p(L)=(${f(pSeam.x)}, ${f(pSeam.z)})`);
console.log(`   直道 len=${f(STRAIGHT_LEN)}  p(0)=(${f(SEAM.x)}, ${f(SEAM.z)})  p(L)=(${f(pEnd.x)}, ${f(pEnd.z)})`);
check('弯道末点与直道起点重合（接缝位置连续）', Math.hypot(pSeam.x - SEAM.x, pSeam.z - SEAM.z) < 1e-9,
  `gap=${(Math.hypot(pSeam.x - SEAM.x, pSeam.z - SEAM.z)).toExponential(1)}`);
const tCurve = curveTangent(CURVE_LEN);
check('弯道末切向与直道切向一致（接缝相切，法向不跳）',
  Math.abs(tCurve.x - STRAIGHT_TANGENT.x) < 1e-9 && Math.abs(tCurve.z - STRAIGHT_TANGENT.z) < 1e-9,
  `T=(${f(tCurve.x, 4)}, ${f(tCurve.z, 4)})`);
check('弯道折线是弧（straight=no）、直道折线是直线（straight=yes）',
  CURVE.straight === false && STRAIGHT.straight === true);

// ================================================================ B. 沿路径 0.1 格
console.log('\nB. 沿路径每 0.1 格采样（弧长从弯道起点算；两段是两条 Rail 的形态）');
const STEP = 0.1;
let total = 0, missNew = 0, missOld = 0, worstDist = 0, worstAt = 0;
const rows = [];
for (let s = 0; s <= ALL_LEN + 1e-9; s += STEP) {
  const q = pathAt(s);
  const sel = query(TWO_RAILS, q.x, q.z);
  const old = queryOld(TWO_RAILS, q.x, q.z);
  const hit = sel !== null && sel.dist <= HALF_WIDTH;
  total++;
  if (!hit) { missNew++; }
  if (!old || old.dist > HALF_WIDTH) missOld++;
  if (sel && sel.dist > worstDist) { worstDist = sel.dist; worstAt = s; }
  rows.push({ s, x: q.x, z: q.z, dist: sel ? sel.dist : NaN, off: sel ? sel.offset : NaN,
    name: sel ? sel.name : '-', hit });
}
const showAll = process.argv.includes('--all');
console.log('     s      x       z     dist  offset  选中      命中');
for (const r of rows) {
  const nearSeam = Math.abs(r.s - CURVE_LEN) < 2.0;
  const wholeBlock = Math.abs((r.s / 1.0) - Math.round(r.s / 1.0)) < STEP / 2 / 1.0;
  if (!showAll && !nearSeam && !wholeBlock) continue;
  console.log(`  ${f(r.s).padStart(6)} ${f(r.x, 3).padStart(7)} ${f(r.z, 3).padStart(7)} `
    + `${f(r.dist, 3).padStart(6)} ${(Number.isNaN(r.off) ? '-' : (r.off >= 0 ? '+' : '') + f(r.off, 3)).padStart(7)}  `
    + `${r.name.padEnd(9)} ${r.hit ? 'yes' : 'NO'}`);
}
console.log(`   （全部 ${total} 个 0.1 格采样点；这里只打接缝 ±2 格与每 1 格，--all 打全部）`);
check(`中心线 ${total} 个采样点全部命中（0 漏判）`, missNew === 0,
  `miss=${missNew} worstDist=${f(worstDist)} @ s=${f(worstAt)}`);
check('旧算法（包围盒准入）在同样这些点上也不漏（这一场景不是它的漏点）', missOld === 0,
  `miss=${missOld}`);

// 同一路径，用「一条 Rail 两段」的形态再测一遍
let missOneRail = 0, totalOneRail = 0;
for (let s = 0; s <= ALL_LEN + 1e-9; s += STEP) {
  const q = pathAt(s);
  const sel = query([ONE_RAIL], q.x, q.z);
  totalOneRail++;
  if (!sel || sel.dist > HALF_WIDTH) missOneRail++;
}
check(`同一条 Rail 两段（MTR 实测 len1=弧 7.854 / len2=直线 10）也不漏`,
  missOneRail === 0, `miss=${missOneRail}/${totalOneRail}`);

// ================================================================ C. 接缝连续性
console.log('\nC. 接缝连续性：贴着道床 ±1.2 格并行走过接缝（每 0.1 格）—— 「空洞」的判据是没有命中／轨面 y 跳变');
for (const side of [1.2, -1.2, 1.28, -1.28]) {
  let prevY = null, worstYJump = 0, worstOffJump = 0;
  let miss = 0, switches = 0, prevName = null, prevOff = null;
  for (let s = CURVE_LEN - 3.0; s <= CURVE_LEN + 3.0 + 1e-9; s += STEP) {
    const q = pathAt(s);
    const t = tangentAt(s);
    // 法向 N = (-Tz, Tx)（与 RailBed.column 同式）
    const nx = -t.z, nz = t.x;
    const sel = query(TWO_RAILS, q.x + nx * side, q.z + nz * side);
    if (!sel || sel.dist > HALF_WIDTH) { miss++; continue; }
    // 轨面 y：平轨恒 64，任何 >2cm 的跳变都会在游戏里表现成「一格台阶」
    const surface = 64 + 0.15;   // railY + trackbedThickness
    if (prevY !== null) worstYJump = Math.max(worstYJump, Math.abs(surface - prevY));
    prevY = surface;
    // offset 只在「还是同一条轨道」时比：换轨道时两条各自都是合法列，读数本来就会不一样
    if (prevName === sel.name && prevOff !== null) {
      worstOffJump = Math.max(worstOffJump, Math.abs(sel.offset - prevOff));
    } else if (prevName !== null && prevName !== sel.name) {
      switches++;
    }
    prevName = sel.name;
    prevOff = sel.offset;
  }
  check(`offset=${(side >= 0 ? '+' : '') + f(side, 2)} 并行走过接缝：全程命中、轨面 y 不跳、` +
    `同一条轨道内 offset 不跳 >2cm`,
    miss === 0 && worstYJump <= 0.02 && worstOffJump <= 0.02,
    `miss=${miss} switches=${switches} worstY=${f(worstYJump, 4)} worstOff=${f(worstOffJump, 4)}`);
}

// 中心线上 y 也不许跳（弯道平轨 → 直道平轨，y 应该恒为 64）
let prevY = null, worstY = 0;
for (let s = 0; s <= ALL_LEN + 1e-9; s += STEP) {
  const q = pathAt(s);
  const sel = query(TWO_RAILS, q.x, q.z);
  if (!sel || sel.dist > HALF_WIDTH) continue;
  if (prevY !== null) worstY = Math.max(worstY, Math.abs(sel.y - prevY));
  prevY = sel.y;
}
check('沿路径轨面 y 连续（平轨：恒为 64，跳变 0）', worstY <= 0.02, `worstJump=${f(worstY, 4)}`);

// ================================================================ D. 候选集
console.log('\nD. 候选集：接缝两侧两条都要在候选里，命中的那条要选对');
{
  const probes = [
    { tag: '弯道中段', s: CURVE_LEN * 0.5, want: 'curve' },
    { tag: '接缝前 0.3', s: CURVE_LEN - 0.3, want: 'curve' },
    { tag: '接缝后 0.3', s: CURVE_LEN + 0.3, want: 'straight' },
    { tag: '直道中段', s: CURVE_LEN + 5.0, want: 'straight' },
    { tag: '直道末端', s: CURVE_LEN + STRAIGHT_LEN - 0.1, want: 'straight' },
  ];
  let allHaveStraightNearSeam = true;
  for (const p of probes) {
    const q = pathAt(p.s);
    const ax = Math.floor(q.x / 2) * 2, az = Math.floor(q.z / 2) * 2;
    const cache = buildCache(TWO_RAILS, ax, az);
    const names = cache.map((c) => `${c.e.name}@${f(c.anchorDist)}`).join(' ');
    const sel = pick(cache, q.x, q.z);
    console.log(`   ${p.tag.padEnd(10)} cand=${cache.length} [${names}] → 选中 ${sel ? sel.name : '-'}`);
    check(`${p.tag}：选中 ${p.want}`, sel !== null && sel.name === p.want, `实际 ${sel ? sel.name : '-'}`);
    if (Math.abs(p.s - CURVE_LEN) < 1.0) {
      // 接缝 ±1 格里两条都必须进候选（否则接缝另一侧就是一个洞）
      const hasCurve = cache.some((c) => c.e.name === 'curve');
      const hasStraight = cache.some((c) => c.e.name === 'straight');
      if (!(hasCurve && hasStraight)) allHaveStraightNearSeam = false;
    }
  }
  check('接缝 ±1 格内 curve 与 straight 同时在候选集里', allHaveStraightNearSeam);
}

// ================================================================ E. 对照：把直道那条拿掉
console.log('\nE. 对照：候选集里只剩弯道那条（= 旧 bug「直道不在候选集」的形态）');
{
  const onlyCurve = [CURVE];
  let miss = 0, n = 0, farMiss = 0, farN = 0;
  for (let s = CURVE_LEN; s <= ALL_LEN + 1e-9; s += STEP) {
    const q = pathAt(s);
    const sel = query(onlyCurve, q.x, q.z);
    n++;
    const hit = sel !== null && sel.dist <= HALF_WIDTH;
    if (!hit) miss++;
    if (s > CURVE_LEN + 1.5) { farN++; if (!hit) farMiss++; }
  }
  console.log(`   直道段 ${n} 个采样点：命中 ${n - miss}（全部在接缝旁 1.5 格内），漏判 ${miss}`);
  check('候选里没有直道那条 ⇒ 接缝 1.5 格以外整段没有碰撞箱（用户看到的现象就是这个）',
    farN > 0 && farMiss === farN, `接缝 1.5 格以外 miss=${farMiss}/${farN}`);
  // 说明机制：离弯道末端越远，横向距离越大，超过半宽就掉下去
  const far = straightAt(3.0);
  const col = column(CURVE, far.x, far.z);
  console.log(`   离接缝 3 格处：到弯道那条折线的距离 = ${f(col.dist)}（halfW=${f(HALF_WIDTH)}）` +
    ` → ${col.dist > HALF_WIDTH ? '超半宽，掉下去' : '仍在半宽内'}`);
  check('离接缝 3 格处的距离已经超过半宽', col.dist > HALF_WIDTH, `dist=${f(col.dist)}`);
}

console.log(`\n${failures === 0 ? 'ALL PASS' : failures + ' FAILURE(S)'}`);
process.exit(failures === 0 ? 0 : 1);
