const fs=require('fs'),path=require('path'),zlib=require('zlib');
function decode(file){const buf=fs.readFileSync(file);let pos=8,w=0,h=0,bd=0,ct=0;const idat=[];
while(pos<buf.length){const len=buf.readUInt32BE(pos);pos+=4;const t=buf.toString('ascii',pos,pos+4);pos+=4;const d=buf.subarray(pos,pos+len);pos+=len+4;
if(t==='IHDR'){w=d.readUInt32BE(0);h=d.readUInt32BE(4);bd=d[8];ct=d[9];}else if(t==='IDAT')idat.push(d);else if(t==='IEND')break;}
const raw=zlib.inflateSync(Buffer.concat(idat));const ch={0:1,2:3,3:1,4:2,6:4}[ct];const bpp=ch*(bd/8);const stride=w*bpp;const out=Buffer.alloc(h*stride);let p=0;
for(let y=0;y<h;y++){const f=raw[p++];const line=raw.subarray(p,p+stride);p+=stride;
for(let x=0;x<stride;x++){const a=x>=bpp?out[y*stride+x-bpp]:0;const b=y>0?out[(y-1)*stride+x]:0;const c=(x>=bpp&&y>0)?out[(y-1)*stride+x-bpp]:0;let v=line[x];
if(f===1)v+=a;else if(f===2)v+=b;else if(f===3)v+=(a+b)>>1;else if(f===4){const pp=a+b-c,pa=Math.abs(pp-a),pb=Math.abs(pp-b),pc=Math.abs(pp-c);v+=(pa<=pb&&pa<=pc)?a:(pb<=pc?b:c);}out[y*stride+x]=v&255;}}
return{w,h,bd,ct,ch,bpp,stride,out};}
function stats(file){const img=decode(file);let n=0,op=0,sr=0,sg=0,sb=0;
for(let y=0;y<img.h;y++)for(let x=0;x<img.w;x++){const o=y*img.stride+x*img.bpp;const a=img.ch>=4?img.out[o+3]:(img.ch===2?img.out[o+1]:255);
n++;if(a>=128){op++;if(img.ch>=3){sr+=img.out[o];sg+=img.out[o+1];sb+=img.out[o+2];}else{sr+=img.out[o];sg+=img.out[o];sb+=img.out[o];}}}
return {w:img.w,h:img.h,ratio:op/n,mean:op?[sr/op|0,sg/op|0,sb/op|0]:null};}
const roots=process.argv.slice(2);
function walk(d,out){for(const e of fs.readdirSync(d,{withFileTypes:true})){const p=path.join(d,e.name);if(e.isDirectory())walk(p,out);else if(e.name.endsWith('.png'))out.push(p);}return out;}
for(const r of roots){if(!fs.existsSync(r)){console.log('no dir '+r);continue;}
const files=walk(r,[]).sort();
console.log('==== '+r+' ('+files.length+' png)');
for(const f of files){try{const s=stats(f);console.log('  '+path.relative(r,f).padEnd(52)+String(s.w).padStart(4)+'x'+String(s.h).padStart(4)+'  op='+(100*s.ratio).toFixed(0).padStart(3)+'%  mean='+(s.mean?s.mean.join(','):'-'));}catch(e){console.log('  '+path.relative(r,f)+' ERR');}}}
