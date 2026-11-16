// Blockbench 模型 JSON → 原版方块模型 JSON（贴图统一指向 mtrlte:block/railing）。
//
// 用法：node tools/bb-json-to-blockmodel.js <src.json> <out.json>
const fs = require('fs');

const src = process.argv[2];
const out = process.argv[3];
const model = JSON.parse(fs.readFileSync(src, 'utf8'));

const TEXTURE = 'mtrlte:block/railing';

const elements = (model.elements || []).map(el => {
    const faces = {};
    for (const [name, face] of Object.entries(el.faces || {})) {
        if (!face || !face.uv) {
            continue;
        }
        const entry = { uv: face.uv.map(n => Math.round(n * 1000) / 1000), texture: '#railing' };
        if (face.rotation) {
            entry.rotation = face.rotation;
        }
        if (face.cullface) {
            entry.cullface = face.cullface;
        }
        if (face.tintindex !== undefined) {
            entry.tintindex = face.tintindex;
        }
        faces[name] = entry;
    }
    const element = { from: el.from, to: el.to, faces };
    if (el.rotation) {
        element.rotation = {
            origin: el.rotation.origin,
            axis: el.rotation.axis,
            angle: el.rotation.angle,
            rescale: Boolean(el.rotation.rescale)
        };
    }
    if (el.shade === false) {
        element.shade = false;
    }
    return element;
});

const outModel = {
    credit: 'Converted from ' + src.replace(/^.*[\\/]/, '') + ' (MSD addon, MIT)',
    textures: {
        particle: TEXTURE,
        railing: TEXTURE
    },
    elements
};

fs.writeFileSync(out, JSON.stringify(outModel, null, 2) + '\n');
console.log(out + ' : elements=' + elements.length);
