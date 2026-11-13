#!/usr/bin/env node
/**
 * 素材（Blockbench 导出的 Minecraft JSON）→ {@code assets/mtrlte/rawmodels/dep_*.json}。
 *
 * <h2>它只做一件事：改贴图命名空间</h2>
 * <p>{@code from / to / rotation / faces.uv} 一个数都不动 —— 这正是要的结果：
 * 几何就是 Blockbench 里那块牌子，跑 {@code RawModelJson} 之后逐顶点一致。
 *
 * <h2>为什么不再走「转成原版方块模型」那条路（旧脚本 gen-dep-on-roof-model.js）</h2>
 * <p>原版 1.20.1 的 {@code ModelElement.Deserializer} 只接受
 * {@code -45 / -22.5 / 0 / 22.5 / 45} 这几种元素旋转，别的直接抛
 * {@code JsonParseException}。而 {@code dep_on_roof.json} 里是
 * {@code 135 / 154 / -87.5 / -73.5 / -59 / -31 …}，还有 {@code {"x":180,"y":-11.75,"z":180}}
 * 这种三轴同时转 —— 于是旧脚本只能「把整 90° 折进盒子坐标 + 把零头吸附到最近的合法角」。
 * 牌框那六件是 135°（折完正好 -45，精确），但<b>吊杆那十节</b>是用来拧出绳感的
 * {@code -26 / -11.75 / 7.25 …}，一吸附就变成了另一根杆子（部分段落还错位、看着像缺了）。
 * 现在这两份模型都改由 {@code xiamen/dep} 的 BER 用 {@link dev.js.mtrheadlight.rawmodel.RawModelJson}
 * 在运行时解析，任意角度、任意多轴都能原样画出来，这条转换链也就不需要了。
 *
 * <h2>用法</h2>
 * <pre>
 *   node tools/gen-dep-rawmodels.js [素材目录]
 * </pre>
 * <p>默认读 {@code ~/Desktop/素材/挂墙发车倒数器/}，写
 * {@code v1.20.1/src/main/resources/assets/mtrlte/rawmodels/}。
 */
'use strict';

const fs = require('fs');
const os = require('os');
const path = require('path');

const SRC_DIR = process.argv[2] || path.join(os.homedir(), 'Desktop', '素材', '挂墙发车倒数器');
const OUT_DIR = path.join(__dirname, '..', 'v1.20.1', 'src', 'main', 'resources',
  'assets', 'mtrlte', 'rawmodels');

/**
 * 贴图名：只写文件名，不带 {@code mtrlte:} / {@code block/}。
 * <p>Jar 里它就是 {@code textures/block/dep_on_wall.png}（两份模型共用同一张图）。
 */
const TEXTURE_NAME = 'dep_on_wall';

const MODELS = ['dep_on_wall', 'dep_on_roof'];

fs.mkdirSync(OUT_DIR, { recursive: true });

for (const name of MODELS) {
  const srcFile = path.join(SRC_DIR, name + '.json');
  const src = JSON.parse(fs.readFileSync(srcFile, 'utf8'));

  // textures：只留一个键指向 jar 里的 mtrlte:block/dep_on_wall.png。
  // 名字只能写 "dep_on_wall"（不带命名空间、不带 block/）—— RawModelJson.resolveTexture
  // 在名字没有 "textures/" 前缀时会自己补 "textures/block/"，写成 "mtrlte:block/dep_on_wall"
  // 会拼出 textures/block/block/dep_on_wall.png，图就丢了。
  const out = {
    credit: 'Made with Blockbench (素材/挂墙发车倒数器/' + name + '.json，几何未改动)',
    textures: { 0: TEXTURE_NAME, particle: TEXTURE_NAME },
    elements: src.elements,
  };

  // 自检：元素/面/角度必须原样带过来（这条脚本永远不该改几何）。
  const before = JSON.stringify(src.elements);
  const after = JSON.stringify(out.elements);
  if (before !== after) {
    throw new Error(name + ': elements 被改动了，拒绝写出');
  }

  const file = path.join(OUT_DIR, name + '.json');
  fs.writeFileSync(file, JSON.stringify(out, null, '\t') + '\n');

  const rotated = src.elements.filter((e) => e.rotation
    && [e.rotation.x, e.rotation.y, e.rotation.z, e.rotation.angle].some((a) => a)).length;
  console.log(`[dep] ${name}: ${src.elements.length} elements (${rotated} rotated) -> ${path.relative(process.cwd(), file)}`);
}
