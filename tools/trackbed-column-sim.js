#!/usr/bin/env node
/*
 * 道床「横向列 + 弧长插值」自检（纯数值，不开游戏）。
 *
 * 三件事：
 *   A. railY 与 MTR 的高程曲线同式（端点 / 中点 / 单调性 / 与「最近采样点的 y」的差）；
 *   B. 上坡行走：老规则（脚底必须在轨面 ±0.03/0.10 内）每几步就断一次，新规则（dy >= -STEP_UP）不断；
 *   C. 弯道横向列：R=5/20/50 的 90° 弧上，横向偏移在 ±halfW 内外的判定与法向预期一致。
 *
 * 用法：node tools/trackbed-column-sim.js
 */
'use strict';

const STEP_UP = 1.0;      // 同 HeadlightConfig#trackbedStepUp 默认值
const LEVEL_TOL = 0.03;   // RailBed.LEVEL_TOL
const STICK = 0.6;        // RailBed.STICK
const CEIL = 2.0;         // RailBed.CEIL
const HALF_WIDTH = 1.3;   // RailBed.HALF_WIDTH
const STEP = 0.5;         // 折线采样步长上限（RailBed.SAMPLE_MAX_STEP）

let failures = 0;
function check(name, ok, detail) {
  const line = `  ${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  ' + detail : ''}`;
  console.log(line);
  if (!ok) failures++;
}
function near(a, b, tol) { return Math.abs(a - b) <= tol; }
function f(v, n = 4) { return Number(v).toFixed(n); }

// ---------------------------------------------------------------- railY（与 RailBed.railY 逐行同式）
// 复刻 MTR Rail#getPositionY：普通轨道是两端平、中间陡的二次曲线；缆车是两头平台 + 线性 + 下垂。
function railY(s, length, yStart, yEnd, cable) {
  if (!(length > 1e-6)) return yStart;
  const v = Math.min(Math.max(s, 0), length);
  const rise = yEnd - yStart;
  if (cable) {
    if (v < 0.5) return yStart;
    if (v > length - 0.5) return yEnd;
    const off = v - 0.5;
    const offLen = length - 1.0;
    if (!(offLen > 1e-6)) return yStart;
    const posY = yStart + rise * off / offLen;
    const dip = offLen * offLen / 4.0 / 1000.0;
    const shape = dip > 8.0 ? 8.0 / dip : 1.0;
    return posY + shape * (off - offLen) * off / 1000.0;
  }
  const half = length / 2.0;
  let yChange, yInitial, offset;
  if (v < half) { yChange = rise / 2.0; yInitial = yStart; offset = v; }
  else { yChange = -rise / 2.0; yInitial = yEnd; offset = length - v; }
  return yChange * offset * offset / (half * half) + yInitial;
}

// ---------------------------------------------------------------- verdict（与 RailBed.verdict 同式）
function verdict(dy, vy, startDy) {
  if (vy > 0.02 && dy > -LEVEL_TOL) return 'jump';
  if (vy < -1e-3 && !Number.isNaN(startDy) && startDy >= -LEVEL_TOL && dy < -LEVEL_TOL) return 'snap';
  if (dy < -STEP_UP || dy > CEIL) return 'dy';
  if (dy <= LEVEL_TOL) return 'snap';
  if (vy <= 0.02 && dy <= STICK) return 'snap';
  return 'dy';
}

// 旧实现（CollisionSystem#trackbed 2026-11 之前的样子）
function oldHolds(dy, vy, startDy) {
  const resting = vy <= 0.02 && dy >= -0.03 && dy <= 0.10;
  const crossed = vy < -1e-3 && startDy >= -0.03 && dy < 0;
  return resting || crossed;
}

// ================================================================ A. railY
console.log('A. railY —— 弧长插值（L=8，yStart=64，yEnd=68：坡道中点坡度 1.0 格/格）');
const L = 8, Y0 = 64, Y1 = 68;
check('y(0) = yStart', near(railY(0, L, Y0, Y1, false), Y0, 1e-9));
check('y(L) = yEnd', near(railY(L, L, Y0, Y1, false), Y1, 1e-9));
check('y(L/2) = 两端平均', near(railY(L / 2, L, Y0, Y1, false), (Y0 + Y1) / 2, 1e-9));
// 中点坡度：解析导数 = 2*(yEnd-yStart)/L = 1.0
const slopeMid = (railY(L / 2 + 1e-4, L, Y0, Y1, false) - railY(L / 2 - 1e-4, L, Y0, Y1, false)) / 2e-4;
check('中点坡度 = 1.0 格/格（1:1 坡）', near(slopeMid, 1.0, 1e-3), `slope=${f(slopeMid)}`);
let monotone = true;
for (let s = 0; s < L; s += 0.01) if (railY(s + 0.01, L, Y0, Y1, false) < railY(s, L, Y0, Y1, false) - 1e-12) monotone = false;
check('单调不减', monotone);

// 「最近采样点」的 y vs 「投影点弧长」的 y：这就是旧实现的离散误差。
let worstSampleErr = 0, worstAt = 0;
for (let s = 0; s <= L; s += 0.01) {
  const nearestSample = Math.round(s / STEP) * STEP;
  const err = Math.abs(railY(s, L, Y0, Y1, false) - railY(nearestSample, L, Y0, Y1, false));
  if (err > worstSampleErr) { worstSampleErr = err; worstAt = s; }
}
check('最近采样点 y 的最大误差 ≤ 0.30 格（旧实现的几何误差量级）', worstSampleErr <= 0.30,
  `max=${f(worstSampleErr)} 格 @ s=${f(worstAt, 2)}`);

// 缆车风格端点
check('缆车：y(0)=yStart, y(L)=yEnd', near(railY(0, L, Y0, Y1, true), Y0, 1e-9)
  && near(railY(L, L, Y0, Y1, true), Y1, 1e-9));

// ================================================================ B. 上坡行走
console.log('\nB. 上坡行走（步长 0.2 格/tick，从坡底走到坡顶）');
function walk(useOld) {
  const ticks = [];
  let s = 0, feet = railY(0, L, Y0, Y1, false), vy = 0, lost = 0, firstLoss = null, maxDrop = 0;
  for (let t = 0; t < 200 && s < L; t++) {
    const sPrev = s;
    const feetPrev = feet;
    s = Math.min(L, s + 0.2);
    const surface = railY(s, L, Y0, Y1, false);
    // 与 Java 一致：起点高差是拿「新位置」的轨面算的（坡道上比老轨面更严格）。
    const startDy = feetPrev - surface;
    const dy = feet - surface;
    const held = useOld ? oldHolds(dy, vy, startDy) : verdict(dy, vy, startDy) === 'snap';
    // 记一行（坡道中段附近）
    if (s > 3.4 && s < 4.6) {
      ticks.push({ s: f(s, 2), surface: f(surface, 3), dy: f(dy, 3), vy: f(vy, 2),
        verdict: useOld ? (held ? 'snap' : 'MISS') : verdict(dy, vy, startDy) });
    }
    if (held) {
      feet = surface;
      vy = 0;
    } else {
      lost++;
      if (firstLoss === null) firstLoss = f(s, 2);
      vy -= 0.08;             // 原版重力（未落地时每 tick 0.08）
      feet += vy;
      maxDrop = Math.max(maxDrop, surface - feet);
    }
  }
  return { lost, firstLoss, maxDrop, ticks };
}
const oldWalk = walk(true);
const newWalk = walk(false);
console.log('   近坡道中段（步长 0.2，轨面每步抬高约 0.2 格）：');
console.log('     s      surface   dy      vy     old     new');
for (const r of newWalk.ticks) {
  const oldRow = oldWalk.ticks.find((x) => x.s === r.s) || { verdict: '-' };
  console.log(`     ${r.s.padEnd(6)} ${r.surface.padEnd(9)} ${r.dy.padEnd(7)} ${String(r.vy).padEnd(6)} ${String(oldRow.verdict).padEnd(7)} ${r.verdict}`);
}
check('旧实现：上坡会掉（漏判 tick > 0）', oldWalk.lost > 0,
  `miss=${oldWalk.lost} 第一次在 s=${oldWalk.firstLoss}，最终离轨面 ${f(oldWalk.maxDrop, 2)} 格`);
check('新实现：上坡一次都不漏', newWalk.lost === 0, `miss=${newWalk.lost}`);

// 最陡的情况：每 tick 抬高 0.2，STEP_UP=1.0 → 余量 5 倍
check('坡度余量：单 tick 抬升 0.2 格 << STEP_UP 1.0', 0.2 <= STEP_UP, '5 倍余量');

// ================================================================ C. 弯道横向列
console.log('\nC. 弯道横向列（90° 弧，R=5/20/50）');
function arcPoints(R, n) {
  // MTR：圆心 (h+0.5, k+0.5)，半径 R，角度 t/R；这里取 h=k=0.5 的 90° 弧
  const pts = [];
  const len = R * Math.PI / 2;
  const steps = Math.max(2, Math.ceil(len / STEP));
  for (let i = 0; i <= steps; i++) {
    const s = len * i / steps;
    const th = s / R;
    pts.push({ x: 1.0 + R * Math.cos(th), z: 1.0 + R * Math.sin(th), s, len });
  }
  return pts;
}
function column(pts, qx, qz) {
  let best = Infinity, off = 0, along = 0;
  for (let i = 0; i + 1 < pts.length; i++) {
    const x1 = pts[i].x, z1 = pts[i].z;
    const ex = pts[i + 1].x - x1, ez = pts[i + 1].z - z1;
    const l2 = ex * ex + ez * ez;
    let frac = 0;
    if (l2 > 1e-12) frac = Math.min(1, Math.max(0, ((qx - x1) * ex + (qz - z1) * ez) / l2));
    const px = x1 + ex * frac, pz = z1 + ez * frac;
    const ox = qx - px, oz = qz - pz;
    const d2 = ox * ox + oz * oz;
    if (d2 >= best) continue;
    best = d2;
    along = pts[i].s + (pts[i + 1].s - pts[i].s) * frac;
    const inv = l2 > 1e-12 ? 1 / Math.sqrt(l2) : 0;
    off = ox * (-ez * inv) + oz * (ex * inv);   // N = T × UP = (-tz, tx)
  }
  return { dist: Math.sqrt(best), offset: off, along };
}
for (const R of [5, 20, 50]) {
  const pts = arcPoints(R, 0);
  let worst = 0, innerOk = true, outerOk = true, mid = null;
  // 弧中段（s = len/2），法向左右各 1.25 / 1.35 格
  const sMid = pts[pts.length - 1].s / 2;
  const th = sMid / R;
  const cx = 1.0 + R * Math.cos(th), cz = 1.0 + R * Math.sin(th);
  const ox = Math.cos(th), oz = Math.sin(th);   // 径向（向外的法向）
  for (const d of [-1.25, -1.0, 0, 1.0, 1.25, 1.35, -1.35]) {
    const c = column(pts, cx + ox * d, cz + oz * d);
    worst = Math.max(worst, Math.abs(Math.abs(c.offset) - Math.abs(d)));
    const hit = c.dist <= HALF_WIDTH;
    if (Math.abs(d) <= 1.25 && !hit) { if (d < 0) innerOk = false; else outerOk = false; }
    if (Math.abs(d) === 1.35 && hit) { if (d < 0) innerOk = false; else outerOk = false; }
    if (d === 0) mid = c;
  }
  check(`R=${R}：横向偏移 = 法向距离（误差 ≤ 0.01）`, worst <= 0.01, `worst=${f(worst)}`);
  check(`R=${R}：内侧 1.25 格内命中 / 1.35 格不命中`, innerOk);
  check(`R=${R}：外侧 1.25 格内命中 / 1.35 格不命中`, outerOk);
  check(`R=${R}：弧中段 s 回读正确`, near(mid.along, sMid, 0.01), `along=${f(mid.along, 3)} / ${f(sMid, 3)}`);
}

// ================================================================ D. 跳跃不被吃掉
console.log('\nD. 跳跃 / 落地');
const jump = [
  { dy: 0.0, vy: 0.42 }, { dy: 0.42, vy: 0.34 }, { dy: 0.76, vy: 0.18 },
  { dy: 1.02, vy: 0.02 }, { dy: 1.20, vy: -0.14 }, { dy: 1.30, vy: -0.30 },
  { dy: 0.9, vy: -0.46 }, { dy: 0.4, vy: -0.62 }, { dy: -0.1, vy: -0.78 },
].map((p, i, all) => ({ ...p, startDy: i === 0 ? 0 : all[i - 1].dy }));
let cancelled = false, landed = false;
for (const p of jump) {
  const v = verdict(p.dy, p.vy, p.startDy);
  if (p.vy > 0.02 && v === 'snap') cancelled = true;
  if (p.dy < 0 && v === 'snap') landed = true;
}
check('上升段一律不吸附（跳得起来）', !cancelled);
check('下落穿过轨面时接住（落得下）', landed);
const apex = verdict(1.25, 0.0, 0.8);
check('跳跃最高点（轨面上方 1.25）不被拽回', apex !== 'snap', `verdict=${apex}`);
const under = verdict(-3.0, 0.0, -3.0);
check('高架下方 3 格走过不吸附', under !== 'snap', `verdict=${under}`);

console.log(`\n${failures === 0 ? 'ALL PASS' : failures + ' FAILURE(S)'}`);
process.exit(failures === 0 ? 0 : 1);
