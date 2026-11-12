// 液体安检机三盏灯的朝向修正（一次性脚本，改完即可删）：
//   1) 待机模型 liquid_scanner.json 里 working 灯（元素 7）原来指着贴图上的纯白 #ffffff → 改成「灭」的暗色块
//      （与 green / red 两盏灯熄灭时用的是同一块 #353637，UV 8.3..8.7 / 0.55..0.95）——
//      于是 working 灯只在检测那 3 秒（result=green/red 的两个模型仍然指着白色）亮，不再常亮微光。
//   2) 三盏灯（元素 5/6/7）加 "shade": false：灯是自发光指示器，不该再乘一次方向光，
//      否则「亮」的时候也只是比周围的灰白一点点（实机上看着就是微光）。
const fs = require('fs');
const path = require('path');

const DIR = 'src/main/resources/assets/mtrlte/models/block';
const OFF_UV = [8.3, 0.55, 8.7, 0.95];
const LAMPS = [5, 6, 7]; // green / red / working

function load(f) {
  return JSON.parse(fs.readFileSync(path.join(DIR, f), 'utf8'));
}
function save(f, obj) {
  fs.writeFileSync(path.join(DIR, f), JSON.stringify(obj, null, 2) + '\n');
}

for (const f of ['liquid_scanner.json', 'liquid_scanner_green.json', 'liquid_scanner_red.json']) {
  const m = load(f);
  for (const i of LAMPS) {
    const el = m.elements[i];
    if (!el) throw new Error(`${f}: 没有元素 ${i}`);
    el.shade = false;
  }
  if (f === 'liquid_scanner.json') {
    const working = m.elements[7];
    let n = 0;
    for (const face of Object.values(working.faces)) {
      if (!face.uv) continue;
      face.uv = OFF_UV.slice();
      n++;
    }
    console.log(`${f}: working 灯 ${n} 个面 → 灭灯 UV`);
  }
  save(f, m);
  console.log(`${f}: 元素 ${LAMPS.join('/')} 已加 shade=false`);
}
