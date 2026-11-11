// Hash every class entry inside each built jar, then diff the maps.
const fs = require('fs');
const { execFileSync } = require('child_process');
const crypto = require('crypto');

const ROOT = 'D:/MC-MTR-JS/mtr-headlight';
const versions = ['v1.18.2', 'v1.19.2', 'v1.19.4', 'v1.20.1'];

function jarOf(v) {
  const dir = `${ROOT}/${v}/build/libs`;
  return fs.readdirSync(dir).filter(f => f.endsWith('.jar') && !f.includes('sources')).map(f => `${dir}/${f}`)[0];
}

const maps = {};
for (const v of versions) {
  const jar = jarOf(v);
  const entries = execFileSync('unzip', ['-Z1', jar], { encoding: 'utf8', maxBuffer: 1 << 26 })
    .split(/\r?\n/).filter(e => e.endsWith('.class'));
  const m = {};
  for (const e of entries) {
    const buf = execFileSync('unzip', ['-p', jar, e], { maxBuffer: 1 << 26 });
    m[e] = crypto.createHash('md5').update(buf).digest('hex');
  }
  maps[v] = m;
  console.log(`${v}: ${entries.length} classes`);
}

function cmp(a, b) {
  const A = maps[a], B = maps[b];
  const onlyA = Object.keys(A).filter(k => !(k in B));
  const onlyB = Object.keys(B).filter(k => !(k in A));
  const diff = Object.keys(A).filter(k => k in B && A[k] !== B[k]);
  console.log(`\n=== ${a} vs ${b}: ${diff.length} differ, ${onlyA.length} only-in-${a}, ${onlyB.length} only-in-${b}`);
  if (onlyA.length) console.log(`  only ${a}: ${onlyA.join(', ')}`);
  if (onlyB.length) console.log(`  only ${b}: ${onlyB.join(', ')}`);
  console.log(`  differ: ${diff.join('\n          ')}`);
}

cmp('v1.19.4', 'v1.19.2');
cmp('v1.19.4', 'v1.18.2');
cmp('v1.19.4', 'v1.20.1');
