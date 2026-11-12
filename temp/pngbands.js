// 区域内的「行带」分析：每一行的亮像素数 + 亮像素的 x 范围
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
const [x0,y0,x1,y1,thrS]=process.argv.slice(3,8);const thr=parseInt(thrS||'150',10);
const at=(x,y)=>{const i=(y*w+x)*ch;return [px[i],px[i+1],px[i+2]];};
const bright=(x,y)=>{const c=at(x,y);return c[0]>thr&&c[1]>thr&&c[2]>thr;};
for(let y=+y0;y<=+y1;y++){let n=0,minx=1e9,maxx=-1;
 for(let x=+x0;x<=+x1;x++)if(bright(x,y)){n++;if(x<minx)minx=x;if(x>maxx)maxx=x;}
 if(n>0)console.log(String(y).padStart(3)+' n='+String(n).padStart(3)+' x='+(minx<1e9?minx+'..'+maxx:'-'));}
