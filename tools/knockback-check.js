// Mirrors CollisionImpact.flightTicks / horizontalReach / knockbackHorizontal exactly,
// then integrates the real landing displacement with the resolved vx.
// Physics (MC 1.20.1 LivingEntity#travel, airborne, no input, feet start at y=0):
//   tick: vy -= 0.08; y += vy; x += vx; vy *= 0.98; vh *= 0.91
// Stop when y <= 0 after at least 1 tick (landing tick counts).
function flightTicks(vy) {
  if (!(vy > 0)) return 1;
  let y = 0, v = vy;
  for (let t = 1; t <= 200; t++) {
    v -= 0.08;
    y += v;
    if (y <= 0 && t > 1) return t;   // same as the Java code
    v *= 0.98;
  }
  return 200;
}
function reach(vy) {
  const n = flightTicks(vy);
  let sum = 0, k = 1;
  for (let i = 0; i < n; i++) { sum += k; k *= 0.91; }
  return sum;
}
function vxFor(distance, vy) { return distance / Math.max(1e-3, reach(vy)); }
function land(vx, vy) {
  let y = 0, x = 0, v = vy, t = 0;
  while (t < 400) {
    t++;
    v -= 0.08; y += v; x += vx;
    v *= 0.98; vx *= 0.91;
    if (y <= 0 && t > 1) break;
  }
  return { t, x };
}
console.log('vy  air  reach   d=5.5 vx      land    | d=8.25(FLY) vx    land');
for (const vy of [0.25, 0.45, 0.60]) {
  const v55 = vxFor(5.5, vy), v825 = vxFor(8.25, vy);
  const l55 = land(v55, vy), l825 = land(v825, vy);
  console.log(
    `${vy.toFixed(2)}  ${String(flightTicks(vy)).padStart(3)}  ${reach(vy).toFixed(3)}  ` +
    `${v55.toFixed(3)}  ${l55.x.toFixed(2)} (${l55.t}t)  | ${v825.toFixed(3)}  ${l825.x.toFixed(2)} (${l825.t}t)`);
}
for (const d of [1, 3, 5.5, 10, 20]) {
  const v = vxFor(d, 0.45);
  const l = land(v, 0.45);
  console.log(`d=${d} vy=0.45 -> vx=${v.toFixed(3)} (${(v*72).toFixed(0)} km/h) land=${l.x.toFixed(2)} blocks in ${l.t}t`);
}
