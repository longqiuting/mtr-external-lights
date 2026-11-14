// Scan a java tree for API-usage counts (for the 1.18.2 port triage).
const fs = require('fs');
const path = require('path');

const root = process.argv[2];
const pats = process.argv.slice(3);

function walk(dir, out) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (e.name.endsWith('.java')) out.push(p);
  }
  return out;
}

const files = walk(root, []);
for (const pat of pats) {
  const hits = [];
  let total = 0;
  for (const f of files) {
    const txt = fs.readFileSync(f, 'utf8');
    const n = txt.split(pat).length - 1;
    if (n) { hits.push(path.relative(root, f) + ':' + n); total += n; }
  }
  console.log(`### ${pat} — ${total} hits in ${hits.length} files`);
  console.log('   ' + hits.join('  '));
}
