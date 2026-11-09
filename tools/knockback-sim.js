// Offline integration of MC 1.20.1 LivingEntity#travel (normal branch, airborne, no input):
//   each tick: vy -= 0.08; pos += (vx, vy, vz); vy *= 0.98; vx,vz *= 0.91
// Landing = the tick where y returns to <= 0 (feet at the launch height).
function flight(vy0, vx0) {
  let y = 0, vy = vy0, vx = vx0, x = 0, t = 0, apex = 0;
  while (t < 400) {
    t++;
    vy -= 0.08;
    y += vy;
    x += vx;
    if (y > apex) apex = y;
    vy *= 0.98;
    vx *= 0.91;
    if (y <= 0 && t > 1) break;
  }
  return { ticks: t, blocks: x, apex: apex };
}

function requiredVx(vy0, target) {
  // distance is linear in vx: d = vx * S, so solve S once then divide.
  const s = flight(vy0, 1.0).blocks;
  return target / s;
}

for (const vy of [0.45, 0.60]) {
  const f1 = flight(vy, 1.0);
  console.log(`vy=${vy}: airtime=${f1.ticks}t vx=1.0 -> ${f1.blocks.toFixed(2)} blocks, apex=${f1.apex.toFixed(2)}`);
  for (const d of [5, 5.5, 8, 12, 20]) {
    const vx = requiredVx(vy, d);
    const got = flight(vy, vx).blocks;
    console.log(`  target=${d} -> vx=${vx.toFixed(3)} b/t (${(vx * 72).toFixed(1)} km/h), sim=${got.toFixed(2)}`);
  }
  console.log(`  fly x1.5 target=8.25 -> vx=${requiredVx(vy, 8.25).toFixed(3)}, sim=${flight(vy, requiredVx(vy, 8.25)).blocks.toFixed(2)}`);
}
