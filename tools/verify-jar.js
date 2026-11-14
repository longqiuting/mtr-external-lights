// Verify that every entrypoint / mixin class named in the jar's fabric.mod.json + mixin configs
// actually exists in the jar.
const { execSync } = require('child_process');

const jar = process.argv[2];
const list = execSync(`unzip -l "${jar}"`, { maxBuffer: 1 << 28 }).toString();
const classes = new Set(
  list.split(/\r?\n/).map(l => l.trim().split(/\s+/).pop())
    .filter(p => p && p.endsWith('.class'))
    .map(p => p.replace(/\.class$/, '').replace(/\//g, '.')));

function readJson(entry) {
  return JSON.parse(execSync(`unzip -p "${jar}" ${entry}`, { maxBuffer: 1 << 26 }).toString());
}

let problems = 0;
function check(name, present) {
  if (!present) { console.log('MISSING  ' + name); problems++; }
}

const fmj = readJson('fabric.mod.json');
for (const [kind, eps] of Object.entries(fmj.entrypoints || {})) {
  for (const ep of eps) {
    const cls = typeof ep === 'string' ? ep : ep.value;
    check(`entrypoint ${kind}: ${cls}`, classes.has(cls));
  }
}
for (const mixin of fmj.mixins || []) {
  const cfg = readJson(mixin);
  const pkg = cfg.package;
  for (const group of ['mixins', 'client', 'server']) {
    for (const rel of cfg[group] || []) {
      check(`${mixin} ${group}: ${rel}`, classes.has(pkg + '.' + rel));
    }
  }
  if (cfg.plugin) check(`${mixin} plugin: ${cfg.plugin}`, classes.has(cfg.plugin));
}
console.log(`classes in jar: ${classes.size}`);
console.log(problems === 0 ? 'ALL REFERENCES RESOLVE' : `${problems} PROBLEM(S)`);
