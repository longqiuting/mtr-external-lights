#!/usr/bin/env node
/*
 * Offline check of the light-slot binding rules in VehicleLights.java.
 *
 * For every MTR3 vehicle definition under resourcepacks/ (zips and folders) it rebuilds the
 * geometry MTR's PartInfo exposes at run time - a group's mean cube centre, in blocks, from the
 * .bbmodel that sits next to the vehicle JSON - and runs the same cascade the mod runs in game:
 *
 *   name + geometry (level 2)  ->  name only (level 3)  ->  estimated default (level 5)
 *
 * It exists so a rule change can be measured against the real packs without starting the game.
 * The numbers it prints are the ones quoted in docs/light-binding-report.md.
 *
 * Usage: node tools/check-light-binding.js [resourcepacks dir]
 */
'use strict';

const fs = require('fs');
const os = require('os');
const path = require('path');
const cp = require('child_process');

const RP = process.argv[2]
    || 'C:/Users/AD/Desktop/PCL/.minecraft/versions/1.20.1-Fabric 0.19.3/resourcepacks';
const TMP = path.join(os.tmpdir(), 'lte-light-binding');

// --- rules copied from VehicleLights.java ------------------------------------------------
const HEAD_WORDS = ['headlight', 'headlights', 'head_light', 'head_lights', 'headlamp', 'head_lamp',
    'lighthead', 'head_led', '前照', '头灯', 'toudeng', 'qiandeng'];
const TAIL_WORDS = ['taillight', 'taillights', 'tail_light', 'tail_lights', 'weideng', '尾灯'];
const REJECT_WORDS = ['always_on', 'alwayson', 'always', 'interior', 'door', 'roof', 'window',
    'side', 'cabin', '车内', '门', '顶', '窗', 'logo', 'display', 'lcd', 'tv', 'screen', 'plate',
    '牌照', 'reading', 'cargo', 'under', 'step', 'handrail', 'spot', 'marker', 'translucent',
    'stablization', 'stabilization'];
const ROOF_FRACTION = 0.95;
const END_FRACTION = 0.35;
const END_MIN_BLOCKS = 1.2;
const MAX_SECTIONS = 8;

// Mirrors of the pairing constants in VehicleLights.java.
const LAMP_ALONG_TOL = 1.0;
const LAMP_CLUSTER_TOL = 0.35;
const FACE_MATCH_BLOCKS = 0.6;
const MIRROR_TOL = 0.35;
const LAMP_DUPLICATE_TOL = 0.35;
const MAX_LAMPS = 4;
const LATERAL_DEAD_ZONE = 0.06;
const LATERAL_CLAMP_FRACTION = 0.45;
const DEFAULT_WIDTH = 2.9;

const containsAny = (s, list) => list.some((w) => s.includes(w));
const rejected = (s) => containsAny(s, REJECT_WORDS) || s.endsWith('_off') || s.endsWith('_closed');
const stageRank = (st) => st === 'ALWAYS_ON_LIGHTS' ? 4 : st === 'LIGHTS' ? 3
    : st === 'INTERIOR' ? 1 : 2;

/** One entry per lamp: sorted laterals greedily grouped at LAMP_CLUSTER_TOL, each at its mean. */
function clusters(values) {
    if (!values.length) {
        return [];
    }
    const sorted = values.slice().sort((a, b) => a - b);
    const out = [];
    let sum = sorted[0], last = sorted[0], n = 1;
    for (let i = 1; i < sorted.length; i++) {
        if (sorted[i] - last <= LAMP_CLUSTER_TOL) {
            sum += sorted[i];
            n++;
        } else {
            out.push(sum / n);
            sum = sorted[i];
            n = 1;
        }
        last = sorted[i];
    }
    out.push(sum / n);
    return out;
}

/** True when one of g's lamps mirrors a lamp already taken (offsets sum to ~0 about the centre). */
function mirrors(g, taken, centre) {
    for (const lamp of g.lamps) {
        for (const other of taken) {
            if (Math.abs((lamp - centre) + (other - centre)) <= MIRROR_TOL) {
                return true;
            }
        }
    }
    return false;
}

// --- pack discovery ----------------------------------------------------------------------
function walk(dir, out) {
    let entries;
    try {
        entries = fs.readdirSync(dir, {withFileTypes: true});
    } catch (e) {
        return;
    }
    for (const e of entries) {
        const p = path.join(dir, e.name);
        if (e.isDirectory()) {
            walk(p, out);
        } else if (e.name.toLowerCase().endsWith('.json')) {
            out.push(p);
        }
    }
}

function collect() {
    const files = [];
    const packs = [];
    if (!fs.existsSync(RP)) {
        throw new Error('resourcepacks not found: ' + RP);
    }
    fs.rmSync(TMP, {recursive: true, force: true});
    fs.mkdirSync(TMP, {recursive: true});
    for (const name of fs.readdirSync(RP)) {
        const p = path.join(RP, name);
        const st = fs.statSync(p);
        if (st.isDirectory()) {
            packs.push({name, kind: 'dir'});
            walk(p, files);
        } else if (name.toLowerCase().endsWith('.zip')) {
            packs.push({name, kind: 'zip'});
            const dest = path.join(TMP, String(packs.length));
            try {
                cp.execFileSync('unzip', ['-o', '-q', p, '*.json', '*.bbmodel', '-d', dest],
                    {stdio: 'ignore', timeout: 120000});
                walk(dest, files);
            } catch (e) {
                // A pack with no json at all is not an error for us.
            }
        }
    }
    return {packs, files};
}

// --- geometry from the .bbmodel ----------------------------------------------------------
/** bbmodel group name -> mean cube centre in blocks, plus the model bbox of those centres. */
function readModelGeometry(bbPath) {
    let bb;
    try {
        bb = JSON.parse(fs.readFileSync(bbPath, 'utf8'));
    } catch (e) {
        return null;
    }
    if (!bb || !Array.isArray(bb.elements)) {
        return null;
    }
    const byUuid = new Map();
    for (const e of bb.elements) {
        if (e && e.uuid) {
            byUuid.set(e.uuid, e);
        }
    }
    const groups = new Map();        // name -> {cubes: [{x,y,z}], loY, hiY}
    const groupsOf = (list) => {
        for (const it of list) {
            if (typeof it === 'string') {
                continue;            // a cube directly under outliner root: unnamed
            }
            if (!it || !Array.isArray(it.children)) {
                continue;
            }
            const name = it.name;
            if (!name) {
                continue;
            }
            if (!groups.has(name)) {
                groups.set(name, {cubes: [], loY: Infinity, hiY: -Infinity});
            }
            const g = groups.get(name);
            const visit = (children) => {
                for (const c of children) {
                    if (typeof c === 'string') {
                        const e = byUuid.get(c);
                        if (!e || !Array.isArray(e.from) || !Array.isArray(e.to)) {
                            continue;
                        }
                        // bbmodel units are 1/16 block. One cube = one PartInfo at run time (MTR
                        // stores origin + offset per cube), so the cube centre is kept - NOT
                        // averaged into the group: averaging is what turned a left/right pair
                        // into a single centre point.
                        g.cubes.push({
                            x: (e.from[0] + e.to[0]) / 2 / 16,
                            y: (e.from[1] + e.to[1]) / 2 / 16,
                            z: (e.from[2] + e.to[2]) / 2 / 16,
                        });
                        // Y extent from the cube's own faces, so the roof test is not measured
                        // against a bbox of pivots (PartInfo.height does the same in the mod).
                        g.loY = Math.min(g.loY, Math.min(e.from[1], e.to[1]) / 16);
                        g.hiY = Math.max(g.hiY, Math.max(e.from[1], e.to[1]) / 16);
                    } else if (c && Array.isArray(c.children)) {
                        visit(c.children);
                    }
                }
            };
            visit(it.children);
        }
    };
    groupsOf(bb.outliner || []);
    if (groups.size === 0) {
        return null;
    }
    let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
    let minZ = Infinity, maxZ = -Infinity;
    const out = new Map();
    for (const [name, g] of groups) {
        if (g.cubes.length === 0) {
            continue;
        }
        out.set(name, g);
        for (const c of g.cubes) {
            minX = Math.min(minX, c.x); maxX = Math.max(maxX, c.x);
            minZ = Math.min(minZ, c.z); maxZ = Math.max(maxZ, c.z);
        }
        minY = Math.min(minY, g.loY); maxY = Math.max(maxY, g.hiY);
    }
    if (out.size === 0) {
        return null;
    }
    return {groups: out, minX, maxX, minY, maxY, minZ, maxZ};
}

// --- one model ---------------------------------------------------------------------------
function analyse(o, geo) {
    // One instance per placement: the vehicle JSON lists, per part, the "positions" (1/16 block
    // x/z offsets) where a copy of it sits. A lamp pair is two placements in one car; a bbmodel
    // holding a whole consist has one placement per car. Averaging all of them would blend
    // separate cars, so they are bucketed per car section below.
    const inst = new Map();
    const stageOf = new Map();
    for (const p of o.parts) {
        if (!p || !p.name) {
            continue;
        }
        const c = geo.groups.get(p.name);
        if (!c) {
            continue;
        }
        const prev = stageOf.get(p.name);
        if (!prev || stageRank(p.stage || '?') > stageRank(prev.stage)) {
            stageOf.set(p.name, {stage: p.stage || '?', cond: p.render_condition || '?'});
        }
        const positions = Array.isArray(p.positions) && p.positions.length ? p.positions : [[0, 0]];
        if (!inst.has(p.name)) {
            inst.set(p.name, []);
        }
        const list = inst.get(p.name);
        for (const cube of c.cubes) {
            for (const pos of positions) {
                list.push({x: cube.x + (pos[0] || 0) / 16, y: cube.y,
                    z: cube.z + (pos[1] || 0) / 16, loY: c.loY, hiY: c.hiY});
            }
        }
    }
    if (inst.size === 0) {
        return null;
    }
    let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
    let minZ = Infinity, maxZ = -Infinity;
    for (const list of inst.values()) {
        for (const i of list) {
            minX = Math.min(minX, i.x); maxX = Math.max(maxX, i.x);
            minY = Math.min(minY, i.loY); maxY = Math.max(maxY, i.hiY);
            minZ = Math.min(minZ, i.z); maxZ = Math.max(maxZ, i.z);
        }
    }
    const zAxis = (maxZ - minZ) >= (maxX - minX);
    const span = Math.max(1e-6, zAxis ? maxZ - minZ : maxX - minX);
    const uMin = zAxis ? minZ : minX;
    // Centreline = median of the per-group lateral means (one vote per group). The bbox midpoint is
    // thrown off by mirrors / open doors / a second car; a per-cube median by one dense group.
    const groupLaterals = [];
    for (const list of inst.values()) {
        let sum = 0;
        for (const i of list) {
            sum += zAxis ? i.x : i.z;
        }
        groupLaterals.push(list.length ? sum / list.length : 0);
    }
    groupLaterals.sort((a, b) => a - b);
    const latCentre = groupLaterals.length === 0 ? 0
        : groupLaterals.length % 2 === 1
            ? groupLaterals[(groupLaterals.length - 1) / 2]
            : (groupLaterals[groupLaterals.length / 2 - 1]
                + groupLaterals[groupLaterals.length / 2]) / 2;
    let halfWidth = 0;
    for (const list of inst.values()) {
        for (const i of list) {
            halfWidth = Math.max(halfWidth, Math.abs((zAxis ? i.x : i.z) - latCentre));
        }
    }

    const length = Number(o.length) || 0;
    let sections = length > 0.5 ? Math.round(span / length) : 1;
    if (sections < 1) sections = 1;
    if (sections > MAX_SECTIONS) sections = MAX_SECTIONS;
    const sectionLen = span / sections;
    const along = (i) => (zAxis ? i.z : i.x);
    const secOf = (a) => Math.max(0, Math.min(sections - 1, Math.floor((a - uMin) / sectionLen)));
    const sigOf = (a, sec) => 2 * ((a - uMin) - sec * sectionLen) / sectionLen - 1;

    const groups = [];
    for (const [name, list] of inst) {
        const count = new Array(sections).fill(0);
        const far = new Array(sections).fill(0);
        for (const i of list) {
            const sec = secOf(along(i));
            count[sec]++;
            far[sec] = Math.max(far[sec], Math.abs(sigOf(along(i), sec)));
        }
        let best = 0;
        for (let s = 1; s < sections; s++) {
            if (count[s] > count[best] || (count[s] === count[best] && far[s] > far[best])) {
                best = s;
            }
        }
        // Anchor on the placement farthest from the car centre (the lamp on the face), then keep
        // EVERY placement of the group on that face - the mirrored partner and all of each lamp's
        // cubes - clustered into one entry per lamp (never averaged: the mean of a pair is 0).
        let anchorAlong = 0, bestAbs = -1;
        for (const i of list) {
            const sec = secOf(along(i));
            if (sec !== best) {
                continue;
            }
            const abs = Math.abs(sigOf(along(i), sec));
            if (abs > bestAbs) {
                bestAbs = abs;
                anchorAlong = along(i);
            }
        }
        const onFace = [];
        const ys = [];
        for (const i of list) {
            if (secOf(along(i)) !== best || Math.abs(along(i) - anchorAlong) > LAMP_ALONG_TOL) {
                continue;
            }
            onFace.push(zAxis ? i.x : i.z);
            ys.push(i.y);
        }
        if (onFace.length === 0) {
            continue;
        }
        const meanOf = (a) => a.reduce((s, v) => s + v, 0) / Math.max(1, a.length);
        const sv = sigOf(anchorAlong, best);
        const st = stageOf.get(name);
        groups.push({
            name, lower: name.toLowerCase(), y: meanOf(ys), lateral: meanOf(onFace),
            lamps: clusters(onFace),
            stage: st.stage, cond: st.cond, count: onFace.length, section: best, s: sv,
            faceDist: (1 - Math.min(1, Math.abs(sv))) * sectionLen / 2,
        });
    }
    if (groups.length === 0) {
        return null;
    }

    const isRoof = (g) => (maxY - minY) > 0.5
        && (g.y - minY) / (maxY - minY) > ROOF_FRACTION;
    let votes = 0;
    for (const g of groups) {
        if (containsAny(g.lower, HEAD_WORDS) && !rejected(g.lower) && !isRoof(g)) {
            votes += Math.sign(g.s);
        }
    }
    const frontSign = votes < 0 ? -1 : 1;
    const endBand = Math.max(END_MIN_BLOCKS, END_FRACTION * sectionLen);

    const faceLamps = (head, anchor) => {
        const words = head ? HEAD_WORDS : TAIL_WORDS;
        const want = Math.sign(anchor.s);
        const raw = anchor.lamps.slice();
        for (const g of groups) {
            if (g === anchor || stageRank(g.stage) !== stageRank(anchor.stage)) {
                continue;
            }
            if (Math.sign(g.s) !== want || Math.abs(g.faceDist - anchor.faceDist) > FACE_MATCH_BLOCKS) {
                continue;
            }
            if (rejected(g.lower) || isRoof(g)) {
                continue;
            }
            if (!containsAny(g.lower, words) && !mirrors(g, raw, latCentre)) {
                continue;
            }
            raw.push(...g.lamps);
        }
        const width = Number(o.width) > 0.6 ? Number(o.width) : DEFAULT_WIDTH;
        const limit = Math.max(LATERAL_CLAMP_FRACTION * width, Math.min(halfWidth + 0.2, 2.5));
        const out2 = [];
        for (const lamp of raw) {
            let rel = Math.max(-limit, Math.min(limit, lamp - latCentre));
            if (Math.abs(rel) < LATERAL_DEAD_ZONE) {
                rel = 0;
            }
            if (!out2.some((v) => Math.abs(v - rel) <= LAMP_DUPLICATE_TOL)) {
                out2.push(rel);
            }
        }
        if (out2.length === 0) {
            return [0];
        }
        out2.sort((a, b) => a - b);
        if (out2.length > 2) {
            let pairTrack = 0, bestSum = Infinity;
            for (let i = 0; i < out2.length; i++) {
                for (let j = i + 1; j < out2.length; j++) {
                    const sum = Math.abs(out2[i] + out2[j]);
                    if (sum < bestSum) {
                        bestSum = sum;
                        pairTrack = Math.max(Math.abs(out2[i]), Math.abs(out2[j]));
                    }
                }
            }
            const keep = pairTrack * 1.3 + 0.1;
            for (let i = out2.length - 1; i >= 0; i--) {
                if (Math.abs(out2[i]) > keep) {
                    out2.splice(i, 1);
                }
            }
        }
        if (out2.length <= MAX_LAMPS) {
            return out2;
        }
        const half = MAX_LAMPS / 2;
        return out2.slice(0, half).concat(out2.slice(out2.length - (MAX_LAMPS - half)))
            .sort((a, b) => a - b);
    };
    const named = (head, g, level) => ({g, level, lamps: faceLamps(head, g)});

    const pick = (head) => {
        const words = head ? HEAD_WORDS : TAIL_WORDS;
        const want = head ? frontSign : -frontSign;
        let best = null, bestScore = -Infinity, relaxed = null, relaxedScore = -Infinity;
        for (const g of groups) {
            if (!containsAny(g.lower, words) || rejected(g.lower) || isRoof(g)) {
                continue;
            }
            const score = stageRank(g.stage) * 10
                + (Math.sign(g.s) === want ? 12 : 0)
                + Math.max(0, 20 * (1 - g.faceDist / endBand))
                + Math.min(4, g.count);
            if (g.faceDist <= endBand) {
                if (score > bestScore) { bestScore = score; best = g; }
            } else if (score > relaxedScore) {
                relaxedScore = score; relaxed = g;
            }
        }
        if (best) return named(head, best, 2);
        if (relaxed) return named(head, relaxed, 3);
        // Level 4: stage + end-face geometry, no name match - a mirrored pair only. A model whose
        // lamps are all at one end cannot vote a front, so the other end is tried for the head.
        const geoAt = (want) => {
            let anchor = null, bestFace = Infinity;
            for (const g of groups) {
                if (stageRank(g.stage) < 3 || Math.sign(g.s) !== want) continue;
                if (rejected(g.lower) || isRoof(g) || g.faceDist > endBand) continue;
                if (g.y < 0.3 || g.y > 3.4 || !g.lamps.length) continue;
                if (g.faceDist < bestFace) { bestFace = g.faceDist; anchor = g; }
            }
            if (!anchor) return null;
            const slot = named(head, anchor, 4);
            return slot.lamps.length >= 2 ? slot : null;
        };
        let geo = geoAt(want);
        if (!geo && head) {
            geo = geoAt(-want);
        }
        if (geo) return geo;
        return {g: null, level: 5, lamps: [-0.36 * (Number(o.width) || DEFAULT_WIDTH),
            0.36 * (Number(o.width) || DEFAULT_WIDTH)]};
    };

    return {
        sections, sectionLen, width: Number(o.width) || 0, length, groups: groups.length,
        minX, maxX, latCentre,
        minY, maxY, head: pick(true), tail: pick(false), groupsAll: groups,
    };
}

// --- main --------------------------------------------------------------------------------
const {packs, files} = collect();
const models = [];
let skipped = 0;
for (const file of files) {
    let text;
    try {
        text = fs.readFileSync(file, 'utf8');
    } catch (e) {
        continue;
    }
    if (!text.includes('"stage"') || !text.includes('"parts"')) {
        skipped++;
        continue;
    }
    let o;
    try {
        o = JSON.parse(text);
    } catch (e) {
        continue;
    }
    if (!o || !Array.isArray(o.parts) || !o.parts.some((p) => p && p.stage)) {
        skipped++;
        continue;
    }
    const bbPath = file.replace(/\.json$/i, '.bbmodel');
    if (!fs.existsSync(bbPath)) {
        continue;
    }
    const geo = readModelGeometry(bbPath);
    if (!geo) {
        continue;
    }
    const r = analyse(o, geo);
    if (!r) {
        continue;
    }
    models.push({file, ...r});
}

const pct = (n) => (100 * n / Math.max(1, models.length)).toFixed(1) + '%';
const headName = models.filter((m) => m.head.level <= 3).length;
const tailName = models.filter((m) => m.tail.level <= 3).length;
const headDefault = models.filter((m) => m.head.level === 5);
const tailDefault = models.filter((m) => m.tail.level === 5);
const multi = models.filter((m) => m.sections > 1);
const bothDefault = models.filter((m) => m.head.level === 5 && m.tail.level === 5);

console.log('packs scanned:      ' + packs.length + ' (' + packs.filter((p) => p.kind === 'zip').length + ' zip)');
console.log('vehicle models:     ' + models.length + ' (json skipped ' + skipped + ')');
console.log('multi-car bbmodels: ' + multi.length);
console.log('');
console.log('head: L2=' + models.filter((m) => m.head.level === 2).length
    + '  L3=' + models.filter((m) => m.head.level === 3).length
    + '  default=' + headDefault.length + '   => name ' + headName + ' (' + pct(headName) + ')');
console.log('tail: L2=' + models.filter((m) => m.tail.level === 2).length
    + '  L3=' + models.filter((m) => m.tail.level === 3).length
    + '  default=' + tailDefault.length + '   => name ' + tailName + ' (' + pct(tailName) + ')');
console.log('both ends default:  ' + bothDefault.length + ' (' + pct(bothDefault.length) + ')');
// How many cones each end would emit. x2+ is the requested left/right pair; x1 is a single lamp
// (centre or one side) - with the old code every named slot was x1, because the pair was averaged.
const lampMix = (side) => {
    const counts = models.map((m) => (m[side].lamps || []).length);
    return 'x1=' + counts.filter((n) => n === 1).length
        + '  x2=' + counts.filter((n) => n === 2).length
        + '  x3+=' + counts.filter((n) => n >= 3).length;
};
console.log('head lamps:         ' + lampMix('head'));
console.log('tail lamps:         ' + lampMix('tail'));
const pairs = models.filter((m) => (m.head.lamps || []).length >= 2
    && m.head.lamps[0] < 0 && m.head.lamps[m.head.lamps.length - 1] > 0);
console.log('head mirrored pair: ' + pairs.length + ' (' + pct(pairs.length) + ')');
if (process.env.LTE_DEBUG_LAMPS) {
    for (const m of models.filter((x) => (x.head.lamps || []).length >= 3)) {
        console.log('  [x' + m.head.lamps.length + '] '
            + path.relative(RP, m.file).replace(/\\/g, '/').slice(-58)
            + ' head=' + (m.head.g ? m.head.g.name : 'default') + ' L' + m.head.level
            + ' lamps=' + JSON.stringify(m.head.lamps.map((v) => +v.toFixed(2))));
    }
    for (const m of models.filter((x) => (x.head.lamps || []).length === 1)) {
        const g = m.head.g;
        console.log('  [x1] ' + path.relative(RP, m.file).replace(/\\/g, '/').slice(-60)
            + ' head=' + (g ? g.name : 'default') + ' L' + m.head.level
            + ' placements=' + (g ? g.count : 0)
            + ' lamps=' + JSON.stringify((m.head.lamps || []).map((v) => +v.toFixed(2)))
            + ' raw=' + (g ? JSON.stringify(g.lamps.map((v) => +v.toFixed(2))) : '-')
            + ' centre=' + m.latCentre.toFixed(2)
            + ' x=[' + m.minX.toFixed(2) + ',' + m.maxX.toFixed(2) + ']'
            + ' w=' + m.width);
        if (g) {
            const near = m.groupsAll.filter((o) => o !== g && Math.sign(o.s) === Math.sign(g.s)
                && Math.abs(o.faceDist - g.faceDist) <= FACE_MATCH_BLOCKS);
            console.log('       face-mates: ' + near.map((o) => o.name + '[' + o.stage
                + ' y=' + o.y.toFixed(2) + ' lat=' + o.lateral.toFixed(2)
                + ' lamps=' + JSON.stringify(o.lamps.map((v) => +v.toFixed(2))) + ']').join(' '));
        }
    }
}

console.log('');
const short = (f) => path.relative(RP, f).replace(/\\/g, '/').slice(-95);
const roofRatio = (m, g) => (m.maxY - m.minY) > 0.5 ? (g.y - m.minY) / (m.maxY - m.minY) : 0;
console.log('--- head found by name but outside the end band (L3, inset clamped) ---');
for (const m of models.filter((x) => x.head.level === 3)) {
    console.log('  ' + short(m.file) + '  ' + m.head.g.name
        + ' face=' + m.head.g.faceDist.toFixed(2) + ' secLen=' + m.sectionLen.toFixed(2)
        + ' roof=' + roofRatio(m, m.head.g).toFixed(2));
}
console.log('');
console.log('--- head defaults (estimated position) ---');
for (const m of headDefault) {
    const names = m.groupsAll.map((g) => g.name).filter((n) => containsAny(n.toLowerCase(), HEAD_WORDS));
    const why = names.length === 0 ? 'no head-named group'
        : names.every((n) => rejected(n.toLowerCase())) ? 'blacklisted: ' + names.join(',')
            : 'roof/mid-car only: ' + names.join(',');
    console.log('  ' + short(m.file) + '  [' + why + ']');
}
console.log('');
console.log('--- tail defaults (estimated position) ---');
for (const m of tailDefault) {
    const names = m.groupsAll.map((g) => g.name).filter((n) => containsAny(n.toLowerCase(), TAIL_WORDS));
    const why = names.length === 0 ? 'no tail-named group'
        : 'roof/mid-car only: ' + names.join(',');
    console.log('  ' + short(m.file) + '  [' + why + ']');
}
console.log('');
console.log('--- multi-car sections ---');
for (const m of multi.slice(0, 15)) {
    console.log('  sections=' + m.sections + ' len=' + m.length
        + ' head=' + (m.head.g ? m.head.g.name + '@L' + m.head.level + ' face=' + m.head.g.faceDist.toFixed(2) : 'default')
        + ' tail=' + (m.tail.g ? m.tail.g.name + '@L' + m.tail.level + ' face=' + m.tail.g.faceDist.toFixed(2) : 'default'));
}
