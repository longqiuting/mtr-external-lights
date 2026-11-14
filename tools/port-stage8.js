// Stage-8: second wave of mechanical 1.20.1 -> 1.18.2 renames.
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

// sendFeedback(() -> EXPR, BOOL) -> sendFeedback(EXPR, BOOL)
// sendError(() -> EXPR)         -> sendError(EXPR)
function unwrapSupplierLambdas(txt) {
  let out = '';
  let i = 0;
  let changes = 0;
  const anchor = /(sendFeedback|sendError)\(\(\)\s*->\s*/g;
  let m;
  while ((m = anchor.exec(txt)) !== null) {
    const start = m.index;
    const bodyStart = anchor.lastIndex;
    // find the matching ')' of the enclosing call, tracking nesting and string literals
    let depth = 0;
    let j = bodyStart;
    let inStr = false;
    let escaped = false;
    let commaAt = -1;
    for (; j < txt.length; j++) {
      const c = txt[j];
      if (inStr) {
        if (escaped) escaped = false;
        else if (c === '\\') escaped = true;
        else if (c === '"') inStr = false;
        continue;
      }
      if (c === '"') { inStr = true; continue; }
      if (c === '(') depth++;
      else if (c === ')') {
        if (depth === 0) break; // end of the enclosing call
        depth--;
      } else if (c === ',' && depth === 0) {
        commaAt = j;
        break;
      }
    }
    if (j >= txt.length) break;
    const inner = txt.slice(bodyStart, commaAt >= 0 ? commaAt : j);
    // rebuild: drop the "() -> " prefix, keep the rest of the call identical
    out += txt.slice(i, start) + m[1] + '(' + inner;
    i = commaAt >= 0 ? commaAt : j;
    changes++;
    anchor.lastIndex = i;
  }
  out += txt.slice(i);
  return { text: out, changes };
}

const SUBS = [
  [/\bthis\.clearAndInit\(\);/g, (m) => 'this.clearChildren();\n        this.init();'],
  [/\bbutton\.getX\(\)/g, () => 'button.x'],
  [/\bbutton\.getY\(\)/g, () => 'button.y'],
  [/\.getHorizontalPlayerFacing\(\)/g, () => '.getPlayerLookDirection()'],
  [/BlockPos\.ofFloored\(/g, () => 'new BlockPos('],
  [/Registry\.ITEM_GROUP\.get\(/g, () => 'LteTabs.get('],
  [/\bthis\.renderBackground\(context\);/g, () => 'this.renderBackground(matrices);'],
  [/AbstractBlock\.Settings\.create\(\)/g, () => 'AbstractBlock.Settings.of(Material.STONE)'],
  [/\.setFocused\(/g, () => '.setTextFieldFocused('],
  [/\bclient\.options\.getFov\(\)\.getValue\(\)/g, () => 'client.options.fov'],
  [/\bclient\.options\.getViewDistance\(\)\.getValue\(\)/g, () => 'client.options.viewDistance'],
  [/\bclient\.options\.getParticles\(\)\.getValue\(\)/g, () => 'client.options.particles'],
  [/\bMinecraftClient\.getInstance\(\)\.options\.getViewDistance\(\)\.getValue\(\)/g, () => 'MinecraftClient.getInstance().options.viewDistance'],
];

let touched = 0;
for (const file of walk(root, [])) {
  if (file.includes('clientgui') || file.includes('compat')) continue;
  let txt = fs.readFileSync(file, 'utf8');
  const eol = txt.includes('\r\n') ? '\r\n' : '\n';
  const before = txt;
  const notes = [];

  const r = unwrapSupplierLambdas(txt);
  if (r.changes) { txt = r.text; notes.push('feedback-lambda x' + r.changes); }

  for (const [re, rep] of SUBS) {
    const n = (txt.match(re) || []).length;
    if (n) { txt = txt.replace(re, rep); notes.push(re.source + ' x' + n); }
  }

  if (txt !== before) { fs.writeFileSync(file, txt); touched++; console.log(path.relative(root, file) + ': ' + notes.join(', ')); }
}
console.log('files: ' + touched);
