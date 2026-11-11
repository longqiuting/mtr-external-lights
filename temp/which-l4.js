const fs = require('fs'), path = require('path');
const RES = 'D:/MC-MTR-JS/mtr-headlight/v1.20.1/src/main/resources/assets/mtrlte';
const isDoorLight = p => p.endsWith('light_on.png') || p.endsWith('light_off.png');
const insert = (p, s) => p.endsWith('.png') ? p.slice(0, -4) + s + '.png' : p + s + '.png';
const exists = id => fs.existsSync(path.join(RES, id.replace(/^mtrlte:/, '')));
function remap(raw, variant, color) {
  const p = raw.slice(4);
  const hv = !!variant;
  const colored = !!color && color !== 'orange' && isDoorLight(p);
  const t = [];
  if (colored) { if (hv) t.push(insert(p, variant + '_' + color)); t.push(insert(p, '_' + color)); }
  if (hv) t.push(insert(p, variant));
  t.push(p);
  for (const c of t) if (exists('mtrlte:' + c)) return 'mtrlte:' + c;
  return 'PACK (mtr jar)';
}
const ids = ['apg_door_top_middle.png', 'apg_door_bottom_middle.png', 'sign/door_not_in_use.png',
  'psd_glass_top_1.png', 'psd_glass_top_back_1.png', 'apg_door_top_left.png', 'apg_door_bottom_single.png',
  'apg_door_top.png', 'apg_door_bottom.png', 'psd_door_end_top_left_1_1.png'];
for (const i of ids) console.log(i.padEnd(30), '->', remap('mtr:textures/block/' + i, '_l4', 'green'));
