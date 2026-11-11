#!/usr/bin/env node
/*
 * PSD/APG blockstate + model + texture validator for mtr-headlight.
 * - resolves every variant/multipart selector against the block's REAL property set
 *   (extracted from MTR bytecode: see PROPS below)
 * - walks the model parent chain, checks every referenced model/texture file exists
 * - reports states that resolve to no model at all (=> invisible + missing particles)
 *
 * usage: node tools/psd-validate.js [--verbose]
 */
'use strict';
const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const ROOT = 'D:/MC-MTR-JS/mtr-headlight';
const TMP = path.join(process.env.LOCALAPPDATA || '/tmp', 'Temp', 'mtrx');
const VERBOSE = process.argv.includes('--verbose');

const VERSIONS = [
  { id: 'v1.18.2', mc: '1.18.2', mtr: 'MTR-fabric-1.18.2-3.2.2-hotfix-1.jar' },
  { id: 'v1.19.2', mc: '1.19.2', mtr: 'MTR-fabric-1.19.2-3.2.2-hotfix-1.jar' },
  { id: 'v1.19.4', mc: '1.19.4', mtr: 'MTR-fabric-1.19.4-3.2.2-hotfix-1.jar' },
  { id: 'v1.20.1', mc: '1.20.1', mtr: 'MTR-fabric-1.20.1-3.6.4.2.jar' },
];

// ---- authoritative property sets (from MTR bytecode appendProperties) ----
const FACING = ['west', 'north', 'east', 'south'];
const HALF = ['lower', 'upper'];
const SIDE = ['left', 'right', 'middle', 'single'];   // IBlock.EnumSide, name "side"
const TOUCH = ['air', 'door', 'none'];                 // EnumPSDAPGGlassEndSide
const PERSISTENT = ['none', 'arrow', 'route', 'blank'];
const BOOL = ['true', 'false'];
const ARROW = ['0', '1', '2', '3'];

const P = {
  facing: FACING, half: HALF, side: SIDE,
  end: BOOL, temp: BOOL, unlocked: BOOL,
  touching_left: TOUCH, touching_right: TOUCH,
  air_left: BOOL, air_right: BOOL,
  arrow_direction: ARROW, persistent: PERSISTENT,
};
const BLOCK_KIND = [
  { re: /^psd_door_/, name: 'psd_door (BlockPSDAPGDoorBase)', props: ['facing', 'side', 'half', 'end', 'temp', 'unlocked'], sides: ['left', 'right'] },
  { re: /^psd_glass_/, name: 'psd_glass (BlockPSDAPGGlassBase)', props: ['facing', 'side', 'half'] },
  { re: /^psd_end_/, name: 'psd_end (BlockPSDAPGGlassEndBase)', props: ['facing', 'side', 'half', 'touching_left', 'touching_right'] },
  { re: /^psd_top_/, name: 'psd_top (BlockPSDTop)', props: ['facing', 'side', 'air_left', 'air_right', 'arrow_direction', 'persistent'] },
  { re: /^apg_door_/, name: 'apg_door (BlockAPGDoor/BlockPSDAPGDoorBase)', props: ['facing', 'side', 'half', 'end', 'temp', 'unlocked'], sides: ['left', 'right'] },
  { re: /^apg_glass_/, name: 'apg_glass (BlockAPGGlass)', props: ['facing', 'side', 'half', 'arrow_direction'] },
  { re: /^apg_end_/, name: 'apg_end (BlockAPGGlassEnd/BlockPSDAPGGlassEndBase)', props: ['facing', 'side', 'half', 'touching_left', 'touching_right'] },
];

const jarCache = new Map();
function jarEntries(jar) {
  if (jarCache.has(jar)) return jarCache.get(jar);
  const out = execSync(`unzip -Z1 "${jar.split(path.sep).join('/')}"`, { maxBuffer: 64 * 1024 * 1024 }).toString();
  const s = new Set(out.split(/\r?\n/).filter(Boolean));
  jarCache.set(jar, s);
  return s;
}

function walk(dir, base, acc) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, base, acc);
    else acc.add(path.relative(base, p).split(path.sep).join('/'));
  }
  return acc;
}

// per-version asset layer: { mod: Set(relative paths), mtr: Set(jar entries), mc: Set(jar entries) }
function layers(v) {
  const assetsDir = path.join(ROOT, v.id, 'src/main/resources/assets/mtrlte');
  const mod = fs.existsSync(assetsDir) ? walk(assetsDir, assetsDir, new Set()) : new Set();
  const mtrJar = path.join(ROOT, 'libs', v.mtr);
  const mcJar = path.join(process.env.USERPROFILE, '.gradle/caches/fabric-loom', v.mc, 'minecraft-client.jar');
  return {
    mtrlte: { set: mod, prefix: '' },
    mtr: { set: jarEntries(mtrJar), prefix: 'assets/mtr' },
    minecraft: { set: jarEntries(mcJar), prefix: 'assets/minecraft' },
  };
}

function findFile(L, ns, rel) {
  const layer = L[ns];
  if (!layer) return null; // builtin/... => not a file asset
  const full = layer.prefix ? `${layer.prefix}/${rel}` : rel;
  return layer.set.has(full) ? full : null;
}

function readJson(L, ns, rel) {
  const full = findFile(L, ns, rel);
  if (!full) return null;
  let p;
  if (ns === 'mtrlte') p = path.join(ROOT, currentVersion.id, 'src/main/resources', 'assets', 'mtrlte', rel);
  else if (ns === 'mtr') p = path.join(TMP, 'mtr-' + currentVersion.id, full);
  else p = path.join(TMP, 'mc-' + currentVersion.mc, full);
  if (!fs.existsSync(p)) return null;
  try { return JSON.parse(fs.readFileSync(p, 'utf8')); } catch (e) { return { __parseError: e.message }; }
}

let currentVersion = null;

function splitId(id) {
  const i = id.indexOf(':');
  return i < 0 ? ['minecraft', id] : [id.slice(0, i), id.slice(i + 1)];
}

// resolve model chain: returns {ok, elements, textures, missingParent, noGeometry}
function resolveModel(L, ns, p, seen = new Set()) {
  const key = ns + ':' + p;
  const res = { id: key, elements: null, textures: {}, missing: null, noGeometry: false };
  if (seen.has(key)) return res;
  seen.add(key);
  const json = readJson(L, ns, 'models/' + p + '.json');
  if (!json) { res.missing = key; return res; }
  if (json.__parseError) { res.missing = key + ' (parse error: ' + json.__parseError + ')'; return res; }

  if (typeof json.parent === 'string') {
    const [pns, pp] = splitId(json.parent);
    if (pns === 'builtin') { /* builtin/generated etc. */ }
    else {
      const par = resolveModel(L, pns, pp, seen);
      if (par.missing) res.missing = par.missing;
      if (par.elements) res.elements = par.elements;
      Object.assign(res.textures, par.textures);
    }
  }
  if (json.textures) Object.assign(res.textures, json.textures);
  if (json.elements) res.elements = json.elements;
  if (json.elements && json.elements.length === 0) res.elements = [];
  res.noGeometry = !res.elements || res.elements.length === 0;
  return res;
}

// check a resolved model's textures + per-face texture keys
function modelProblems(L, m) {
  const probs = [];
  const info = [];
  if (m.missing) probs.push('missing model file: ' + m.missing);
  const used = new Set();
  for (const el of (m.elements || [])) {
    for (const [dir, face] of Object.entries(el.faces || {})) {
      let tex = face && face.texture;
      if (typeof tex === 'string' && tex.startsWith('#')) {
        used.add(tex.slice(1));
        tex = m.textures[tex.slice(1)];
        if (!tex) { probs.push(`element face ${dir} -> unresolved texture key ${JSON.stringify(face.texture)}`); continue; }
      }
      if (typeof tex === 'string' && !tex.startsWith('#')) {
        const [tns, tp] = splitId(tex);
        if (tns === 'builtin') continue;
        if (!findFile(L, tns, 'textures/' + tp + '.png')) probs.push(`missing texture ${tex}`);
      }
    }
    if (el.particleTexture) { /* noop */ }
  }
  // particle texture: explicit "particle" key or the first face's texture
  let particle = m.textures.particle;
  if (!particle) {
    const first = (m.elements || []).flatMap(e => Object.values(e.faces || {}))[0];
    let t = first && first.texture;
    if (typeof t === 'string' && t.startsWith('#')) t = m.textures[t.slice(1)];
    particle = t;
  }
  let particleOk = false;
  if (typeof particle === 'string') {
    const [pns, pp] = splitId(particle);
    particleOk = pns === 'builtin' || !!findFile(L, pns, 'textures/' + pp + '.png');
  }
  if (!particleOk) probs.push('NO particle texture -> breaking this block drops black/purple particles');
  if (m.noGeometry && particleOk) info.push('no geometry but particle ok (invisible on purpose, BER-drawn)');
  if (m.noGeometry && !particleOk) probs.push('model has no elements (invisible)');
  return { probs, info };
}

// ---- selector matching ----
function parseWhen(when) {
  // returns list of alternative predicate maps (OR), each predicate: prop -> Set(values)
  if (!when) return [{}]; // no "when" => always matches
  if (Array.isArray(when.OR)) return when.OR.flatMap(parseWhen);
  const pred = {};
  for (const [k, v] of Object.entries(when)) {
    if (k === 'OR') continue;
    pred[k] = String(v).split('|');
  }
  return [pred];
}
function predMatches(pred, combo) {
  for (const [k, vals] of Object.entries(pred)) {
    if (!(k in combo)) return false;
    if (!vals.includes(combo[k])) return false;
  }
  return true;
}

function parseBlockStateJson(json) {
  const selectors = [];
  let type = null;
  if (json.variants) {
    type = 'variants';
    for (const [key, val] of Object.entries(json.variants)) {
      const alts = key.trim() === '' ? [{}] : [Object.fromEntries(key.split(',').map(s => {
        const [k, v] = s.split('=');
        return [k.trim(), v.trim().split('|')];
      }))];
      const applies = Array.isArray(val) ? val : [val];
      selectors.push({ raw: key || '<any>', alts, applies });
    }
  } else if (json.multipart) {
    type = 'multipart';
    for (const part of json.multipart) {
      selectors.push({ raw: JSON.stringify(part.when || '<any>'), alts: parseWhen(part.when), applies: Array.isArray(part.apply) ? part.apply : [part.apply] });
    }
  }
  return { type, selectors };
}

function cartesian(props, overrides) {
  let out = [{}];
  for (const p of props) {
    const vals = (overrides && overrides[p]) || P[p];
    const next = [];
    for (const c of out) for (const v of vals) next.push({ ...c, [p]: v });
    out = next;
  }
  return out;
}

function validate(v) {
  currentVersion = v;
  const L = layers(v);
  const bsDir = path.join(ROOT, v.id, 'src/main/resources/assets/mtrlte/blockstates');
  const report = { version: v.id, blocks: [], missingModels: new Set(), missingTextures: new Set(), problems: [] };

  const files = fs.readdirSync(bsDir).filter(f => /^(psd|apg)_.*\.json$/.test(f)).sort();
  for (const f of files) {
    const blockId = f.replace(/\.json$/, '');
    const kind = BLOCK_KIND.find(k => k.re.test(blockId));
    if (!kind) continue;
    const json = JSON.parse(fs.readFileSync(path.join(bsDir, f), 'utf8'));
    const { type, selectors } = parseBlockStateJson(json);

    // 1. unknown property names
    const allowed = new Set([...kind.props, 'OR']);
    const unknown = new Set();
    for (const s of selectors) for (const alt of s.alts) for (const k of Object.keys(alt)) if (!allowed.has(k)) unknown.add(k);

    // 2. cartesian product vs selectors
    const combos = cartesian(kind.props, kind.sides ? { side: kind.sides } : null);
    let uncovered = [];
    const usedModels = new Set();
    for (const combo of combos) {
      if (type === 'variants') {
        const hit = selectors.filter(s => s.alts.some(a => predMatches(a, combo)));
        if (hit.length !== 1) { uncovered.push({ combo, why: hit.length === 0 ? 'no variant' : 'ambiguous' }); }
        else for (const a of hit[0].applies) usedModels.add(a.model);
      } else {
        const hits = selectors.filter(s => s.alts.some(a => predMatches(a, combo)));
        if (hits.length === 0) uncovered.push({ combo, why: 'no multipart selector' });
        else for (const h of hits) for (const a of h.applies) usedModels.add(a.model);
      }
    }
    // models actually referenced by every selector (even for states not covered)
    for (const s of selectors) for (const a of s.applies) usedModels.add(a.model);

    // 3. models + textures
    const modelInfo = [];
    for (const mid of usedModels) {
      const [ns, p] = splitId(mid);
      const m = resolveModel(L, ns, p);
      const { probs, info } = modelProblems(L, m);
      if (m.missing) report.missingModels.add(m.missing);
      for (const pr of probs) if (pr.startsWith('missing texture')) report.missingTextures.add(pr.slice(16));
      modelInfo.push({ id: mid, probs, info, noGeometry: m.noGeometry });
    }

    report.blocks.push({
      block: blockId, kind: kind.name, type,
      states: combos.length, selectors: selectors.length,
      uncovered, unknownProps: [...unknown], models: modelInfo,
    });
  }
  return report;
}

function describeCombo(c, props) { return props.map(p => `${p}=${c[p]}`).join(','); }

const reports = VERSIONS.map(validate);

for (let i = 0; i < reports.length; i++) {
  const r = reports[i];
  const v = VERSIONS[i];
  console.log('\n================ ' + r.version + '  (MC ' + v.mc + ', ' + v.mtr + ') ================');
  let bad = 0;
  for (const b of r.blocks) {
    const kind = BLOCK_KIND.find(k => k.re.test(b.block));
    const problems = [];
    if (b.unknownProps.length) problems.push('UNKNOWN PROPERTIES: ' + b.unknownProps.join(', '));
    if (b.uncovered.length) {
      problems.push(`UNCOVERED STATES: ${b.uncovered.length}/${b.states} have no model`);
    }
    const badModels = b.models.filter(m => m.probs.length);
    if (badModels.length) problems.push(`BROKEN MODELS: ${badModels.length}/${b.models.length}`);
    const tag = problems.length ? 'FAIL' : 'ok  ';
    if (problems.length) bad++;
    console.log(`${tag} ${b.block.padEnd(24)} ${String(b.states).padStart(5)} states  ${String(b.selectors).padStart(3)} selectors  ${String(b.models.length).padStart(4)} models`);
    for (const p of problems) console.log('       - ' + p);
    if (b.uncovered.length) {
      const uniq = new Map();
      for (const u of b.uncovered) {
        const key = Object.entries(u.combo).filter(([k, val]) => {
          // which property makes it fail? just show full combo, grouped by first prop
          return true;
        }).map(([k, val]) => `${k}=${val}`).join(',');
        uniq.set(key, (uniq.get(key) || 0) + 1);
      }
      const sample = [...uniq.keys()].slice(0, VERBOSE ? 40 : 6);
      console.log('       uncovered sample: ' + sample.join(' | '));
      const props = new Set(b.uncovered.flatMap(u => Object.keys(u.combo)));
      const valuesSeen = {};
      for (const p of props) valuesSeen[p] = [...new Set(b.uncovered.map(u => u.combo[p]))];
      console.log('       uncovered property values: ' + JSON.stringify(valuesSeen));
    }
    if (VERBOSE) for (const m of badModels) console.log(`       model ${m.id}: ${m.probs.join('; ')}`);
  }
  console.log(`---- ${r.version}: ${bad}/${r.blocks.length} blocks with problems; missing model files: ${r.missingModels.size}; missing textures: ${r.missingTextures.size}`);
  if (r.missingModels.size) console.log('     MISSING MODELS: ' + [...r.missingModels].slice(0, 20).join(', '));
  if (r.missingTextures.size) console.log('     MISSING TEXTURES: ' + [...r.missingTextures].slice(0, 20).join(', '));
}
