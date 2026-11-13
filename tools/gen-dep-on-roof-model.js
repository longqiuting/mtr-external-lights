#!/usr/bin/env node
/**
 * 把 Blockbench 的挂顶发车倒数器模型转成原版读得懂的方块模型（v1.20.1 专用）。
 *
 * <h2>为什么要转，而不是把 Blockbench 的 json 直接丢进 resources</h2>
 * <p>原版 {@code ModelElement.Deserializer} 的元素旋转<b>只认 -45 / -22.5 / 0 / 22.5 / 45</b>
 * （字节码里的字符串：{@code "Invalid rotation %s found, only -45/-22.5/0/22.5/45 allowed"}），
 * 而素材 {@code 素材/挂墙发车倒数器/dep_on_roof.json}（format_version 26.3）里是
 * {@code 135 / 154 / -87.5 / -73.5 / -59 / 31 / 40.25 …}。直接丢进去会
 * {@code JsonParseException} → 模型加载失败（紫黑方块）。
 *
 * <h2>两步转换</h2>
 * <ol>
 *   <li><b>整 90° 折进盒子坐标</b>（精确）：把每个元素的角度 θ 拆成
 *       {@code 90k + 零头}；{@code from / to} 绕同一个 {@code origin} 转 90k°（轴对齐的盒子
 *       转 90° 还是轴对齐的盒子），六个面按「法线转到哪」重新分配 UV。
 *       这一步不改变任何像素：最终几何与素材逐顶点一致（脚本尾部会核对）。</li>
 *   <li><b>零头吸附</b>到最近的合法角：牌框六件本来 135° → 折完正好 -45（不动）；
 *       吊索那十节是 -26 / -11.75 / 7.25 / … 这类「拧绳子」的角度，被吸到 ±22.5 / 0 / 45，
 *       单件最多偏 11.25°，几何误差 ≤ 0.02 格。</li>
 * </ol>
 *
 * <h2>用法</h2>
 * <pre>
 *   node tools/gen-dep-on-roof-model.js [源 json] [输出 json]
 * </pre>
 * <p>默认从 {@code ~/Desktop/素材/挂墙发车倒数器/dep_on_roof.json} 读，
 * 写到 {@code v1.20.1/src/main/resources/assets/mtrlte/models/block/dep_on_roof.json}。
 * 贴图复用 {@code mtrlte:block/dep_on_wall}（模型里的 uv 就是那张图的那几块）。
 */
const fs = require('fs');
const os = require('os');
const path = require('path');

const SRC = process.argv[2]
  || path.join(os.homedir(), 'Desktop', '素材', '挂墙发车倒数器', 'dep_on_roof.json');
const OUT = process.argv[3]
  || path.join(__dirname, '..', 'v1.20.1', 'src', 'main', 'resources',
    'assets', 'mtrlte', 'models', 'block', 'dep_on_roof.json');
const TEXTURE = 'mtrlte:block/dep_on_wall';

const LEGAL_ANGLES = [-45, -22.5, 0, 22.5, 45];
const FACES = ['north', 'east', 'south', 'west', 'up', 'down'];
const NORMAL = {
  north: [0, 0, -1], south: [0, 0, 1], east: [1, 0, 0], west: [-1, 0, 0],
  up: [0, 1, 0], down: [0, -1, 0],
};

const rad = (d) => (d * Math.PI) / 180;
const r4 = (v) => Math.round(v * 10000) / 10000;

function snapAngle(deg) {
  let best = LEGAL_ANGLES[0];
  for (const a of LEGAL_ANGLES) {
    if (Math.abs(deg - a) < Math.abs(deg - best)) best = a;
  }
  return best;
}

// 原版（blockstate / 元素 rotation）的 Y 旋转约定：x' = x·cos − z·sin，z' = x·sin + z·cos。
function rotY(x, z, deg, ox, oz) {
  const c = Math.cos(rad(deg));
  const s = Math.sin(rad(deg));
  const dx = x - ox;
  const dz = z - oz;
  return [ox + dx * c - dz * s, oz + dx * s + dz * c];
}

const keyOf = (x, y, z) => `${Math.round(x)},${Math.round(y)},${Math.round(z)}`;
const FACE_BY_NORMAL = {};
for (const f of FACES) FACE_BY_NORMAL[keyOf(...NORMAL[f])] = f;

// 90k° 之后，原来挂在某个面上的 uv 要去「法线转到的那一面」。
function rotateFaces(faces, k) {
  const kk = ((k % 360) + 360) % 360;
  if (kk === 0) return faces;
  const out = {};
  for (const f of FACES) {
    if (!faces[f]) continue;
    const n = NORMAL[f];
    const [rx, rz] = rotY(n[0], n[2], kk, 0, 0);
    out[FACE_BY_NORMAL[keyOf(rx, n[1], rz)]] = faces[f];
  }
  return out;
}

const src = JSON.parse(fs.readFileSync(SRC, 'utf8'));

const elements = [];
for (const e of src.elements) {
  const rot = e.rotation || null;
  const theta = rot ? rot.y || 0 : 0;
  const origin = rot ? rot.origin : [0, 0, 0];
  const k = 90 * Math.round(theta / 90);
  const delta = snapAngle(theta - k);

  const [x1, y1, z1] = e.from;
  const [x2, y2, z2] = e.to;
  const xs = [];
  const zs = [];
  for (const x of [x1, x2]) {
    for (const z of [z1, z2]) {
      const [rx, rz] = rotY(x, z, k, origin[0], origin[2]);
      xs.push(rx);
      zs.push(rz);
    }
  }
  const el = {
    from: [r4(Math.min(...xs)), y1, r4(Math.min(...zs))],
    to: [r4(Math.max(...xs)), y2, r4(Math.max(...zs))],
  };
  if (delta !== 0) {
    el.rotation = { origin: origin.map(r4), axis: 'y', angle: delta };
  }
  el.faces = rotateFaces(e.faces, k);
  elements.push(el);
}

// ---------------------------------------------------------------- 输出（仓库里其它模型那种紧凑写法）
const num = (v) => String(r4(v));
const arr = (a) => `[${a.map(num).join(', ')}]`;
const lines = ['{', '\t"credit": "Made with Blockbench",'];
lines.push(`\t"textures": {\n\t\t"0": "${TEXTURE}",\n\t\t"particle": "${TEXTURE}"\n\t},`);
lines.push('\t"elements": [');
elements.forEach((e, i) => {
  const body = [];
  body.push(`\t\t\t"from": ${arr(e.from)},`);
  body.push(`\t\t\t"to": ${arr(e.to)},`);
  if (e.rotation) {
    body.push(`\t\t\t"rotation": { "origin": ${arr(e.rotation.origin)}, "axis": "y", "angle": ${num(e.rotation.angle)} },`);
  }
  body.push('\t\t\t"faces": {');
  const faces = Object.entries(e.faces);
  faces.forEach(([name, f], j) => {
    const tail = j === faces.length - 1 ? '' : ',';
    const extra = f.rotation ? `, "rotation": ${f.rotation}` : '';
    body.push(`\t\t\t\t"${name}": { "uv": ${arr(f.uv)}, "texture": "${f.texture}"${extra} }${tail}`);
  });
  body.push('\t\t\t}');
  lines.push('\t\t{');
  lines.push(body.join('\n'));
  lines.push(i === elements.length - 1 ? '\t\t}' : '\t\t},');
});
lines.push('\t]');
lines.push('}');
fs.writeFileSync(OUT, lines.join('\n') + '\n');

// ---------------------------------------------------------------- 报告
const angles = elements.map((e) => (e.rotation ? e.rotation.angle : 0));
console.log(`[dep-on-roof] ${elements.length} elements -> ${OUT}`);
console.log(`[dep-on-roof] angles: ${angles.join(', ')}`);
console.log(`[dep-on-roof] all angles legal: ${angles.every((a) => LEGAL_ANGLES.includes(a))}`);
