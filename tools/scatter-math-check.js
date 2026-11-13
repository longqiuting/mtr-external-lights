// Offline check of the light_scatter.fsh model. Not the shader itself - a port of its math, used to
// pin down signs and rough magnitudes before trusting a 90-second game launch. Camera sits at the
// origin; view space is GL convention (forward = -Z).
//
// Two terms, one composite:
//   volume  tau  = (chord/R) * cone * window * phase * trans * power * falloff * 0.125
//   surface s    = power * spot * win * 1/(1 + d^2/9) * max(N.L, 0)
//   added        = volumeSum * 1.6 + surfaceSum * (scene*6.0 + 0.03)
//   lit          = scene + (1 - exp(-added * k)) * (1 - 0.5*scene)
'use strict';

const R = 6.0;              // scatterRadius
const LEN = 16.0;           // beam length
const EXT = 0.075;          // extinction per block
const G = 0.55;             // HG anisotropy
const GAIN = 1.6;           // volume calibration (uParams.x); strength k is applied separately
const K = 1.0;              // scatterStrength
const COS_IN = 0.94, COS_OUT = 0.55;
const ATT_REF = 9.0;        // uSurface.y: 1/(1 + d^2/9)
const SURF_ALBEDO = 8.0;
const SURF_FLOOR = 0.05;

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

/**
 * The surface term of one lamp at surface point P with face normal N.
 * Mirrors the shader: cosAngle = dot(axis, -L) with L = normalize(lamp - P), i.e. the beam axis vs
 * the direction the lamp sends light along. It must be ~1 for a floor point under the beam and <= 0
 * for anything behind the lamp.
 */
function surface(N, P, lamp, axis, power) {
  const toLamp = sub(lamp, P);
  const d = len(toLamp);
  if (d <= 1e-3) return { s: 0, cosAngle: 0, ndotl: 0, att: 0 };
  const L = scale(toLamp, 1 / d);
  const cosAngle = dot(axis, scale(L, -1));
  const spot = smooth01((cosAngle - COS_OUT) / Math.max(COS_IN - COS_OUT, 1e-3));
  const win = 1 - smooth01((d - LEN * 0.6) / Math.max(LEN * 0.4, 1e-3));
  const att = 1 / (1 + d * d / ATT_REF);
  const ndotl = Math.max(dot(N, L), 0);
  return { s: power * spot * win * att * ndotl, cosAngle, ndotl, att, spot, win };
}

/** The shader's composite, per channel (all three channels carry the same scalar here). */
function composite(scene, volumeSum, surfaceSum, k = K) {
  const added = volumeSum * GAIN + surfaceSum * (scene * SURF_ALBEDO + SURF_FLOOR);
  return scene + (1 - Math.exp(-added * k)) * (1 - 0.5 * scene);
}

// ---------------------------------------------------------------------------------------------
// Volume term (unchanged behaviour: with k = 1 this is the old 1 - exp(-tau * 1.6))
// ---------------------------------------------------------------------------------------------
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

// ---------------------------------------------------------------------------------------------
// Surface term: camera 1.6 blocks above a flat floor (y = -1.6), one head lamp at [0, 1.0, -6]
// aimed forward and down (axis [0, -0.25, -1] normalised). N = (0,1,0) for the floor.
// ---------------------------------------------------------------------------------------------
console.log('\n--- surface term (floor y = -1.6, lamp at [0, 1.0, -6], axis forward+down) ---');
const lamp = [0, 1.0, -6];
const axis = norm([0, -0.25, -1]);
const N = [0, 1, 0];

const floorPoint = (x, z) => [x, -1.6, z];
const rows = [
  ['1 m under the lamp', floorPoint(0, -6)],
  ['2 blocks in front of the nose', floorPoint(0, -8)],
  ['5 blocks in front of the nose', floorPoint(0, -11)],
  ['10 blocks in front', floorPoint(0, -16)],
  ['4 blocks to the side, 6 ahead', floorPoint(4, -6)],
  ['6 blocks BEHIND the lamp', floorPoint(0, 0)],
];
for (const [name, P] of rows) {
  const r = surface(N, P, lamp, axis, 1.0);
  console.log(`${name.padEnd(40)} s=${r.s.toFixed(3)}  cos=${r.cosAngle.toFixed(2)}`
    + ` spot=${(r.spot === undefined ? 0 : r.spot).toFixed(2)} ndotl=${r.ndotl.toFixed(2)}`
    + ` att=${r.att.toFixed(2)}`);
}

// Composite on three albedos: dark asphalt, concrete platform, white wall.
console.log('\n--- composite (surface only, volume = 0, k = 1) ---');
for (const [name, scene] of [['dark asphalt', 0.04], ['concrete platform', 0.15], ['white wall', 0.60]]) {
  for (const [where, P] of [['under the lamp', floorPoint(0, -6)], ['5 blocks ahead', floorPoint(0, -11)]]) {
    const s = surface(N, P, lamp, axis, 1.0).s;
    const out = composite(scene, 0, s);
    console.log(`${name.padEnd(20)} ${where.padEnd(16)} scene=${scene.toFixed(2)}`
      + ` s=${s.toFixed(3)} -> lit=${out.toFixed(3)} (+${(out - scene).toFixed(3)})`);
  }
}

// A wall facing the camera: N must be flipped toward the eye by the shader; here we feed the
// flipped normal directly. The point is that the normal, not the position, decides - a wall the
// lamp hits straight on is brighter than the floor next to it at the same distance.
console.log('\n--- wall vs floor at the same distance (N decides) ---');
const wallP = [0, 0.2, -8];
const wallN = norm([0, 0, 1]);        // faces the camera
const floorNear = [0, -1.6, -8];
const sw = surface(wallN, wallP, lamp, axis, 1.0).s;
const sf = surface(N, floorNear, lamp, axis, 1.0).s;
console.log(`wall (N toward camera) s=${sw.toFixed(3)}   floor at the same z s=${sf.toFixed(3)}`);

// Identity check: with no lamps the composite must return the scene untouched - that is what makes
// a wrong matrix harmless (the pass can only fail to add light, never blank the screen).
console.log('\n--- identity ---');
for (const scene of [0.0, 0.04, 0.5, 1.0]) {
  const out = composite(scene, 0, 0);
  console.log(`scene=${scene.toFixed(2)} -> ${out.toFixed(6)} (delta ${(out - scene).toExponential(1)})`);
}

// Brightness ceiling: a lamp 0.5 blocks from the floor surface, full power - the result must stay
// under 1 and must not be a white card.
console.log('\n--- ceiling ---');
const close = [0, -1.0, -6];
for (const scene of [0.04, 0.15, 0.6]) {
  const s = surface(N, close, [0, -0.5, -6], norm([0, -1, 0]), 1.0).s;
  const out = composite(scene, 0, s);
  console.log(`scene=${scene.toFixed(2)} s=${s.toFixed(2)} -> lit=${out.toFixed(3)}`);
}
