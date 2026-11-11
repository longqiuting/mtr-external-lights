// One-shot asset alignment for the three legacy ports (1.18.2 / 1.19.2 / 1.19.4).
//
// B) apg_glass blockstates: MTR 3.2.2's BlockAPGGlass builds its IntProperty as
//    IntProperty.of("propagate_property", 0, 3)  <- javap of the shipped jar
//    so `arrow_direction=...` is not a property this block has. Rename the keys.
//
// C) psd_door / apg_door blockstates: 1.20.1 ships a single "" -> *_particle_only model, i.e. the
//    door is drawn ONLY by MTR's block entity renderer. The legacy ports still carry a 33-entry
//    multipart set of hand-written static door models that pick the *other* half of the box-UV
//    atlas for the visible face (front = atlas left region, back = a separate *_back*.png), so any
//    frame in which MTR's 3.2.2 TEMP placeholder path wins paints the opposite face's design.
//    Align with 1.20.1: same file content, BER only.
const fs = require('fs');
const path = require('path');

const ROOT = 'D:/MC-MTR-JS/mtr-headlight';
const versions = ['v1.18.2', 'v1.19.2', 'v1.19.4'];
const bsRel = 'src/main/resources/assets/mtrlte/blockstates';

let changed = 0;

for (const v of versions) {
  // ---- B: glass property key
  for (const f of ['apg_glass_xiamen.json', 'apg_glass_xiamen_l2.json', 'apg_glass_xiamen_l3.json']) {
    const p = path.join(ROOT, v, bsRel, f);
    const before = fs.readFileSync(p, 'utf8');
    if (!before.includes('arrow_direction')) { console.log('skip (no arrow_direction)', v, f); continue; }
    const after = before.split('arrow_direction=').join('propagate_property=');
    fs.writeFileSync(p, after);
    changed++;
    console.log('B', v, f, 'arrow_direction -> propagate_property');
  }

  // ---- C: door blockstates == 1.20.1 (BER only)
  for (const f of ['psd_door_xiamen.json', 'psd_door_xiamen_l2.json', 'psd_door_xiamen_l3.json',
                   'apg_door_xiamen.json', 'apg_door_xiamen_l2.json', 'apg_door_xiamen_l3.json']) {
    const src = path.join(ROOT, 'v1.20.1', bsRel, f);
    const dst = path.join(ROOT, v, bsRel, f);
    const want = fs.readFileSync(src, 'utf8');
    const have = fs.readFileSync(dst, 'utf8');
    if (have === want) { console.log('skip (already aligned)', v, f); continue; }
    fs.writeFileSync(dst, want);
    changed++;
    console.log('C', v, f, 'aligned with 1.20.1 (', have.length, '->', want.length, 'bytes )');
  }
}

console.log('files changed:', changed);
