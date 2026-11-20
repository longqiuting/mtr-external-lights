'use strict';
/*
 * 厦门 PIDS 屏幕贴合探针（离线，不开游戏）+ 黑边检测图
 * =================================================
 * 回答一个问题：**渲染器画的那块 512×235 的 quad，到底有没有盖满方块模型上那块玻璃？**
 * 盖不满的地方露出的是 mtrlte:block/black 的黑面 —— 用户报的「底部一整条黑边」。
 *
 * 做法：
 *   1. 从 PidsXiamenLayout.java 里**读**常量（单一真相，别在这里抄一遍）；
 *   2. 从 pids_xiamen.json 里读模型，把两块 22.5° 斜板的大面算成世界坐标里的四边形；
 *   3. 用和 PidsXiamenRenderer#applyScreenTransform 完全一样的矩阵链算 quad 的四个角；
 *   4. 把 quad 投到玻璃平面上，量四条边的余量（面板 px）—— 正数 = 露黑边；
 *   5. 把「玻璃 = 黑底 + quad 覆盖处贴底图」的整屏预览画出来，并逐行/列检测整行纯黑。
 *
 * 用法：
 *   node tools/pids-xiamen/probe-screen-fit.js            # 只打印 + 断言
 *   node tools/pids-xiamen/probe-screen-fit.js --png      # 另存 preview_screen_fit.png
 */
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const MOD = path.resolve(__dirname, '../..');
const LAYOUT = path.join(MOD, 'src/main/java/dev/js/mtrheadlight/xiamen/pids/PidsXiamenLayout.java');
const MODEL = path.join(MOD, 'src/main/resources/assets/mtrlte/models/block/pids_xiamen.json');
const TEX = path.join(MOD, 'src/main/resources/assets/mtrlte/textures/block/pids_xiamen_metro.png');

// ------------------------------------------------------------------ 读常量
const layoutSrc = fs.readFileSync(LAYOUT, 'utf8');
function readConst(name) {
  const m = layoutSrc.match(new RegExp(name + '\\s*=\\s*(-?[0-9.]+)F?\\s*;'));
  if (!m) throw new Error('PidsXiamenLayout 里找不到 ' + name);
  return parseFloat(m[1]);
}
const SCREEN_W = readConst('SCREEN_WIDTH');
const SCREEN_H = readConst('SCREEN_HEIGHT');
const DIV = readConst('SCREEN_SCALE_DIVISOR');
// 可选：允许 X / Y 分开缩放（修好之后用这两个；没写就退回 DIV）
const DIV_X = /SCREEN_SCALE_DIVISOR_X/.test(layoutSrc) ? readConst('SCREEN_SCALE_DIVISOR_X') : DIV;
const DIV_Y = /SCREEN_SCALE_DIVISOR_Y/.test(layoutSrc) ? readConst('SCREEN_SCALE_DIVISOR_Y') : DIV;
const TILT = readConst('SCREEN_TILT_X');

// --before：把三个常量换回修黑边之前的旧值，用来复现「露黑」并做前后对照
const BEFORE = process.argv.includes('--before');
const ANCHOR_OUT = BEFORE ? 0.4797 : readConst('ANCHOR_OUT');
const ANCHOR_ALONG = BEFORE ? 0.48 : readConst('ANCHOR_ALONG');
const ANCHOR_Y = BEFORE ? 0.8467 : readConst('ANCHOR_Y');
const SCALE_X = BEFORE ? DIV : DIV_X;
const SCALE_Y = BEFORE ? DIV : DIV_Y;

// ------------------------------------------------------------------ 4x4
function mul(a, b) {
  const o = new Array(16).fill(0);
  for (let r = 0; r < 4; r++) for (let c = 0; c < 4; c++) {
    let s = 0;
    for (let k = 0; k < 4; k++) s += a[r * 4 + k] * b[k * 4 + c];
    o[r * 4 + c] = s;
  }
  return o;
}
const tr = (x, y, z) => [1,0,0,x, 0,1,0,y, 0,0,1,z, 0,0,0,1];
function rotY(d) { const a = d*Math.PI/180, c = Math.cos(a), s = Math.sin(a); return [c,0,s,0, 0,1,0,0, -s,0,c,0, 0,0,0,1]; }
function rotZ(d) { const a = d*Math.PI/180, c = Math.cos(a), s = Math.sin(a); return [c,-s,0,0, s,c,0,0, 0,0,1,0, 0,0,0,1]; }
function rotX(d) { const a = d*Math.PI/180, c = Math.cos(a), s = Math.sin(a); return [1,0,0,0, 0,c,-s,0, 0,s,c,0, 0,0,0,1]; }
const scaleM = (x, y, z) => [x,0,0,0, 0,y,0,0, 0,0,z,0, 0,0,0,1];
const apply = (m, p) => [
  m[0]*p[0] + m[1]*p[1] + m[2]*p[2] + m[3],
  m[4]*p[0] + m[5]*p[1] + m[6]*p[2] + m[7],
  m[8]*p[0] + m[9]*p[1] + m[10]*p[2] + m[11]];
const sub = (a, b) => [a[0]-b[0], a[1]-b[1], a[2]-b[2]];
const dot = (a, b) => a[0]*b[0] + a[1]*b[1] + a[2]*b[2];
const cross = (a, b) => [a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0]];
const len = (a) => Math.sqrt(dot(a, a));
const norm = (a) => { const l = len(a); return [a[0]/l, a[1]/l, a[2]/l]; };

// ------------------------------------------------------------------ 模型
const model = JSON.parse(fs.readFileSync(MODEL, 'utf8'));
function rotAxis(p, rot) {
  if (!rot) return p;
  const a = rot.angle*Math.PI/180, c = Math.cos(a), s = Math.sin(a), o = rot.origin;
  let v = sub(p, o);
  if (rot.axis === 'z') v = [v[0]*c - v[1]*s, v[0]*s + v[1]*c, v[2]];
  else if (rot.axis === 'y') v = [v[0]*c + v[2]*s, v[1], -v[0]*s + v[2]*c];
  else v = [v[0], v[1]*c - v[2]*s, v[1]*s + v[2]*c];
  return [v[0]+o[0], v[1]+o[1], v[2]+o[2]];
}
/** 两块「玻璃斜板」：都是 z 轴 22.5°、x 方向 1px 厚、y 0..15、z 0..16 */
const GLASS = model.elements.filter((el) => el.rotation && el.rotation.axis === 'z'
  && Math.abs(el.to[0] - el.from[0] - 1) < 1e-6 && el.to[1] === 15
  && Math.abs(el.from[2]) < 1e-6 && el.to[2] === 16);

const FACINGS = ['north', 'east', 'south', 'west'];
const ROT_OF = { north: 0, east: 90, south: 180, west: 270 };
const AS_ROT = { south: 0, west: 90, north: 180, east: 270 };
const OFF = { north: [0, -1], south: [0, 1], west: [-1, 0], east: [1, 0] };
const CW = { north: 'east', east: 'south', south: 'west', west: 'north' };

/** 变体 y 转：MC 的 blockstate "y" 是**绕格中心、俯视顺时针**（等价于 rotY(-y)） */
const MODEL_SIGN = -1;
function modelToWorld(p, facing) {
  let v = [p[0]/16, p[1]/16, p[2]/16];
  v = apply(tr(-0.5, 0, -0.5), v);
  v = apply(rotY(MODEL_SIGN * ROT_OF[facing]), v);
  return apply(tr(0.5, 0, 0.5), v);
}
function dirToWorld(d, facing) {
  return apply(rotY(MODEL_SIGN * ROT_OF[facing]), d);
}

/** 渲染器那块 quad（完全对应 applyScreenTransform + drawQuad） */
function screenMatrix(facing, cellDx, cellDz) {
  const out = CW[facing];
  const [ox, oz] = OFF[out];
  const [fx, fz] = OFF[facing];
  let m = tr(0.5 + cellDx + ox*ANCHOR_OUT - fx*ANCHOR_ALONG,
             ANCHOR_Y,
             0.5 + cellDz + oz*ANCHOR_OUT - fz*ANCHOR_ALONG);
  m = mul(m, rotY(90 - AS_ROT[facing]));
  m = mul(m, rotZ(180));
  m = mul(m, rotX(TILT));
  return mul(m, scaleM(1/SCALE_X, 1/SCALE_Y, 1/DIV));
}
function quadCorners(facing, cellDx, cellDz) {
  const m = screenMatrix(facing, cellDx, cellDz);
  return [apply(m, [0,0,0]), apply(m, [0,SCREEN_H,0]),
          apply(m, [SCREEN_W,SCREEN_H,0]), apply(m, [SCREEN_W,0,0])];
}

// ------------------------------------------------------------------ 找「这一面画在哪块玻璃上」
function quadNormal(facing) {
  const c = quadCorners(facing, 0, 0);
  const n = norm(cross(sub(c[1], c[0]), sub(c[3], c[0])));   // 左下-左上 × 右上-左上
  // drawQuad 的法线朝观察者（−Z 局部）；这里只要「和玻璃法线同向」，符号统一即可
  const outDir = [OFF[CW[facing]][0], 0, OFF[CW[facing]][1]];
  return dot(n, outDir) < 0 ? n.map((v) => -v) : n;
}

/**
 * 对每个变体 facing：遍历两块玻璃的四个大面，取<b>法线和 quad 法线最平行</b>的那个
 * （= quad 实际贴在哪块玻璃上）。只比 X/Z 的「朝外」分量不够 —— 两块斜板的朝外分量一样，
 * 但一个朝上、一个朝下（V 形），必须连倾角一起比。
 */
function findGlass(facing) {
  const qn = quadNormal(facing);
  let best = null;
  for (const el of GLASS) {
    const [x0, , , ] = el.from;
    const x1 = el.to[0];
    for (const side of ['east', 'west']) {
      const x = side === 'east' ? x1 : x0;
      const pTopPx = rotAxis([x, el.to[1], 0], el.rotation);
      const pBotPx = rotAxis([x, 0, 0], el.rotation);
      const nPx = rotAxis([x + (side === 'east' ? 1 : -1), 0, 0], el.rotation);
      const n0 = rotAxis([x, 0, 0], el.rotation);
      const n = norm([nPx[0]-n0[0], nPx[1]-n0[1], nPx[2]-n0[2]]);
      const nw = norm(dirToWorld(n, facing));
      const align = dot(nw, qn);
      if (!best || align > best.align) best = { el, side, x, pTopPx, pBotPx, nw, align };
    }
  }
  return best;
}

function measure(facing) {
  const g = findGlass(facing);
  const top = modelToWorld(g.pTopPx, facing);
  const bottom = modelToWorld(g.pBotPx, facing);
  const vAxis = norm(sub(bottom, top));                     // 板面「向下」
  const normal = g.nw;
  let uAxis = norm(cross(vAxis, normal));
  const fDir = [OFF[facing][0], 0, OFF[facing][1]];         // 屏长方向 = facing
  if (dot(uAxis, fDir) < 0) uAxis = uAxis.map((v) => -v);   // 让 u 沿 +facing 递增
  const to2D = (p) => {                                       // p 是世界坐标（格）
    const d = sub(p, top);
    return [dot(d, uAxis) * 16, dot(d, vAxis) * 16, dot(d, normal) * 16];
  };
  // 两格玻璃：本格 + partner 格（沿 +facing 各 1 格），每格 4 个角（z=0 / z=16）都投进来
  const shift = [OFF[facing][0], 0, OFF[facing][1]];
  const gl = [];
  for (const k of [0, 1]) {
    const dx = shift[0] * k, dz = shift[2] * k;
    for (const pPx of [g.pTopPx, g.pBotPx]) {
      for (const z of [0, 16]) {
        const w = modelToWorld([pPx[0], pPx[1], z], facing);
        gl.push(to2D([w[0] + dx, w[1], w[2] + dz]));
      }
    }
  }
  const u0 = Math.min(...gl.map((c) => c[0]));
  const gl2 = gl.map((c) => [c[0] - u0, c[1], c[2]]);
  const gL = Math.min(...gl2.map((c) => c[0]));
  const gR = Math.max(...gl2.map((c) => c[0]));
  const pick = (c) => [c[0] - u0, c[1], c[2]];               // 平移 u 原点

  const q = quadCorners(facing, 0, 0).map(to2D).map(pick);
  const us = q.map((c) => c[0]), vs = q.map((c) => c[1]), zs = q.map((c) => c[2]);
  const r = {
    facing, glass: g, uAxis, vAxis, normal,
    glassU0: gL, glassU1: gR,
    quadU0: Math.min(...us), quadU1: Math.max(...us),
    quadV0: Math.min(...vs), quadV1: Math.max(...vs),
    float: zs.reduce((a, b) => a + b, 0) / 4,                 // quad 平面到玻璃平面的距离（模型 px）
  };
  r.gapTop = r.quadV0;                                        // >0 = 顶上露黑
  r.gapBottom = 15 - r.quadV1;                                // >0 = 底下露黑
  r.gapStart = r.quadU0 - r.glassU0;                           // >0 = 屏长起点露黑
  r.gapEnd = r.glassU1 - r.quadU1;                             // >0 = 屏长终点露黑
  return r;
}

// ------------------------------------------------------------------ PNG（最小编码器）
function writePng(file, w, h, rgb) {
  const raw = Buffer.alloc((w * 3 + 1) * h);
  for (let y = 0; y < h; y++) {
    raw[y * (w * 3 + 1)] = 0;
    rgb.copy(raw, y * (w * 3 + 1) + 1, y * w * 3, (y + 1) * w * 3);
  }
  const chunk = (type, data) => {
    const b = Buffer.alloc(8 + data.length + 4);
    b.writeUInt32BE(data.length, 0);
    b.write(type, 4, 'ascii');
    data.copy(b, 8);
    const crcTable = writePng._t || (writePng._t = (() => {
      const t = [];
      for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1; t[n] = c >>> 0; }
      return t;
    })());
    let crc = 0xFFFFFFFF;
    const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
    for (const byte of body) crc = crcTable[(crc ^ byte) & 0xFF] ^ (crc >>> 8);
    b.writeUInt32BE((crc ^ 0xFFFFFFFF) >>> 0, 8 + data.length);
    return b;
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8; ihdr[9] = 2;
  fs.writeFileSync(file, Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]),
    chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0))]));
}
/** 最小 PNG 解码（colorType 6 / 2，8bit） */
function readPng(file) {
  const buf = fs.readFileSync(file);
  let off = 8, w = 0, h = 0, ct = 0;
  const idat = [];
  while (off < buf.length) {
    const l = buf.readUInt32BE(off), t = buf.toString('ascii', off + 4, off + 8);
    const d = buf.subarray(off + 8, off + 8 + l);
    if (t === 'IHDR') { w = d.readUInt32BE(0); h = d.readUInt32BE(4); ct = d[9]; }
    else if (t === 'IDAT') idat.push(d);
    else if (t === 'IEND') break;
    off += 12 + l;
  }
  const bpp = ct === 6 ? 4 : 3, stride = w * bpp;
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const pix = Buffer.alloc(h * stride);
  let prev = Buffer.alloc(stride);
  const paeth = (a, b, c) => { const p = a + b - c, pa = Math.abs(p-a), pb = Math.abs(p-b), pc = Math.abs(p-c);
    return pa <= pb && pa <= pc ? a : (pb <= pc ? b : c); };
  for (let y = 0; y < h; y++) {
    const ro = y * (stride + 1), f = raw[ro], row = pix.subarray(y*stride, (y+1)*stride);
    raw.copy(row, 0, ro + 1, ro + 1 + stride);
    for (let i = 0; i < stride; i++) {
      const a = i >= bpp ? row[i-bpp] : 0, b = prev[i], c = i >= bpp ? prev[i-bpp] : 0;
      if (f === 1) row[i] = (row[i] + a) & 255;
      else if (f === 2) row[i] = (row[i] + b) & 255;
      else if (f === 3) row[i] = (row[i] + ((a + b) >> 1)) & 255;
      else if (f === 4) row[i] = (row[i] + paeth(a, b, c)) & 255;
    }
    prev = row;
  }
  return { w, h, bpp, stride, pix };
}

// ------------------------------------------------------------------ 主
console.log('== 常量（读自 PidsXiamenLayout.java）' + (BEFORE ? '  [--before：用修之前的旧值复现]' : ''));
console.log(`   SCREEN ${SCREEN_W}×${SCREEN_H}px  →  世界 ${(SCREEN_W/SCALE_X).toFixed(4)} × ${(SCREEN_H/SCALE_Y).toFixed(4)} 格`
  + ` = ${(SCREEN_W/SCALE_X*16).toFixed(2)} × ${(SCREEN_H/SCALE_Y*16).toFixed(2)} 模型 px`);
console.log(`   divisor X ${SCALE_X} / Y ${SCALE_Y}     ANCHOR out ${ANCHOR_OUT} along ${ANCHOR_ALONG} y ${ANCHOR_Y}   tilt ${TILT}°`);
console.log(`   玻璃（模型）= ${GLASS.length} 块斜板 × 板长 15px；两格合起来 32 × 15 模型 px`
  + `（宽高比 ${(32/15).toFixed(4)}）；底图 512×235（${(512/235).toFixed(4)}）`);

console.log('\n== 贴合测量：正数 = 那一侧露黑（面板 px）');
let worst = 0, worstWhat = '';
const rows = [];
for (const facing of ['south', 'west']) {                      // 只有这两种能存数据
  const m = measure(facing);
  console.log(`   facing=${facing.padEnd(5)} 玻璃=${m.glass.side}`
    + `  上 ${m.gapTop.toFixed(3).padStart(7)}  下 ${m.gapBottom.toFixed(3).padStart(7)}`
    + `  起 ${m.gapStart.toFixed(3).padStart(7)}  末 ${m.gapEnd.toFixed(3).padStart(7)}`
    + `   浮空 ${m.float.toFixed(3)}px   玻璃 u ${m.glassU0.toFixed(2)}..${m.glassU1.toFixed(2)}`
    + `  quad u ${m.quadU0.toFixed(2)}..${m.quadU1.toFixed(2)}  v ${m.quadV0.toFixed(2)}..${m.quadV1.toFixed(2)}`);
  for (const [k, v] of [['上', m.gapTop], ['下', m.gapBottom], ['起', m.gapStart], ['末', m.gapEnd]]) {
    if (v > worst) { worst = v; worstWhat = `${facing} ${k}`; }
  }
  rows.push(m);
}
const TOL = 0.25;   // 面板 px：1/4 像素以内算盖满（GPU 上亚像素的边界无所谓）
const fitOk = rows.every((m) => m.gapTop <= TOL && m.gapBottom <= TOL
  && m.gapStart <= TOL && m.gapEnd <= TOL);

// 宽高比：quad 的世界宽高 vs 底图像素宽高
const quadAspect = (SCREEN_W / SCALE_X) / (SCREEN_H / SCALE_Y);
const bitmapAspect = SCREEN_W / SCREEN_H;
const aspectErr = Math.abs(quadAspect / bitmapAspect - 1);
// quad 的世界宽高 vs 玻璃的宽高
const glassAspect = 32 / 15;
const glassErr = Math.abs(quadAspect / glassAspect - 1);
// 各向异性 = quad 的 px/格 在 X / Y 上不一样 —— 必须**正好**等于底图和玻璃面的宽高比之差
const aniso = (SCREEN_W / SCALE_X) / (SCREEN_H / SCALE_Y) / bitmapAspect - 1;
const inherent = glassAspect / bitmapAspect - 1;

// ------------------------------------------------------------------ 底图四周扫纯黑 / 透明
const tex0 = readPng(TEX);
let darkRows = 0, darkCols = 0;
for (let y = 0; y < tex0.h; y++) {
  let all = true;
  for (let x = 0; x < tex0.w && all; x++) {
    const i = y * tex0.stride + x * tex0.bpp;
    const a = tex0.bpp === 4 ? tex0.pix[i + 3] : 255;
    if (!(a < 8 || (tex0.pix[i] < 8 && tex0.pix[i+1] < 8 && tex0.pix[i+2] < 8))) all = false;
  }
  if (all) darkRows++;
}
for (let x = 0; x < tex0.w; x++) {
  let all = true;
  for (let y = 0; y < tex0.h && all; y++) {
    const i = y * tex0.stride + x * tex0.bpp;
    const a = tex0.bpp === 4 ? tex0.pix[i + 3] : 255;
    if (!(a < 8 || (tex0.pix[i] < 8 && tex0.pix[i+1] < 8 && tex0.pix[i+2] < 8))) all = false;
  }
  if (all) darkCols++;
}

console.log('\n== 断言');
console.log(`   [A1] quad 尺寸 == 玻璃面 32×15：偏差 X ${(Math.abs(SCREEN_W/SCALE_X*16 - 32)*1000).toFixed(1)}‰`
  + ` / Y ${(Math.abs(SCREEN_H/SCALE_Y*16 - 15)*1000).toFixed(1)}‰ 型号 px`);
console.log(`   [A2] 四边盖满（余量 ≤ ${TOL}px）：最大 ${worst.toFixed(3)}px @ ${worstWhat || '-'}   ${fitOk ? '通过' : '失败！（露黑）'}`);
console.log(`   [A3] quad 宽高比 == 玻璃面 ${glassAspect.toFixed(4)}：偏差 ${(glassErr*100).toFixed(3)}%   ${glassErr < 0.001 ? '通过' : '失败！'}`);
console.log(`   [A4] 各向异性 ${(aniso*100).toFixed(2)}% == 底图/玻璃宽高比之差 ${(inherent*100).toFixed(2)}%`
  + `（差 ${(Math.abs(aniso - inherent)*100).toFixed(3)}%）  ${Math.abs(aniso - inherent) < 0.001 && Math.abs(aniso) < 0.03 ? '通过（无额外形变，且 ≤3%）' : '失败！'}`);
console.log(`   [A5] 底图四周无整行/整列纯黑或透明：黑行 ${darkRows} / 黑列 ${darkCols}   ${darkRows === 0 && darkCols === 0 ? '通过（黑边不是底图带来的）' : '失败！（底图自带黑边）'}`);
console.log('   注：quad 宽高比 vs **底图像素**宽高比必然差 ' + (Math.abs(bitmapAspect/glassAspect - 1)*100).toFixed(2)
  + '%（玻璃 2.1333 / 底图 2.1787）—— 想同时满足「贴着玻璃不留缝」和「<1%」'
  + ' 只能重画 512×240 的底图；这里两条都钉在 A3/A4 上。');

// quad 的 UV 是 0..1 ↔ 整张 512×235：px → 格子 的两种映射都要随时能从常量读出来
console.log(`   [A6] UV ↔ 像素：u = x/512, v = y/235；px→格 X 1/${SCALE_X.toFixed(2)}、Y 1/${SCALE_Y.toFixed(2)}，`
  + `即 1 底图像素 = X ${(1/SCALE_X).toFixed(6)} 格 / Y ${(1/SCALE_Y).toFixed(6)} 格`);

// ------------------------------------------------------------------ 整屏预览 + 黑边检测图
if (process.argv.includes('--png')) {
  const tex = readPng(TEX);
  const S = 16;                       // 设备 px / 模型 px：玻璃 32×15 模型 px → 512×240 的整屏图
  const W = 32 * S, H = 15 * S;
  const rgb = Buffer.alloc(W * H * 3, 0);        // 黑底 = 没盖住的玻璃（mtrlte:block/black）
  const m = rows[0];
  for (let py = 0; py < H; py++) {
    for (let px = 0; px < W; px++) {
      const u = (px + 0.5) / S, v = (py + 0.5) / S;     // 模型 px
      if (u < m.quadU0 || u > m.quadU1 || v < m.quadV0 || v > m.quadV1) continue;   // 露黑
      const tu = (u - m.quadU0) / (m.quadU1 - m.quadU0);
      const tv = (v - m.quadV0) / (m.quadV1 - m.quadV0);
      const sx = Math.min(tex.w - 1, Math.max(0, Math.round(tu * (tex.w - 1))));
      const sy = Math.min(tex.h - 1, Math.max(0, Math.round(tv * (tex.h - 1))));
      const si = sy * tex.stride + sx * tex.bpp;
      const i = (py * W + px) * 3;
      rgb[i] = tex.pix[si]; rgb[i+1] = tex.pix[si+1]; rgb[i+2] = tex.pix[si+2];
    }
  }
  // 黑边检测：整行 / 整列是否全黑（阈值很严：<8 算黑）
  const isBlack = (i) => rgb[i] < 8 && rgb[i+1] < 8 && rgb[i+2] < 8;
  const rowsBlack = [], colsBlack = [];
  for (let y = 0; y < H; y++) { let all = true;
    for (let x = 0; x < W && all; x++) if (!isBlack((y*W+x)*3)) all = false;
    if (all) rowsBlack.push(y); }
  for (let x = 0; x < W; x++) { let all = true;
    for (let y = 0; y < H && all; y++) if (!isBlack((y*W+x)*3)) all = false;
    if (all) colsBlack.push(x); }
  const out = process.argv[process.argv.indexOf('--png') + 1] || path.join(MOD, 'temp/preview_screen_fit.png');
  writePng(out, W, H, rgb);
  const top = rowsBlack.filter((y) => y < H / 2).length;
  const bottom = rowsBlack.filter((y) => y >= H / 2).length;
  const left = colsBlack.filter((x) => x < W / 2).length;
  const right = colsBlack.filter((x) => x >= W / 2).length;
  console.log(`\n== 整屏预览（玻璃 32×15 模型 px → ${W}×${H}，黑底 + quad 覆盖处贴底图；没盖住的地方就是屏上的黑边）`);
  console.log(`   ${out}`);
  console.log(`   整行纯黑 ${rowsBlack.length} 行（上 ${top} / 下 ${bottom}，共 ${(rowsBlack.length / S).toFixed(2)} 模型 px）`
    + `  整列纯黑 ${colsBlack.length} 列（左 ${left} / 右 ${right}，共 ${(colsBlack.length / S).toFixed(2)} 模型 px）`);
  const bandOk = rowsBlack.length === 0 && colsBlack.length === 0;
  console.log(`   [A7] 预览里没有整行/整列纯黑（= 四条边都不露黑）  ${bandOk ? '通过' : '失败！'}`);
  if (!bandOk || !fitOk) process.exitCode = 1;
} else if (!fitOk) {
  process.exitCode = 1;
}
