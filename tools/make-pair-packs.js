#!/usr/bin/env node
/*
 * Synthetic vehicle packs for tools/check-light-binding.js.
 *
 * Four minimal packs, each one rule from docs/cone-binding-rules.md:
 *
 *   A-two-groups  headlight_left + headlight_right, same stage, same face  -> 2 beams (mirror pair)
 *   B-one-group   one "headlights" group with a cube on each side          -> 2 beams (clustered)
 *   C-single      one centred lamp                                         -> 1 beam
 *   D-geometry    an unknown name ("qianzhao") that is a LIGHTS pair       -> 2 beams (level 4)
 *
 * Every pack has a 2.9 x 2 x 20 block body so the car section / end face / centreline are realistic.
 *
 * Usage:
 *   node tools/make-pair-packs.js && node tools/check-light-binding.js "<printed dir>"
 *   LTE_DEBUG_LAMPS=1 ...   # also dump the per-model lamp offsets
 *
 * The expected summary is: head x1=1  x2=3, tail x2=4, head mirrored pair 3/4 (C is the single).
 */
'use strict';

const fs = require('fs');
const path = require('path');
const os = require('os');

const root = path.join(os.tmpdir(), 'lte-pair-test');
fs.rmSync(root, {recursive: true, force: true});

// bbmodel units are 1/16 block.
const BODY = {from: [-23, 0, -160], to: [23, 32, 160]};
const LAMP_L = {from: [-17, 8, -160], to: [-13, 12, -152]};
const LAMP_R = {from: [13, 8, -160], to: [17, 12, -152]};
const LAMP_C = {from: [-2, 8, -160], to: [2, 12, -152]};

function pack(name, groups) {
    const dir = path.join(root, name, 'assets', 'mtr', 'veh');
    fs.mkdirSync(dir, {recursive: true});
    const all = [{name: 'body', stage: 'EXTERIOR', cubes: [BODY]}].concat(groups);
    let uuid = 0;
    const elements = [];
    const outliner = [];
    for (const g of all) {
        const children = [];
        for (const cube of g.cubes) {
            const id = 'u' + (uuid++);
            elements.push({uuid: id, from: cube.from, to: cube.to});
            children.push(id);
        }
        outliner.push({name: g.name, children});
    }
    fs.writeFileSync(path.join(dir, 'veh.bbmodel'), JSON.stringify({elements, outliner}));
    fs.writeFileSync(path.join(dir, 'veh.json'), JSON.stringify({
        length: 20,
        width: 2.9,
        parts: all.map((g) => ({name: g.name, stage: g.stage, positions: [[0, 0]]})),
    }));
}

pack('A-two-groups', [
    {name: 'headlight_left', stage: 'ALWAYS_ON_LIGHTS', cubes: [LAMP_L]},
    {name: 'headlight_right', stage: 'ALWAYS_ON_LIGHTS', cubes: [LAMP_R]},
]);

pack('B-one-group', [
    {name: 'headlights', stage: 'LIGHTS', cubes: [LAMP_L, LAMP_R]},
]);

pack('C-single', [
    {name: 'head_light', stage: 'LIGHTS', cubes: [LAMP_C]},
]);

pack('D-geometry', [
    {name: 'qianzhao', stage: 'ALWAYS_ON_LIGHTS', cubes: [LAMP_L, LAMP_R]},
]);

console.log(root);
