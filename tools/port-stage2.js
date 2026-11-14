// Stage-2: rewrite the four creative-tab registrations onto compat.LteTabs.create().
const fs = require('fs');
const path = require('path');

const root = process.argv[2];
const files = [
  'dev/js/mtrheadlight/decor/RccPlatforms.java',
  'dev/js/mtrheadlight/signal/LteCoreItems.java',
  'dev/js/mtrheadlight/xiamen/eyecandy/EyecandyBlocks.java',
  'dev/js/mtrheadlight/xiamen/XiamenObjects.java',
];

const re = /Registry\.register\(\s*Registry\.ITEM_GROUP\s*,\s*([A-Za-z0-9_]+)\s*,\s*FabricItemGroup\.builder\(\)\s*\.displayName\([\s\S]*?\)\s*\.icon\(([\s\S]*?)\)\s*\.build\(\)\s*\);/g;

for (const rel of files) {
  const p = path.join(root, rel);
  let txt = fs.readFileSync(p, 'utf8');
  const eol = txt.includes('\r\n') ? '\r\n' : '\n';
  let hits = 0;
  txt = txt.replace(re, (m, id, icon) => {
    hits++;
    // keep the original indentation of the statement
    const indent = m.match(/^[ \t]*/)[0];
    return indent + `LteTabs.create(${id}, ${icon.trim()});`;
  });
  txt = txt.split('import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;' + eol).join('');
  fs.writeFileSync(p, txt);
  console.log(`${rel}: tabs rewritten = ${hits}`);
}
