// Stage-6: ButtonWidget.builder(..).dimensions(..).build() -> clientgui.Widgets.button(..)....build()
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

let touched = 0;
for (const file of walk(root, [])) {
  if (file.includes('clientgui')) continue;
  let txt = fs.readFileSync(file, 'utf8');
  if (!txt.includes('ButtonWidget.builder(')) continue;
  const eol = txt.includes('\r\n') ? '\r\n' : '\n';
  const n = txt.split('ButtonWidget.builder(').length - 1;
  txt = txt.split('ButtonWidget.builder(').join('Widgets.button(');
  if (!/^import dev\.js\.mtrheadlight\.clientgui\.Widgets;$/m.test(txt)) {
    const lines = txt.split(/\r?\n/);
    let idx = -1;
    for (let i = 0; i < lines.length; i++) {
      if (/^import dev\.js\.mtrheadlight\./.test(lines[i])) idx = i;
    }
    if (idx < 0) for (let i = 0; i < lines.length; i++) if (/^import /.test(lines[i])) idx = i;
    lines.splice(idx + 1, 0, 'import dev.js.mtrheadlight.clientgui.Widgets;');
    txt = lines.join(eol);
  }
  fs.writeFileSync(file, txt);
  touched++;
  console.log(path.relative(root, file) + ': ' + n);
}
console.log('files: ' + touched);
