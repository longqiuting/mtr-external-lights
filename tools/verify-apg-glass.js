// Resolve every blockstate -> model -> parent -> texture reference for one version's jar.
// Usage: node tools/verify-apg-glass.js v1.19.2 [extra jars...]
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const ver = process.argv[2];
const extraJars = process.argv.slice(3);
const res = path.join(ver, 'src/main/resources');
const A = path.join(res, 'assets');

function readJson(p) {
  try { return JSON.parse(fs.readFileSync(p, 'utf8')); } catch (e) { return null; }
}

function existsFile(p) { return fs.existsSync(p) && fs.statSync(p).isFile(); }

// index of another mod's assets, from its jar
const foreign = {};
for (const jar of extraJars) {
  try {
    const out = execFileSync('unzip', ['-Z1', jar], { encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
    for (const line of out.split(/\r?\n/)) if (line) foreign[line] = jar;
  } catch (e) { /* ignore */ }
}

function nsPath(id) {
  const i = id.indexOf(':');
  return i < 0 ? ['minecraft', id] : [id.slice(0, i), id.slice(i + 1)];
}

function resolveModel(id) {
  const [ns, p] = nsPath(id);
  // vanilla has no file for its own models in this repo; treat minecraft: as external
  if (ns === 'minecraft') return foreign[`assets/minecraft/models/${p}.json`] ? 'OK-foreign' : 'ASSUME-VANILLA';
  const f = path.join(A, ns, 'models', p + '.json');
  return existsFile(f) ? f : null;
}

function resolveTexture(id) {
  const [ns, p] = nsPath(id);
  if (ns === 'minecraft') return 'ASSUME-VANILLA';
  const f = path.join(A, ns, 'textures', p + '.png');
  if (existsFile(f)) return f;
  if (foreign[`assets/${ns}/textures/${p}.png`]) return 'OK-foreign';
  return null;
}

const missing = [];
const stats = { blockstates: 0, variants: 0, models: 0, textures: 0 };

const bsDir = path.join(A, 'mtrlte/blockstates');
for (const file of fs.readdirSync(bsDir)) {
  if (!file.endsWith('.json')) continue;
  const j = readJson(path.join(bsDir, file));
  if (!j) { missing.push(`blockstate unparsable: ${file}`); continue; }
  stats.blockstates++;
  const refs = j.variants ? Object.values(j.variants) : Object.values(j.multipart || []).flatMap(m => m.apply ? [].concat(m.apply) : []);
  for (const v of refs) {
    if (!v || !v.model) continue;
    stats.variants++;
    if (resolveModel(v.model) === null) missing.push(`blockstate ${file}: model ${v.model} MISSING`);
  }
}

// walk all mtrlte models
const modelDir = path.join(A, 'mtrlte/models');
function walk(d) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, e.name);
    if (e.isDirectory()) walk(p);
    else if (e.name.endsWith('.json')) checkModel(p);
  }
}
function checkModel(p) {
  const j = readJson(p);
  if (!j) return;
  stats.models++;
  const rel = path.relative(A, p).replace(/\\/g, '/');
  if (j.parent) {
    const pns = resolveModel(j.parent);
    if (pns === null) missing.push(`model ${rel}: parent ${j.parent} MISSING`);
  }
  for (const [k, v] of Object.entries(j.textures || {})) {
    if (typeof v !== 'string' || v.startsWith('#')) continue;
    stats.textures++;
    if (resolveTexture(v) === null) missing.push(`model ${rel}: texture ${k}=${v} MISSING`);
  }
}
if (fs.existsSync(modelDir)) walk(modelDir);

// items
const itemDir = path.join(A, 'mtrlte/models/item');
if (fs.existsSync(itemDir)) {
  for (const f of fs.readdirSync(itemDir)) {
    const rel = 'models/item/' + f;
    const j = readJson(path.join(itemDir, f));
    if (!j) continue;
    stats.models++;
    if (j.parent && resolveModel(j.parent) === null) missing.push(`model ${rel}: parent ${j.parent} MISSING`);
    for (const [k, v] of Object.entries(j.textures || {})) {
      if (typeof v !== 'string' || v.startsWith('#')) continue;
      stats.textures++;
      if (resolveTexture(v) === null) missing.push(`model ${rel}: texture ${k}=${v} MISSING`);
    }
  }
}

// item model presence for every block id (a block without an item model shows the purple/black
// "missing model" in the inventory and in the hand)
const lang = readJson(path.join(A, 'mtrlte/lang/en_us.json')) || {};

console.log(`[${ver}] blockstates=${stats.blockstates} variants=${stats.variants} models=${stats.models} textureRefs=${stats.textures}`);
if (missing.length === 0) {
  console.log(`[${ver}] ALL REFERENCES RESOLVE`);
} else {
  console.log(`[${ver}] ${missing.length} PROBLEM(S):`);
  for (const m of missing.slice(0, 40)) console.log('  - ' + m);
}
