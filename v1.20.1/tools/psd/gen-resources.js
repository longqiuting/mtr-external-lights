// Generate the block/item/loot/lang resources for the 14 fangsu PSD door blocks.
// Ids and names must stay in sync with FangsuPsdDef.java (same derivation rules).
const fs = require('fs');
const path = require('path');

const RES = process.argv[2]; // .../src/main/resources
const ASSETS = path.join(RES, 'assets/mtrlte');
const PSD = path.join(ASSETS, 'fangsu_psd');
const DATA = path.join(RES, 'data/mtrlte');

// ---------------------------------------------------------------- obj/mtl helpers
function objGroups(text) {
  const verts = [];
  const uvs = [];
  let group = '';
  let mat = '';
  const groups = new Map();
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const sp = line.indexOf(' ');
    const kw = sp < 0 ? line : line.slice(0, sp);
    const rest = sp < 0 ? '' : line.slice(sp + 1).trim();
    if (kw === 'v') verts.push(rest.split(/\s+/).map(Number));
    else if (kw === 'vt') uvs.push(rest.split(/\s+/).map(Number));
    else if (kw === 'usemtl') mat = rest;
    else if (kw === 'o' || kw === 'g') group = rest;
    else if (kw === 'f') {
      let g = groups.get(group);
      if (!g) { g = { min: [1e9, 1e9, 1e9], max: [-1e9, -1e9, -1e9], uvMin: [1e9, 1e9], uvMax: [-1e9, -1e9], perMat: new Map() }; groups.set(group, g); }
      const faces = rest.split(/\s+/).length;
      g.perMat.set(mat, (g.perMat.get(mat) || 0) + faces);
      for (const corner of rest.split(/\s+/)) {
        const [vi, ti] = corner.split('/').map((s) => (s === '' || s === undefined ? NaN : parseInt(s, 10)));
        const v = verts[vi - 1];
        if (v) for (let a = 0; a < 3; a++) { g.min[a] = Math.min(g.min[a], v[a]); g.max[a] = Math.max(g.max[a], v[a]); }
        const uv = uvs[ti - 1];
        if (uv) { g.uvMin[0] = Math.min(g.uvMin[0], uv[0]); g.uvMin[1] = Math.min(g.uvMin[1], uv[1]); g.uvMax[0] = Math.max(g.uvMax[0], uv[0]); g.uvMax[1] = Math.max(g.uvMax[1], uv[1]); }
      }
    }
  }
  return groups;
}

function mtlMaps(mtlPath) {
  const out = new Map();
  let mat = null;
  for (const raw of fs.readFileSync(mtlPath, 'utf8').split(/\r?\n/)) {
    const line = raw.trim();
    if (line.startsWith('newmtl ')) mat = line.slice(7).trim();
    else if (line.startsWith('map_Kd ') && mat) out.set(mat, line.slice(7).trim());
  }
  return out;
}

// obj units (1 unit = 1 block, xz centred) -> model units 0..16
const mx = (v) => v * 16 + 8;
const mz = (v) => v * 16 + 8;
const my = (v) => v * 16;

// ---------------------------------------------------------------- definitions
const DEFS = [];

function readJson(p) { return JSON.parse(fs.readFileSync(p, 'utf8')); }

function duanmenNames(file, right) {
  const m = {
    brtduanmen: ['方速端门 BRT', 'Fangsu end door BRT'],
    mtrduanmen: ['方速端门 MTR', 'Fangsu end door MTR'],
    drlduanmen: ['方速端门 DRL', 'Fangsu end door DRL'],
    shlowduanmen: ['方速端门 SH 低', 'Fangsu end door SH low'],
    shhighduanmen: ['方速端门 SH 高', 'Fangsu end door SH high'],
  }[file];
  return [m[0] + (right ? '（右）' : '（左）'), m[1] + (right ? ' (right)' : ' (left)')];
}

for (const file of ['brtduanmen', 'mtrduanmen', 'drlduanmen', 'shlowduanmen', 'shhighduanmen']) {
  const json = readJson(path.join(PSD, 'duanmen', file + '.json'));
  for (const entry of json.content) {
    const right = entry.id.endsWith('_right');
    const [zh, en] = duanmenNames(file, right);
    DEFS.push({
      id: 'fangsu_psd_' + entry.id,
      zh, en,
      obj: entry.model.replace('mtrlte:', ''),
      groups: [entry.subModels.main, entry.subModels.door],
    });
  }
}

for (const file of ['low', 'high']) {
  const json = readJson(path.join(PSD, 'screendoor/xiamen', file + '.json'));
  const label = file === 'low' ? '低' : '高';
  const labelEn = file === 'low' ? 'low' : 'high';
  for (const side of ['left', 'right']) {
    const groups = [];
    for (const entry of json.door[side]) {
      for (const door of entry.doors || []) groups.push(door.subModel);
    }
    if (!groups.length) continue;
    DEFS.push({
      id: 'fangsu_psd_screendoor_' + file + '_' + side,
      zh: '方速屏蔽门 厦门' + label + '（' + (side === 'left' ? '左' : '右') + '）',
      en: 'Fangsu platform screen door Xiamen ' + labelEn + ' (' + side + ')',
      obj: json.model.replace('mtrlte:', ''),
      groups,
    });
  }
}

// ---------------------------------------------------------------- write blockstates
const BS_DIR = path.join(ASSETS, 'blockstates');
const MODEL_DIR = path.join(ASSETS, 'models/block');
const ITEM_DIR = path.join(ASSETS, 'models/item');
const LOOT_DIR = path.join(DATA, 'loot_tables/blocks');
for (const d of [BS_DIR, MODEL_DIR, ITEM_DIR, LOOT_DIR]) fs.mkdirSync(d, { recursive: true });

// shared particle-only block model (geometry is drawn by the BER, like MTR's own PSD doors)
fs.writeFileSync(path.join(MODEL_DIR, 'fangsu_psd_particle_only.json'),
  JSON.stringify({ textures: { particle: 'mtrlte:fangsu_psd/duanmen/mtrduanmen' } }, null, 2) + '\n');

for (const def of DEFS) {
  fs.writeFileSync(path.join(BS_DIR, def.id + '.json'),
    JSON.stringify({ variants: { '': { model: 'mtrlte:block/fangsu_psd_particle_only' } } }, null, 2) + '\n');

  // 3D item icon: one cuboid per `o` group (union AABB of the group, dominant material's texture).
  const objPath = path.join(ASSETS, def.obj);
  const texDir = 'textures/' + def.obj.substring(0, def.obj.lastIndexOf('/') + 1);
  const groups = objGroups(fs.readFileSync(objPath, 'utf8'));
  const maps = mtlMaps(objPath.replace(/\.obj$/, '.mtl'));

  const boxes = [];
  let maxExtent = 16;
  for (const name of def.groups) {
    const g = groups.get(name);
    if (!g) continue;
    let best = null;
    let bestFaces = -1;
    for (const [mat, faces] of g.perMat) if (faces > bestFaces) { bestFaces = faces; best = mat; }
    const png = maps.get(best);
    if (!png) continue;
    // obj bounds (1 unit = 1 block, xz centred) -> model units, 0..16 with the NW bottom corner at the origin
    const box = {
      from: [mx(g.min[0]), my(g.min[1]), mz(g.min[2])],
      to: [mx(g.max[0]), my(g.max[1]), mz(g.max[2])],
      uv: [g.uvMin[0], 1 - g.uvMax[1], g.uvMax[0], 1 - g.uvMin[1]],
      tex: 'mtrlte:' + texDir.replace(/^textures\//, '') + png.replace(/\.png$/, ''),
    };
    boxes.push(box);
    maxExtent = Math.max(maxExtent, ...box.from.map(Math.abs), ...box.to.map(Math.abs));
  }
  if (!boxes.length) {
    console.log('!! no geometry for ' + def.id);
    continue;
  }
  const s = Math.min(1, 28 / maxExtent);
  const q = (v) => Math.round(v * s * 1000) / 1000;
  const elements = [];
  const textures = {};
  boxes.forEach((box, index) => {
    const key = String(index);
    textures[key] = box.tex;
    const from = box.from.map(q);
    const to = box.to.map(q);
    for (let a = 0; a < 3; a++) {
      if (to[a] - from[a] < 0.5) {
        const mid = (from[a] + to[a]) / 2;
        from[a] = Math.round((mid - 0.25) * 1000) / 1000;
        to[a] = Math.round((mid + 0.25) * 1000) / 1000;
      }
      from[a] = Math.max(-16, Math.min(31.5, from[a]));
      to[a] = Math.max(from[a] + 0.5, Math.min(32, to[a]));
    }
    const u = box.uv;
    const uv = [Math.round(u[0] * 16 * 1000) / 1000, Math.round(u[1] * 16 * 1000) / 1000,
      Math.round(u[2] * 16 * 1000) / 1000, Math.round(u[3] * 16 * 1000) / 1000];
    const faces = {};
    for (const face of ['north', 'east', 'south', 'west', 'up', 'down']) {
      faces[face] = { uv, texture: '#' + key };
    }
    elements.push({ from, to, faces });
  });
  fs.writeFileSync(path.join(MODEL_DIR, def.id + '_icon.json'), JSON.stringify({
    textures,
    elements,
    display: {
      gui: { rotation: [30, 225, 0], scale: [0.625, 0.625, 0.625] },
      ground: { rotation: [0, 0, 0], translation: [0, 3, 0], scale: [0.25, 0.25, 0.25] },
      fixed: { rotation: [0, 0, 0], scale: [0.5, 0.5, 0.5] },
      thirdperson_righthand: { rotation: [75, 45, 0], translation: [0, 2.5, 0], scale: [0.375, 0.375, 0.375] },
      firstperson_righthand: { rotation: [0, 45, 0], scale: [0.4, 0.4, 0.4] },
    },
  }, null, 2) + '\n');
  fs.writeFileSync(path.join(ITEM_DIR, def.id + '.json'),
    JSON.stringify({ parent: 'mtrlte:block/' + def.id + '_icon' }, null, 2) + '\n');

  // loot: only the lower half drops an item
  fs.writeFileSync(path.join(LOOT_DIR, def.id + '.json'), JSON.stringify({
    type: 'minecraft:block',
    pools: [{
      rolls: 1,
      entries: [{
        type: 'minecraft:item',
        name: 'mtrlte:' + def.id,
        conditions: [{ condition: 'minecraft:block_state_property', block: 'mtrlte:' + def.id, properties: { half: 'lower' } }],
      }],
      conditions: [{ condition: 'minecraft:survives_explosion' }],
    }],
  }, null, 2) + '\n');
}

// ---------------------------------------------------------------- lang
function addLang(file, pick) {
  const p = path.join(ASSETS, 'lang', file);
  if (!fs.existsSync(p)) { console.log('missing lang ' + file); return; }
  const json = JSON.parse(fs.readFileSync(p, 'utf8'));
  for (const def of DEFS) json['block.mtrlte.' + def.id] = pick(def);
  const sorted = {};
  for (const key of Object.keys(json).sort()) sorted[key] = json[key];
  fs.writeFileSync(p, JSON.stringify(sorted, null, 2) + '\n');
}
addLang('zh_cn.json', (d) => d.zh);
addLang('en_us.json', (d) => d.en);

console.log(DEFS.length + ' blocks generated:');
for (const d of DEFS) console.log('  ' + d.id + '  ' + d.zh + '  <- ' + d.obj);
