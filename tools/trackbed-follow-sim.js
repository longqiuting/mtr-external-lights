#!/usr/bin/env node
'use strict';

/*
 * 道床「状态延续」模型的纯计算仿真（不碰 MC、不碰 MTR，只有几何）。
 *
 * 场景：R=5 的 90° 弯道 → 10 格直道 → R=5 的 90° 弯道（三段轨道、两处接缝），
 * 玩家沿中心线以 0.215 格/tick（原版步行速度）走过整条路径，从<b>三个不同的起点</b>出发：
 *
 *   #1 弧 1/3 处            #2 接缝前 0.3 格（弧末端）      #3 接缝后 0.5 格（直道开头）
 *
 * 每一臂都跑两遍，唯一的区别是「这一 tick 的轨面从哪来」：
 *
 *   lookup-only  每 tick 从零查表：按 2 格量化锚点重建候选集（准入 = 到锚点折线距离
 *                ≤ CANDIDATE_REACH），再取最近的一条。这是「每 tick 从零查表」的模型。
 *   follow       状态延续：延续（不查表）→ 回退查表 → 跨段（端点键）。
 *
 * 表被故意做成「直道那条进不了候选缓存」（candidate cache 静默跳过它）——那正是
 * docs/trackbed-junction-report.md §1.3 列出来的三条静默路径之一（build 里单条异常 /
 * 高度粗筛 / physical 判成 NONE），也是真机上「弯道能站、紧接的直道整段没有碰撞」的成因。
 *
 * 两条臂共用同一张表、同一个起点、同一串位置，所以对照是干净的：差别只在状态。
 *
 * 用法： node tools/trackbed-follow-sim.js [--all]
 */

const R = 5;
const Y = 64;

// —— 判定常量：与 RailBed.java 逐个对齐 ——
const HALF_WIDTH = 1.3;
const CANDIDATE_REACH = HALF_WIDTH + 1.2;   // 2.5
const FOLLOW_REACH = 1.6;
const FOLLOW_BELOW = 1.5;
const FOLLOW_ABOVE = 1.0;
const HOP_REACH = 2.5;
const HOP_AHEAD = -0.35;
const ADVANCE_MAX = 2.0;
const ADVANCE_SLACK = 0.6;
const END_EPS = 0.02;
const END_LEAVE = HALF_WIDTH;   // 与 RailBed.END_LEAVE 一致：超过半宽才算「走出这一条」
const SEAM_TIE = 0.02;

// —— 走路的步长：原版步行 4.317 m/s ÷ 20 tick ——
const STEP = 0.215;

// ---------------------------------------------------------------------------------------------
// 轨道几何：与 MTR 的 Rail#getPosition 同构（弧长参数化，弧是圆弧、直道是线段）
// ---------------------------------------------------------------------------------------------

function arcRail(cx, cz, r, a0, a1, startKey, endKey, name) {
    const sweep = a1 - a0;
    const len = Math.abs(sweep) * r;
    const dir = Math.sign(sweep);
    return {
        name, kind: 'arc', len, straight: false, startKey, endKey,
        cx, cz, r, a0,
        pos(s) {
            const t = clamp(s, 0, len);
            const a = a0 + dir * (t / r);
            return { x: cx + r * Math.cos(a), z: cz + r * Math.sin(a), y: Y };
        },
    };
}

function lineRail(x0, z0, x1, z1, startKey, endKey, name) {
    const dx = x1 - x0;
    const dz = z1 - z0;
    const len = Math.hypot(dx, dz);
    return {
        name, kind: 'line', len, straight: true, startKey, endKey,
        pos(s) {
            const t = clamp(s, 0, len) / len;
            return { x: x0 + dx * t, z: z0 + dz * t, y: Y };
        },
    };
}

/** 反向轨：MTR 的表里每条实体轨道都有一份反向伙伴（独立的 Rail 对象，方向相反）。 */
function reverseRail(rail) {
    return {
        name: rail.name + '(rev)', kind: rail.kind, len: rail.len, straight: rail.straight,
        startKey: rail.endKey, endKey: rail.startKey,
        pos(s) { return rail.pos(rail.len - clamp(s, 0, rail.len)); },
    };
}

const key = (x, z) => `${x},${Y},${z}`;

// 节点（整数格）：A --弧--> B --直道--> C --弧--> D
const A = key(0, 10);
const B = key(5, 5);
const C = key(15, 5);
const D = key(20, 10);

const arc1 = arcRail(5, 10, R, Math.PI, Math.PI * 1.5, A, B, 'arc1');
const straight = lineRail(5, 5, 15, 5, B, C, 'straight');
const arc2 = arcRail(15, 10, R, Math.PI * 1.5, Math.PI * 2, C, D, 'arc2');

const RAILS = [arc1, straight, arc2];

/** 双向表：Map<端点键, Map<另一端点键, Rail>>（与 MTR 的 ClientData.RAILS 同构）。 */
function buildTable(rails) {
    const table = new Map();
    const all = [];
    for (const rail of rails) {
        if (!table.has(rail.startKey)) table.set(rail.startKey, new Map());
        table.get(rail.startKey).set(rail.endKey, rail);
        const back = reverseRail(rail);
        if (!table.has(back.startKey)) table.set(back.startKey, new Map());
        table.get(back.startKey).set(back.endKey, back);
        all.push(rail, back);
    }
    return { table, all };
}

const { table, all: ALL_RAILS } = buildTable(RAILS);

/**
 * 候选缓存「看不到」直道那条：models docs/trackbed-junction-report.md §1.3 的三条静默路径
 * （build 里单条轨道抛异常被跳过 / 高度粗筛 / physical 判成占位）。注意：进不了<b>缓存</b>，
 * 不代表 {@link geometry} 拿不到它 —— 跨段那一级直接调 geometry，所以它照样能用。
 * 正向轨和它的反向伙伴都要跳（MTR 的表里两条都在）。
 */
const CACHE_SKIPS = 'straight';

const cacheSkips = (rail) => rail.name.startsWith(CACHE_SKIPS);

/** 真实实现里 build() 的准入半径：queryMargin(SEARCH_RADIUS+2=10) + CANDIDATE_REACH。 */
const LOOKUP_REACH = 10 + CANDIDATE_REACH;

// ---------------------------------------------------------------------------------------------
// 折线 + 横向列（RailBed.entry / RailBed.column 的移植）
// ---------------------------------------------------------------------------------------------

const ENTRY = new Map();

function entry(rail) {
    if (ENTRY.has(rail)) return ENTRY.get(rail);
    const step = Math.min(0.5, rail.len / 64);
    const n = Math.max(2, Math.ceil(rail.len / step));
    const xs = [], zs = [], ss = [];
    let minX = Infinity, maxX = -Infinity, minZ = Infinity, maxZ = -Infinity;
    for (let i = 0; i <= n; i++) {
        const s = (rail.len * i) / n;
        const p = rail.pos(s);
        xs.push(p.x); zs.push(p.z); ss.push(s);
        minX = Math.min(minX, p.x); maxX = Math.max(maxX, p.x);
        minZ = Math.min(minZ, p.z); maxZ = Math.max(maxZ, p.z);
    }
    const e = { rail, len: rail.len, xs, zs, ss, minX, maxX, minZ, maxZ, y: Y };
    ENTRY.set(rail, e);
    return e;
}

/** 点 (qx,qz) 到折线的投影（可限定弧长窗口 [sLo, sHi]），返回 {distance, offset, along, y}。 */
function column(e, qx, qz, sLo = 0, sHi = e.len) {
    const last = e.xs.length - 1;
    let bestSq = Infinity, bestOffset = 0, bestAlong = 0;
    for (let i = 0; i < last; i++) {
        if (e.ss[i + 1] < sLo - 1e-9 || e.ss[i] > sHi + 1e-9) continue;
        const x1 = e.xs[i], z1 = e.zs[i];
        const ex = e.xs[i + 1] - x1, ez = e.zs[i + 1] - z1;
        const len2 = ex * ex + ez * ez;
        const dx = qx - x1, dz = qz - z1;
        let f = 0;
        if (len2 > 1e-12) f = clamp((dx * ex + dz * ez) / len2, 0, 1);
        const px = x1 + ex * f, pz = z1 + ez * f;
        const ox = qx - px, oz = qz - pz;
        const sq = ox * ox + oz * oz;
        if (sq >= bestSq) continue;
        bestSq = sq;
        bestAlong = e.ss[i] + (e.ss[i + 1] - e.ss[i]) * f;
        if (len2 > 1e-12) {
            const inv = 1 / Math.sqrt(len2);
            bestOffset = ox * (-ez * inv) + oz * (ex * inv);
        } else {
            bestOffset = Math.sqrt(sq);
        }
    }
    if (bestSq === Infinity) return null;
    return {
        distance: Math.sqrt(bestSq), offset: bestOffset, along: bestAlong,
        y: e.y, length: e.len,
    };
}

/** 折线在弧长 s 处的单位切向（沿正向）。 */
function tangentAt(e, s) {
    const last = e.xs.length - 1;
    for (let i = 0; i < last; i++) {
        if (s <= e.ss[i + 1] || i === last - 1) {
            return unit(e.xs[i + 1] - e.xs[i], e.zs[i + 1] - e.zs[i]);
        }
    }
    return { x: 0, z: 0 };
}

/** 端点处沿<b>行进方向</b>的单位切向。 */
function tangentAtEnd(e, atEnd) {
    const last = e.xs.length - 1;
    const i = atEnd ? last - 1 : 0;
    const t = unit(e.xs[i + 1] - e.xs[i], e.zs[i + 1] - e.zs[i]);
    return atEnd ? t : { x: -t.x, z: -t.z };
}

// ---------------------------------------------------------------------------------------------
// 第一臂：每 tick 从零查表（锚点缓存 + 最近横向列）
// ---------------------------------------------------------------------------------------------

function anchorOf(v) {
    return Math.floor(v / 2) * 2;
}

function candidates(anchorX, anchorZ) {
    const out = [];
    for (const rail of ALL_RAILS) {
        if (cacheSkips(rail)) continue;
        const e = entry(rail);
        // 包围盒距离 = 真实距离的下界（精确剪枝，与 RailBed 同一条）
        const dx = Math.max(0, Math.max(e.minX - anchorX, anchorX - e.maxX));
        const dz = Math.max(0, Math.max(e.minZ - anchorZ, anchorZ - e.maxZ));
        if (Math.hypot(dx, dz) > LOOKUP_REACH) continue;
        out.push(e);
    }
    return out;
}

function lookupOnly(x, z) {
    const list = candidates(anchorOf(x), anchorOf(z));
    let best = null;
    for (const e of list) {
        const s = column(e, x, z);
        if (!s || s.distance > CANDIDATE_REACH) continue;
        if (!best || s.distance < best.distance) best = s;
    }
    return best;
}

// ---------------------------------------------------------------------------------------------
// 第二臂：状态延续（RailBed.follow 的移植：延续 → 回退查表 → 跨段）
// ---------------------------------------------------------------------------------------------

const KIND = { CONTINUE: 'cont', TABLE: 'table', HOP: 'hop', LOST: 'lost' };

function accept(sample, feet) {
    if (!(sample.distance <= FOLLOW_REACH)) return false;
    // 投影钳在端点、人已经走出端点 0.35 格以外 → 这一条走到头了，不算接上。
    if ((sample.along <= END_EPS || sample.along >= sample.length - END_EPS)
        && sample.distance > END_LEAVE) {
        return false;
    }
    const dy = feet - sample.y;
    return dy >= -FOLLOW_BELOW && dy <= FOLLOW_ABOVE;
}

function advance(prev, x, feet, z) {
    const moved = Math.hypot(x - prev.x, z - prev.z);
    const window = Math.min(moved, ADVANCE_MAX) + ADVANCE_SLACK;
    const sample = column(prev.entry, x, z,
        Math.max(0, prev.s - window), Math.min(prev.entry.len, prev.s + window));
    return sample && accept(sample, feet) ? sample : null;
}

function hopOne(prev, x, feet, z) {
    const e = prev.entry;
    const last = e.xs.length - 1;
    const dStart = Math.hypot(x - e.xs[0], z - e.zs[0]);
    const dEnd = Math.hypot(x - e.xs[last], z - e.zs[last]);
    const atEnd = dEnd <= dStart;
    if (!(Math.min(dStart, dEnd) <= HOP_REACH)) return null;
    const keyHere = atEnd ? prev.endKey : prev.startKey;
    const back = atEnd ? prev.startKey : prev.endKey;
    const tangent = tangentAtEnd(e, atEnd);
    const ax = x - (atEnd ? e.xs[last] : e.xs[0]);
    const az = z - (atEnd ? e.zs[last] : e.zs[0]);
    const ad = Math.hypot(ax, az);
    if (ad > 1e-6 && (ax * tangent.x + az * tangent.z) / ad < HOP_AHEAD) return null;
    const inner = table.get(keyHere);
    if (!inner) return null;
    let best = null, bestFollow = null, bestTurn = Infinity;
    for (const [otherKey, rail] of inner.entries()) {
        if (rail === prev.rail) continue;
        if (otherKey === back) continue;       // 回头那条（反向伙伴）
        const ne = entry(rail);
        const sample = column(ne, x, z);
        if (!sample || !accept(sample, feet)) continue;
        const nt = tangentAt(ne, sample.along);
        const dot = tangent.x * nt.x + tangent.z * nt.z;
        if (!(dot > 0.25)) continue;           // 岔道上方向不对的那条
        const turn = 1 - dot;
        if (!best || sample.distance < best.distance - SEAM_TIE
            || (sample.distance <= best.distance + SEAM_TIE && turn < bestTurn)) {
            best = sample;
            bestTurn = turn;
            bestFollow = {
                rail, entry: ne, startKey: keyHere, endKey: otherKey,
                s: sample.along, y: sample.y, x, z,
            };
        }
    }
    return bestFollow ? { follow: bestFollow, sample: best, kind: KIND.HOP } : null;
}

function follow(prev, x, feet, z) {
    if (prev) {
        const a = advance(prev, x, feet, z);
        if (a) {
            return {
                follow: {
                    rail: prev.rail, entry: prev.entry, startKey: prev.startKey,
                    endKey: prev.endKey, s: a.along, y: a.y, x, z,
                },
                sample: a, kind: KIND.CONTINUE,
            };
        }
    }
    const t = lookupOnly(x, z);
    if (t && accept(t, feet)) {
        // 查表命中：把命中的那条接成新状态（真实实现里 probe 会记下 cand 的端点键）
        const hit = findRailAt(x, z, t);
        if (hit) {
            return {
                follow: { rail: hit.rail, entry: hit.entry, startKey: hit.rail.startKey,
                    endKey: hit.rail.endKey, s: t.along, y: t.y, x, z },
                sample: t, kind: KIND.TABLE,
            };
        }
    }
    const hop = hopOne(prev, x, feet, z);
    if (hop) return hop;
    return { follow: null, sample: t, kind: KIND.LOST };
}

/** 查表命中之后，反查是哪条候选（真实实现里这个信息由 probe 记在 lastEntry 上）。 */
function findRailAt(x, z, sample) {
    const list = candidates(anchorOf(x), anchorOf(z));
    let best = null;
    for (const e of list) {
        const s = column(e, x, z);
        if (!s || Math.abs(s.along - sample.along) > 1e-9
            || Math.abs(s.distance - sample.distance) > 1e-9) continue;
        if (!best) best = { rail: e.rail, entry: e };
    }
    return best;
}

// ---------------------------------------------------------------------------------------------
// 路径 + 走一遍
// ---------------------------------------------------------------------------------------------

const PIECES = [];
{
    let acc = 0;
    for (const rail of RAILS) {
        PIECES.push({ rail, s0: acc });
        acc += rail.len;
    }
}
const TOTAL = PIECES.reduce((sum, p) => sum + p.rail.len, 0);

/** 路径上全局弧长 gs 处的点（可加横向偏移 lateral，沿法向）。 */
function pathPoint(gs, lateral) {
    const piece = PIECES.find((p) => gs <= p.s0 + p.rail.len + 1e-9) || PIECES[PIECES.length - 1];
    const local = clamp(gs - piece.s0, 0, piece.rail.len);
    const p = piece.rail.pos(local);
    if (!lateral) return { x: p.x, z: p.z, y: p.y, piece, local };
    const e = entry(piece.rail);
    const t = tangentAt(e, local);
    return {
        x: p.x + -t.z * lateral, z: p.z + t.x * lateral, y: p.y, piece, local,
    };
}

/** 起点播种：模拟「上一 tick 已经站在这条轨道上」（一次成功的命中会得到同样的状态）。 */
function seed(gs, lateral) {
    const p = pathPoint(gs, lateral);
    const e = entry(p.piece.rail);
    const s = column(e, p.x, p.z);
    return {
        rail: p.piece.rail, entry: e, startKey: p.piece.rail.startKey,
        endKey: p.piece.rail.endKey, s: s.along, y: s.y, x: p.x, z: p.z,
    };
}

function walk(gs0, lateral, ticks, mode, dir = 1) {
    const stats = { ticks: 0, ok: 0, lost: 0, cont: 0, table: 0, hop: 0, maxDy: 0, maxDist: 0 };
    const trace = [];
    let state = mode === 'follow' ? seed(gs0, lateral) : null;
    for (let i = 0; i < ticks; i++) {
        const gs = gs0 + dir * (i + 1) * STEP;
        if (gs > TOTAL - 0.5 || gs < 0.5) break;
        const p = pathPoint(gs, lateral);
        stats.ticks++;
        let sample, kind;
        if (mode === 'follow') {
            const r = follow(state, p.x, p.y, p.z);
            state = r.follow;
            sample = r.sample;
            kind = r.kind;
            if (kind === KIND.CONTINUE) stats.cont++;
            else if (kind === KIND.TABLE) stats.table++;
            else if (kind === KIND.HOP) stats.hop++;
        } else {
            sample = lookupOnly(p.x, p.z);
            kind = sample ? 'table' : KIND.LOST;
        }
        const hit = !!sample && sample.distance <= HALF_WIDTH
            && Math.abs(p.y - sample.y) <= 0.03;
        if (hit) {
            stats.ok++;
        } else if (kind === KIND.LOST) {
            stats.lost++;
        } else {
            stats.lost++;   // 找到了但横向没踩上（lat）同样是「掉」
        }
        if (sample) {
            stats.maxDist = Math.max(stats.maxDist, sample.distance);
            stats.maxDy = Math.max(stats.maxDy, Math.abs(p.y - sample.y));
        }
        trace.push({ gs, kind, dist: sample ? sample.distance : NaN });
    }
    return { stats, trace };
}

// ---------------------------------------------------------------------------------------------
// 自检 + 输出
// ---------------------------------------------------------------------------------------------

function clamp(v, lo, hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

function unit(dx, dz) {
    const len = Math.hypot(dx, dz);
    return len > 1e-9 ? { x: dx / len, z: dz / len } : { x: 0, z: 0 };
}

function fmt(v, digits = 4) {
    return Number.isFinite(v) ? v.toFixed(digits) : '-';
}

const showAll = process.argv.includes('--all');

function main() {
    console.log('道床状态延续仿真：R=5 弧 → 10 格直道 → R=5 弧（3 段轨道、2 处接缝）');
    console.log(`路径全长 ${fmt(TOTAL, 3)} 格，步长 ${STEP} 格/tick（原版步行）`);
    console.log('');

    // A) 几何自检：接缝连续、相切
    const seam1a = arc1.pos(arc1.len);
    const seam1b = straight.pos(0);
    const seam2a = straight.pos(straight.len);
    const seam2b = arc2.pos(0);
    const t1a = tangentAt(entry(arc1), arc1.len - 1e-6);
    const t1b = tangentAt(entry(straight), 1e-6);
    const t2a = tangentAt(entry(straight), straight.len - 1e-6);
    const t2b = tangentAt(entry(arc2), 1e-6);
    console.log(`A. 接缝 1 位置连续   gap=${Math.hypot(seam1a.x - seam1b.x, seam1a.z - seam1b.z).toExponential(2)}`
        + `  切向 dot=${(t1a.x * t1b.x + t1a.z * t1b.z).toFixed(6)}`);
    console.log(`A. 接缝 2 位置连续   gap=${Math.hypot(seam2a.x - seam2b.x, seam2a.z - seam2b.z).toExponential(2)}`
        + `  切向 dot=${(t2a.x * t2b.x + t2a.z * t2b.z).toFixed(6)}`);
    console.log(`A. 段长 arc1=${fmt(arc1.len)} straight=${fmt(straight.len)} arc2=${fmt(arc2.len)}`);
    console.log('');

    // B) 三臂 × 三起点
    const ANCHORS = [
        { id: 1, at: arc1.len / 3, label: '弧 1/3 处（弧上）' },
        { id: 2, at: arc1.len - 0.3, label: '接缝前 0.3 格（弧末端）' },
        { id: 3, at: arc1.len + 0.5, label: '接缝后 0.5 格（直道开头）' },
    ];
    const LATERALS = [0, 0.5, -1.1];
    const TICKS = Math.ceil(TOTAL / STEP);

    let bad = 0;
    for (const anchor of ANCHORS) {
        console.log(`起点 #${anchor.id}  s=${fmt(anchor.at, 3)}  ${anchor.label}`);
        for (const lateral of LATERALS) {
            const legacy = walk(anchor.at, lateral, TICKS, 'lookup');
            const next = walk(anchor.at, lateral, TICKS, 'follow');
            const tag = lateral === 0 ? '' : ` (横向 ${lateral > 0 ? '+' : ''}${lateral})`;
            console.log(`  lookup-only${tag}  ticks=${String(legacy.stats.ticks).padStart(3)}`
                + ` ok=${String(legacy.stats.ok).padStart(3)} lost=${String(legacy.stats.lost).padStart(3)}`);
            console.log(`  follow     ${tag}  ticks=${String(next.stats.ticks).padStart(3)}`
                + ` ok=${String(next.stats.ok).padStart(3)} lost=${String(next.stats.lost).padStart(3)}`
                + `  cont=${next.stats.cont} table=${next.stats.table} hop=${next.stats.hop}`
                + `  maxDist=${fmt(next.stats.maxDist, 3)} maxDy=${fmt(next.stats.maxDy, 3)}`);
            if (next.stats.lost !== 0 || next.stats.ok !== next.stats.ticks) bad++;
            if (showAll && lateral === 0) {
                for (const row of next.trace) {
                    console.log(`      s=${fmt(row.gs, 2).padStart(7)} ${row.kind.padEnd(5)}`
                        + ` dist=${fmt(row.dist, 3)}`);
                }
            }
        }
        console.log('');
    }

    // C) 直道那段的逐点核对（用来对照 docs 里的「候选里只剩弯道」那条取证）
    let straightTicks = 0;
    let straightLostLegacy = 0;
    const leg = walk(arc1.len - 0.3, 0, TICKS, 'lookup');
    for (const row of leg.trace) {
        if (row.gs > arc1.len + 0.2 && row.gs < arc1.len + straight.len - 0.2) {
            straightTicks++;
            if (row.kind === KIND.LOST) straightLostLegacy++;
        }
    }
    console.log(`B. 直道中段 ${straightTicks} 个 tick 里，lookup-only 漏判 ${straightLostLegacy} 个`
        + `（「直道整段没有碰撞箱」的形态）`);

    // D) 反向走一遍：跨段必须能走「回头那条」以外的另一侧（反向伙伴要被挡掉，否则原地来回跳）
    const back = walk(TOTAL - 0.6, 0, TICKS, 'follow', -1);
    const backLegacy = walk(TOTAL - 0.6, 0, TICKS, 'lookup', -1);
    console.log(`D. 反向（从弧 2 末端往回）  lookup-only ok=${backLegacy.stats.ok}`
        + ` lost=${backLegacy.stats.lost}（共 ${backLegacy.stats.ticks}）`
        + `  follow ok=${back.stats.ok} lost=${back.stats.lost}`
        + `  cont=${back.stats.cont} table=${back.stats.table} hop=${back.stats.hop}`);
    let backBad = 0;
    if (back.stats.lost !== 0 || back.stats.ok !== back.stats.ticks) backBad++;

    const resultText = bad === 0 && backBad === 0
        ? 'lost=0（三起点 × 三横向 + 反向全接住）'
        : '有 ' + (bad + backBad) + ' 组没接住';
    console.log('C. follow 臂：' + resultText);
    console.log('');
    console.log(bad === 0 && backBad === 0 ? 'RESULT lost=0' : 'RESULT FAILED');
    process.exitCode = bad === 0 && backBad === 0 ? 0 : 1;
}

main();
