// Validate the generated Line 4 / Line 6 resource sets for every version:
//  - every JSON parses
//  - every `mtrlte:` reference resolves (model -> models/<path>.json, texture -> textures/<path>.png)
//  - each new block id has a blockstate; count textures / icons / loot tables
const fs = require('fs');
const path = require('path');

const ROOT = 'D:/MC-MTR-JS/mtr-headlight';
const VERSIONS = ['v1.18.2', 'v1.19.2', 'v1.19.4', 'v1.20.1'];
const LINES = [['l4', 4], ['l6', 6]];
const BLOCKS = ['psd_door_xiamen', 'psd_glass_xiamen', 'psd_end_xiamen', 'psd_top_xiamen',
  'apg_door_xiamen', 'apg_glass_xiamen', 'apg_end_xiamen'];

let problems = 0;
const seenBad = new Set();
const bad = (msg) => {
  if (seenBad.has(msg)) return;
  seenBad.add(msg);
  problems++;
  console.log('  !! ' + msg);
};

for (const v of VERSIONS) {
  const assets = path.join(ROOT, v, 'src/main/resources/assets/mtrlte');
  const data = path.join(ROOT, v, 'src/main/resources/data/mtrlte');
  console.log(`== ${v} ==`);

  let parsed = 0;
  const walk = (dir) => {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
      const p = path.join(dir, e.name);
      if (e.isDirectory()) walk(p);
      else if (e.name.endsWith('.json')) {
        try { JSON.parse(fs.readFileSync(p, 'utf8')); parsed++; }
        catch (err) { bad(`bad json ${path.relative(ROOT, p)}: ${err.message}`); }
      }
    }
  };
  walk(assets);

  const strip = (ref) => (typeof ref === 'string' && ref.startsWith('mtrlte:') ? ref.slice(7) : null);
  const checkModel = (ref, where) => {
    const p = strip(ref);
    if (p == null) return;
    if (!fs.existsSync(path.join(assets, 'models', p + '.json'))) bad(`${where}: missing model ${ref}`);
  };
  const checkTex = (ref, where) => {
    const p = strip(ref);
    if (p == null) return;
    if (!fs.existsSync(path.join(assets, 'textures', p + '.png'))) bad(`${where}: missing texture ${ref}`);
  };

  for (const [suf, num] of LINES) {
    let modelRefs = 0;
    for (const f of fs.readdirSync(path.join(assets, 'blockstates'))) {
      if (!f.endsWith(`_${suf}.json`)) continue;
      const st = JSON.parse(fs.readFileSync(path.join(assets, 'blockstates', f), 'utf8'));
      const applyModel = (a) => {
        if (!a) return;
        for (const one of (Array.isArray(a) ? a : [a])) { checkModel(one.model, `blockstates/${f}`); modelRefs++; }
      };
      if (st.variants) for (const k of Object.keys(st.variants)) applyModel(st.variants[k]);
      if (st.multipart) for (const part of st.multipart) applyModel(part.apply);
    }
    for (const sub of ['block', 'item']) {
      for (const f of fs.readdirSync(path.join(assets, 'models', sub))) {
        if (!f.endsWith(`_${suf}.json`)) continue;
        const m = JSON.parse(fs.readFileSync(path.join(assets, 'models', sub, f), 'utf8'));
        if (m.parent) checkModel(m.parent, `models/${sub}/${f}`);
        for (const k of Object.keys(m.textures || {})) checkTex(m.textures[k], `models/${sub}/${f}#${k}`);
      }
    }
    let missingState = 0;
    for (const b of BLOCKS) {
      if (!fs.existsSync(path.join(assets, 'blockstates', `${b}_${suf}.json`))) { bad(`missing blockstate ${b}_${suf}.json`); missingState++; }
    }
    const nTex = fs.readdirSync(path.join(assets, 'textures/block')).filter((f) => f.endsWith(`_${suf}.png`)).length;
    const nItem = fs.readdirSync(path.join(assets, 'textures/item')).filter((f) => f.startsWith(`amtr_${num}_`)).length;
    const lootDir = path.join(data, 'loot_tables/blocks');
    const nLoot = fs.existsSync(lootDir) ? fs.readdirSync(lootDir).filter((f) => f.endsWith(`_${suf}.json`)).length : 0;
    console.log(`  _${suf}: states=7 missing=${missingState} modelRefs=${modelRefs} blockTex=${nTex} itemIcons=${nItem} loot=${nLoot}`);
  }
  console.log(`  json files parsed: ${parsed}`);
}
console.log(problems === 0 ? 'ALL CHECKS PASSED' : `PROBLEMS: ${problems}`);
