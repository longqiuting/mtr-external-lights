#!/usr/bin/env node
/*
 * 厦门 PIDS（地铁）顶部编组条的像素扫描
 * =====================================
 *
 * 背景：`pids_xiamen_metro.png`（512×235）顶部那条编组 —— 青色带 + 深蓝「车头」抽象形状 +
 * 一排 1车..6车 卡片（绿座椅图标 + 白字 + 雪花）—— **是烘死在底图里的静态像素**。
 * 现在要把它换成按 MTR 实际车厢数画的动态叠加层（`PidsXiamenConsist`），
 * 这个脚本负责量出叠加层需要的三样东西，结果直接抄进那个类的常量：
 *
 *   1. **编组带矩形**（= 叠加层四边形贴在底图上的位置 / 裁背景的源矩形）
 *   2. **擦除区**（烘死的卡片 + 车号文字的墨迹包围盒）—— 叠加层要先把这一块填成
 *      带内的纯深蓝，否则 2 节编组时右半边会露出烘死的 3..6 车
 *   3. **卡片几何**（宽 / 高 / 间隙 / 行带）—— 用来校核 `CARD_W` / `CARD_H` / `GAP`，
 *      以及 `--emit` 要切的卡片矩形（第 1 张卡的真实包围盒）
 *   4. **带外底色**（叠加层里 `x > bandWidth` 那段要铺的面板底色）—— 在底带矩形
 *      左边紧邻的 3 列 / 正下方 3 行上取众数（同一行、矩形外面），写进
 *      `PidsXiamenConsist#PANEL_BG`；脚本会顺手核对 Java 常量，不一致直接提示。
 *      底带矩形**里面**不能采样 —— 那里是青色光晕 + 深蓝带体。
 *   5. **带体右缘**（`consist_band.png` 里深蓝那一截的最后一列）—— 素材右侧那圈
 *      青色余边（实测 20 列）不算带体，绘制时**不画**：带子一收短，它就会变成
 *      「底带后面多出一块青色矩形」。三段（左 / 中 / 收边）加起来正好等于带体宽度。
 *
 * 判定用的三个色类（都按底图实际像素定，换底图就得重量）：
 *   navy  深蓝带底  b > 150 && r < 60 && g < 60
 *   cyan  带边框     b > 150 && g > 140 && r < 100      ← 注意：卡片上的白字 r ≥ 110，不算 cyan
 *   ink   卡片/文字 其余（白卡、绿座椅、白字）
 *
 *   node v1.20.1/tools/pids-xiamen/scan-consist-strip.js --emit   # 顺手切出卡片 / 雪花 png
 *
 * --emit 写两张**原尺寸、不缩放、不压缩**的切片（就是底图那块像素的逐字节拷贝）：
 *   gui/pids/consist_card.png   第 1 张卡片    实测 41 × 20（x 210..250 / y 3..22）
 *   gui/pids/consist_flake.png  标签区第 1 颗雪花  实测 7 × 7（x 228..234 / y 23..29）
 * 「车」字**不切** —— 那是文字，游戏里走 MC 的 TextRenderer。
 */
'use strict';

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const HERE = __dirname;
const VERSION_DIR = path.resolve(HERE, '..', '..');
const PANEL = path.join(VERSION_DIR,
  'src/main/resources/assets/mtrlte/textures/block/pids_xiamen_metro.png');

// ---------------------------------------------------------------- PNG 解码（无依赖）

function paeth(a, b, c) {
  const p = a + b - c;
  const pa = Math.abs(p - a);
  const pb = Math.abs(p - b);
  const pc = Math.abs(p - c);
  return pa <= pb && pa <= pc ? a : (pb <= pc ? b : c);
}

function decodePng(file) {
  const buf = fs.readFileSync(file);
  if (buf.readUInt32BE(0) !== 0x89504e47) throw new Error('not a png: ' + file);
  let off = 8;
  let width = 0, height = 0, depth = 0, colorType = 0, interlace = 0;
  const idat = [];
  while (off < buf.length) {
    const len = buf.readUInt32BE(off);
    const type = buf.toString('ascii', off + 4, off + 8);
    const data = buf.subarray(off + 8, off + 8 + len);
    if (type === 'IHDR') {
      width = data.readUInt32BE(0);
      height = data.readUInt32BE(4);
      depth = data[8];
      colorType = data[9];
      interlace = data[12];
    } else if (type === 'IDAT') {
      idat.push(data);
    } else if (type === 'IEND') {
      break;
    }
    off += 12 + len;
  }
  if (depth !== 8 || interlace !== 0 || (colorType !== 2 && colorType !== 6)) {
    throw new Error(`unsupported png: depth=${depth} colorType=${colorType}`);
  }
  const bpp = colorType === 6 ? 4 : 3;
  const stride = width * bpp;
  const raw = zlib.inflateSync(Buffer.concat(idat));
  const pix = Buffer.alloc(height * stride);
  let prev = Buffer.alloc(stride);
  for (let y = 0; y < height; y++) {
    const rowOff = y * (stride + 1);
    const filter = raw[rowOff];
    const row = pix.subarray(y * stride, (y + 1) * stride);
    raw.copy(row, 0, rowOff + 1, rowOff + 1 + stride);
    for (let i = 0; i < stride; i++) {
      const a = i >= bpp ? row[i - bpp] : 0;
      const b = prev[i];
      const c = i >= bpp ? prev[i - bpp] : 0;
      switch (filter) {
        case 0: break;
        case 1: row[i] = (row[i] + a) & 0xff; break;
        case 2: row[i] = (row[i] + b) & 0xff; break;
        case 3: row[i] = (row[i] + ((a + b) >> 1)) & 0xff; break;
        case 4: row[i] = (row[i] + paeth(a, b, c)) & 0xff; break;
        default: throw new Error('bad png filter ' + filter);
      }
    }
    prev = row;
  }
  return {
    width, height, bpp, pix, stride,
    rgb(x, y) {
      const i = y * stride + x * bpp;
      return [pix[i], pix[i + 1], pix[i + 2], bpp === 4 ? pix[i + 3] : 255];
    },
  };
}

// ---------------------------------------------------------------- 扫描

const png = decodePng(PANEL);
const W = png.width, H = png.height;

const isNavy = ([r, g, b]) => b > 150 && r < 60 && g < 60;
const isCyan = ([r, g, b]) => b > 150 && g > 140 && r < 100;
const isCard = ([r, g, b]) => r > 185 && g > 185 && b > 185;
const isGreen = ([r, g, b]) => g > 110 && r < 160 && g > r + 30 && b < 210;
const isInk = (c) => !isNavy(c) && !isCyan(c);

// 面板底色（左下角那块大片蓝）
const panelBg = png.rgb(3, H - 3);
const dist = (a, b) => Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) + Math.abs(a[2] - b[2]);

// ---- 1) 编组带 bbox：右上角区域里「与面板底色不同」的像素
const SX0 = 170;              // 左栏框线到 x=169，编组带从 179 起
let sx0 = 1e9, sy0 = 1e9, sx1 = -1, sy1 = -1;
for (let y = 0; y <= Math.min(60, H - 1); y++) {
  for (let x = SX0; x < W; x++) {
    if (dist(png.rgb(x, y), panelBg) > 40) {
      if (x < sx0) sx0 = x;
      if (x > sx1) sx1 = x;
      if (y < sy0) sy0 = y;
      if (y > sy1) sy1 = y;
    }
  }
}

// ---- 2) 卡片行带：带内「白卡 + 绿座椅」像素够多的行（= 烘死的卡片那 20 行）
const stripW = sx1 - sx0 + 1;
const rowProf = [];
for (let y = sy0; y <= sy1; y++) {
  let card = 0;
  for (let x = sx0; x <= sx1; x++) {
    const c = png.rgb(x, y);
    if (isCard(c) || isGreen(c)) card++;
  }
  rowProf.push({ y, card });
}
const cardRows = rowProf.filter(r => r.card > stripW * 0.10).map(r => r.y);
const cardBandY0 = Math.min(...cardRows);
const cardBandY1 = Math.max(...cardRows);

// ---- 3) 卡片列：卡片行带内的列投影 → 逐张卡片的 x 区间
const cardCols = [];
for (let x = sx0; x <= sx1; x++) {
  let n = 0;
  for (let y = cardBandY0; y <= cardBandY1; y++) {
    const c = png.rgb(x, y);
    if (isCard(c) || isGreen(c)) n++;
  }
  cardCols.push(n);
}
const runs = [];
let start = null;
for (let i = 0; i < cardCols.length; i++) {
  if (cardCols[i] >= 6) {
    if (start === null) start = i + sx0;
  } else if (start !== null) {
    runs.push([start, i - 1 + sx0]);
    start = null;
  }
}
if (start !== null) runs.push([start, sx1]);
// 合并被数字 / 雪花打断的相邻列（间隙 ≤ 2px）
const cards = [];
for (const r of runs) {
  const last = cards[cards.length - 1];
  if (last && r[0] - last[1] <= 2) last[1] = r[1];
  else cards.push([r[0], r[1]]);
}
const cardsX0 = cards[0][0];
const cardsX1 = cards[cards.length - 1][1];

// ---- 4) 文字行带：卡片行带**下方**、卡片 x 区间内还有墨迹的行（= 烘死的「N车 ❄」）
const textRows = [];
for (let y = cardBandY1 + 1; y <= sy1; y++) {
  let ink = 0;
  for (let x = cardsX0; x <= cardsX1; x++) {
    if (isInk(png.rgb(x, y))) ink++;
  }
  if (ink > 0) textRows.push(y);
}

// ---- 5) 底带素材 consist_band.png：尺寸 / 不透明 / 三段宽度（左斜车头 / 中纯色 / 右收边）
//          + 带体右缘（素材右侧那圈青色余边不算带体，绘制时不画）
const BAND = path.join(VERSION_DIR,
  'src/main/resources/assets/mtrlte/textures/gui/pids/consist_band.png');
let bandReport = null;
if (fs.existsSync(BAND)) {
  const band = decodePng(BAND);
  const isCyan = ([r, g, b]) => r < 110 && g > 150 && b > 150;
  const isNavy = ([r, g, b]) => b > 150 && r < 60 && g < 60;
  let maxFirst = 0;
  let minLast = band.width - 1;
  let opaque = true;
  for (let y = 2; y < band.height - 2; y++) {
    for (let x = 0; x < band.width; x++) {
      if (band.rgb(x, y)[3] < 250) opaque = false;
      if (!isCyan(band.rgb(x, y))) {
        maxFirst = Math.max(maxFirst, x);
        break;
      }
    }
    for (let x = band.width - 1; x >= 0; x--) {
      if (!isCyan(band.rgb(x, y))) {
        minLast = Math.min(minLast, x);
        break;
      }
    }
  }
  const leftW = Math.min(band.width - 2, maxFirst + 3);
  const cyanRightW = Math.max(2, band.width - minLast);
  // 带体右缘：逐行找「深蓝带体」的最右列，取最大值（= PidsXiamenConsist#bodyRight）
  let bodyRight = -1;
  for (let y = 3; y < band.height - 2; y++) {
    for (let x = band.width - 1; x >= 0; x--) {
      if (isNavy(band.rgb(x, y))) { bodyRight = Math.max(bodyRight, x); break; }
    }
  }
  if (bodyRight < 0) bodyRight = band.width - 1;
  // 收边：带体右缘那条「压暗的边线」有几列（蓝通道比带内纯色暗 3 以上）
  const midY = band.height >> 1;
  const interiorB = band.rgb(Math.max(0, bodyRight - 20), midY)[2];
  let edgeRun = 1;
  for (let x = bodyRight - 1; x >= 0 && edgeRun < 8; x--) {
    if (Math.abs(band.rgb(x, midY)[2] - interiorB) <= 3) break;
    edgeRun++;
  }
  const capW = Math.max(1, Math.min(Math.min(8, bodyRight - leftW), edgeRun + 1));
  const bodyW = bodyRight + 1;
  // 和底图那块逐列比对：中段（左上角青色 + 深蓝）能对上的列数
  let matchingColumns = 0;
  for (let x = 0; x < band.width; x++) {
    let same = true;
    for (let y = 0; y < Math.min(band.height, H); y++) {
      const a = band.rgb(x, y);
      const b = png.rgb(sx0 + x, sy0 + y);
      if (Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) + Math.abs(a[2] - b[2]) > 12) {
        same = false;
        break;
      }
    }
    if (same) matchingColumns++;
  }
  bandReport = {
    width: band.width, height: band.height, opaque,
    leftW, midW: bodyW - leftW - capW, capW, bodyW, cyanRightW,
    cyanRightFrom: bodyW, matchingColumns,
  };
}

// ---- 6) 带外底色：叠加层里 x > bandWidth 那段要铺的色 —— 必须和底图逐字节一致
//
// 采样点（同一行上取众数，两种取法互相交叉验证）：
//   ① 编组带矩形**左边紧邻**的 3 列（x sx0−3..sx0−1）× 全部 33 行 —— 同一行、矩形外面；
//   ② 编组带矩形**正下方** 3 行（y sy1+1..sy1+3）× 编组带全宽。
// 底带矩形**里面**不能采样：那里是青色光晕 + 深蓝带体（(16,224,255)…(0,0,212)），不是底色。
const bgSamples = [];
for (let y = sy0; y <= sy1; y++) {
  for (let x = Math.max(0, sx0 - 3); x < sx0; x++) {
    bgSamples.push(png.rgb(x, y).slice(0, 3).join(','));
  }
}
for (let y = sy1 + 1; y <= Math.min(H - 1, sy1 + 3); y++) {
  for (let x = sx0; x <= sx1; x++) {
    bgSamples.push(png.rgb(x, y).slice(0, 3).join(','));
  }
}
const bgCounts = new Map();
for (const c of bgSamples) bgCounts.set(c, (bgCounts.get(c) || 0) + 1);
const bgTop = [...bgCounts.entries()].sort((a, b) => b[1] - a[1])[0];
const bgRgb = bgTop[0].split(',').map(Number);
const bgHex = (0xFF000000 | (bgRgb[0] << 16) | (bgRgb[1] << 8) | bgRgb[2]) >>> 0;
const bgPure = bgTop[1] === bgSamples.length;
// 核对 Java 常量（改了绘制逻辑就得让两边一致）
const CONSIST_JAVA = path.join(VERSION_DIR,
  'src/main/java/dev/js/mtrheadlight/xiamen/pids/PidsXiamenConsist.java');
let javaPanelBg = null;
try {
  const src = fs.readFileSync(CONSIST_JAVA, 'utf8');
  const m = src.match(/PANEL_BG\s*=\s*0x([0-9A-Fa-f]{8})/);
  if (m) javaPanelBg = parseInt(m[1], 16) >>> 0;
} catch (err) {
  javaPanelBg = null;
}
const bgMatchesJava = javaPanelBg === bgHex;

const cardW = cards.length
  ? cards.reduce((s, c) => s + (c[1] - c[0] + 1), 0) / cards.length : 0;
const gaps = [];
for (let i = 1; i < cards.length; i++) gaps.push(cards[i][0] - cards[i - 1][1] - 1);

// ---------------------------------------------------------------- 6) 切片矩形

const NAVY = png.rgb(cards[0][1] + 2, cardBandY0 + 5);   // 第 1、2 张卡片之间的空档（纯深蓝 ≈ 0,0,228）
const luma = ([r, g, b]) => 0.299 * r + 0.587 * g + 0.114 * b;
const navyLuma = luma(NAVY);
/**
 * 标签区的墨迹判定：比深蓝底亮 20 以上（带内暗部 luma≈22、雪花顶端那点抗锯齿≈56、
 * 白字≈180）—— 专门用来把「车」/ 雪花切成几段，不参与卡片判定。
 */
const isMark = (c) => luma(c) > navyLuma + 20;
const isCardPixel = (c) => isCard(c) || isGreen(c);

/** 在 [xa..xb] × [ya..yb] 里找 isCardPixel 的包围盒（卡片本体）。 */
function cardBBox(xa, xb, ya, yb) {
  let x0 = 1e9, x1 = -1, y0 = 1e9, y1 = -1;
  for (let y = ya; y <= yb; y++) {
    for (let x = xa; x <= xb; x++) {
      if (!isCardPixel(png.rgb(x, y))) continue;
      if (x < x0) x0 = x;
      if (x > x1) x1 = x;
      if (y < y0) y0 = y;
      if (y > y1) y1 = y;
    }
  }
  return x1 < x0 ? null : { x0, y0, w: x1 - x0 + 1, h: y1 - y0 + 1 };
}

/** 第 1 张卡片：列区间取第一段卡片列，行区间取卡片行带（= 那 20 行）。 */
const cardRect = cardBBox(cards[0][0] - 2, cards[0][1] + 2, cardBandY0, cardBandY1);

/**
 * 雪花：第 1 张卡片下面那行标签里，最后一次「列游程」就是雪花
 * （第一次「N车」，第二次雪花）—— 不用写死像素坐标，换底图也能量对。
 */
function flakeRect(card) {
  const ya = cardBandY1 + 1;                          // 标签区第一行（= 23）
  const yb = Math.min(sy1 - 2, cardBandY1 + 8);       // 别把带底那条亮边算进去
  const cols = [];
  for (let x = card.x0; x <= card.x0 + card.w - 1; x++) {
    let ink = 0;
    for (let y = ya; y <= yb; y++) if (isMark(png.rgb(x, y))) ink++;
    cols.push(ink >= 2);                              // 至少 2 行有墨迹才算「一列字」
  }
  const runs = [];
  let start = null;
  for (let i = 0; i < cols.length; i++) {
    if (cols[i] && start === null) start = i;
    else if (!cols[i] && start !== null) { runs.push([start, i - 1]); start = null; }
  }
  if (start !== null) runs.push([start, cols.length - 1]);
  if (runs.length < 2) return null;                   // 至少「车」+「雪花」
  const [a, b] = runs[runs.length - 1];
  let x0 = 1e9, x1 = -1, y0 = 1e9, y1 = -1;
  for (let y = ya; y <= yb; y++) {
    for (let x = card.x0 + a; x <= card.x0 + b; x++) {
      if (!isMark(png.rgb(x, y))) continue;
      if (x < x0) x0 = x;
      if (x > x1) x1 = x;
      if (y < y0) y0 = y;
      if (y > y1) y1 = y;
    }
  }
  return x1 < x0 ? null : { x0, y0, w: x1 - x0 + 1, h: y1 - y0 + 1 };
}
const flakeRectOut = flakeRect({ x0: cards[0][0], w: cards[0][1] - cards[0][0] + 1 });

// ---------------------------------------------------------------- 7) 写切片 png

function crc32(buf) {
  let c, crc = 0xffffffff;
  for (let n = 0; n < buf.length; n++) {
    c = (crc ^ buf[n]) & 0xff;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    crc = (crc >>> 8) ^ c;
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function pngChunk(type, data) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
  const t = Buffer.from(type, 'ascii');
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(Buffer.concat([t, data])));
  return Buffer.concat([len, t, data, crc]);
}

/** 逐像素原样写出（不做任何缩放 / 量化 / 抖动），alpha 一律 255。 */
function encodeSlice(rect) {
  const w = rect.w, h = rect.h;
  const raw = Buffer.alloc(h * (w * 4 + 1));
  for (let y = 0; y < h; y++) {
    const rowOff = y * (w * 4 + 1);
    raw[rowOff] = 0;                                  // filter: none
    for (let x = 0; x < w; x++) {
      const c = png.rgb(rect.x0 + x, rect.y0 + y);
      const i = rowOff + 1 + x * 4;
      raw[i] = c[0]; raw[i + 1] = c[1]; raw[i + 2] = c[2]; raw[i + 3] = 255;
    }
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    pngChunk('IHDR', ihdr),
    pngChunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    pngChunk('IEND', Buffer.alloc(0)),
  ]);
}

const GUI_DIR = path.join(VERSION_DIR,
  'src/main/resources/assets/mtrlte/textures/gui/pids');
const emitted = [];
if (process.argv.includes('--emit')) {
  if (!cardRect) throw new Error('没切到卡片');
  if (!flakeRectOut) throw new Error('没切到雪花');
  for (const [name, rect] of [['consist_card.png', cardRect], ['consist_flake.png', flakeRectOut]]) {
    const file = path.join(GUI_DIR, name);
    fs.writeFileSync(file, encodeSlice(rect));
    emitted.push({ file, rect });
  }
}

const report = {
  panel: { width: W, height: H, background: panelBg.slice(0, 3) },
  strip: {
    x0: sx0, y0: sy0, x1: sx1, y1: sy1,
    w: sx1 - sx0 + 1, h: sy1 - sy0 + 1,
    normalized: {
      x0: +(sx0 / W).toFixed(4), x1: +((sx1 + 1) / W).toFixed(4),
      y0: +(sy0 / H).toFixed(4), y1: +((sy1 + 1) / H).toFixed(4),
    },
  },
  bakedCards: { x0: cardsX0, x1: cardsX1, y0: cardBandY0, y1: cardBandY1, rects: cards },
  bakedCardText: textRows.length ? { y0: Math.min(...textRows), y1: Math.max(...textRows) } : null,
  cardWidthAvg: +cardW.toFixed(2),
  cardPitchAvg: cards.length > 1
    ? +((cards[cards.length - 1][0] - cards[0][0]) / (cards.length - 1)).toFixed(2) : null,
  gaps,
  gapRatio: +((gaps.reduce((s, g) => s + g, 0) / Math.max(1, gaps.length)) / cardW).toFixed(3),
  slices: { card: cardRect, flake: flakeRectOut },
  band: bandReport,
  panelBg: {
    rgb: bgRgb, hex: '0x' + bgHex.toString(16).toUpperCase().padStart(8, '0'),
    samples: bgSamples.length, uniform: bgPure,
    javaConstant: javaPanelBg === null ? null
      : '0x' + javaPanelBg.toString(16).toUpperCase().padStart(8, '0'),
    matchesJava: bgMatchesJava,
  },
};

if (process.argv.includes('--json')) {
  console.log(JSON.stringify(report, null, 2));
  process.exit(0);
}

const rgb = a => `(${a.join(',')})`;
console.log('=== 厦门 PIDS 地铁底图：顶部编组条 ===');
console.log(`面板 ${W}×${H}，底色 ${rgb(panelBg)}`);
console.log('');
console.log(`[1] 编组带  x ${sx0}..${sx1}  y ${sy0}..${sy1}   (${sx1 - sx0 + 1} × ${sy1 - sy0 + 1})`);
console.log(`    归一化  x ${(sx0 / W).toFixed(4)}..${((sx1 + 1) / W).toFixed(4)}` +
  `   y ${(sy0 / H).toFixed(4)}..${((sy1 + 1) / H).toFixed(4)}`);
console.log(`[2] 底图里烘死的：卡片 x ${cardsX0}..${cardsX1} y ${cardBandY0}..${cardBandY1}` +
  `（${cards.length} 张，被 consist_band.png 整片盖住，不用擦）` +
  (textRows.length ? `，车号 y ${Math.min(...textRows)}..${Math.max(...textRows)}` : ''));
console.log(`    平均宽 ${cardW.toFixed(2)}  间隙 ${gaps.join('/')}  → gapRatio ${report.gapRatio}`);
console.log(`    卡片自然宽 = 高 × (2144/1024) = ${((cardBandY1 - cardBandY0 + 1) * 2144 / 1024).toFixed(2)}`);
if (bandReport) {
  console.log(`[3] 底带素材 gui/pids/consist_band.png  ${bandReport.width} × ${bandReport.height}` +
    `  全不透明=${bandReport.opaque}`);
  console.log(`    三段（绘制用）：左（斜车头）${bandReport.leftW} / 中（纯色，横向拉伸）` +
    `${bandReport.midW} / 收边（右端暗边线）${bandReport.capW}   → 带体 ${bandReport.bodyW} 列` +
    `（x 0..${bandReport.bodyW - 1}）`);
  console.log(`    右侧青色余边：${bandReport.width - bandReport.bodyW} 列` +
    `（x ${bandReport.cyanRightFrom}..${bandReport.width - 1}）→ **不画**` +
    `（底图里带子铺满整行时才有的光晕；带子一收短就会变成「后面一块青色」）`);
  console.log(`    和底图那块逐像素一致列：${bandReport.matchingColumns}/${bandReport.width}`);
} else {
  console.log('[3] 底带素材 gui/pids/consist_band.png 缺失');
}
console.log(`[3b] 带外底色（叠加层 x > bandWidth 那段）  RGB(${bgRgb.join(',')}) = ` +
  `0x${bgHex.toString(16).toUpperCase().padStart(8, '0')}` +
  `   ← ${bgSamples.length} 个采样点${bgPure ? '全部相同' : '，众数'}` +
  `（编组带左边紧邻 3 列 × 33 行 + 正下方 3 行）`);
console.log(`     = PidsXiamenConsist.PANEL_BG ` +
  (bgMatchesJava ? '（一致 ✓ 叠加层带外块和面板像素逐字节相同，看不出拼缝）'
    : `（不一致！Java 里是 ${javaPanelBg === null ? '读不到'
      : '0x' + javaPanelBg.toString(16).toUpperCase().padStart(8, '0')}，要改成上面这个）`));

const fmtRect = (r) => r ? `x ${r.x0}..${r.x0 + r.w - 1}  y ${r.y0}..${r.y0 + r.h - 1}` +
  `  (${r.w} × ${r.h})` : '未切到';
console.log(`[4] 切片·卡片 gui/pids/consist_card.png   ${fmtRect(cardRect)}`);
console.log(`    切片·雪花 gui/pids/consist_flake.png  ${fmtRect(flakeRectOut)}` +
  `   ← 标签区第 1 颗；「车」字不切，走 MC 文字`);
console.log(`    间距：卡片 ${gaps.join('/')} px（平均 ${(gaps.reduce((s, g) => s + g, 0) / Math.max(1, gaps.length)).toFixed(1)}）` +
  `，雪花到卡片右缘由布局裁（见 PidsXiamenConsist.layout）`);
if (emitted.length) {
  for (const e of emitted) {
    console.log(`    已写 ${path.relative(VERSION_DIR, e.file)}  (${e.rect.w} × ${e.rect.h}，原尺寸、未压缩)`);
  }
} else {
  console.log('    （加 --emit 才会写切片 png）');
}
