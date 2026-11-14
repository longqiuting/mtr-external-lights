// Stage-7: 1.19 Text statics -> 1.18.2 text classes.
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
  let txt = fs.readFileSync(file, 'utf8');
  const eol = txt.includes('\r\n') ? '\r\n' : '\n';
  const before = txt;
  const notes = [];
  for (const [a, b, tag] of [
    ['Text.translatable(', 'new TranslatableText(', 'translatable'],
    ['Text.literal(', 'new LiteralText(', 'literal'],
    ['Text.empty()', 'LiteralText.EMPTY', 'empty'],
  ]) {
    const n = txt.split(a).length - 1;
    if (n) { txt = txt.split(a).join(b); notes.push(tag + ' x' + n); }
  }
  if (txt === before) continue;

  // imports for the text classes that are now referenced
  const lines = txt.split(/\r?\n/);
  const want = [];
  if (/new TranslatableText\(|TranslatableText\b/.test(txt)) want.push('import net.minecraft.text.TranslatableText;');
  if (/new LiteralText\(|LiteralText\.EMPTY/.test(txt)) want.push('import net.minecraft.text.LiteralText;');
  let insertAt = -1;
  for (let i = 0; i < lines.length; i++) if (/^import /.test(lines[i])) insertAt = i;
  for (const imp of want) {
    if (!lines.includes(imp)) {
      if (insertAt < 0) {
        for (let i = 0; i < lines.length; i++) if (/^package /.test(lines[i])) insertAt = i;
      }
      lines.splice(insertAt + 1, 0, imp);
      insertAt++;
    }
  }
  fs.writeFileSync(file, lines.join(eol));
  touched++;
  console.log(path.relative(root, file) + ': ' + notes.join(', '));
}
console.log('files: ' + touched);
