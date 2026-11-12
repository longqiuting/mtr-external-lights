const fs = require('fs');
const files = process.argv.slice(2);
for (const f of files) {
  const j = JSON.parse(fs.readFileSync(f, 'utf8'));
  console.log('=== ' + f + '  parent=' + (j.parent || '-') + '  elements=' + (j.elements ? j.elements.length : 0));
  if (j.textures) console.log('   textures: ' + JSON.stringify(j.textures));
  for (const [i, e] of (j.elements || []).entries()) {
    const rot = e.rotation ? JSON.stringify(e.rotation) : '';
    console.log(`  [${i}] from=${JSON.stringify(e.from)} to=${JSON.stringify(e.to)} ${rot}`);
  }
}
