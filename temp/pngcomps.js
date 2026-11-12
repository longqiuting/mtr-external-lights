// 连通域找「框」：predicate 由颜色给出；输出外接矩形（按面积排序）
const fs=require('fs'),zlib=require('zlib');
function decode(file){const buf=fs.readFileSync(file);let pos=8,w=0,h=0,bd=0,ct=0;const idat=[];
 while(pos<buf.length){const len=buf.readUInt32BE(pos),type=buf.toString('ascii',pos+4,pos+8),data=buf.subarray(pos+8,pos+8+len);
  if(type==='IHDR'){w=data.readUInt32BE(0);h=data.readUInt32BE(4);bd=data[8];ct=data[9];}else if(type==='IDAT')idat.push(data);else if(type==='IEND')break;pos+=12+len;}
 const raw=zlib.inflateSync(Buffer.concat(idat));const ch={0:1,2:3,3:1,4:2,6:4}[ct];const stride=w*ch;const out=Buffer.alloc(w*h*ch);let prev=Buffer.alloc(stride);
 for(let y=0;y<h;y++){const f=raw[y*(stride+1)];const cur=Buffer.from(raw.subarray(y*(stride+1)+1,(y+1)*(stride+1)));
  for(let i=0;i<stride;i++){const a=i>=ch?cur[i-ch]:0,b=prev[i],c=i>=ch?prev[i-ch]:0;let v=cur[i];
   if(f===1)v=(v+a)&255;else if(f===2)v=(v+b)&255;else if(f===3)v=(v+((a+b)>>1))&255;
   else if(f===4){const p=a+b-c,pa=Math.abs(p-a),pb=Math.abs(p-b),pc=Math.abs(p-c);v=(v+(pa<=pb&&pa<=pc?a:pb<=pc?b:c))&255;}
   cur[i]=v;}
  cur.copy(out,y*stride);prev=cur;}
 return {w,h,ch,px:out};}
const img=decode(process.argv[2]);const {w,h,ch,px}=img;
const mode=process.argv[3];
const at=(x,y)=>{const i=(y*w+x)*ch;return [px[i],px[i+1],px[i+2]];};
let pred;
if(mode==='white') pred=(x,y)=>{const c=at(x,y);return c[0]>120&&c[1]>120&&c[2]>120;};
else if(mode==='card') pred=(x,y)=>{const c=at(x,y);return Math.abs(c[0]-0x22)<=6&&Math.abs(c[1]-0x2e)<=6&&Math.abs(c[2]-0x46)<=6;};
else pred=(x,y)=>{const c=at(x,y);return !(c[0]<6&&c[1]<8&&c[2]<8)&&!(c[0]>110&&c[1]>110&&c[2]>110)===false;};
const seen=new Uint8Array(w*h);const comps=[];
for(let y=0;y<h;y++)for(let x=0;x<w;x++){
 if(seen[y*w+x]||!pred(x,y))continue;
 const st=[[x,y]];seen[y*w+x]=1;let minx=x,maxx=x,miny=y,maxy=y,n=0;
 while(st.length){const [cx,cy]=st.pop();n++;
  if(cx<minx)minx=cx;if(cx>maxx)maxx=cx;if(cy<miny)miny=cy;if(cy>maxy)maxy=cy;
  for(const [dx,dy] of [[1,0],[-1,0],[0,1],[0,-1]]){const nx=cx+dx,ny=cy+dy;
   if(nx<0||ny<0||nx>=w||ny>=h||seen[ny*w+nx])continue;
   if(!pred(nx,ny))continue;seen[ny*w+nx]=1;st.push([nx,ny]);}}
 comps.push({minx,miny,maxx,maxy,n});}
comps.sort((a,b)=>b.n-a.n);
for(const c of comps.slice(0,14))console.log(`x ${c.minx}..${c.maxx}  y ${c.miny}..${c.maxy}  w=${c.maxx-c.minx+1} h=${c.maxy-c.miny+1} px=${c.n}`);
