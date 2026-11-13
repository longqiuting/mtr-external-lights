// Copy the fangsu PSD assets (obj / mtl / png / json descriptors) into the mod jar.
// Namespace rewrite: "fangsu:<path>" -> "mtrlte:fangsu_psd/<path>".
//   objs + mtl + json  ->  assets/mtrlte/fangsu_psd/<path>
//   png                ->  assets/mtrlte/textures/fangsu_psd/<path>   (Textures need the textures/ prefix)
const fs = require('fs');
const path = require('path');

const SRC = process.argv[2];
const RES = process.argv[3]; // .../src/main/resources
const ASSETS = path.join(RES, 'assets/mtrlte');

const REL = ['duanmen', 'screendoor/xiamen'];
const MODEL_DIR = path.join(ASSETS, 'fangsu_psd');
const TEX_DIR = path.join(ASSETS, 'textures/fangsu_psd');

let copied = 0;
for (const rel of REL) {
  const from = path.join(SRC, rel);
  for (const name of fs.readdirSync(from)) {
    const src = path.join(from, name);
    if (!fs.statSync(src).isFile()) continue;
    const ext = path.extname(name).toLowerCase();
    if (ext === '.png') {
      const dst = path.join(TEX_DIR, rel, name);
      fs.mkdirSync(path.dirname(dst), { recursive: true });
      fs.copyFileSync(src, dst);
    } else if (ext === '.obj' || ext === '.mtl') {
      const dst = path.join(MODEL_DIR, rel, name);
      fs.mkdirSync(path.dirname(dst), { recursive: true });
      fs.copyFileSync(src, dst);
    } else if (ext === '.json') {
      const dst = path.join(MODEL_DIR, rel, name);
      fs.mkdirSync(path.dirname(dst), { recursive: true });
      const text = fs.readFileSync(src, 'utf8').replace(/"fangsu:/g, '"mtrlte:fangsu_psd/');
      fs.writeFileSync(dst, text);
    } else {
      continue;
    }
    copied++;
    console.log('copied', path.relative(RES, path.join(ext === '.png' ? TEX_DIR : MODEL_DIR, rel, name)));
  }
}
console.log(copied + ' files');
