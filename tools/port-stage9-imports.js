// Add missing imports for symbols introduced by the bulk stages.
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

const RULES = [
  { test: /\bMaterial\./, imp: 'import net.minecraft.block.Material;' },
  { test: /\bLteTabs\./, imp: 'import dev.js.mtrheadlight.compat.LteTabs;' },
];

let touched = 0;
for (const file of walk(root, [])) {
  if (file.includes('compat/LteTabs') || file.includes('clientgui')) continue;
  let txt = fs.readFileSync(file, 'utf8');
  const eol = txt.includes('\r\n') ? '\r\n' : '\n';
  let lines = txt.split(/\r?\n/);
  let changed = false;
  for (const rule of RULES) {
    if (!rule.test.test(txt.replace(/^import .*$/gm, ''))) continue;
    if (lines.includes(rule.imp)) continue;
    let at = -1;
    for (let i = 0; i < lines.length; i++) {
      if (/^import (net\.minecraft\.|dev\.js\.mtrheadlight\.)/.test(lines[i])) at = i;
    }
    if (at < 0) for (let i = 0; i < lines.length; i++) if (/^import /.test(lines[i])) at = i;
    if (at < 0) for (let i = 0; i < lines.length; i++) if (/^package /.test(lines[i])) at = i;
    lines.splice(at + 1, 0, rule.imp);
    changed = true;
  }
  if (changed) { fs.writeFileSync(file, lines.join(eol)); touched++; console.log(path.relative(root, file)); }
}
console.log('files: ' + touched);
