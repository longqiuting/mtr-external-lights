const fs=require('fs'),path=require('path');
const root=process.argv[2];
function walk(d,o){for(const e of fs.readdirSync(d,{withFileTypes:true})){const p=path.join(d,e.name);if(e.isDirectory())walk(p,o);else if(e.name.endsWith('.java'))o.push(p);}return o;}
const RULES=[
 {test:/\bResource\b(?![A-Za-z])/, imp:'import net.minecraft.resource.Resource;'},
 {test:/\bMtrCompat\./, imp:'import dev.js.mtrheadlight.MtrCompat;'},
 {test:/\bWorldTicks\./, imp:'import dev.js.mtrheadlight.compat.WorldTicks;'},
 {test:/\bMaterial\./, imp:'import net.minecraft.block.Material;'},
];
for(const file of walk(root,[])){
  if(file.includes('/compat/')||file.includes('clientgui'))continue;
  let txt=fs.readFileSync(file,'utf8');
  const eol=txt.includes('\r\n')?'\r\n':'\n';
  let lines=txt.split(/\r?\n/);
  const body=txt.replace(/^import .*$/gm,'');
  let changed=false;
  for(const r of RULES){
    if(!r.test.test(body))continue;
    if(lines.includes(r.imp))continue;
    // already covered by a wildcard/existing import of the same simple name?
    const simple=r.imp.replace(/^import /,'').replace(/;$/,'').split('.').pop();
    if(lines.some(l=>l.startsWith('import ')&&l.endsWith('.'+simple+';')))continue;
    let at=-1;
    for(let i=0;i<lines.length;i++){if(/^import (net\.minecraft\.|dev\.js\.mtrheadlight\.)/.test(lines[i]))at=i;}
    if(at<0)for(let i=0;i<lines.length;i++){if(/^import /.test(lines[i]))at=i;}
    if(at<0)for(let i=0;i<lines.length;i++){if(/^package /.test(lines[i]))at=i;}
    lines.splice(at+1,0,r.imp);changed=true;
  }
  if(changed){fs.writeFileSync(file,lines.join(eol));console.log(path.relative(root,file));}
}
