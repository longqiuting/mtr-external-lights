// 用「接近某个颜色」做掩码，输出 ASCII 图，便于量卡片/框线
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
const hex=process.argv[3].replace('#','');const tol=parseInt(process.argv[4]||'12',10);
const step=parseInt(process.argv[5]||'4',10);
const t=[0,2,4].map(i=>parseInt(hex.substr(i,2),16));
const at=(x,y)=>{const i=(y*w+x)*ch;return [px[i],px[i+1],px[i+2]];};
const hit=(x,y)=>{const c=at(x,y);return Math.abs(c[0]-t[0])<=tol&&Math.abs(c[1]-t[1])<=tol&&Math.abs(c[2]-t[2])<=tol;};
console.log('size',w,'x',h,'target #'+hex);
for(let y=0;y<h;y+=step){let s='';for(let x=0;x<w;x+=step){let h2=false;
 for(let dy=0;dy<step&&y+dy<h&&!h2;dy++)for(let dx=0;dx<step&&x+dx<w;dx++)if(hit(x+dx,y+dy)){h2=true;break;}
 s+=h2?'#':'.';}
 console.log(String(y).padStart(3)+'|'+s);}
console.log('    '+Array.from({length:Math.ceil(w/step)},(_,i)=>i*step%100===0?'|':(i%10===0?'+':'-')).join(''));
