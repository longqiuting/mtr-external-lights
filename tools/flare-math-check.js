// Reproduces LightFlare.quad()'s size/alpha math for a report table (no game needed).
const FOV = 70, H = 360, TAN = Math.tan(FOV * Math.PI / 360);
const BASE = 1.4, MINPX = Math.max(8, Math.round(H * 0.012)), PEAK = 0.55, SHARE = 0.35;
const FADE_FROM = 0.70, LIMIT = 128; // eff.beamDistance default (HeadlightConfig.beamDistance)
const fadeOf = (d, limit) => {
  if (limit <= 0) return 1;
  const from = limit * FADE_FROM;
  if (d <= from) return 1;
  if (d >= limit) return 0;
  const t = (d - from) / (limit - from), inv = 1 - t;
  return inv * inv;
};
const rows = [];
for (const d of [6, 20, 40, 60]) {
  for (const f of [1.0, 0.5, 0.2]) {
    const f3 = f * f * f;
    let w = BASE * (0.45 + 1.4 * f3);
    let px = w / (2 * d * TAN) * H;
    if (px > 1e-6 && px < MINPX) { w *= MINPX / px; px = MINPX; }
    const fade = fadeOf(d, LIMIT);
    const bright = Math.min(1, PEAK * 1.0 * (0.35 + 0.65 * f3));
    const a = Math.min(1, Math.max(bright, PEAK * SHARE) * fade);
    rows.push({ d, f, w: +w.toFixed(2), px: +px.toFixed(1), a: +a.toFixed(3), fade: +fade.toFixed(2),
      verdict: f < -0.15 ? 'back' : 'ok' });
  }
}
console.log('minPx floor for this screen:', MINPX);
console.table(rows);
