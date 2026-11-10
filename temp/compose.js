// Composite a PNG over a magenta background and write it out, so transparency is obvious.
const fs=require('fs'),zlib=require('zlib');
function decode(file){const buf=fs.readFileSync(file);let pos=8,w=0,h=0,bd=0,ct=0;const idat=[];
while(pos<buf.length){const len=buf.readUInt32BE(pos);pos+=4;const t=buf.toString('ascii',pos,pos+4);pos+=4;const d=buf.subarray(pos,pos+len);pos+=len+4;
if(t==='IHDR'){w=d.readUInt32BE(0);h=d.readUInt32BE(4);bd=d[8];ct=d[9];}else if(t==='IDAT')idat.push(d);else if(t==='IEND')break;}
const raw=zlib.inflateSync(Buffer.concat(idat));const ch={0:1,2:3,3:1,4:2,6:4}[ct];const bpp=ch*(bd/8);const stride=w*bpp;const out=Buffer.alloc(h*stride);let p=0;
for(let y=0;y<h;y++){const f=raw[p++];const line=raw.subarray(p,p+stride);p+=stride;
for(let x=0;x<stride;x++){const a=x>=bpp?out[y*stride+x-bpp]:0;const b=y>0?out[(y-1)*stride+x]:0;const c=(x>=bpp&&y>0)?out[(y-1)*stride+x-bpp]:0;let v=line[x];
if(f===1)v+=a;else if(f===2)v+=b;else if(f===3)v+=(a+b)>>1;else if(f===4){const pp=a+b-c,pa=Math.abs(pp-a),pb=Math.abs(pp-b),pc=Math.abs(pp-c);v+=(pa<=pb&&pa<=pc)?a:(pb<=pc?b:c);}out[y*stride+x]=v&255;}}
return{w,h,ch,bpp,stride,out};}
function crc32(buf){let c,crc=0xffffffff;for(let n=0;n<buf.length;n++){c=(crc^buf[n])&0xff;for(let k=0;k<8;k++)c=c&1?0xedb88320^(c>>>1):c>>>1;crc=(crc>>>8)^c;}return (crc^0xffffffff)>>>0;}
function chunk(type,data){const len=Buffer.alloc(4);len.writeUInt32BE(data.length);const td=Buffer.concat([Buffer.from(type,'ascii'),data]);const c=Buffer.alloc(4);c.writeUInt32BE(crc32(td));return Buffer.concat([len,td,c]);}
function encode(w,h,rgba){const ihdr=Buffer.alloc(13);ihdr.writeUInt32BE(w,0);ihdr.writeUInt32BE(h,4);ihdr[8]=8;ihdr[9]=6;
const raw=Buffer.alloc(h*(w*4+1));for(let y=0;y<h;y++){raw[y*(w*4+1)]=0;rgba.copy(raw,y*(w*4+1)+1,y*w*4,(y+1)*w*4);}
return Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]),chunk('IHDR',ihdr),chunk('IDAT',zlib.deflateSync(raw)),chunk('IEND',Buffer.alloc(0))]);}
const [inp,outp]=process.argv.slice(2);
const img=decode(inp);
const out=Buffer.alloc(img.w*img.h*4);
for(let y=0;y<img.h;y++)for(let x=0;x<img.w;x++){const o=y*img.stride+x*img.bpp;const d=(y*img.w+x)*4;
 const chk=((x>>4)+(y>>4))&1?60:110; // checkerboard grey
 let r=chk,g=chk,b=chk,a=255;
 if(img.ch>=3){r=img.out[o];g=img.out[o+1];b=img.out[o+2];}else{r=g=b=img.out[o];}
 let al=img.ch>=4?img.out[o+3]:(img.ch===2?img.out[o+1]:255);
 const t=al/255;
 out[d]=Math.round(r*t+chk*(1-t));out[d+1]=Math.round(g*t+chk*(1-t));out[d+2]=Math.round(b*t+chk*(1-t));out[d+3]=255;}
fs.writeFileSync(outp,encode(img.w,img.h,out));
console.log('wrote',outp,img.w+'x'+img.h);
