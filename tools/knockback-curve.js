// Offline check of the exponential knockback distance, mirroring
// CollisionImpact.knockbackDistanceAt / knockbackHorizontal / flightTicks / horizontalReach
// line by line, then integrating the real 1.20.1 LivingEntity#travel airborne motion.
//
//   d(v) = knockbackDistance * exp(knockbackExpK * (v - 50) / 50), clamped 1..knockbackMax
//   vx   = d(v) / sum(0.91^i, i = 0..n-1)     (n = airtime ticks for that tier's vy)
//
// Physics (MC 1.20.1, airborne, no input, feet start at y = 0):
//   tick: vy -= 0.08; y += vy; x += vx; vy *= 0.98; vx *= 0.91
// Landing tick counts (move happens before the drag multiply).
'use strict';

const CFG = {
  knockbackDistance: 5.5,   // HeadlightConfig.knockbackDistance
  knockbackExpK: 1.0,       // HeadlightConfig.knockbackExpK
  knockbackMax: 80.0,       // HeadlightConfig.knockbackMax
  launchVertical: 0.45,     // HeadlightConfig.trainCollisionLaunchVertical
  flyVertical: 0.60,        // HeadlightConfig.trainCollisionFlyVertical
  highMaxKmh: 50,           // trainCollisionHighDamageMaxKmh
  launchMaxKmh: 80,         // trainCollisionLaunchMaxKmh
};

const REF_KMH = 50;         // CollisionImpact.KNOCKBACK_REF_KMH
const EXP_SCALE = 50;       // CollisionImpact.KNOCKBACK_EXP_SCALE_KMH
const GRAVITY = 0.08, V_DRAG = 0.98, H_DRAG = 0.91;

const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));

function tierOf(speedKmh) {
  if (speedKmh < CFG.highMaxKmh) return 'HIGH';          // does not launch
  if (speedKmh < CFG.launchMaxKmh) return 'LAUNCH';
  return 'FLY';
}
function launchVertical(tier) {
  return tier === 'FLY' ? CFG.flyVertical : CFG.launchVertical;
}

// CollisionImpact.knockbackDistanceAt
function knockbackDistanceAt(speedKmh) {
  const base = clamp(CFG.knockbackDistance, 1, 80);
  const k = clamp(CFG.knockbackExpK, 0, 3);
  const max = clamp(CFG.knockbackMax, 1, 200);
  const exponent = k * (speedKmh - REF_KMH) / EXP_SCALE;
  if (!(exponent < 20)) return max;
  const d = base * Math.exp(exponent);
  if (!(d > 0)) return 1;
  return Math.min(max, Math.max(1, d));
}

// CollisionImpact.flightTicks
function flightTicks(vy) {
  if (!(vy > 0)) return 1;
  let y = 0, v = vy;
  for (let t = 1; t <= 200; t++) {
    v -= GRAVITY; y += v;
    if (y <= 0 && t > 1) return t;
    v *= V_DRAG;
  }
  return 200;
}

// CollisionImpact.horizontalReach
function horizontalReach(vy) {
  const n = flightTicks(vy);
  let sum = 0, k = 1;
  for (let i = 0; i < n; i++) { sum += k; k *= H_DRAG; }
  return sum;
}

// CollisionImpact.knockbackHorizontal (caps 8 / 12 b/t)
function knockbackHorizontal(tier, speedKmh) {
  const vy = launchVertical(tier);
  const reach = Math.max(1e-3, horizontalReach(vy));
  const vx = knockbackDistanceAt(speedKmh) / reach;
  const cap = tier === 'FLY' ? 12 : 8;
  return Math.min(cap, Math.max(0, vx));
}

// The real flight integration.
function fly(vx, vy) {
  let y = 0, x = 0, v = vy, h = vx, t = 0, apex = 0;
  while (t < 400) {
    t++;
    v -= GRAVITY; y += v; x += h;
    if (y > apex) apex = y;
    v *= V_DRAG; h *= H_DRAG;
    if (y <= 0 && t > 1) break;
  }
  return { t, x, apex, vxEnd: h };
}

const speeds = [50, 80, 120, 160];
console.log('km/h  tier    air  vy    formula   vx      landed    err     apex');
const rows = [];
for (const v of speeds) {
  const tier = tierOf(v);
  if (tier === 'HIGH') { console.log(`${v}  ${tier}  (does not launch)`); continue; }
  const vy = launchVertical(tier);
  const d = knockbackDistanceAt(v);
  const vx = knockbackHorizontal(tier, v);
  const f = fly(vx, vy);
  const err = f.x - d;
  console.log(
    `${String(v).padStart(4)}  ${tier.padEnd(6)}  ${String(f.t).padStart(3)}  ` +
    `${vy.toFixed(2)}  ${d.toFixed(3).padStart(7)}  ${vx.toFixed(3)}  ` +
    `${f.x.toFixed(3).padStart(7)}  ${err >= 0 ? '+' : ''}${err.toFixed(3)}  ${f.apex.toFixed(2)}`);
  rows.push({ v, tier, air: f.t, vy, d, vx, landed: f.x, err });
}

// Curve at other knobs, to show the shape is really exponential.
console.log('\nsanity: k = 0 (fixed distance) and the 80-block cap');
for (const k of [0, 1, 2, 3]) {
  const line = [50, 80, 120, 160, 200, 300].map(v => {
    CFG.knockbackExpK = k;
    return knockbackDistanceAt(v).toFixed(1);
  });
  console.log(`  k=${k}: ` + line.join(' / ') + '  (50/80/120/160/200/300 km/h)');
}
CFG.knockbackExpK = 1.0;

// JSON for the report.
console.log('\nJSON ' + JSON.stringify(rows));
