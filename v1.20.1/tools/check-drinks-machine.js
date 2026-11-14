// 逐面体检：UV 越界（**错误**）/ UV 落在贴图空白（警告）/ 面积为零的面 / 反向盒。
//
// 为什么超界是错误而不是「只是丑一点」：游戏里实体贴图是 REPEAT 采样，uv 超过 16 的那一条会
// 绕回贴图另一边，采到的是**给别的面准备的**像素 —— 这张 atlas 的左上角正好是一块纯黑，
// 于是饮料机正上方的前沿被染出一道黑边（Blockbench 预览是 clamp，所以看不出来）。
//
// 用法：node tools/check-drinks-machine.js [rawmodel 名…]
// 默认检查三份：drinks_machine_top / _bottom_closed / _bottom_opened
// 退出码：有「错误」时 1，否则 0（可以直接挂进 CI / verify-*.sh）。
'use strict';

const fs = require('fs');
const path = require('path');
// 仓库根目录的 tools/preview-rawmodels.js 提供 PNG 解码（v1.20.1/tools → 上两级）
const { decodePng } = require(path.resolve(__dirname, '..', '..', 'tools', 'preview-rawmodels.js'));

const RES = path.resolve(__dirname, '..', 'src', 'main', 'resources', 'assets', 'mtrlte');
const RAW = path.join(RES, 'rawmodels');

const names = process.argv.slice(2).length
  ? process.argv.slice(2)
  : ['drinks_machine_top', 'drinks_machine_bottom_closed', 'drinks_machine_bottom_opened'];

const texCache = new Map();
function texture(file) {
  const hit = texCache.get(file);
  if (hit) return hit;
  const tex = decodePng(path.join(RES, 'textures', 'block', file + '.png'));
  texCache.set(file, tex);
  return tex;
}

/** uv（0..16 空间）矩形里 alpha < 128 的像素比例。 */
function transparentFraction(tex, u1, v1, u2, v2) {
  const x0 = Math.floor((u1 / 16) * tex.width);
  const x1 = Math.ceil((u2 / 16) * tex.width);
  const y0 = Math.floor((v1 / 16) * tex.height);
  const y1 = Math.ceil((v2 / 16) * tex.height);
  let total = 0;
  let open = 0;
  for (let y = Math.max(0, y0); y < Math.min(tex.height, y1); y++) {
    for (let x = Math.max(0, x0); x < Math.min(tex.width, x1); x++) {
      const o = (y * tex.width + x) * 4;
      total++;
      if (tex.data[o + 3] < 128) open++;
    }
  }
  return total ? open / total : 0;
}

let errors = 0;
let warnings = 0;

for (const name of names) {
  const model = JSON.parse(fs.readFileSync(path.join(RAW, name + '.json'), 'utf8'));
  const texName = Object.entries(model.textures || {})
    .filter(([k]) => k !== 'particle')
    .map(([, v]) => v)[0] || (model.textures || {}).particle;
  const tex = texture(texName);
  console.log(`\n=== ${name}  (texture ${texName} ${tex.width}x${tex.height}, ${model.elements.length} elements)`);

  let worstU = 0;
  let worstV = 0;
  model.elements.forEach((el, i) => {
    const from = el.from;
    const to = el.to;
    const x1 = Math.min(from[0], to[0]); const x2 = Math.max(from[0], to[0]);
    const y1 = Math.min(from[1], to[1]); const y2 = Math.max(from[1], to[1]);
    const z1 = Math.min(from[2], to[2]); const z2 = Math.max(from[2], to[2]);
    if (el.shade === false) console.log(`  [info] el#${i} shade=false（自发光面）`);

    for (const [dir, face] of Object.entries(el.faces || {})) {
      const [u1, v1, u2, v2] = face.uv;
      const zeroArea = dir === 'north' || dir === 'south' ? x2 - x1 === 0
        : dir === 'east' || dir === 'west' ? z2 - z1 === 0 : y2 - y1 === 0;
      if (zeroArea) continue; // 解析器会丢掉，不报

      worstU = Math.max(worstU, u1, u2);
      worstV = Math.max(worstV, v1, v2);

      const outOfRange = u1 < 0 || v1 < 0 || u2 > 16 || v2 > 16;
      if (outOfRange) {
        errors++;
        console.log(`  [ERROR] el#${i} ${dir}: UV 越界 u[${u1}..${u2}] v[${v1}..${v2}]`
          + ' → 游戏里会 REPEAT 绕到贴图另一边（多半是黑/空白）。'
          + ' 跑 node tools/gen-drinks-machine.js 夹回 16。');
        continue;
      }
      const frac = transparentFraction(tex, Math.min(u1, u2), Math.min(v1, v2),
        Math.max(u1, u2), Math.max(v1, v2));
      if (frac > 0.02) {
        // 面积（模型单位²）：小于 4 的薄片/螺钉，cutout 掉一点点无所谓，只标 info。
        const area = dir === 'north' || dir === 'south' ? (x2 - x1) * (y2 - y1)
          : dir === 'east' || dir === 'west' ? (z2 - z1) * (y2 - y1) : (x2 - x1) * (z2 - z1);
        const tag = area < 4 ? '[info]' : '[WARN]';
        if (area >= 4) warnings++;
        console.log(`  ${tag} el#${i} ${dir}: uv[${u1},${v1},${u2},${v2}] 有 ${(frac * 100).toFixed(0)}% 落在透明像素`
          + `（面积 ${area.toFixed(2)} 单位²，cutout 后是洞）`);
      }
    }
  });
  console.log(`  最大 uv：u ${worstU} / v ${worstV}（上限 16）`);
}

console.log(`\n${errors} 个错误，${warnings} 个警告`);
process.exitCode = errors ? 1 : 0;
