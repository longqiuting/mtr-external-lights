// Offline check of the light_scatter.fsh model. Not the shader itself - a port of its math, used to
// pin down signs and rough magnitudes before trusting a 90-second game launch. Camera sits at the
// origin; view space is GL convention (forward = -Z).
'use strict';

const R = 6.0;              // scatterRadius
const LEN = 16.0;           // beam length
const EXT = 0.075;          // extinction per block
const G = 0.55;             // HG anisotropy
const GAIN = 1.6;           // strength (1.0) * 1.6
const COS_IN = 0.94, COS_OUT = 0.55;

const sub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const len = a => Math.sqrt(dot(a, a));
const scale = (a, k) => [a[0] * k, a[1] * k, a[2] * k];
const norm = a => { const l = len(a) || 1; return scale(a, 1 / l); };
const smooth01 = x => { const t = Math.min(1, Math.max(0, x)); return t * t * (3 - 2 * t); };

function tau(rd, tHit, lamp, axis, power) {
  const tClosest = dot(lamp, rd);
  const perp2 = dot(lamp, lamp) - tClosest * tClosest;
  if (perp2 >= R * R) return 0;
  const half = Math.sqrt(Math.max(R * R - perp2, 0));
  const t0 = Math.max(tClosest - half, 0);
  const t1 = Math.min(tClosest + half, tHit);
  if (t1 <= t0) return 0;
  const chord = t1 - t0;
  const mid = scale(rd, 0.5 * (t0 + t1));
  const toMid = sub(mid, lamp);
  const dist = len(toMid);
  const midDir = dist > 1e-4 ? scale(toMid, 1 / dist) : axis;
  const cone = Math.max(smooth01((dot(midDir, axis) - COS_OUT) / Math.max(COS_IN - COS_OUT, 1e-3)),
                        1 - smooth01(dist / R));
  const axial = dot(toMid, axis);
  const window = Math.max(1 - smooth01((axial - LEN * 0.6) / Math.max(LEN * 0.4, 1e-3)),
                          1 - smooth01(dist / R));
  const ct = Math.min(1, Math.max(-1, -dot(axis, rd)));
  const phase = Math.min((1 - G * G) / Math.pow(Math.max(1 + G * G - 2 * G * ct, 1e-3), 1.5), 4.0);
  const trans = Math.exp(-EXT * dist);
  const falloff = 1 / (1 + dist * dist / 9);
  return (chord / R) * cone * window * phase * trans * power * falloff * 0.125;
}

const lit = t => 1 - Math.exp(-t * GAIN);
const cases = [];
const push = (name, t) => cases.push([name, t, lit(t)]);

const camFacing = [0, 0, 1];        // lamp shining back at the camera
const camAway = [0, 0, -1];

push('lamp dead ahead (6 blocks), shining at the camera', tau([0, 0, -1], 96, [0, 0, -6], camFacing, 1));
push('same lamp, seen from the side (ray 4 blocks off axis)', tau([4 / Math.hypot(4, 6), 0, -6 / Math.hypot(4, 6)], 96, [0, 0, -6], camFacing, 1));
push('same lamp, grazing the sphere edge (perp 5.9)', tau([5.9 / Math.hypot(5.9, 6), 0, -6 / Math.hypot(5.9, 6)], 96, [0, 0, -6], camFacing, 1));
push('lamp dead ahead but shining AWAY (wrong phase)', tau([0, 0, -1], 96, [0, 0, -6], camAway, 1));
push('lamp 2 blocks ahead, ray fully inside its sphere', tau([0, 0, -1], 96, [0, 0, -2], camFacing, 1));
push('lamp behind the camera', tau([0, 0, -1], 96, [0, 0, 6], camFacing, 1));
push('dead ahead, a wall 3 blocks in front (occluded lamp)', tau([0, 0, -1], 3, [0, 0, -6], camFacing, 1));
push('dead ahead, wall 30 blocks in front (lamp in front of it)', tau([0, 0, -1], 30, [0, 0, -6], camFacing, 1));
push('lamp 40 blocks ahead', tau([0, 0, -1], 96, [0, 0, -40], camFacing, 1));
push('tail lamp (power 0.6, radius 3.9), dead ahead', tau([0, 0, -1], 96, [0, 0, -6], camFacing, 0.6));

for (const [name, t, l] of cases) {
  console.log(`${name.padEnd(52)} tau=${t.toFixed(3).padStart(7)}  lit=${l.toFixed(3)}`);
}

// Two head lamps of one nose, 1.05 blocks apart laterally, seen head-on: the sum must saturate but
// the off-axis pixel must stay well below it (soft edge, no hard disc).
const a = tau([0, 0, -1], 96, [-1.05, 0, -6], camFacing, 1);
const b = tau([0, 0, -1], 96, [1.05, 0, -6], camFacing, 1);
console.log(`\ntwo-lamp nose, centre pixel: lit=${lit(a + b).toFixed(3)} (tau=${(a + b).toFixed(2)})`);
const edge = tau([3 / Math.hypot(3, 6), 0, -6 / Math.hypot(3, 6)], 96, [-1.05, 0, -6], camFacing, 1)
           + tau([3 / Math.hypot(3, 6), 0, -6 / Math.hypot(3, 6)], 96, [1.05, 0, -6], camFacing, 1);
console.log(`two-lamp nose, 3-block off-axis pixel: lit=${lit(edge).toFixed(3)}`);
