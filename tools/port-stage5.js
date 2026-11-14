// Stage-5: rotation helper / random / text / qualified-name moves that are pure renames.
const fs = require('fs');
const path = require('path');

const root = process.argv[2];

function walk(dir, out) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (e.name.endsWith('.java')) out.push(p);
  }
  return out;
}

const SUBS = [
  // 1.19.3+ RotationAxis#rotationDegrees -> 1.18.2 Vec3f#getDegreesQuaternion
  ['RotationAxis.POSITIVE_X.rotationDegrees(', 'Vec3f.POSITIVE_X.getDegreesQuaternion('],
  ['RotationAxis.NEGATIVE_X.rotationDegrees(', 'Vec3f.NEGATIVE_X.getDegreesQuaternion('],
  ['RotationAxis.POSITIVE_Y.rotationDegrees(', 'Vec3f.POSITIVE_Y.getDegreesQuaternion('],
  ['RotationAxis.NEGATIVE_Y.rotationDegrees(', 'Vec3f.NEGATIVE_Y.getDegreesQuaternion('],
  ['RotationAxis.POSITIVE_Z.rotationDegrees(', 'Vec3f.POSITIVE_Z.getDegreesQuaternion('],
  ['RotationAxis.NEGATIVE_Z.rotationDegrees(', 'Vec3f.NEGATIVE_Z.getDegreesQuaternion('],
  ['RotationAxis.POSITIVE_X.rotation(', 'Vec3f.POSITIVE_X.getRadialQuaternion('],
  ['RotationAxis.POSITIVE_Y.rotation(', 'Vec3f.POSITIVE_Y.getRadialQuaternion('],
  ['RotationAxis.POSITIVE_Z.rotation(', 'Vec3f.POSITIVE_Z.getRadialQuaternion('],
  // imports
  ['import net.minecraft.util.math.RotationAxis;', 'import net.minecraft.util.math.Vec3f;'],
  // 1.19.3 moved Random into net.minecraft.util.math.random
  ['import net.minecraft.util.math.random.Random;', 'import java.util.Random;'],
  // 1.19 renamed the text content classes
  ['import net.minecraft.text.TranslatableTextContent;', 'import net.minecraft.text.TranslatableText;'],
  ['TranslatableTextContent', 'TranslatableText'],
  // fully qualified leftovers
  ['net.minecraft.registry.RegistryKey<', 'net.minecraft.util.registry.RegistryKey<'],
];

let touched = 0;
for (const file of walk(root, [])) {
  let txt = fs.readFileSync(file, 'utf8');
  const eol = txt.includes('\r\n') ? '\r\n' : '\n';
  const before = txt;
  const notes = [];
  for (const [a, b] of SUBS) {
    const n = txt.split(a).length - 1;
    if (n) { txt = txt.split(a).join(b); notes.push(`${a} x${n}`); }
  }
  // dedupe imports
  const lines = txt.split(/\r?\n/);
  const seen = new Set();
  const kept = [];
  for (const line of lines) {
    if (/^import .*;$/.test(line)) {
      if (seen.has(line)) { continue; }
      seen.add(line);
    }
    kept.push(line);
  }
  txt = kept.join(eol);
  if (txt !== before) { fs.writeFileSync(file, txt); touched++; console.log(path.relative(root, file) + ': ' + notes.join(', ')); }
}
console.log('files: ' + touched);
