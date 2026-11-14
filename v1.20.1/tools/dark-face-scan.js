// 找出「画出来是黑的」面：逐面采样它的 UV 矩形，统计黑色像素占比。
// 用法：node tools/dark-face-scan.js [rawmodel 名…]
'use strict';

const fs = require('fs');
const path = require('path');
const { decodePng } = require(path.resolve(__dirname, '..', '..', 'tools', 'preview-rawmodels.js'));

const RES = path.resolve(__dirname, '..', 'src', 'main', 'resources', 'assets', 'mtrlte');
const RAW = path.join(RES, 'rawmodels');
const names = process.argv.slice(2).length
  ? process.argv.slice(2)
  : ['drinks_machine_top', 'drinks_machine_bottom_closed', 'drinks_machine_bottom_opened'];

const cache = new Map();
function tex(file) {
  if (!cache.has(file)) cache.set(file, decodePng(path.join(RES, 'textures', 'block', file + '.png')));
  return cache.get(file);
}

for (const name of names) {
  const model = JSON.parse(fs.readFileSync(path.join(RAW, name + '.json'), 'utf8'));
  const texName = Object.entries(model.textures || {}).find(([k]) => k !== 'particle')[1];
  const img = tex(texName);
  console.log(`\n=== ${name}`);
  model.elements.forEach((el, i) => {
    for (const [dir, face] of Object.entries(el.faces || {})) {
      const [u1, v1, u2, v2] = face.uv;
      const x0 = Math.round((Math.min(u1, u2) / 16) * img.width);
      const x1 = Math.round((Math.max(u1, u2) / 16) * img.width);
      const y0 = Math.round((Math.min(v1, v2) / 16) * img.height);
      const y1 = Math.round((Math.max(v1, v2) / 16) * img.height);
      let n = 0, dark = 0, clear = 0, sum = 0;
      for (let y = Math.max(0, y0); y < Math.min(img.height, Math.max(y0 + 1, y1)); y += 2) {
        for (let x = Math.max(0, x0); x < Math.min(img.width, Math.max(x0 + 1, x1)); x += 2) {
          const o = (y * img.width + x) * 4;
          const a = img.data[o + 3];
          n++;
          if (a < 128) { clear++; continue; }
          const lum = 0.299 * img.data[o] + 0.587 * img.data[o + 1] + 0.114 * img.data[o + 2];
          sum += lum;
          if (lum < 24) dark++;
        }
      }
      const darkPct = n ? (dark / n) * 100 : 0;
      const clearPct = n ? (clear / n) * 100 : 0;
      if (darkPct > 15) {
        console.log(`  el#${i} ${dir.padEnd(5)} uv[${face.uv}] box ${JSON.stringify(el.from)}..${JSON.stringify(el.to)}`
          + ` 黑${darkPct.toFixed(0)}% 透明${clearPct.toFixed(0)}% 均亮度${(sum / Math.max(1, n - clear)).toFixed(0)}`);
      }
    }
  });
}
