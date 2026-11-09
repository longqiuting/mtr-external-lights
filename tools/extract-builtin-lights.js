#!/usr/bin/env node
/*
 * Regenerates v1.20.1/src/main/resources/assets/mtrlte/builtin_lights.json from the offline
 * extraction data committed next to it:
 *
 *   tools/builtin-train-types.json  TrainType -> MTR model class (+ constructor args), 114 rows
 *   tools/builtin-model-lights.json model class|args -> lamp boxes (see below)
 *
 * How the two inputs were produced (MTR 3.6.4.2, no game launch):
 *   1. javap -c mtr.client.TrainClientRegistry  ->  the 106 register(TrainType, ..., new ModelX) calls
 *   2. CFR-decompile mtr/model/Model*.java      ->  confirm headlights/tail_lights + flip flags
 *   3. an offline Java probe instantiated each model, rendered only those groups into a capturing
 *      VertexConsumer and printed one box per cuboid; ModelMapper.render resets the part pivot, so
 *      the authored pivot y (24 px = 1.5 blocks, the car floor) is added back. Coordinates are blocks.
 *
 * Output format (consumed by dev.js.mtrheadlight.BuiltinLights):
 *   { "<trainId>": { "head":[{"x","y","z","w","h"}, ...], "tail":[...] } }
 * x = lateral (blocks), y = height above the car reference point, z = along the car
 * (negative = front), w/h = lamp box size; one entry per physical lamp.
 *
 * Usage: node tools/extract-builtin-lights.js
 */
'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const TYPES = path.join(__dirname, 'builtin-train-types.json');
const MODELS = path.join(__dirname, 'builtin-model-lights.json');
const OUT = path.join(ROOT, 'v1.20.1', 'src', 'main', 'resources', 'assets', 'mtrlte', 'builtin_lights.json');

const types = JSON.parse(fs.readFileSync(TYPES, 'utf8'));
const models = JSON.parse(fs.readFileSync(MODELS, 'utf8'));

const R = v => Math.round(v * 1000) / 1000;

function usable(list) {
  if (!Array.isArray(list)) return [];
  return list
    .filter(p => p && p.y >= 0.15 && p.y <= 3.4 && Math.abs(p.x) <= 2.2 && p.w >= 0.01 && p.h >= 0.01)
    .map(p => ({ x: R(p.x), y: R(p.y), z: R(p.z), w: R(p.w), h: R(p.h) }))
    .sort((a, b) => a.x - b.x);
}

function mirror(list) {
  return list
    .map(p => ({ x: R(-p.x), y: p.y, z: R(-p.z), w: p.w, h: p.h }))
    .sort((a, b) => a.x - b.x);
}

const table = {};
const report = [];
for (const row of types) {
  const id = row.type.toLowerCase();
  if (!row.model) {
    report.push({ id, status: 'no-model', reason: 'vanilla model (minecart/boat), no MTR lamp geometry' });
    continue;
  }
  const key = row.model + '|' + (row.args || []).join(',');
  const m = models[key];
  if (!m) {
    report.push({ id, status: 'uncertain', model: row.model, reason: 'no extraction data' });
    continue;
  }
  const head = usable(m.head);
  const tail = usable(m.tail);
  if (!head.length) {
    report.push({ id, status: 'uncertain', model: row.model, reason: 'no headlights group in the model' });
    continue;
  }
  const ys = head.map(p => p.y);
  if (Math.max(...ys) - Math.min(...ys) > 0.6) {
    report.push({ id, status: 'uncertain', model: row.model, reason: 'lamp heights disagree' });
    continue;
  }
  table[id] = { head, tail: tail.length ? tail : mirror(head) };
  report.push({
    id, status: 'ok', model: row.model, lamps: head.length, tail: table[id].tail.length,
    y: R(ys.reduce((a, b) => a + b, 0) / ys.length), basis: m.basis
  });
}

fs.writeFileSync(OUT, JSON.stringify(table, null, 1) + '\n');
fs.writeFileSync(path.join(__dirname, 'builtin-light-report.json'), JSON.stringify(report, null, 1) + '\n');

const ok = report.filter(r => r.status === 'ok');
console.log('builtin lights: %d / %d vehicles written to %s', ok.length, types.length, OUT);
console.log('  lamp counts: %s', JSON.stringify(ok.reduce((a, r) => (a[r.lamps] = (a[r.lamps] || 0) + 1, a), {})));
const bad = report.filter(r => r.status !== 'ok');
if (bad.length) {
  console.log('  uncertain (%d):', bad.length);
  for (const r of bad) console.log('    - %s%s: %s', r.id, r.model ? ' (' + r.model + ')' : '', r.reason);
}
