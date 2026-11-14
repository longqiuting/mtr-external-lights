#!/usr/bin/env node
/*
 * beam-gizmo-check.js —— 光束编辑器三轴抓手的「纯数学」自检。
 *
 * 这份脚本是 v1.20.1 里 BeamGizmo.java / TrainPreview#pickRing 的**逐行对照实现**：
 * 同样的公式、同样的常量、同样的矩阵顺序，用 node 跑一遍，验证
 *
 *   1. 步进：结果永远 = 锚点 + k·步长（1/16、1/64、2.5°、0.5°）；
 *   2. 环 ↔ rot 分量的对应表 + 拖动一个环只改一个分量，且方向变化等于绕环法线的
 *      Rodrigues 旋转（即“拖环 = 绕那根轴转”在数值上成立）；
 *   3. 翻转只反一个方向分量，pos（锚点）逐位不动 —— 灯不会飞到地图另一头；
 *   4. 90° 旋转把 -Z 转到相邻轴上；
 *   5. 环的**命中数学**：把环上的点投到像素再反解，角度能还原、误差 ~0、径向偏 20px 就不命中；
 *      顺带证明射线起点少加 camera.targetY 会让角度系统性偏掉（那一项不是可省的）。
 *
 * 用法： node tools/beam-gizmo-check.js        （失败时退出码 1）
 */

'use strict';

const DEG = Math.PI / 180;

// ---------------------------------------------------------------------------------------------
// BeamGizmo 常量（改动时两边一起改）
// ---------------------------------------------------------------------------------------------
const AXIS_X = 0, AXIS_Y = 1, AXIS_Z = 2;
const MOVE_STEP = 0.0625;        // 1/16
const MOVE_STEP_FINE = 0.015625; // 1/64
const ROT_STEP = 2.5;
const ROT_STEP_FINE = 0.5;
const RADIUS_PX = 44;
const SHAFT_PX = 48;
const MIN_SIZE = 0.35, MAX_SIZE = 32;
const RING_TOL_PX = 7;
const AXIS_TOL_PX = 6;
const PLANE_MIN_COS = 0.15;
const RING_SEGMENTS = 48;

// ---------------------------------------------------------------------------------------------
// 断言
// ---------------------------------------------------------------------------------------------
let failures = 0;
let checks = 0;
function ok(cond, label) {
    checks++;
    if (!cond) {
        failures++;
        console.error('FAIL  ' + label);
    }
}
function near(a, b, tol, label) {
    ok(Math.abs(a - b) <= tol, label + `  (${a} vs ${b}, tol ${tol})`);
}

// ---------------------------------------------------------------------------------------------
// 向量 / 旋转小工具（与 BeamGizmo 同名字同语义）
// ---------------------------------------------------------------------------------------------
const cross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const norm = (v) => {
    const l = Math.hypot(v[0], v[1], v[2]);
    return l < 1e-9 ? [0, 0, 1] : [v[0] / l, v[1] / l, v[2] / l];
};
const dist3 = (a, b) => Math.hypot(a[0] - b[0], a[1] - b[1], a[2] - b[2]);

const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));
const wrap = (deg) => {
    let d = deg % 360;
    if (d > 180) d -= 360;
    if (d <= -180) d += 360;
    return d;
};

function quantize(anchor, value, step) {
    if (!(step > 0) || !Number.isFinite(value)) return value;
    return anchor + Math.round((value - anchor) / step) * step;
}
function steps(anchor, value, step) {
    return Math.round((value - anchor) / step);
}

const moveStep = (fine) => (fine ? MOVE_STEP_FINE : MOVE_STEP);
const rotStep = (fine) => (fine ? ROT_STEP_FINE : ROT_STEP);

const ringSize = (scale) => clamp(RADIUS_PX / Math.max(1e-3, scale), MIN_SIZE, MAX_SIZE);
const shaftSize = (scale) => clamp(SHAFT_PX / Math.max(1e-3, scale), MIN_SIZE, MAX_SIZE);

const nameOf = (axis) => (axis === AXIS_X ? 'X' : axis === AXIS_Y ? 'Y' : 'Z');
const unit = (axis) => (axis === AXIS_X ? [1, 0, 0] : axis === AXIS_Y ? [0, 1, 0] : [0, 0, 1]);

/** 头灯口径的方向：head = (-sin yaw·cos p, -sin p, -cos yaw·cos p)。 */
function headDirection(rot) {
    const yaw = (rot[0] || 0) * DEG;
    const pitch = (rot[1] || 0) * DEG;
    const cp = Math.cos(pitch);
    return [-Math.sin(yaw) * cp, -Math.sin(pitch), -Math.cos(yaw) * cp];
}

/** headDirection 的逆（pitch 落在 [-90,90]，超出时给等价的另一组角）。 */
function rotFromHead(dir, roll) {
    const len = Math.hypot(dir[0], dir[1], dir[2]);
    if (!(len > 1e-9)) return [0, 0, wrap(roll || 0)];
    const x = dir[0] / len, y = dir[1] / len, z = dir[2] / len;
    const pitch = Math.asin(clamp(-y, -1, 1)) / DEG;
    const cp = Math.cos(pitch * DEG);
    const yaw = cp < 1e-6 ? 0 : Math.atan2(-x, -z) / DEG;
    return [wrap(yaw), pitch, wrap(roll || 0)];
}

/** 环的法线 = 绕哪根轴转。X = localX、Y = 世界 Y、Z = 光束自己的方向。 */
function ringNormal(axis, rot) {
    if (axis === AXIS_X) {
        const yaw = (rot[0] || 0) * DEG;
        return [Math.cos(yaw), 0, -Math.sin(yaw)];
    }
    if (axis === AXIS_Y) return [0, 1, 0];
    return norm(headDirection(rot));
}

/** 环 → 它改的 rot 分量：X = pitch(1)、Y = yaw(0)、Z = roll(2)。 */
const rotIndex = (axis) => (axis === AXIS_X ? 1 : axis === AXIS_Y ? 0 : 2);
const ringSign = (axis) => (axis === AXIS_X ? -1 : 1);

/** 环平面的一组正交基。 */
function planeBasis(normal) {
    const n = norm(normal);
    const ref = Math.abs(n[1]) > 0.9 ? [1, 0, 0] : [0, 1, 0];
    const u = norm(cross(ref, n));
    const v = norm(cross(n, u));
    return [u, v];
}

/** Rodrigues：v 绕轴 axis 转 deg（右手定则）。 */
function rotate(v, axis, deg) {
    const k = norm(axis);
    const a = deg * DEG;
    const c = Math.cos(a), s = Math.sin(a);
    const kxv = cross(k, v);
    const kd = dot(k, v);
    return [
        v[0] * c + kxv[0] * s + k[0] * kd * (1 - c),
        v[1] * c + kxv[1] * s + k[1] * kd * (1 - c),
        v[2] * c + kxv[2] * s + k[2] * kd * (1 - c),
    ];
}

/** 关于「过 pivot、法线为该模型轴」的平面镜像。 */
function mirror(v, axis) {
    const e = unit(axis);
    const d = 2 * dot(v, e);
    return [v[0] - d * e[0], v[1] - d * e[1], v[2] - d * e[2]];
}
function mirrorPoint(p, pivot, axis) {
    const e = unit(axis);
    const d = (p[0] - pivot[0]) * e[0] + (p[1] - pivot[1]) * e[1] + (p[2] - pivot[2]) * e[2];
    return [p[0] - 2 * d * e[0], p[1] - 2 * d * e[1], p[2] - 2 * d * e[2]];
}

// ---------------------------------------------------------------------------------------------
// 矩阵（行主序 4x4，和 TrainPreview 用 JOML 拼出来的那次乘法等价）
// ---------------------------------------------------------------------------------------------
const mIdentity = () => [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1];
const mMul = (a, b) => {
    const out = new Array(16).fill(0);
    for (let r = 0; r < 4; r++) {
        for (let c = 0; c < 4; c++) {
            let s = 0;
            for (let k = 0; k < 4; k++) s += a[r * 4 + k] * b[k * 4 + c];
            out[r * 4 + c] = s;
        }
    }
    return out;
};
const mTranslate = (x, y, z) => [1, 0, 0, x, 0, 1, 0, y, 0, 0, 1, z, 0, 0, 0, 1];
const mScale = (x, y, z) => [x, 0, 0, 0, 0, y, 0, 0, 0, 0, z, 0, 0, 0, 0, 1];
const mRotX = (deg) => {
    const a = deg * DEG, c = Math.cos(a), s = Math.sin(a);
    return [1, 0, 0, 0, 0, c, -s, 0, 0, s, c, 0, 0, 0, 0, 1];
};
const mRotY = (deg) => {
    const a = deg * DEG, c = Math.cos(a), s = Math.sin(a);
    return [c, 0, s, 0, 0, 1, 0, 0, -s, 0, c, 0, 0, 0, 0, 1];
};
const mRotZ = (deg) => {
    const a = deg * DEG, c = Math.cos(a), s = Math.sin(a);
    return [c, -s, 0, 0, s, c, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1];
};
const projectX = (m, x, y, z) => m[0] * x + m[1] * y + m[2] * z + m[3];
const projectY = (m, x, y, z) => m[4] * x + m[5] * y + m[6] * z + m[7];
/** 行 r 的第 0..2 个元素（JOML 的 m0r/m1r/m2r…，也就是数学上的第 r 行）。 */
const row = (m, r) => [m[r * 4], m[r * 4 + 1], m[r * 4 + 2]];

// ---------------------------------------------------------------------------------------------
// TrainPreview.Camera 的默认值 + 那两次矩阵乘法
// ---------------------------------------------------------------------------------------------
const camera = { yaw: 34, pitch: 16, scale: 22, panX: 0, panY: 0, targetY: 1.1 };
const VIEW = { x0: 150, y0: 36, x1: 650, y1: 400 };
const centreX = (VIEW.x0 + VIEW.x1) / 2;
const centreY = (VIEW.y0 + VIEW.y1) / 2;

/** rotation() = Rx(pitch)·Ry(yaw)·Rz(180)·T(0,−targetY,0)（MODEL_FLIP_Z180 = true）。 */
function rotationMatrix(cam) {
    let m = mRotX(cam.pitch);
    m = mMul(m, mRotY(cam.yaw));
    m = mMul(m, mRotZ(180));
    m = mMul(m, mTranslate(0, -cam.targetY, 0));
    return m;
}
/** toPixels(centre) = T(centre+pan)·S(scale,−scale,scale)。 */
function toPixelsMatrix(cam) {
    return mMul(mTranslate(centreX + cam.panX, centreY + cam.panY, 0),
        mScale(cam.scale, -cam.scale, cam.scale));
}
const beamRotate = (cam) => rotationMatrix(cam); // BEAM_MIRROR_X = false
const fullMatrix = (cam) => mMul(toPixelsMatrix(cam), beamRotate(cam));

/**
 * TrainPreview#ringSample 的平面那一支（MODE_PLANE）：射线 ∩ 环平面 → 径向误差 + 平面内角度。
 * setTargetY=false 只用于「漏掉 +targetY 会怎样」的对照实验。
 */
function ringSamplePlane(cam, matrix, mouseX, mouseY, base, normal, radius, setTargetY = true) {
    const rot = beamRotate(cam);
    // JOML 的 m_ij 是「第 i 列、第 j 行」= 行主序数组的 [j*4+i]。下面这几行就是
    // TrainPreview 里的 rot.m00() / m01() / m10() … 逐项对照。
    const m = (i, j) => rot[j * 4 + i];
    const u = norm([m(0, 2), m(1, 2), m(2, 2)]); // = Rᵀ·(0,0,1)
    const denom = dot(normal, u);
    if (Math.abs(denom) < PLANE_MIN_COS) return { err: Infinity, angle: NaN };
    const vx = (mouseX - (centreX + cam.panX)) / cam.scale;
    const vy = -(mouseY - (centreY + cam.panY)) / cam.scale;
    // 视平面点 (vx,vy,0) 的原像 = 射线起点；rotation() 末尾的 translate(0,−targetY,0)
    // 逆转成 +targetY（漏掉它角度就会系统性偏，见下面的对照断言）。
    const ox = vx * m(0, 0) + vy * m(0, 1);
    const oy = vx * m(1, 0) + vy * m(1, 1) + (setTargetY ? cam.targetY : 0);
    const oz = vx * m(2, 0) + vy * m(2, 1);
    const t = (dot(normal, [base[0] - ox, base[1] - oy, base[2] - oz])) / denom;
    const rx = ox + u[0] * t - base[0];
    const ry = oy + u[1] * t - base[1];
    const rz = oz + u[2] * t - base[2];
    const radial = Math.hypot(rx, ry, rz);
    const [bu, bv] = planeBasis(normal);
    const angle = Math.atan2(rx * bv[0] + ry * bv[1] + rz * bv[2], rx * bu[0] + ry * bu[1] + rz * bu[2]);
    return { err: Math.abs(radial - radius) * cam.scale, angle };
}

/** pickRing 的骨架：三条环各算一次，取屏幕上最近且 <= 阈值的那条。 */
function pickRing(cam, mouseX, mouseY, base, rot) {
    const matrix = fullMatrix(cam);
    const radius = ringSize(cam.scale);
    let best = null;
    for (let axis = 0; axis < 3; axis++) {
        const sample = ringSamplePlane(cam, matrix, mouseX, mouseY, base, ringNormal(axis, rot), radius);
        if (!(sample.err <= RING_TOL_PX)) continue;
        if (best && sample.err >= best.err) continue;
        best = { axis, err: sample.err, angle: sample.angle };
    }
    return best;
}

// ---------------------------------------------------------------------------------------------
// 1. 步进
// ---------------------------------------------------------------------------------------------
console.log('--- 1. 步进 / 量化 ---');
for (const step of [MOVE_STEP, MOVE_STEP_FINE, ROT_STEP, ROT_STEP_FINE]) {
    for (let k = -8; k <= 8; k++) {
        const anchor = 0.3125;
        const target = anchor + k * step + step * 0.31;
        const q = quantize(anchor, target, step);
        near(q, anchor + steps(anchor, q, step) * step, 1e-12, `quantize on grid (step ${step}, k ${k})`);
        ok(Math.abs(q - target) <= step * 0.5 + 1e-12, `quantize within half a step (step ${step}, k ${k})`);
    }
}
near(quantize(0.3125, 0.68, MOVE_STEP), 0.6875, 1e-12, 'quantize(0.3125, 0.68, 1/16) = 0.6875 (6 steps)');
ok(steps(0.3125, 0.6875, MOVE_STEP) === 6, '6 steps of 1/16 from 0.3125');
ok(Math.abs(quantize(0.3125, 0.3126, MOVE_STEP_FINE) - 0.3125) < 1e-12, 'fine 1/64 keeps a 0.0001 nudge');
ok(Math.abs(quantize(0.3125, 0.3126, MOVE_STEP) - 0.3125) < 1e-12, 'coarse 1/16 swallows it');

// ---------------------------------------------------------------------------------------------
// 2. 环 ↔ rot 分量 + 只改一个分量
// ---------------------------------------------------------------------------------------------
console.log('--- 2. 环 / 方向 ---');
ok(rotIndex(AXIS_X) === 1 && rotIndex(AXIS_Y) === 0 && rotIndex(AXIS_Z) === 2,
    'ring -> rot component table (X=pitch, Y=yaw, Z=roll)');
ok(ringSign(AXIS_X) === -1 && ringSign(AXIS_Y) === 1 && ringSign(AXIS_Z) === 1,
    'ring angle sign table (X = -1)');

let rnd = 12345;
const rand = () => {
    rnd = (rnd * 1103515245 + 12345) & 0x7fffffff;
    return rnd / 0x7fffffff;
};
for (let i = 0; i < 200; i++) {
    const base = [rand() * 360 - 180, rand() * 160 - 80, rand() * 360 - 180];
    const delta = (rand() * 12 - 6) * ROT_STEP;
    for (let axis = 0; axis < 3; axis++) {
        const component = rotIndex(axis);
        const next = base.slice();
        next[component] = wrap(base[component] + ringSign(axis) * delta);
        for (let other = 0; other < 3; other++) {
            if (other === component) continue;
            ok(next[other] === base[other],
                `ring ${nameOf(axis)} leaves rot[${other}] bit-identical (sample ${i})`);
        }
        const before = headDirection(base);
        const after = headDirection(next);
        const expect = rotate(before, ringNormal(axis, base), delta);
        near(dist3(after, expect), 0, 1e-9,
            `ring ${nameOf(axis)} direction == Rodrigues(dir, normal, ${delta.toFixed(2)}deg) (sample ${i})`);
    }
}

// ---------------------------------------------------------------------------------------------
// 3. 翻转 / 90°
// ---------------------------------------------------------------------------------------------
console.log('--- 3. 翻转 / 90° ---');
for (let axis = 0; axis < 3; axis++) {
    const dir = headDirection([20, 10, 5]);
    const flipped = mirror(dir, axis);
    near(flipped[axis], -dir[axis], 1e-12, `mirror ${nameOf(axis)} negates that component`);
    for (let other = 0; other < 3; other++) {
        if (other !== axis) near(flipped[other], dir[other], 1e-12, `mirror ${nameOf(axis)} keeps component ${other}`);
    }
    const pivot = [1.5, 2, -3.5];
    near(dist3(mirrorPoint(pivot, pivot, axis), pivot), 0, 1e-12, `mirror ${nameOf(axis)} does not move the anchor`);
    const back = headDirection(rotFromHead(flipped, 0));
    near(dist3(back, flipped), 0, 1e-9, `rotFromHead round-trip on mirrored ${nameOf(axis)}`);
}
near(rotate([0, 0, -1], unit(AXIS_X), 90)[1], 1, 1e-12, 'rotate X +90 of -Z gives +Y');
near(rotate([0, 0, -1], unit(AXIS_Y), 90)[0], -1, 1e-12, 'rotate Y +90 of -Z gives -X (left)');
{
    const v = rotate([0, 0, -1], unit(AXIS_X), 90);
    near(Math.hypot(v[0], v[1], v[2]), 1, 1e-12, '90 deg keeps unit length');
}
near(rotFromHead([0, 1, 0], 0)[1], -90, 1e-9, 'straight up is pitch -90');
near(rotFromHead([0, -1, 0], 0)[1], 90, 1e-9, 'straight down is pitch +90');

// 90° 旋转后再翻回 0：四次 90° 必须回到原方向
for (let axis = 0; axis < 3; axis++) {
    let d = headDirection([20, 10, 5]);
    for (let i = 0; i < 4; i++) d = rotate(d, unit(axis), 90);
    near(dist3(d, headDirection([20, 10, 5])), 0, 1e-9, `four 90 deg turns about ${nameOf(axis)} come back`);
}

// ---------------------------------------------------------------------------------------------
// 4. 尺寸：屏幕像素恒定
// ---------------------------------------------------------------------------------------------
console.log('--- 4. 尺寸 ---');
for (const scale of [6, 11, 22, 45, 90]) {
    near(ringSize(scale) * scale, RADIUS_PX, 1e-9, `ring radius stays ${RADIUS_PX}px at scale ${scale}`);
    near(shaftSize(scale) * scale, SHAFT_PX, 1e-9, `shaft length stays ${SHAFT_PX}px at scale ${scale}`);
}

// ---------------------------------------------------------------------------------------------
// 5. 命中数学：把环上的点投到像素，再反解回角度
// ---------------------------------------------------------------------------------------------
console.log('--- 5. 环的命中 / 反解 ---');
const base = [1.5, 2.0, -3.5];
const matrix = fullMatrix(camera);
const radius = ringSize(camera.scale);
let worstAngle = 0;
let worstErr = 0;
let misses = 0;
const rot = [20, 10, 5];
for (let axis = 0; axis < 3; axis++) {
    const normal = ringNormal(axis, rot);
    const [bu, bv] = planeBasis(normal);
    ok(Math.abs(dot(normal, bu)) < 1e-12 && Math.abs(dot(normal, bv)) < 1e-12, `basis for ${nameOf(axis)} is in the ring plane`);
    for (let deg = -170; deg <= 170; deg += 10) {
        const a = deg * DEG;
        const pt = [
            base[0] + (bu[0] * Math.cos(a) + bv[0] * Math.sin(a)) * radius,
            base[1] + (bu[1] * Math.cos(a) + bv[1] * Math.sin(a)) * radius,
            base[2] + (bu[2] * Math.cos(a) + bv[2] * Math.sin(a)) * radius,
        ];
        const px = projectX(matrix, pt[0], pt[1], pt[2]);
        const py = projectY(matrix, pt[0], pt[1], pt[2]);
        const sample = ringSamplePlane(camera, matrix, px, py, base, normal, radius);
        if (!Number.isFinite(sample.err)) continue; // 这一帧环侧对相机，走屏幕折线兜底
        ok(sample.err <= RING_TOL_PX, `ring ${nameOf(axis)} @${deg}deg is a hit (err ${sample.err.toFixed(3)}px)`);
        worstErr = Math.max(worstErr, sample.err);
        const d = Math.abs(Math.atan2(Math.sin(sample.angle - a), Math.cos(sample.angle - a)));
        worstAngle = Math.max(worstAngle, d / DEG);
        // 拾取必须选回同一根轴；三条环相交的那两个点上允许“选到另一条”，
        // 但那条也必须真的就在光标底下（误差 < 1px），否则就是选错了。
        const hit = pickRing(camera, px, py, base, rot);
        ok(hit !== null, `pickRing hits something at ${nameOf(axis)} ${deg}deg`);
        if (hit) {
            if (hit.axis === axis) {
                ok(true, `pickRing returns ${nameOf(axis)} at ${deg}deg`);
            } else {
                const alt = ringSamplePlane(camera, matrix, px, py, base,
                    ringNormal(hit.axis, rot), radius);
                ok(alt.err < 1.0,
                    `pickRing prefers ${nameOf(hit.axis)} at ${nameOf(axis)} ${deg}deg only on a ring crossing`
                    + ` (err ${alt.err.toFixed(3)}px)`);
            }
        }

        // 漏掉 +targetY 时角度会偏
        const broken = ringSamplePlane(camera, matrix, px, py, base, normal, radius, false);
        const brokenDelta = Math.abs(Math.atan2(Math.sin(broken.angle - a), Math.cos(broken.angle - a))) / DEG;
        if (brokenDelta > 1) misses++;
    }
}
ok(worstErr < 1e-6, `radial error at the exact ring is ~0 (worst ${worstErr.toExponential(2)}px)`);
ok(worstAngle < 1e-6, `recovered ring angle matches the input (worst ${worstAngle.toExponential(2)} deg)`);
ok(misses > 0, `omitting camera.targetY would skew the angle in ${misses} sample(s) - that term is required`);

// 径向偏 20px 就必须打不中（阈值 7px）
{
    const normal = ringNormal(AXIS_Y, rot);
    const [bu, bv] = planeBasis(normal);
    const a = 40 * DEG;
    const r2 = radius + 20 / camera.scale;
    const pt = [
        base[0] + (bu[0] * Math.cos(a) + bv[0] * Math.sin(a)) * r2,
        base[1] + (bu[1] * Math.cos(a) + bv[1] * Math.sin(a)) * r2,
        base[2] + (bu[2] * Math.cos(a) + bv[2] * Math.sin(a)) * r2,
    ];
    const px = projectX(matrix, pt[0], pt[1], pt[2]);
    const py = projectY(matrix, pt[0], pt[1], pt[2]);
    const sample = ringSamplePlane(camera, matrix, px, py, base, normal, radius);
    ok(sample.err > RING_TOL_PX, `20px outside the ring is a miss (err ${sample.err.toFixed(1)}px)`);
}

// 轴杆：6px 阈值，屏幕尺寸恒定 —— 拾取的像素阈值本来就是屏幕量，缩放不影响手感
for (const scale of [8, 22, 80]) {
    const size = shaftSize(scale);
    ok(size * scale <= SHAFT_PX + 1e-9, `shaft at scale ${scale} is ${(size * scale).toFixed(1)}px on screen`);
    ok(AXIS_TOL_PX < RING_TOL_PX, 'axis threshold (6px) is tighter than the ring threshold (7px)');
}

// 环侧看成一条线（俯仰 = 0 时的 Y 环）→ 平面求交退化 → 不给角度，编辑器就不接管按下，
// 让它落回「拖光束本体旋转」（否则一按就跳 180°）。
{
    const flat = Object.assign({}, camera, { pitch: 0 });
    const m2 = fullMatrix(flat);
    const n = ringNormal(AXIS_Y, rot);
    const px = projectX(m2, base[0] + radius, base[1], base[2]);
    const py = projectY(m2, base[0] + radius, base[1], base[2]);
    const deg = ringSamplePlane(flat, m2, px, py, base, n, radius);
    ok(!Number.isFinite(deg.err) && Number.isNaN(deg.angle),
        'an edge-on ring (pitch 0, Y ring) yields no angle - the drag refuses instead of jumping 180deg');
}

// ---------------------------------------------------------------------------------------------
// 汇总
// ---------------------------------------------------------------------------------------------
console.log('');
if (failures === 0) {
    console.log(`BEAM GIZMO CHECK: PASS (${checks} assertions)`);
} else {
    console.error(`BEAM GIZMO CHECK: FAIL (${failures}/${checks} assertions)`);
    process.exitCode = 1;
}
