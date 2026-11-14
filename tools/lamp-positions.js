#!/usr/bin/env node
/*
 * Offline check for the lamp placement introduced with the 1.20.1 light rework.
 *
 * It answers two questions with real data and no game:
 *
 *   1. does EVERY built-in MTR train type produce light? (114 types, `tools/builtin-train-types.json`)
 *   2. where do those lamps land in world space, and are the numbers sane?
 *
 * It reads the same table the runtime reads (`builtin_lights.json`, 95 measured models) and applies
 * the transform documented in `common/.../TrainLamps.java` step by step, so the arithmetic in the
 * report can be checked line by line against the code:
 *
 *     base  = rail point on that car's end face          (front() / rear() in TrainPose)
 *     out   = +carBodyForward for the head, -it for the tail
 *     right = normalize(out x (0,1,0))
 *     pos   = base + right * lateral + (0, height, 0) + out * (|inset| + 0.08)
 *
 * Two deliberate differences from the runtime, both visible in the output:
 *   - the *car pitch* (`spacing`, i.e. how long one car is) is a game-side value we do not have
 *     offline; it is taken from `--spacing` (default 24) and the effect of it is printed, because it
 *     only decides how far behind the face a lamp may be inset (clamped to 1.5 anyway);
 *   - the model-space -> car-space step uses the built-in table's own convention (z<0 = nose,
 *     y from the car reference plane), which is the convention `BuiltinLights` encodes.
 *
 * Usage:
 *     node tools/lamp-positions.js                 # every type + a coordinate dump for 5 models
 *     node tools/lamp-positions.js --spacing 22    # assume a different car pitch
 *     node tools/lamp-positions.js --json          # machine-readable
 */
'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const LIGHTS = JSON.parse(fs.readFileSync(path.join(ROOT, 'v1.20.1', 'src', 'main', 'resources',
    'assets', 'mtrlte', 'builtin_lights.json'), 'utf8'));
const TYPES = JSON.parse(fs.readFileSync(path.join(ROOT, 'tools', 'builtin-train-types.json'), 'utf8'));

// --- constants mirrored from the Java side (keep in sync by eye; they are all documented) ------
const FLARE_GAP = 0.08;       // TrainLamps.FLARE_GAP
const MIN_LAMP_HEIGHT = 0.30; // TrainLamps.MIN_LAMP_HEIGHT
const MAX_LAMP_HEIGHT = 3.30; // TrainLamps.MAX_LAMP_HEIGHT
const MAX_LATERAL = 2.4;      // TrainLamps.MAX_LATERAL
const MAX_INSET = 1.5;        // BuiltinLights.MAX_INSET
const MAX_AHEAD = 2.0;       // TrainLamps.MAX_LAMP_AHEAD
const FALLBACK_WIDTH = 2.9;   // TrainLamps.FALLBACK_WIDTH
const FALLBACK_LATERAL = 0.36;// TrainLamps.FALLBACK_LATERAL
const DEFAULT_HEIGHT_OFFSET = 0.85; // HeadlightConfig.heightOffset

function args() {
    const out = { spacing: 24, json: false };
    const argv = process.argv.slice(2);
    for (let i = 0; i < argv.length; i++) {
        if (argv[i] === '--spacing') {
            out.spacing = Number(argv[++i]);
        } else if (argv[i] === '--json') {
            out.json = true;
        }
    }
    return out;
}

const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));

/** BuiltinLights.slot(): one end of a car, from the measured lamp boxes. */
function slot(lamps, spacing) {
    if (!lamps || lamps.length === 0) {
        return null;
    }
    const face = spacing / 2;
    let sy = 0;
    let sz = 0;
    for (const l of lamps) {
        sy += l.y;
        sz += Math.abs(l.z);
    }
    const height = sy / lamps.length;
    const zMean = sz / lamps.length;
    const inset = clamp(face - zMean, -MAX_AHEAD, MAX_INSET);
    return {
        lateral: lamps.map((l) => l.x),
        height: clamp(height, 0.2, 3.4),
        inset,
        faceDist: Math.max(0, face - zMean),
        source: 'builtin',
    };
}

/** TrainLamps.estimated(): the pair used when nothing at all is known about the model. */
function estimated(spacing) {
    const lateral = FALLBACK_LATERAL * FALLBACK_WIDTH;
    const y = clamp(DEFAULT_HEIGHT_OFFSET, MIN_LAMP_HEIGHT, MAX_LAMP_HEIGHT);
    return {
        head: [lateral, -lateral].map((x) => ({ lateral: x, height: y, inset: 0, source: 'default' })),
        tail: [lateral, -lateral].map((x) => ({ lateral: x, height: y, inset: 0, source: 'default' })),
        spacing,
    };
}

/** Everything the runtime would have for one vehicle id. */
function lampsOf(id, spacing) {
    const entry = LIGHTS[id];
    if (entry && Array.isArray(entry.head) && Array.isArray(entry.tail)
        && entry.head.length > 0 && entry.tail.length > 0) {
        const head = slot(entry.head, spacing);
        const tail = slot(entry.tail, spacing);
        return {
            head: head.lateral.map((x) => ({ lateral: x, height: head.height, inset: head.inset,
                source: 'builtin' })),
            tail: tail.lateral.map((x) => ({ lateral: x, height: tail.height, inset: tail.inset,
                source: 'builtin' })),
            spacing,
        };
    }
    return estimated(spacing);
}

/** TrainLamps.place(): car-local lamp -> world, for a car whose lead face is at `base`. */
function place(lamp, base, out, head) {
    // out = 端面外法线（车头 +弦 / 车尾 -弦）。位置用它，灯面朝向 dir 是它的反向：
    //   pos = base + right*lateral + (0,height,0) + out*(|inset| + FLARE_GAP)
    //   dir = -out      （LightFlare 的 facing = -dot(dir, view) 要的那一侧）
    const outDir = head ? out : { x: -out.x, y: -out.y, z: -out.z };
    // right = out x up, then normalised (name it exactly as the Java side does).
    let right = {
        x: outDir.y * 0 - outDir.z * 1,
        y: outDir.z * 0 - outDir.x * 0,
        z: outDir.x * 1 - outDir.y * 0,
    };
    const len = Math.hypot(right.x, right.y, right.z);
    right = { x: right.x / len, y: right.y / len, z: right.z / len };
    const lateral = clamp(lamp.lateral, -MAX_LATERAL, MAX_LATERAL);
    const height = clamp(lamp.height < MIN_LAMP_HEIGHT ? DEFAULT_HEIGHT_OFFSET : lamp.height,
        MIN_LAMP_HEIGHT, MAX_LAMP_HEIGHT);
    const outward = clamp(Math.abs(lamp.inset), 0, MAX_AHEAD) + FLARE_GAP;
    return {
        x: base.x + right.x * lateral + outDir.x * outward,
        y: base.y + height + outDir.y * outward,
        z: base.z + right.z * lateral + outDir.z * outward,
        right,
        dir: { x: -outDir.x, y: -outDir.y, z: -outDir.z },
        beam: outDir,
    };
}

function rot(yawDeg) {
    const a = (yawDeg * Math.PI) / 180;
    return { x: Math.cos(a), y: 0, z: Math.sin(a) };
}

function main() {
    const opts = args();
    const ids = TYPES.map((t) => String(t.type).toLowerCase());
    const sp = opts.spacing;

    // --- 1. coverage ---------------------------------------------------------------------------
    let measured = 0;
    let fallback = 0;
    let bad = [];
    for (const id of ids) {
        const lamps = lampsOf(id, sp);
        const total = lamps.head.length + lamps.tail.length;
        if (lamps.head[0].source === 'builtin') {
            measured++;
        } else {
            fallback++;
        }
        if (total < 4) {
            bad.push(id + ' (' + total + ' lamps)');
        }
        for (const l of [...lamps.head, ...lamps.tail]) {
            if (!(Math.abs(l.lateral) <= MAX_LATERAL + 1e-9)
                || !(l.height >= MIN_LAMP_HEIGHT - 1e-9 && l.height <= MAX_LAMP_HEIGHT + 1e-9)) {
                bad.push(id + ' out of range: ' + JSON.stringify(l));
            }
        }
    }
    const lines = [];
    lines.push(`built-in train types: ${ids.length}`);
    lines.push(`  with measured lamp boxes (builtin_lights.json): ${measured}`);
    lines.push(`  falling back to the estimated pair:             ${fallback}`);
    lines.push(`  types producing fewer than 4 lamps / out of range: ${bad.length}`);
    for (const b of bad.slice(0, 5)) {
        lines.push('    ' + b);
    }

    // --- 2. where the lamps land --------------------------------------------------------------
    const show = ['a_train_tcl', 'c_train', 'sp1900', 'm_train', 'k_train'];
    const dump = [];
    for (const id of show) {
        if (!ids.includes(id)) {
            continue;
        }
        const lamps = lampsOf(id, sp);
        const base = { x: 0, y: 64, z: 0 };            // rail point on the leading car's face
        const out = rot(0);                            // car heading +X, flat track
        const head = lamps.head.map((l) => place(l, base, out, true));
        const rearBase = { x: -sp, y: 64, z: 0 };      // trailing face of a car heading +X
        const tail = lamps.tail.map((l) => place(l, rearBase, out, false));
        dump.push({ id, head: lamps.head, tail: lamps.tail, headWorld: head, tailWorld: tail });
        lines.push('');
        lines.push(`${id}  (spacing=${sp}, car heading +X, rail y=64)`);
        lamps.head.forEach((l, i) => {
            lines.push(`  head[${i}] car-local(lat=${l.lateral.toFixed(3)}, y=${l.height.toFixed(3)}, `
                + `inset=${l.inset.toFixed(3)}) -> world(${head[i].x.toFixed(3)}, `
                + `${head[i].y.toFixed(3)}, ${head[i].z.toFixed(3)})`);
        });
        lamps.tail.forEach((l, i) => {
            lines.push(`  tail[${i}] car-local(lat=${l.lateral.toFixed(3)}, y=${l.height.toFixed(3)}, `
                + `inset=${l.inset.toFixed(3)}) -> world(${tail[i].x.toFixed(3)}, `
                + `${tail[i].y.toFixed(3)}, ${tail[i].z.toFixed(3)})`);
        });
    }

    // --- 3. the basis turns with the car -------------------------------------------------------
    const curve = lampsOf('a_train_tcl', sp);
    lines.push('');
    lines.push('heading sensitivity (a_train_tcl head lamp 0, same car-local numbers):');
    for (const yaw of [0, 45, 90, 180]) {
        const p = place(curve.head[0], { x: 0, y: 64, z: 0 }, rot(yaw), true);
        lines.push(`  yaw=${String(yaw).padStart(3)}deg -> world(${p.x.toFixed(3)}, ${p.y.toFixed(3)}, `
            + `${p.z.toFixed(3)}) right=(${p.right.x.toFixed(2)}, ${p.right.z.toFixed(2)})`);
    }
    lines.push('');
    lines.push('A nonzero right vector that rotates with the heading is the check that the lamps are');
    lines.push('placed in the CAR frame (and therefore stay on the car through a curve) rather than at');
    lines.push('a fixed offset in world space.');

    if (opts.json) {
        console.log(JSON.stringify({ coverage: { total: ids.length, measured, fallback, bad }, dump },
            null, 1));
    } else {
        console.log(lines.join('\n'));
    }
    if (bad.length > 0) {
        process.exitCode = 1;
    }
}

if (require.main === module) {
    main();
}
