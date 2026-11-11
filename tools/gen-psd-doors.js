#!/usr/bin/env node
/*
 * Restores the PSD/APG door blockstates + models that MTR draws while the door's
 * block entity has not taken over yet, and repairs the APG glass blockstate.
 *
 * Why the door needs a model at all
 * ---------------------------------
 * MTR 3.2.2 (MC 1.18.2 / 1.19.2 / 1.19.4) renders doors with a block entity renderer,
 * but `mtr.render.RenderPSDAPGDoor#render` starts with
 *
 *     if (IBlock.getStatePropertySafe(world, pos, BlockPSDAPGDoorBase.TEMP)) return;
 *
 * and TEMP is a BooleanProperty, so its default value (the first entry of
 * ImmutableSet.of(true, false)) is **true**.  MTR's own psd_door.json therefore ships
 * models for `temp=true` only, and drops TEMP to false the first time the door is
 * opened by a train (`TileEntityPSDAPGDoorBase#setOpen`) or its NBT is rewritten.
 *
 * The mod replaced those blockstates with `minecraft:block/air` for every state, so a
 * freshly placed door had no model AND no renderer -> invisible, and its baked model
 * had no particle sprite -> black/purple break particles.
 *
 * MTR 3.6.4.2 (MC 1.20.1) dropped the TEMP check and ships `air` itself, so there the
 * door only needs a particle sprite; we give it an element-less model that declares one.
 */
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = 'D:/MC-MTR-JS/mtr-headlight';
const VERSIONS = [
  { id: 'v1.18.2', legacyTemp: true },
  { id: 'v1.19.2', legacyTemp: true },
  { id: 'v1.19.4', legacyTemp: true },
  { id: 'v1.20.1', legacyTemp: false }, // MTR 3.6.4.2: BER no longer checks TEMP
];
const LINES = [
  { suf: '', dir: '1' },
  { suf: '_l2', dir: '2' },
  { suf: '_l3', dir: '3' },
];
const FACING = ['west', 'north', 'east', 'south'];
const ROT = { west: 0, north: 90, east: 180, south: 270 };

const problems = [];
const written = [];

function assets(v) { return path.join(ROOT, v.id, 'src/main/resources/assets/mtrlte'); }

function tex(v, name) {
  const p = path.join(assets(v), 'textures/block', name + '.png');
  if (!fs.existsSync(p)) { problems.push(`${v.id}: missing texture ${name}.png`); return false; }
  return true;
}

function write(v, rel, obj) {
  const p = path.join(assets(v), rel);
  fs.mkdirSync(path.dirname(p), { recursive: true });
  const text = typeof obj === 'string' ? obj : JSON.stringify(obj, null, '\t') + '\n';
  const before = fs.existsSync(p) ? fs.readFileSync(p, 'utf8') : null;
  if (before === text) return;
  fs.writeFileSync(p, text);
  written.push(`${v.id}/${rel}${before === null ? ' (new)' : ' (changed)'}`);
}

// ---------------------------------------------------------------- model bodies
// PSD door panel: a 2px slab, same geometry as MTR's door_tile_entity_migration
// placeholders.  The panel sheets (`psd_door_*_left_1*.png`) are authored for MTR's
// 36x18 leaf box, so the 16x16 model face uses only half the sheet width (uv 0..8)
// to keep the artwork from being squashed 2:1.
const psdBase = (flip) => ({
  parent: 'minecraft:block/block',
  textures: { black: 'mtrlte:block/black' },
  elements: [{
    from: [0, 0, 0], to: [2, 16, 16],
    faces: {
      down: { uv: [0, 0, 16, 16], texture: '#bottom', cullface: 'down' },
      up: { uv: [0, 0, 16, 16], texture: '#top', cullface: 'up' },
      east: { uv: flip ? [16, 8, 0, 0] : [0, 0, 8, 16], texture: '#panel' },
      west: { uv: flip ? [16, 0, 0, 16] : [0, 0, 16, 16], texture: '#back' },
      north: { texture: '#black' },
      south: { texture: '#black' },
    },
  }],
});

// PSD door end cap: two boxes (the 8-deep panel + the 2px return), like MTR's end base.
const psdEndBase = (flip) => ({
  parent: 'minecraft:block/block',
  textures: { black: 'mtrlte:block/black', light: 'mtrlte:block/psd_side_light' },
  elements: [
    {
      from: [0, 0, 0], to: [2, 16, 8],
      faces: {
        down: { uv: [0, 0, 16, 16], texture: '#bottom', cullface: 'down' },
        up: { uv: [0, 0, 16, 16], texture: '#top', cullface: 'up' },
        east: { uv: flip ? [8, 0, 0, 16] : [8, 0, 16, 16], texture: '#panel' },
        west: { uv: [0, 0, 16, 16], texture: '#back' },
        north: { texture: '#black' },
        south: { texture: '#black' },
      },
    },
    {
      from: [2, 0, 8], to: [4, 16, 16],
      faces: {
        down: { uv: [0, 0, 16, 16], texture: '#bottom', cullface: 'down' },
        up: { uv: [0, 0, 16, 16], texture: '#top', cullface: 'up' },
        east: { uv: [0, 0, 8, 16], texture: '#panel' },
        west: { uv: [0, 0, 8, 16], texture: '#back' },
        north: { texture: '#light' },
        south: { texture: '#black' },
      },
    },
  ],
});

// APG door: 1px leaf + a rounded bottom lip (copied from MTR's apg_door_* models).
const apgBottom = (flip) => ({
  textures: {
    side: 'mtrlte:block/psd_side_light',
    black: 'mtrlte:block/black',
    panel: '#PANEL#',
  },
  elements: [
    {
      from: [1, 0, 0], to: [2, 16, 16],
      faces: {
        down: { uv: [0, 0, 16, 16], texture: '#black', cullface: 'down' },
        east: { uv: flip ? [16, 0, 0, 16] : [0, 0, 16, 16], texture: '#panel' },
        west: { uv: flip ? [0, 0, 16, 16] : [16, 0, 0, 16], texture: '#panel' },
        north: { texture: '#black' },
        south: { texture: '#side' },
      },
    },
    {
      from: [0, 0, 0], to: [1, 6, 16],
      faces: {
        down: { uv: [0, 0, 16, 16], texture: '#black', cullface: 'down' },
        west: { uv: [16, 9, 0, 9.1], texture: '#panel', cullface: 'west' },
        north: { texture: '#black' },
        south: { texture: '#side' },
      },
    },
    {
      from: [0, 5, 0.05], to: [1.414, 6, 15.95],
      rotation: { origin: [0, 6, 8], axis: 'z', angle: 45 },
      faces: {
        up: { uv: [0, 9, 16, 9.1], texture: '#panel', rotation: 270 },
        north: { texture: '#black' },
        south: { texture: '#side' },
      },
    },
  ],
});

const apgTop = (flip) => ({
  textures: {
    side: 'mtrlte:block/psd_side_light',
    black: 'mtrlte:block/black',
    panel: '#PANEL#',
  },
  elements: [
    {
      from: [1, 0, 0], to: [2, 8, 16],
      faces: {
        up: { uv: flip ? [0, 8, 16, 8.1] : [16, 8, 0, 8.1], texture: '#panel', rotation: 270 },
        east: { uv: flip ? [16, 8, 0, 16] : [0, 8, 16, 16], texture: '#panel' },
        west: { uv: flip ? [0, 8, 16, 16] : [16, 8, 0, 16], texture: '#panel' },
        north: { texture: '#side' },
        south: { texture: '#black' },
      },
    },
  ],
});

// element-less model: nothing is drawn, but the baked model gets a particle sprite
const particleOnly = (texture) => ({ textures: { particle: texture } });

// ---------------------------------------------------------------- generation
function cartesian(items) {
  let out = [{}];
  for (const [k, vals] of items) {
    const next = [];
    for (const c of out) for (const val of vals) next.push({ ...c, [k]: val });
    out = next;
  }
  return out;
}

for (const v of VERSIONS) {
  for (const line of LINES) {
    const s = line.suf;

    // ---- textures this line must have
    const t = {
      psdBottomLeft: `psd_door_bottom_left_1${s}`,
      psdBottomRight: `psd_door_bottom_right_1${s}`,
      psdTopLeft: `psd_door_top_left_1${s}`,
      psdTopRight: `psd_door_top_right_1${s}`,
      psdBottomBack: `psd_door_bottom_back_1${s}`,
      psdTopBack: `psd_door_top_back_1${s}`,
      psdEndBottomLeft: `psd_door_end_bottom_left_1_1${s}`,
      psdEndBottomRight: `psd_door_end_bottom_right_1_1${s}`,
      psdEndTopLeft: `psd_door_end_top_left_1_1${s}`,
      psdEndTopRight: `psd_door_end_top_right_1_1${s}`,
      apgBottom: `apg_door_bottom${s}`,
      apgTop: `apg_door_top${s}`,
    };
    for (const name of Object.values(t)) tex(v, name);
    tex(v, 'black');
    tex(v, 'transparent');
    tex(v, 'psd_side_light');

    // ---- base models (shared by all three lines)
    write(v, 'models/block/psd_door_left_base.json', psdBase(false));
    write(v, 'models/block/psd_door_right_base.json', psdBase(true));
    write(v, 'models/block/psd_door_end_left_base.json', psdEndBase(false));
    write(v, 'models/block/psd_door_end_right_base.json', psdEndBase(true));

    // ---- per-line PSD door panel models
    const psdPanel = {
      'bottom_left': { base: 'psd_door_left_base', panel: t.psdBottomLeft, back: t.psdBottomBack, bottom: 'black', top: 'transparent' },
      'top_left': { base: 'psd_door_left_base', panel: t.psdTopLeft, back: t.psdTopBack, bottom: 'transparent', top: 'black' },
      'bottom_right': { base: 'psd_door_right_base', panel: t.psdBottomRight, back: t.psdBottomBack, bottom: 'black', top: 'transparent' },
      'top_right': { base: 'psd_door_right_base', panel: t.psdTopRight, back: t.psdTopBack, bottom: 'transparent', top: 'black' },
      'end_bottom_left': { base: 'psd_door_end_left_base', panel: t.psdEndBottomLeft, back: t.psdBottomBack, bottom: 'black', top: 'transparent' },
      'end_top_left': { base: 'psd_door_end_left_base', panel: t.psdEndTopLeft, back: t.psdTopBack, bottom: 'transparent', top: 'black' },
      'end_bottom_right': { base: 'psd_door_end_right_base', panel: t.psdEndBottomRight, back: t.psdBottomBack, bottom: 'black', top: 'transparent' },
      'end_top_right': { base: 'psd_door_end_right_base', panel: t.psdEndTopRight, back: t.psdTopBack, bottom: 'transparent', top: 'black' },
    };
    for (const [key, m] of Object.entries(psdPanel)) {
      write(v, `models/block/psd_door_${key}_1${s}.json`, {
        parent: `mtrlte:block/${m.base}`,
        textures: {
          bottom: `mtrlte:block/${m.bottom}`,
          top: `mtrlte:block/${m.top}`,
          panel: `mtrlte:block/${m.panel}`,
          back: `mtrlte:block/${m.back}`,
          particle: `mtrlte:block/${m.panel}`,
        },
      });
    }

    // ---- per-line APG door panel models
    for (const [key, panel, flip, body] of [
      ['bottom_left', t.apgBottom, false, apgBottom],
      ['bottom_right', t.apgBottom, true, apgBottom],
      ['top_left', t.apgTop, false, apgTop],
      ['top_right', t.apgTop, true, apgTop],
    ]) {
      const model = body(flip);
      model.textures.panel = `mtrlte:block/${panel}`;
      write(v, `models/block/apg_door_${key}${s}.json`, model);
    }

    // ---- particle-only models (invisible, but with a real particle sprite)
    write(v, `models/block/psd_door_particle_only${s}.json`, particleOnly(`mtrlte:block/${t.psdBottomLeft}`));
    write(v, `models/block/apg_door_particle_only${s}.json`, particleOnly(`mtrlte:block/${t.apgBottom}`));

    // ---- blockstates
    if (v.legacyTemp) {
      // psd_door: mirrors MTR's own 32 temp=true selectors, plus a temp=false
      // catch-all that is invisible (the block entity renders it) but still has
      // a particle sprite so breaking the door drops the right particles.
      const psd = [];
      for (const st of cartesian([['facing', FACING], ['side', ['left', 'right']], ['half', ['lower', 'upper']], ['end', ['false', 'true']]])) {
        const v2 = st.half === 'lower' ? 'bottom' : 'top';
        const name = (st.end === 'true' ? `psd_door_end_${v2}_${st.side}` : `psd_door_${v2}_${st.side}`) + `_1${s}`;
        const apply = { model: `mtrlte:block/${name}` };
        if (ROT[st.facing]) apply.y = ROT[st.facing];
        psd.push({ when: { facing: st.facing, side: st.side, half: st.half, end: st.end, temp: 'true' }, apply });
      }
      psd.push({ when: { temp: 'false' }, apply: { model: `mtrlte:block/psd_door_particle_only${s}` } });
      write(v, `blockstates/psd_door_xiamen${s}.json`, { multipart: psd });

      const apg = [];
      for (const st of cartesian([['facing', FACING], ['side', ['left', 'right']], ['half', ['lower', 'upper']], ['end', ['false', 'true']]])) {
        const v2 = st.half === 'lower' ? 'bottom' : 'top';
        const apply = { model: `mtrlte:block/apg_door_${v2}_${st.side}${s}` };
        if (ROT[st.facing]) apply.y = ROT[st.facing];
        apg.push({ when: { facing: st.facing, side: st.side, half: st.half, end: st.end, temp: 'true' }, apply });
      }
      apg.push({ when: { temp: 'false' }, apply: { model: `mtrlte:block/apg_door_particle_only${s}` } });
      write(v, `blockstates/apg_door_xiamen${s}.json`, { multipart: apg });
    } else {
      // MTR 3.6.4.2: the BER always draws the door, the block must stay invisible;
      // keep a particle sprite so breaking it does not spawn missing-texture dust.
      write(v, `blockstates/psd_door_xiamen${s}.json`,
        { variants: { '': { model: `mtrlte:block/psd_door_particle_only${s}` } } });
      write(v, `blockstates/apg_door_xiamen${s}.json`,
        { variants: { '': { model: `mtrlte:block/apg_door_particle_only${s}` } } });
    }

    // ---- APG glass: `propagate_property` is not a property of BlockAPGGlass;
    // the fourth property is `arrow_direction` (IntProperty 0..3).
    const glassPath = path.join(assets(v), `blockstates/apg_glass_xiamen${s}.json`);
    const glassText = fs.readFileSync(glassPath, 'utf8');
    if (glassText.includes('propagate_property')) {
      const fixed = glassText.split('propagate_property=').join('arrow_direction=');
      fs.writeFileSync(glassPath, fixed);
      written.push(`${v.id}/blockstates/apg_glass_xiamen${s}.json (changed: propagate_property -> arrow_direction)`);
    }
  }
}

console.log(written.length ? written.join('\n') : 'nothing to do');
if (problems.length) {
  console.error('\nPROBLEMS:\n' + problems.join('\n'));
  process.exit(1);
}
