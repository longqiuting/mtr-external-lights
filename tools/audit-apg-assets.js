// Audit mtrlte blockstates/models/textures: every model ref, parent chain, texture id, existence.
const fs = require('fs');
const path = require('path');

const RES = 'D:/MC-MTR-JS/mtr-headlight/v1.20.1/src/main/resources/assets';
const MTR = 'D:/MC-MTR-JS/temp/mtrjar/assets';

const readJson = p => { try { return JSON.parse(fs.readFileSync(p, 'utf8').replace(/^\uFEFF/, '')); } catch (e) { return null; } };

// resolve model file path for an id
function modelPath(id) {
  const [ns0, p] = id.includes(':') ? id.split(':') : ['minecraft', id];
  const ns = ns0;
  const base = ns === 'mtrlte' ? path.join(RES, 'mtrlte/models', p + '.json')
            : ns === 'mtr' ? path.join(MTR, 'mtr/models', p + '.json')
            : null;
  if (base) return fs.existsSync(base) ? base : null;
  return 'VANILLA';
}

function textureExists(id) {
  const [ns, p] = id.split(':');
  const rel = 'textures/' + p + '.png';
  if (ns === 'mtrlte') {
    const f = path.join(RES, 'mtrlte', rel);
    return fs.existsSync(f) ? 'ok' : 'MISSING(mtrlte)';
  }
  if (ns === 'mtr') {
    const f = path.join(MTR, 'mtr', rel);
    return fs.existsSync(f) ? 'ok(mtr)' : 'MISSING(mtr)';
  }
  if (ns === 'minecraft') {
    const f = path.join(MTR, 'minecraft', rel);
    if (fs.existsSync(f)) return 'ok(mc)';
    return 'mc-builtin?';
  }
  return 'ns:' + ns;
}

// recursively gather textures from model chain
function gather(id, seen = []) {
  const file = modelPath(id);
  if (file === 'VANILLA') return { textures: {} };
  if (!file) return { textures: {}, missingParent: id };
  const json = readJson(file);
  if (!json) return { textures: {}, missingParent: id + ' (bad json)' };
  let tex = {};
  if (json.parent) {
    const up = gather(json.parent, seen);
    if (up.missingParent) return { textures: {}, missingParent: up.missingParent, chain: seen };
    tex = { ...up.textures };
  }
  if (json.textures) tex = { ...tex, ...json.textures };
  return { textures: tex };
}

const bsDir = path.join(RES, 'mtrlte/blockstates');
const rows = [];
const modelRefs = new Set();

for (const f of fs.readdirSync(bsDir)) {
  if (!f.endsWith('.json')) continue;
  const name = f.replace(/\.json$/, '');
  const json = readJson(path.join(bsDir, f));
  if (!json) { rows.push({ block: name, note: 'BAD JSON' }); continue; }
  const refs = [];
  if (json.variants) for (const v of Object.values(json.variants)) { if (v.model) refs.push(v.model); if (v.model === 'minecraft:block/air') refs.push('(air)'); }
  if (json.multipart) for (const part of json.multipart) { if (part.apply && part.apply.model) refs.push(part.apply.model); }
  for (const r of refs) if (r !== '(air)') modelRefs.add(r);
  rows.push({ block: name, models: [...new Set(refs)] });
}

// registered block ids from XiamenObjects
const objSrc = fs.readFileSync('D:/MC-MTR-JS/mtr-headlight/v1.20.1/src/main/java/dev/js/mtrheadlight/xiamen/XiamenObjects.java', 'utf8');
const ids = [...objSrc.matchAll(/new Identifier\("mtrlte",\s*"([a-z0-9_]+)"\)/g)].map(m => m[1]);
const blockIds = [...new Set(ids.filter(i => !i.endsWith('_id') && i !== 'core'))];
const bsNames = fs.readdirSync(bsDir).filter(f => f.endsWith('.json')).map(f => f.replace(/\.json$/, ''));

console.log('=== BLOCK ID -> BLOCKSTATE FILE ===');
for (const b of ['psd_door_xiamen','psd_glass_xiamen','psd_end_xiamen','psd_top_xiamen','apg_door_xiamen','apg_glass_xiamen','apg_end_xiamen','xiamen_logo']) {
  console.log(`${b.padEnd(20)} blockstate=${bsNames.includes(b) ? 'OK' : '*** MISSING assets/mtrlte/blockstates/' + b + '.json ***'}`);
}
const orphans = bsNames.filter(n => !['psd_door_xiamen','psd_glass_xiamen','psd_end_xiamen','psd_top_xiamen','apg_door_xiamen','apg_glass_xiamen','apg_end_xiamen','xiamen_logo'].includes(n));
if (orphans.length) console.log('orphan blockstate files (no such block):', orphans.join(', '));

console.log('\n=== MODEL -> TEXTURE TABLE ===');
for (const r of rows) {
  if (!r.models) { console.log(r.block + ' :: ' + r.note); continue; }
  console.log('\n[' + r.block + ']');
  for (const m of r.models) {
    if (m === 'minecraft:block/air' || m === '(air)') { console.log('  ' + m); continue; }
    const g = gather(m);
    if (g.missingParent) { console.log(`  ${m}  *** PARENT MISSING: ${g.missingParent} ***`); continue; }
    const texs = Object.entries(g.textures).map(([k, v]) => `${k}=${v} [${v.startsWith('#') ? 'var' : textureExists(v)}]`);
    console.log(`  ${m}`);
    for (const t of texs) console.log('      ' + t);
  }
}

console.log('\n=== ALL MODEL FILES present but unused (info) ===');
const allModels = fs.readdirSync(path.join(RES, 'mtrlte/models/block')).map(f => 'mtrlte:block/' + f.replace(/\.json$/, ''));
const unused = allModels.filter(m => !modelRefs.has(m) && !allModels.some(() => false));
console.log(unused.length ? unused.join('\n') : '(none)');
