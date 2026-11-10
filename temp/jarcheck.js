// Structural validation of the built mod jar: every class named by fabric.mod.json
// entrypoints, every mixin class in every *.mixins.json, and the access widener must
// actually exist inside the jar. A truncated / half-written jar fails here.
const fs = require('fs');
const path = require('path');

const dir = process.argv[2];
const all = [];
(function walk(d, rel) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, e.name);
    const r = rel ? rel + '/' + e.name : e.name;
    if (e.isDirectory()) walk(p, r); else all.push(r);
  }
})(dir, '');
const set = new Set(all);
let problems = 0;
const check = (cls, why) => {
  const p = cls.replace(/\./g, '/') + '.class';
  if (!set.has(p)) { console.log(`MISSING ${p}  <- ${why}`); problems++; }
};

const fmj = JSON.parse(fs.readFileSync(path.join(dir, 'fabric.mod.json'), 'utf8'));
for (const [kind, list] of Object.entries(fmj.entrypoints || {})) {
  for (const ep of list) {
    const v = typeof ep === 'string' ? ep : ep.value;
    check(v.split('::')[0], `entrypoint ${kind}`);
  }
}
for (const mj of all.filter(f => f.endsWith('.mixins.json'))) {
  const j = JSON.parse(fs.readFileSync(path.join(dir, mj), 'utf8'));
  const pkg = (j.package || '').replace(/\.$/, '');
  for (const key of ['mixins', 'client', 'server']) {
    for (const m of j[key] || []) check(pkg + '.' + m, `mixin ${mj}[${key}]`);
  }
  if (j.plugin) check(j.plugin, `mixin plugin ${mj}`);
  if (j['minVersion'] === undefined) { /* not a check */ }
}
if (fmj.accessWidener && !set.has(fmj.accessWidener)) { console.log(`MISSING ${fmj.accessWidener} <- accessWidener`); problems++; }

const classes = all.filter(f => f.endsWith('.class')).length;
const res = all.length - classes;
console.log(`\nfiles=${all.length} classes=${classes} resources=${res} mixinConfigs=${all.filter(f => f.endsWith('.mixins.json')).length}`);
console.log(problems === 0 ? 'OK: every referenced class is present' : `PROBLEMS=${problems}`);
process.exit(problems === 0 ? 0 : 1);
