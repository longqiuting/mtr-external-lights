// Static validator: every blockstate model ref and every model texture ref must resolve inside the jar.
const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const jar = process.argv[2];
const out = process.argv[3];
if (!fs.existsSync(path.join(out, 'assets'))) {
  fs.rmSync(out, { recursive: true, force: true });
  execSync(`unzip -qo "${jar}" "assets/*" -d "${out}"`, { shell: 'bash' });
}

const assets = path.join(out, 'assets');
const namespaces = fs.readdirSync(assets);

// Minecraft models we know exist in the vanilla jar (only the ones referenced by our jsons).
const vanillaOk = new Set(['block/block', 'item/generated', 'item/handheld', 'block/cross']);

const problems = [];
const seen = new Set();

function modelPath(ns, p) {
  return path.join(assets, ns, 'models', p + '.json');
}
function texturePath(ns, p) {
  return path.join(assets, ns, 'textures', p + '.png');
}

function splitId(id, defaultNs) {
  const i = id.indexOf(':');
  return i < 0 ? [defaultNs, id] : [id.slice(0, i), id.slice(i + 1)];
}

function checkModelFile(ns, p, from) {
  const key = ns + ':' + p;
  if (seen.has(key)) return;
  seen.add(key);
  const file = modelPath(ns, p);
  if (!fs.existsSync(file)) {
    if (ns === 'minecraft' && vanillaOk.has(p)) return;
    if (ns === 'minecraft') return; // vanilla: trust the jar
    problems.push(`missing MODEL ${key} (referenced from ${from})`);
    return;
  }
  const json = JSON.parse(fs.readFileSync(file, 'utf8'));
  if (json.parent) {
    const [pns, pp] = splitId(json.parent, ns);
    checkModelFile(pns, pp, key);
  }
  for (const [k, v] of Object.entries(json.textures || {})) {
    if (typeof v !== 'string' || v.startsWith('#')) continue;
    const [tns, tp] = splitId(v, ns);
    if (!fs.existsSync(texturePath(tns, tp))) {
      problems.push(`missing TEXTURE ${tns}:${tp} (in model ${key}, key #${k})`);
    }
  }
}

for (const ns of namespaces) {
  const bsDir = path.join(assets, ns, 'blockstates');
  if (!fs.existsSync(bsDir)) continue;
  for (const f of fs.readdirSync(bsDir)) {
    const json = JSON.parse(fs.readFileSync(path.join(bsDir, f), 'utf8'));
    const from = ns + ':' + f;
    if (json.variants) {
      for (const variant of Object.values(json.variants)) {
        for (const v of (Array.isArray(variant) ? variant : [variant])) {
          const [mns, mp] = splitId(v.model, ns);
          checkModelFile(mns, mp, from);
        }
      }
    }
    for (const part of json.multipart || []) {
      for (const apply of (Array.isArray(part.apply) ? part.apply : [part.apply])) {
        const [mns, mp] = splitId(apply.model, ns);
        checkModelFile(mns, mp, from);
      }
    }
  }
  // item models too (they are what the inventory draws)
  const itemDir = path.join(assets, ns, 'models', 'item');
  if (fs.existsSync(itemDir)) {
    for (const f of fs.readdirSync(itemDir)) {
      if (f.endsWith('.json')) checkModelFile(ns, 'item/' + f.replace(/\.json$/, ''), 'scan');
    }
  }
}

console.log(`checked mtrlte models: ${seen.size} file refs`);
if (problems.length === 0) {
  console.log('OK: no unresolved model/texture references');
} else {
  console.log('PROBLEMS:');
  for (const p of problems) console.log('  ' + p);
  process.exitCode = 1;
}
