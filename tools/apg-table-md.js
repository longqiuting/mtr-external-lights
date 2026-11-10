// Emit the APG part of the audit as a markdown table.
const fs = require('fs');
const path = require('path');
const RES = 'D:/MC-MTR-JS/mtr-headlight/v1.20.1/src/main/resources/assets';
const MTR = 'D:/MC-MTR-JS/temp/mtrjar/assets';
const readJson = p => JSON.parse(fs.readFileSync(p, 'utf8').replace(/^\uFEFF/, ''));

function modelPath(id) {
  const [ns, p] = id.includes(':') ? id.split(':') : ['minecraft', id];
  if (ns === 'mtrlte') { const f = path.join(RES, 'mtrlte/models', p + '.json'); return fs.existsSync(f) ? f : null; }
  if (ns === 'mtr') { const f = path.join(MTR, 'mtr/models', p + '.json'); return fs.existsSync(f) ? f : null; }
  return 'VANILLA';
}
function tex(id) {
  const [ns, p] = id.split(':');
  if (ns === 'mtrlte') return fs.existsSync(path.join(RES, 'mtrlte/textures', p + '.png')) ? '✔' : '**缺失**';
  if (ns === 'mtr') return fs.existsSync(path.join(MTR, 'mtr/textures', p + '.png')) ? '✔ (mtr)' : '**缺失 (mtr)**';
  return '原版';
}
function gather(id, acc = {}) {
  const f = modelPath(id);
  if (f === 'VANILLA') return acc;
  if (!f) return acc;
  const j = readJson(f);
  if (j.parent) gather(j.parent, acc);
  if (j.textures) Object.assign(acc, j.textures);
  return acc;
}

const out = [];
for (const bs of ['xiamen_apg_glass.json', 'xiamen_apg_end.json']) {
  const j = readJson(path.join(RES, 'mtrlte/blockstates', bs));
  const refs = new Set();
  if (j.variants) for (const v of Object.values(j.variants)) if (v.model) refs.add(v.model);
  if (j.multipart) for (const p of j.multipart) if (p.apply && p.apply.model) refs.add(p.apply.model);
  out.push(`\n### \`${bs.replace('.json', '')}\`（${refs.size} 个模型）\n`);
  out.push('| 模型 | 贴图键 | 贴图 id | 存在 |');
  out.push('| --- | --- | --- | --- |');
  for (const m of [...refs].sort()) {
    const t = gather(m);
    for (const [k, v] of Object.entries(t)) {
      out.push(`| \`${m.replace('mtrlte:block/', '')}\` | ${k} | \`${v}\` | ${v.startsWith('#') ? '变量' : tex(v)} |`);
    }
  }
}
fs.writeFileSync('D:/MC-MTR-JS/temp/apg-table.md', out.join('\n') + '\n');
console.log(out.join('\n'));
