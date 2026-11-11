// Replay XiamenTextures#remap offline and print, for every texture MTR's RenderPSDAPGDoor may ask
// for, which file actually wins for Line 4 / Line 6 (green door light) and Line 3 (orange control).
// Mirrors the Java candidate order exactly: line+colour -> colour -> line -> base -> resource pack.
const fs = require('fs');
const path = require('path');

const RES = 'D:/MC-MTR-JS/mtr-headlight/v1.20.1/src/main/resources/assets/mtrlte';
const PKG = 'mtr';

const isDoorLight = (p) => p.endsWith('light_on.png') || p.endsWith('light_off.png');
const insert = (p, suf) => (p.endsWith('.png') ? p.slice(0, -4) + suf + '.png' : p + suf + '.png');
const exists = (id) => fs.existsSync(path.join(RES, id.replace(/^mtrlte:/, '')));

function remap(raw, variant, lightColor) {
  const p = raw.slice(PKG.length + 1);
  const hasVariant = !!variant;
  const colored = !!lightColor && lightColor !== 'orange' && isDoorLight(p);
  const tries = [];
  if (colored) {
    if (hasVariant) tries.push(insert(p, variant + '_' + lightColor));
    tries.push(insert(p, '_' + lightColor));
  }
  if (hasVariant) tries.push(insert(p, variant));
  tries.push(p);
  for (const t of tries) if (exists('mtrlte:' + t)) return 'mtrlte:' + t;
  return raw + '  (falls back to the resource pack)';
}

const TYPE_TAG = '1';
const ids = [];
for (const half of ['top', 'bottom'])
  for (const side of ['left', 'right']) {
    ids.push(`mtr:textures/block/psd_door_${half}_${side}_${TYPE_TAG}.png`);
    ids.push(`mtr:textures/block/psd_door_end_${half}_${side}_1_${TYPE_TAG}.png`);
    ids.push(`mtr:textures/block/psd_door_end_${half}_${side}_2_${TYPE_TAG}.png`);
  }
ids.push('mtr:textures/block/light_on.png', 'mtr:textures/block/light_off.png',
  'mtr:textures/block/apg_door_light_on.png', 'mtr:textures/block/apg_door_light_off.png',
  'mtr:textures/block/sign/door_not_in_use.png');
for (const half of ['top', 'bottom'])
  for (const side of ['left', 'right', 'middle', 'single'])
    ids.push(`mtr:textures/block/apg_door_${half}_${side}.png`);
ids.push('mtr:textures/block/psd_glass_top_1.png', 'mtr:textures/block/psd_glass_top_back_1.png');

for (const [label, variant, color] of [['L3 (control)', '_l3', 'orange'], ['L4', '_l4', 'green'], ['L6', '_l6', 'green']]) {
  let win = { line: 0, colour: 0, base: 0, pack: 0 };
  const rows = [];
  for (const raw of ids) {
    const out = remap(raw, variant, color);
    let kind;
    if (out.includes('(falls back')) kind = 'pack';
    else if (out.includes(variant + '_' + color)) kind = 'line+colour';
    else if (out.includes('_' + color + '.png') && color !== 'orange') kind = 'colour';
    else if (out.includes(variant)) kind = 'line';
    else kind = 'base';
    win[kind === 'line+colour' ? 'colour' : kind]++;
    rows.push(`    ${path.basename(raw).padEnd(34)} -> ${path.basename(out.replace(/  \(.*$/, ''))}`);
  }
  console.log(`== ${label}  variant=${variant} colour=${color} ==`);
  console.log(`   hits: line=${win.line} coloured(base colour png)=${win.colour} base=${win.base} resourcepack=${win.pack}`);
  if (label !== 'L3 (control)') console.log(rows.filter((r) => /light/.test(r)).join('\n'));
}
