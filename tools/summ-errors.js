// Summarise a javac (Chinese locale) error log from the gradle build.
const fs = require('fs');
const txt = fs.readFileSync(process.argv[2], 'utf8');
const lines = txt.split(/\r?\n/);

const errs = [];
for (let i = 0; i < lines.length; i++) {
  const m = lines[i].match(/^(.*?\.java):(\d+): (错误|error): (.*)$/);
  if (!m) continue;
  const file = m[1].split(/[\\/]/).slice(-2).join('/');
  const msg = m[4].trim();
  // collect the "符号:"/"位置:" detail lines until the next error header
  const details = [];
  for (let j = i + 1; j < lines.length && j < i + 6; j++) {
    const d = lines[j].trim();
    if (!d) break;
    if (/符号:|位置:|symbol:|location:/.test(d)) details.push(d.replace(/^(符号|symbol):\s*/, 'sym=').replace(/^(位置|location):\s*/, 'at='));
    else if (/^\^$/.test(d)) continue;
    else if (/^(import |public |protected |private |final |static |return |if |for |while |[a-zA-Z_].*[;{}()])/.test(d) && !/^错误/.test(d)) details.push('src:' + d);
    else break;
  }
  errs.push({ file, line: +m[2], msg, detail: details.join(' ') });
}

const byFile = new Map();
for (const e of errs) {
  if (!byFile.has(e.file)) byFile.set(e.file, []);
  byFile.get(e.file).push(e);
}
for (const [f, list] of [...byFile.entries()].sort((a, b) => b[1].length - a[1].length)) {
  console.log(`\n## ${f}  (${list.length})`);
  const seen = new Set();
  for (const e of list) {
    const key = e.msg + '|' + e.detail;
    const suffix = seen.has(key) ? '' : '   <<<';
    seen.add(key);
    console.log(`   ${e.line}: ${e.msg} ${e.detail}${suffix}`);
  }
}
const msgs = new Map();
for (const e of errs) msgs.set(e.msg + ' ' + e.detail, (msgs.get(e.msg + ' ' + e.detail) || 0) + 1);
console.log('\n=== top messages ===');
for (const [k, v] of [...msgs.entries()].sort((a, b) => b[1] - a[1]).slice(0, 40)) console.log(`${v}x  ${k}`);
console.log(`\ntotal errors: ${errs.length}, files: ${byFile.size}`);
