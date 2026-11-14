// Stage-3: 1.20.1 shader/draw-batch API -> 1.18.2 twins.
const fs = require('fs');
const path = require('path');

const root = process.argv[2];
const files = [
  'dev/js/mtrheadlight/GlowCompat.java',
  'dev/js/mtrheadlight/sss/ScatterPass.java',
];

const subs = [
  ['GameRenderer::getPositionColorProgram', 'GameRenderer::getPositionColorShader'],
  ['GameRenderer::getPositionTexColorProgram', 'GameRenderer::getPositionTexColorShader'],
  ['GameRenderer::getPositionTexProgram', 'GameRenderer::getPositionTexShader'],
  ['GameRenderer::getPositionProgram', 'GameRenderer::getPositionShader'],
];

for (const rel of files) {
  const p = path.join(root, rel);
  let txt = fs.readFileSync(p, 'utf8');
  const notes = [];
  for (const [a, b] of subs) {
    const n = txt.split(a).length - 1;
    if (n) { txt = txt.split(a).join(b); notes.push(`${a}->${b} x${n}`); }
  }
  // BufferRenderer.drawWithGlobalProgram(X.end())  ->  BufferRenderer.draw(X)
  const re = /BufferRenderer\.drawWithGlobalProgram\(\s*([A-Za-z0-9_]+)\.end\(\)\s*\)/g;
  let n = 0;
  txt = txt.replace(re, (m, id) => { n++; return `BufferRenderer.draw(${id})`; });
  if (n) notes.push(`drawWithGlobalProgram x${n}`);
  fs.writeFileSync(p, txt);
  console.log(rel + ': ' + (notes.join(', ') || 'no change'));
}
