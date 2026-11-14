// Stage-1 mechanical transforms for the 1.20.1 -> 1.18.2 port of the mtr-headlight sources.
// Handles only the moves that are truly mechanical; everything semantic stays for hand edits.
const fs = require('fs');
const path = require('path');

const root = process.argv[2];
const dry = process.argv.includes('--dry');

function walk(dir, out) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (e.name.endsWith('.java')) out.push(p);
  }
  return out;
}

const IMPORT_SWAPS = [
  ['import net.minecraft.registry.Registries;', 'import net.minecraft.util.registry.Registry;'],
  ['import net.minecraft.registry.Registry;', 'import net.minecraft.util.registry.Registry;'],
  ['import net.minecraft.registry.entry.RegistryEntry;', 'import net.minecraft.util.registry.RegistryEntry;'],
  ['import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;', 'import dev.js.mtrheadlight.compat.LteTabs;'],
  ['import net.minecraft.client.gui.DrawContext;', 'import dev.js.mtrheadlight.clientgui.DrawContext;'],
];

const report = [];
for (const file of walk(root, [])) {
  let txt = fs.readFileSync(file, 'utf8');
  const before = txt;
  const notes = [];

  // 1. Item group call sites: modifyEntriesEvent(RegistryKey.of(RegistryKeys.ITEM_GROUP, X)) -> LteTabs.modify(X)
  const igre = /ItemGroupEvents\.modifyEntriesEvent\(\s*RegistryKey\.of\(\s*RegistryKeys\.ITEM_GROUP\s*,\s*([\s\S]*?)\)\s*\)/g;
  if (igre.test(txt)) {
    let n = 0;
    txt = txt.replace(igre, (m, arg) => { n++; return 'LteTabs.modify(' + arg.trim() + ')'; });
    notes.push('itemgroup-calls=' + n);
  }

  // 2. import swaps
  for (const [from, to] of IMPORT_SWAPS) {
    if (txt.includes(from)) { txt = txt.split(from).join(to); notes.push('imp:' + to); }
  }

  // 3. ITEM_GROUP / RegistryKeys leftovers (tab creation) get flagged, not touched
  if (/RegistryKeys|Registries\.ITEM_GROUP/.test(txt)) notes.push('NEEDS-TAB-EDIT');

  // 4. RegistryKey import is gone from 1.18.2 for item groups; drop it when nothing else uses it
  const body = txt.replace(/^import .*$/gm, '');
  for (const imp of ['import net.minecraft.registry.RegistryKey;', 'import net.minecraft.registry.RegistryKeys;',
                     'import net.minecraft.util.registry.RegistryKey;']) {
    if (txt.includes(imp)) {
      txt = txt.split(imp + '\r\n').join('').split(imp + '\n').join('');
      notes.push('drop:' + imp);
    }
  }

  // 5. Registries.X -> Registry.X
  if (txt.includes('Registries.')) {
    const n = txt.split('Registries.').length - 1;
    txt = txt.split('Registries.').join('Registry.');
    notes.push('registries=' + n);
  }

  // 6. duplicate import lines (two Registry imports after the swap)
  const eol = before.includes('\r\n') ? '\r\n' : '\n';
  const lines = txt.split(/\r?\n/);
  const seen = new Set();
  const kept = [];
  for (const line of lines) {
    if (/^import .*;$/.test(line)) {
      if (seen.has(line)) { notes.push('dup:' + line); continue; }
      seen.add(line);
    }
    kept.push(line);
  }
  txt = kept.join(eol);
  if (txt !== before && !dry) fs.writeFileSync(file, txt);
  if (notes.length) report.push(path.relative(root, file) + '  ->  ' + notes.join(', '));
}

console.log(report.join('\n'));
console.log('\nfiles touched: ' + report.length);
