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
const hex=(x,y)=>{const i=(y*w+x)*ch;return ch>=3?'#'+[px[i],px[i+1],px[i+2]].map(v=>v.toString(16).padStart(2,'0')).join(''):''+px[i];};
for(const spec of process.argv.slice(3)){
  const [mode,idx,a,b]=spec.split(':');
  console.log('== '+spec);
  if(mode==='row'){let s='';for(let x=+a;x<=+b;x+=2)s+=x+':'+hex(x,+idx)+' ';console.log(s);}
  else {let s='';for(let y=+a;y<=+b;y+=2)s+=y+':'+hex(+idx,y)+' ';console.log(s);}
}
