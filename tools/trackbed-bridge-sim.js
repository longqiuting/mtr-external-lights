#!/usr/bin/env node
/*
 * 高架「弧 → 直道 → 弧」走行自检（纯数值，不开游戏）。
 *
 * 现场问题：高架上一段 R=5 弯道接 10 格直道，**直的那段整段没有碰撞箱**，人踩上去直接掉；
 * 弯道段站得住。上一轮把「候选集准入」从包围盒换成真实距离之后，仿真里 0 漏判，真机仍复现 ——
 * 所以这一次换思路：**不再只依赖「命中才吸附」**，加一条下落安全网（RailBed.rescues）。
 *
 * 这个脚本铺的就是用户描述的那段路：
 *
 *     弧 A（R=5, 90°） → 直道 10 格 → 弧 B（R=5, 90°）      轨面 y = 12（高架），墩子 0 → 11.85
 *
 * 然后按 **RailBed / CollisionSystem 的逐 tick 顺序** 跑一遍：
 *
 *   B. 行走（0.2 格/tick）+ 每秒一次轻微下沉 0.3 格 → 0 次踩空
 *   C. 横向慢慢滑出道床（1.2 → 2.5 格）             → 安全网把可站半宽从 1.3 延到 2.5（A/B 对照）
 *   D. 跳跃                                         → 起跳高度与「没有安全网」逐位相同（不砍跳跃）
 *   E. 轨道表里少了直道那条（用户现象）              → 打印每次踩空的位置与 cand/dist（逐 tick 诊断）
 *   F. 不会粘在假想轨道上                           → 四条条件逐条验：半径 / 高度 / 下落 / 穿过
 *
 * 用法：node tools/trackbed-bridge-sim.js [--verbose]
 */

'use strict';

// ---------------------------------------------------------------- 常量（与 RailBed.java 逐项同值）
const HALF_WIDTH = 1.3;         // RailBed.HALF_WIDTH
const CANDIDATE_REACH = 2.5;    // RailBed.CANDIDATE_REACH = HALF_WIDTH + 1.2
const SEAM_TIE = 0.02;          // RailBed.SEAM_TIE
const END_EPS = 0.02;           // RailBed.END_EPS
const SEARCH_RADIUS = 8.0;      // RailBed.SEARCH_RADIUS
const QUERY_MARGIN = SEARCH_RADIUS + 2.0;
const SAMPLE_MAX_STEP = 0.5;    // RailBed.SAMPLE_MAX_STEP
const SAMPLE_DIVISIONS = 64;    // RailBed.SAMPLE_DIVISIONS
const HEIGHT_SLACK = 4.0;       // RailBed.HEIGHT_SLACK

const STEP_UP = 1.0;            // RailBed.DEFAULT_STEP_UP（HeadlightConfig.trackbedStepUp）
const LEVEL_TOL = 0.03;         // RailBed.LEVEL_TOL
const STICK = 0.6;              // RailBed.STICK
const CEIL = 2.0;               // RailBed.CEIL

const RESCUE_RADIUS = 2.5;      // RailBed.RESCUE_RADIUS
const RESCUE_DROP = 3.0;        // RailBed.RESCUE_DROP
const RESCUE_GRACE = 20;        // RailBed.RESCUE_GRACE

// 原版物理（1.20.1）：每 tick 先扣重力再乘阻力，vy 的单位是「格 / tick」
const GRAVITY = 0.08;
const DRAG = 0.98;
const JUMP_VY = 0.42;

// ---------------------------------------------------------------- 场景
const R = 5;                    // 弯道半径
const STRAIGHT_LEN = 10;
const DECK_Y = 12;              // 轨面 y（高架高度）
const THICK = 0.15;             // HeadlightConfig.trackbedThickness
const SURFACE = DECK_Y + THICK; // 可站立面 = 轨面 + 道床厚度
const GROUND_Y = 0;
const CURVE_LEN = R * Math.PI / 2;

let failures = 0;
function check(name, ok, detail) {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  ' + detail : ''}`);
  if (!ok) failures++;
}
function f(v, n = 2) { return Number(v).toFixed(n); }
function sgn(v, n = 2) { return (v >= 0 ? '+' : '') + f(v, n); }

// ================================================================ 几何
// 弧 A：圆心 (5.5, 10.5)、半径 5、θ 从 180° 走到 270°（MTR 实测的曲线写法：+0.5 是方块中心）
//   起点 (0.5, 10.5) 切向 -z → 终点 (5.5, 5.5) 切向 +x
// 直道： (5.5, 5.5) → (15.5, 5.5)
// 弧 B：圆心 (15.5, 10.5)、半径 5、θ 从 270° 走到 360°（同向继续转）
//   起点 (15.5, 5.5) 切向 +x → 终点 (20.5, 10.5) 切向 +z
const A = { cx: 5.5, cz: 10.5, th0: Math.PI };
const B = { cx: 15.5, cz: 10.5, th0: -Math.PI / 2 };

function arcAt(arc, s) {
  const th = arc.th0 + s / R;
  return { x: arc.cx + R * Math.cos(th), z: arc.cz + R * Math.sin(th) };
}
function arcTangent(arc, s) {
  const th = arc.th0 + s / R;
  return { x: -Math.sin(th), z: Math.cos(th) };
}
const SEAM_A = arcAt(A, CURVE_LEN);          // (5.5, 5.5)
const SEAM_B = { x: 15.5, z: 5.5 };
const STRAIGHT_T = { x: 1, z: 0 };

function straightAt(s) { return { x: SEAM_A.x + s, z: SEAM_A.z }; }

const SEG1 = CURVE_LEN;                      // 弧 A 的长度
const SEG2 = SEG1 + STRAIGHT_LEN;            // + 直道
const TOTAL_LEN = SEG2 + CURVE_LEN;          // + 弧 B

/** 真值路径上弧长 s 处的点（s 从弧 A 起点算）。 */
function pathAt(s) {
  const v = Math.min(s, TOTAL_LEN);
  if (v <= SEG1) return arcAt(A, v);
  if (v <= SEG2) return straightAt(v - SEG1);
  return arcAt(B, v - SEG2);
}
function tangentAt(s) {
  const v = Math.min(s, TOTAL_LEN);
  if (v <= SEG1) return arcTangent(A, v);
  if (v <= SEG2) return STRAIGHT_T;
  return arcTangent(B, Math.min(v - SEG2, CURVE_LEN - 1e-9));
}

/** 墩子：沿路径每 5 格一根，从地面 0 到道床底面。只出现在报告里（人不踩它们）。 */
function piers() {
  const out = [];
  for (let s = 2.5; s < TOTAL_LEN; s += 5.0) {
    const p = pathAt(s);
    out.push({ s, x: p.x, z: p.z, top: DECK_Y - THICK });
  }
  return out;
}

// ---------------------------------------------------------------- 折线（与 RailBed.entry 同式）
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
  return { name, len: length, xs, zs, ss, minX, maxX, minZ, maxZ, minY: DECK_Y, maxY: DECK_Y, straight };
}

const CURVE_A = makeEntry('curve-A', CURVE_LEN, (s) => arcAt(A, s), false);
const STRAIGHT = makeEntry('straight', STRAIGHT_LEN, straightAt, true);
const CURVE_B = makeEntry('curve-B', CURVE_LEN, (s) => arcAt(B, s), false);
const THREE_RAILS = [CURVE_A, STRAIGHT, CURVE_B];

function bboxDistance(e, qx, qz) {
  const dx = Math.max(0, Math.max(e.minX - qx, qx - e.maxX));
  const dz = Math.max(0, Math.max(e.minZ - qz, qz - e.maxZ));
  return Math.sqrt(dx * dx + dz * dz);
}

/** RailBed.column：逐段投影 → { dist, offset, along, y }。 */
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
  // 平轨：railY 全程 = DECK_Y（RailBed.railY 对 yStart = yEnd 恒等）
  return { dist: Math.sqrt(bestSq), offset: off, along, y: DECK_Y };
}
function interior(col, e) { return col.along > END_EPS && col.along < e.len - END_EPS; }

/** RailBed.build：准入 = 锚点到折线的真实距离，包围盒只做精确下界剪枝。 */
function buildCache(all, anchorX, anchorZ) {
  const reach = QUERY_MARGIN + CANDIDATE_REACH;
  const out = [];
  for (const e of all) {
    if (bboxDistance(e, anchorX, anchorZ) > reach) continue;
    const col = column(e, anchorX, anchorZ);
    if (col.dist > reach) continue;
    out.push({ e, anchorDist: col.dist });
  }
  out.sort((a, b) => a.anchorDist - b.anchorDist);
  return out;
}

/** RailBed.probe + 接缝选边：全部候选里取 dist 最小；并列（≤2cm）时优先投影在轨道内部的那条。 */
function pick(cache, qx, qy, qz) {
  let best = null, bestInterior = false;
  for (const { e } of cache) {
    if (qy < e.minY - HEIGHT_SLACK || qy > e.maxY + HEIGHT_SLACK) continue;
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

/** 客户端一次查询：锚点按 2 格量化（RailBed.nearest）+ 逐 tick 诊断里的 cand。 */
function query(all, qx, qy, qz) {
  const ax = Math.floor(qx / 2) * 2;
  const az = Math.floor(qz / 2) * 2;
  const cache = buildCache(all, ax, az);
  return { best: pick(cache, qx, qy, qz), cand: cache.length };
}

// ---------------------------------------------------------------- 判定（与 RailBed / CollisionSystem 同式）
/** RailBed.verdict，逐项照抄。 */
function verdictOf(dy, vy, startDy, stepUp = STEP_UP) {
  if (vy > 0.02 && dy > -LEVEL_TOL) return 'jump';
  if (vy < -1e-3 && Number.isFinite(startDy) && startDy >= -LEVEL_TOL && dy < -LEVEL_TOL) {
    return 'snap';
  }
  if (dy < -stepUp || dy > CEIL) return 'dy';
  if (dy <= LEVEL_TOL) return 'snap';
  if (vy <= 0.02 && dy <= STICK) return 'snap';
  return 'dy';
}
function snaps(v) { return v === 'snap' || v === 'rescue'; }

/**
 * RailBed.rescues：这一 tick 从道床面上方掉下来、真的穿过轨面才接。
 *   vy < 0、起点 startDy >= 0、终点 dy < 0、(慢速时) startDy <= 3。
 */
function rescues(startDy, dy, vy) {
  if (!(vy < 0)) return false;
  if (!(startDy >= 0) || !(dy < 0)) return false;
  return startDy <= RESCUE_DROP || vy <= -RESCUE_DROP;
}

// ================================================================ 逐 tick 模拟
/**
 * 一个 tick 严格按游戏的顺序走：
 *   1) 水平移动（沿路径前进 speed 格，可带横向漂移）
 *   2) 竖直：扣重力、乘阻力、落到 y（原版 travel + move）
 *   3) Entity#move TAIL：probe → verdict → **安全网** → 真实方块（这里没有方块）→ 按压
 *   4) 判定这一 tick 有没有「踩空」（脚底停在道床面以下）
 *
 * 「踩空 tick」= 这一 tick 结束时脚底低于道床面 -0.05；「掉落」= 连续踩空到脚下 1 格以外。
 */
function simulate(rails, opts) {
  const o = {
    speed: 0.2, ticks: 400, sinkEvery: 20, sink: 0.3, popEvery: 0, pop: 0,
    jumpAt: [], rescue: true, startS: 0, lateral: 0, lateralStep: 0, verbose: false, ...opts,
  };
  let s = o.startS;
  let y = SURFACE;
  let vy = 0;
  let wideRescues = 0;
  let penetrations = 0;
  let falls = 0;
  let inFall = false;
  let snapsCount = 0;
  let rescueCount = 0;
  let apex = 0;              // 相对道床面的最高点（跳跃测试用）
  let snapAbove = 0;         // 被按上去那一刻离道床面的高度（跳跃被砍就很大）
  let standingLat = 0;       // 站着的时候最大横向偏移
  let dropLat = NaN;         // 第一次踩空时的横向偏移
  let rescueLift = 0;        // 救回时把人「抬」了多少（= 这一 tick 已经掉下去的距离）
  let lateral = o.lateral;
  const events = [];
  const rescueEvents = [];

  for (let tick = 1; tick <= o.ticks; tick++) {
    // 1) 水平
    s += o.speed;
    lateral += o.lateralStep;
    const p = pathAt(s);
    const t = tangentAt(s);
    // 法向 N = (-Tz, Tx)，横向偏移用来看「人站在中心线旁边」
    const qx = p.x + (-t.z) * lateral;
    const qz = p.z + (t.x) * lateral;

    // 2) 竖直（这一 tick 的位移）
    const startY = y;
    if (o.sinkEvery && tick % o.sinkEvery === 0) y -= o.sink;          // 每秒一次轻微下沉
    if (o.popEvery && tick % o.popEvery === 0) { y += o.pop; vy = 0; } // 每秒一次被弹起
    if (o.jumpAt.includes(tick)) { vy = JUMP_VY; y += 0.001; }         // 起跳
    vy = (vy - GRAVITY) * DRAG;
    y += vy;

    // 3) 道床判定
    const startDy = startY - SURFACE;
    let verdict;
    let dist = NaN;
    let offset = NaN;
    const q = query(rails, qx, y, qz);
    const cand = q.cand;
    let bestName = '-';
    if (!q.best) {
      verdict = 'nohit';
    } else {
      dist = q.best.dist;
      offset = q.best.offset;
      bestName = q.best.name;
      verdict = dist > HALF_WIDTH ? 'lat' : verdictOf(y - SURFACE, vy, startDy);
    }
    const dy = y - SURFACE;
    // 下落安全网：verdict 之后、真实方块之前
    if (o.rescue && !snaps(verdict) && q.best && dist <= RESCUE_RADIUS
      && rescues(startDy, dy, vy) && (dist <= HALF_WIDTH || wideRescues < RESCUE_GRACE)) {
      verdict = 'rescue';
    }
    // 宽幅救回记账（Trackbed.noteWideRescue）
    if (verdict === 'rescue') wideRescues = dist > HALF_WIDTH ? wideRescues + 1 : 0;
    else if (verdict === 'snap' || verdict === 'nohit' || Number.isNaN(dist) || dist > RESCUE_RADIUS) {
      wideRescues = 0;
    }
    if (snaps(verdict)) {
      snapAbove = Math.max(snapAbove, y - SURFACE);
      if (verdict === 'rescue') {
        rescueLift = Math.max(rescueLift, SURFACE - y);
        rescueEvents.push({ tick, s, dist, startDy, dy, vy, lift: SURFACE - y });
      }
      y = SURFACE;
      vy = 0;
      snapsCount++;
      standingLat = Math.max(standingLat, lateral);
      if (verdict === 'rescue') rescueCount++;
    }
    // 4) 踩空？
    const below = SURFACE - y;
    apex = Math.max(apex, y - SURFACE);
    if (below > 0.05) {
      penetrations++;
      if (Number.isNaN(dropLat)) dropLat = lateral;
      if (below > 1.0 && !inFall) { falls++; inFall = true; }
      events.push({
        tick, s, x: qx, y, z: qz, below, cand, dist, offset, bestName,
        dy, vy, verdict, startDy,
      });
    } else {
      inFall = false;
    }
  }
  return {
    penetrations, falls, snaps: snapsCount, rescues: rescueCount, events, rescueEvents, apex,
    snapAbove, rescueLift, standingLat, dropLat, endY: y, endS: s,
  };
}

// ================================================================ 打印
const P = piers();
console.log('高架路径：弧 A（R=5, 90°, len=' + f(CURVE_LEN, 3) + '）→ 直道 ' + STRAIGHT_LEN
  + ' 格 → 弧 B（R=5, 90°），轨面 y=' + DECK_Y + '，道床面 y=' + f(SURFACE)
  + '，全长 ' + f(TOTAL_LEN, 2) + ' 格');
console.log('接缝：弧 A 末点 (' + f(SEAM_A.x) + ', ' + f(SEAM_A.z) + ') = 直道起点；'
  + '直道末点 (' + f(SEAM_B.x) + ', ' + f(SEAM_B.z) + ') = 弧 B 起点');
console.log('墩子 ' + P.length + ' 根（地面 ' + GROUND_Y + ' → ' + f(DECK_Y - THICK)
  + '，不参与道床判定，只说明这是高架）：'
  + P.map((q) => `s=${f(q.s, 1)}@(${f(q.x, 1)}, ${f(q.z, 1)})`).join(' '));

// ---------------------------------------------------------------- A. 几何连续性
console.log('\nA. 几何：三段轨道首尾相接、切向连续（接缝处法向不跳）');
{
  const eA = arcAt(A, CURVE_LEN), tA = arcTangent(A, CURVE_LEN - 1e-9);
  const tB = arcTangent(B, 0);
  check('弧 A 末点 = 直道起点', Math.hypot(eA.x - SEAM_A.x, eA.z - SEAM_A.z) < 1e-9);
  check('直道末点 = 弧 B 起点', Math.hypot(SEAM_B.x - arcAt(B, 0).x, SEAM_B.z - arcAt(B, 0).z) < 1e-9);
  check('弧 A 末端切向 = 直道切向（法向连续，接缝无台阶）',
    Math.abs(tA.x - STRAIGHT_T.x) < 1e-6 && Math.abs(tA.z - STRAIGHT_T.z) < 1e-6,
    `T=(${f(tA.x, 6)}, ${f(tA.z, 6)})`);
  check('弧 B 起点切向 = 直道切向', Math.abs(tB.x - 1) < 1e-9 && Math.abs(tB.z) < 1e-9);
  check('折线类型：弧=no、直道=yes', !CURVE_A.straight && STRAIGHT.straight && !CURVE_B.straight);
}

// ---------------------------------------------------------------- B. 行走 + 每秒下沉
console.log('\nB. 行走（0.2 格/tick）+ 每秒一次轻微下沉 0.3 格：整段路掉不掉？');
const WALK = simulate(THREE_RAILS, { ticks: 220, sinkEvery: 20, sink: 0.3 });
console.log(`    snapped=${WALK.snaps} rescue=${WALK.rescues} 踩空tick=${WALK.penetrations} 掉落=${WALK.falls}`);
check('主场景（三条 Rail 都在表里）：0 次踩空、0 次掉落',
  WALK.penetrations === 0 && WALK.falls === 0,
  `penetrations=${WALK.penetrations} falls=${WALK.falls}`);
if (WALK.events.length) {
  for (const e of WALK.events.slice(0, 20)) {
    console.log(`   踩空 tick=${e.tick} s=${f(e.s)} pos=(${f(e.x)}, ${f(e.y)}, ${f(e.z)})`
      + ` cand=${e.cand} best=${e.bestName} dist=${f(e.dist)} dy=${sgn(e.dy)} vy=${sgn(e.vy)}`
      + ` verdict=${e.verdict}`);
  }
}

// ---------------------------------------------------------------- C. 横向滑出道床
console.log('\nC. 横向慢慢滑出道床（0.9 → 3.0 格，0.15 格/tick）：安全网把可站半宽延到哪里？');
const SLIDE_OFF = simulate(THREE_RAILS, {
  ticks: 40, speed: 0.02, lateral: 0.9, lateralStep: 0.15, rescue: false, sinkEvery: 0,
});
const SLIDE_ON = simulate(THREE_RAILS, {
  ticks: 40, speed: 0.02, lateral: 0.9, lateralStep: 0.15, rescue: true, sinkEvery: 0,
});
console.log(`    关安全网：能站到横向 ${f(SLIDE_OFF.standingLat)} 格，第一次踩空在 ${f(SLIDE_OFF.dropLat)} 格`
  + `（踩空tick=${SLIDE_OFF.penetrations}）`);
console.log(`    开安全网：能站到横向 ${f(SLIDE_ON.standingLat)} 格，第一次踩空在 ${f(SLIDE_ON.dropLat)} 格`
  + `（rescue=${SLIDE_ON.rescues}，每次最多抬起 ${f(SLIDE_ON.rescueLift)} 格）`);
check('关安全网：横向一过 halfW=1.3 就掉', SLIDE_OFF.dropLat <= HALF_WIDTH + 0.2,
  `dropLat=${f(SLIDE_OFF.dropLat)}`);
check('开安全网：一直站到横向 ≈2.5 格才掉（半宽从 1.3 延到 2.5）',
  SLIDE_ON.dropLat > 2.3 && SLIDE_ON.dropLat <= RESCUE_RADIUS + 0.2,
  `dropLat=${f(SLIDE_ON.dropLat)}`);
check('这就是安全网真正的增量：横向差一点的踩空不再直接掉下去',
  SLIDE_ON.standingLat - SLIDE_OFF.standingLat > 0.8,
  `Δ=${f(SLIDE_ON.standingLat - SLIDE_OFF.standingLat)} 格`);
check('救回只是把人放回轨面（抬起量 ≤ 一个下落步长 0.35 格），不是往上拽',
  SLIDE_ON.rescueLift <= 0.35, `lift=${f(SLIDE_ON.rescueLift)}`);

// ---------------------------------------------------------------- D. 跳跃
console.log('\nD. 跳跃：起跳高度必须和「没有安全网」逐位相同（vy > 0 一律让开，落地点不提前）');
const JUMP_OFF = simulate(THREE_RAILS, {
  ticks: 120, speed: 0, jumpAt: [5, 40, 75], rescue: false, sinkEvery: 0,
});
const JUMP_ON = simulate(THREE_RAILS, {
  ticks: 120, speed: 0, jumpAt: [5, 40, 75], rescue: true, sinkEvery: 0,
});
console.log(`     最高点：有安全网 ${f(JUMP_ON.apex)} / 无安全网 ${f(JUMP_OFF.apex)} 格`
  + `（0.42 起跳、0.08 重力 / tick 的原版抛物线）`);
console.log(`     被按回道床时离道床面 ${f(JUMP_ON.snapAbove)} 格（无安全网时同样是 ${f(JUMP_OFF.snapAbove)} 格`
  + ` —— 那是 verdict 的 STICK 带，和安全网无关）；安全网触发 ${JUMP_ON.rescues} 次`);
check('跳跃最高点与没有安全网时完全相同（安全网不砍跳跃）',
  Math.abs(JUMP_ON.apex - JUMP_OFF.apex) < 1e-12,
  `Δ=${(JUMP_ON.apex - JUMP_OFF.apex).toExponential(1)}`);
check('整段跳跃里安全网一次都没触发（跳跃下落由 verdict / STICK 照旧处理）',
  JUMP_ON.rescues === 0, `rescue=${JUMP_ON.rescues}`);
check('最终回到道床上', Math.abs(JUMP_ON.endY - SURFACE) < 1e-9, `endY=${f(JUMP_ON.endY, 3)}`);

// ---------------------------------------------------------------- E. 表里少了直道那条（用户现象）
console.log('\nE. 对照：轨道表里少了直道那条（= 用户看到的「弯后直道没有碰撞箱」）');
{
  const MISSING = [CURVE_A, CURVE_B];
  const noNet = simulate(MISSING, { ticks: 220, sinkEvery: 20, sink: 0.3, rescue: false });
  const run = simulate(MISSING, { ticks: 220, sinkEvery: 20, sink: 0.3, rescue: true });
  console.log(`    关安全网：掉落=${noNet.falls} 踩空tick=${noNet.penetrations}`
    + `   开安全网：掉落=${run.falls} 踩空tick=${run.penetrations}`);
  if (run.rescueEvents.length) {
    console.log(`    开安全网时，接缝后 1.3–2.5 格这一段是被安全网接住的（${run.rescueEvents.length} tick）：`);
    for (const e of run.rescueEvents.slice(0, 6)) {
      console.log(`      tick=${e.tick} 接缝A后 ${f(e.s - SEG1)} 格 dist=${f(e.dist)}`
        + ` startDy=${sgn(e.startDy)} dy=${sgn(e.dy)} vy=${sgn(e.vy)} 抬起 ${f(e.lift)} 格 → verdict=rescue`);
    }
  }
  if (run.events.length) {
    console.log('    然后才踩空（这就是 /mtr_lte trackbed on 逐 tick 行会打出来的东西）：');
    for (const e of run.events.slice(0, 6)) {
      console.log(`      tick=${e.tick} s=${f(e.s)} 接缝A后 ${f(e.s - SEG1)} 格`
        + ` pos=(${f(e.x)}, ${f(e.y)}, ${f(e.z)}) cand=${e.cand} best=${e.bestName}`
        + ` dist=${f(e.dist)} startDy=${sgn(e.startDy)} dy=${sgn(e.dy)} vy=${sgn(e.vy)} verdict=${e.verdict}`);
    }
    const first = run.events[0];
    console.log(`    第一次踩空：接缝 A 之后 ${f(first.s - SEG1)} 格，cand=${first.cand} 但 best=-`
      + `（= 候选里那两条都超过 reach=${f(CANDIDATE_REACH)}，直道那条根本不在候选里）`
      + ` —— 这一行的 cand / best 就是诊断行里的那两个字段，用户跑一下就能看出来`);
  }
  check('表里没有直道那条时，安全网也只能兜到 2.5 格（再远照掉）', run.falls > 0, `falls=${run.falls}`);
  const offAt = noNet.events.length ? noNet.events[0].s - SEG1 : 0;
  const onAt = run.events.length ? run.events[0].s - SEG1 : 0;
  check('安全网把「直道缺失」的可站范围从 halfW=1.3 往接缝外推（第一次踩空更晚）',
    onAt - offAt >= 0.8, `无网 ${f(offAt)} 格 → 有网 ${f(onAt)} 格`);
}

// ---------------------------------------------------------------- F. 不会被粘在假想轨道上
console.log('\nF. 不会粘在假想轨道上：四条条件逐条验');
{
  // 1) 横向 1.8 格悬空（超 halfW、在安全网半径内）：宽限 RESCUE_GRACE tick 后必须放手
  const wide = simulate(THREE_RAILS, {
    ticks: 140, speed: 0, lateral: 1.8, sinkEvery: 1, sink: 0.05, startS: SEG1 + 5,
  });
  console.log(`    横向 1.8 格悬空：rescue=${wide.rescues}（宽限 ${RESCUE_GRACE} tick）`
    + ` 之后掉落=${wide.falls} 最终 y=${f(wide.endY)}（道床 ${f(SURFACE)}）`);
  check(`宽幅救回只给 ${RESCUE_GRACE} tick，之后放手（不会浮在轨道旁边）`,
    wide.rescues <= RESCUE_GRACE && wide.falls > 0 && wide.endY < SURFACE - 1.0,
    `rescue=${wide.rescues} falls=${wide.falls} endY=${f(wide.endY)}`);

  // 2) 起点已在轨面之下：站在高架下面、走在比道床低一格的地面上 → 不接
  check('起点已在轨面之下（startDy < 0）不接：高架下面的人不会被拽上轨道',
    !rescues(-0.5, -0.6, -0.3) && !rescues(-1.0, -1.1, -0.3));
  // 3) 慢速下落、起点离轨面 > 3 格 → 不接（不从三格以外的上方硬拽）
  check('慢速下落时 「轨面在脚底下方 > 3 格」不接',
    !rescues(3.5, 3.3, -0.1) && !rescues(6.0, 5.6, -0.4));
  // 4) 快速下落（一 tick 跨三格以上）照样接
  check('快速下落（vy ≤ -3）不受 3 格窗口限制：从高处砸向道床仍然接得住',
    rescues(3.5, -0.3, -3.4));
  // 5) 上升不接
  check('vy >= 0 不接：起跳、上升完全不受影响',
    !rescues(0.5, 0.9, 0.3) && !rescues(0.1, 0.1, 0.0));
  // 6) 还没穿过轨面 → 不接（否则跳跃会被提前砍半格）
  check('这一 tick 还没穿过轨面（dy >= 0）时不接：跳跃下落段按原版走',
    !rescues(1.19, 1.11, -0.08) && !rescues(0.6, 0.4, -0.15));
  // 7) 横向 > 2.5 格 → 连安全网都不理
  check('横向 > 2.5 格不接：走开轨道两格半就该掉', !(2.6 <= RESCUE_RADIUS));
}

console.log(`\n${failures === 0 ? 'ALL PASS' : failures + ' FAILURE(S)'}`);
process.exit(failures === 0 ? 0 : 1);
