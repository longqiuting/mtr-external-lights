#!/usr/bin/env node
/*
 * PSD-LCD 屏幕贴合 + 版式自检（离线）
 * ================================
 *
 * 这块屏出过四次同类/邻类的错：内容画了两遍、内容整体偏下且溢出到吊板下面、窗口上半截是黑的、
 * **上一轮把「广告区」当多余删了（右侧一片蓝到底）、编组行挤到最右边并压在当前站上**。
 * 前三次的真因都是「四边形 / 位图 / 模型窗口」三者尺寸或原点对不上，后一次是版式比例没跟设计稿对。
 * 这个脚本把三者 + 版式摊开来逐条对账，不进游戏也能判定：
 *
 *   1. 从模型 json 里读窗口矩形（ScreenPlane 的 from/to）→ 窗口像素宽高比；
 *   2. 和 PsdLcdLayout.java 的常量对一遍（模型改了、代码没改 → 直接失败）；
 *   3. 算出每段（1 格）的位图尺寸，断言 |位图宽高比 ÷ 窗口宽高比 − 1| < 1%；
 *   4. 真的调 Java2D 画三张预览（4 段 / 8 段 / 13:1），从 PNG 字节里读**实际**宽高再断言一遍；
 *   5. 读 PNG 第一行的像素，断言「第一行不是纯黑」（= 位图首行就是内容首行，上面没有黑边）；
 *   6. 打印窗口四个顶点 + 位图 ↔ 窗口的映射，方便和实机截图对账；
 *   7. 版式比例：PsdLcdPainter.java 里的分栏比例 vs 设计稿
 *      `厦门地铁PSD-LCD示意.png`（5383×779）逐像素量出来的比例，误差 < 0.01；
 *   8. 版式落到位图上：**广告区必须占 0.79…1.00 且不是底色**（整屏高）、
 *      编组行右缘 ≤ 0.78 且与「当前站」那一列不重叠。
 *
 * 用法（在仓库根目录跑）：
 *   node v1.20.1/tools/psd-lcd-fit-check.js
 *   node v1.20.1/tools/psd-lcd-fit-check.js --no-preview   # 只算数，不跑 Java / 不画图
 *
 * 退出码：0 = 全部通过；1 = 有断言失败。
 */
'use strict';

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const { execFileSync } = require('child_process');

const HERE = __dirname;                                    // …/v1.20.1/tools
const VERSION_DIR = path.resolve(HERE, '..');               // …/v1.20.1
const REPO = path.resolve(VERSION_DIR, '..');               // …/mtr-headlight
const MODEL = path.join(VERSION_DIR, 'src/main/resources/assets/mtrlte/models/block/psd_lcd_xiamen.json');
const LAYOUT_JAVA = path.join(VERSION_DIR, 'src/main/java/dev/js/mtrheadlight/xiamen/pids/PsdLcdLayout.java');
const PAINTER_JAVA = path.join(VERSION_DIR, 'src/main/java/dev/js/mtrheadlight/xiamen/pids/PsdLcdPainter.java');
const PAINTER_DIR = path.join(VERSION_DIR, 'src/main/java/dev/js/mtrheadlight/xiamen/pids');
const OUT_DIR = path.join(REPO, 'temp/psd-lcd-fit');

// 和 PsdLcdLayout 一致的常量（下面会和 Java 源码对一遍，防止两边走岔）
const PX_PER_BLOCK = 520;
const BITMAP_SCALE = 2;

/**
 * 设计稿 `C:\Users\AD\Desktop\厦门地铁PSD-LCD示意.png`（5383×779 = 6.910:1）上**逐像素量出来**的
 * 归一化包围盒（x ÷ 宽、y ÷ 高）。量法：temp/probe-design/DesignProbe6.java
 * （按色相分出蓝底 / 洋红广告 / 红徽标 / 白字，再求墨迹包围盒）。
 *
 * 注意：设计稿里广告区的左缘是 **0.7197**（蓝色内容区 x 0…3873px、洋红 3874…5382px），
 * 不是「0.79」—— 0.79 那条线落在广告区<b>里面</b>，见第 8 节的两条断言。
 */
const DESIGN = {
  card: { x0: 0.0020, x1: 0.0995, y0: 0.0193, y1: 0.9756 },
  label: { x0: 0.1100 },
  dest: { cx: 0.2212 },
  digit: { right: 0.3400 },
  unit: { x0: 0.3660 },
  badge: { x0: 0.4392, x1: 0.5367, y0: 0.0565, y1: 0.3273 },
  station: { cx: 0.6075, y0: 0.0693, y1: 0.3145 },
  cars: { x0: 0.3158, x1: 0.6894, y0: 0.4492, y1: 0.6870 },
  note: { x0: 0.3950, y0: 0.6980 },
  ticker: { x0: 0.0999, x1: 0.7171, y0: 0.8280, y1: 0.9756 },
  ad: { x0: 0.7197, x1: 1.0000, y0: 0.0000, y1: 1.0000 },
};

/** 版式比例允许的误差（设计稿是手量的，±0.01 已经比肉眼严）。 */
const LAYOUT_TOL = 0.01;

const failures = [];
const notes = [];
const rendered = [];

function ok(cond, label, detail) {
  if (cond) {
    console.log(`  PASS  ${label}${detail ? '   ' + detail : ''}`);
  } else {
    console.log(`  FAIL  ${label}${detail ? '   ' + detail : ''}`);
    failures.push(label);
  }
}

function f(n, digits = 4) {
  return Number(n).toFixed(digits);
}

// ---------------------------------------------------------------- 1. 模型窗口矩形

console.log('== 1. 模型里的屏幕窗口（ScreenPlane）==');
const model = JSON.parse(fs.readFileSync(MODEL, 'utf8'));
const elements = model.elements || [];
const plane = elements.find((e) => e.name === 'ScreenPlane')
  || elements.find((e) => e.faces && e.faces.north && e.faces.north.texture === '#screen')
  || elements.find((e) => e.from && e.to && e.from[2] === e.to[2] && e.faces && e.faces.north);

if (!plane) {
  console.error('  FAIL  模型里找不到屏幕窗口面（ScreenPlane / #screen north face）');
  process.exit(1);
}

const [wx0, wy0, wz0] = plane.from;
const [wx1, wy1, wz1] = plane.to;
const winW = wx1 - wx0;              // 16px = 1 格
const winH = wy1 - wy0;              // 7px
const winAspect = winW / winH;

console.log(`  屏幕面元素    ${plane.name || '(unnamed)'}`);
console.log(`  from          [${plane.from.join(', ')}]`);
console.log(`  to            [${plane.to.join(', ')}]`);
console.log(`  窗口像素      ${winW} × ${winH} px（16 分之几）→ 纯宽度比 ${f(winAspect, 6)}:1 / 段`);
console.log(`  屏幕材质      ${plane.faces.north.texture}`);

ok(wx0 === 0 && wx1 === 16, '窗口 x 是整格宽（0 … 16）', `x ${wx0} … ${wx1}`);
ok(wy0 === 1.5 && wy1 === 8.5, '窗口 y 是 1.5 … 8.5（7px 带）', `y ${wy0} … ${wy1}`);
ok(wz0 === wz1, '屏幕面是零厚度的平面（z 固定）', `z ${wz0} / ${wz1}`);

// 前面最靠外那一层蒙皮（z = PANEL_Z0）不能在窗口带里多出一块 —— 用户看到的「上面多出一个黑块」就是这么来的
const frontZ = Math.min(...elements.filter((e) => e.faces && e.faces.north && e.from).map((e) => e.from[2]));
const frontSkins = elements.filter((e) => e.faces && e.faces.north && e.from && e.to
  && Math.abs(e.from[2] - frontZ) < 1e-6 && e !== plane);
let overlap = false;
for (const e of frontSkins) {
  const y0 = e.from[1];
  const y1 = e.to[1];
  if (y1 > wy0 + 1e-6 && y0 < wy1 - 1e-6) {
    overlap = true;
    console.log(`  FAIL  前面蒙皮 ${e.name} 压进了窗口带 y ${y0}…${y1}`);
  }
}
ok(!overlap, '窗口带里没有任何前面蒙皮（内容不会被压住 / 不会多出黑块）');

// 前表面从 y=1 到 y=16 必须连续覆盖：蒙皮 ∪ 窗口 = 整块高，没有漏光的缝
const bands = frontSkins.map((e) => [e.from[1], e.to[1]]).concat([[wy0, wy1]])
  .sort((a, b) => a[0] - b[0]);
let cursor = 1;
let continuous = true;
for (const [a, b] of bands) {
  if (a > cursor + 1e-6) continuous = false;
  cursor = Math.max(cursor, b);
}
ok(continuous && cursor >= 16 - 1e-6, '前面由「蒙皮 + 窗口」从上到下连续铺满 y 1…16', `覆盖到 ${cursor}`);

// ---------------------------------------------------------------- 2. 和 Java 常量对账

console.log('\n== 2. 模型 ↔ PsdLcdLayout.java ==');
const java = fs.readFileSync(LAYOUT_JAVA, 'utf8');
function constOf(source, name) {
  const m = source.match(new RegExp(`static final float ${name}\\s*=\\s*([-0-9.f]+)F`));
  return m ? parseFloat(m[1]) : NaN;
}
const jWinY0 = constOf(java, 'WINDOW_Y0_PX');
const jWinY1 = constOf(java, 'WINDOW_Y1_PX');
const jWinX0 = constOf(java, 'WINDOW_X0_PX');
const jWinX1 = constOf(java, 'WINDOW_X1_PX');
const jThick = constOf(java, 'THICKNESS_PX');
const jGap = constOf(java, 'FACE_GAP_PX');
const jPxPerBlock = constOf(java, 'PX_PER_BLOCK');
const jScale = constOf(java, 'BITMAP_SCALE');

ok(jWinX0 === wx0 && jWinX1 === wx1, 'Java 的 WINDOW_X0/X1 与模型一致', `${jWinX0} … ${jWinX1}`);
ok(jWinY0 === wy0 && jWinY1 === wy1, 'Java 的 WINDOW_Y0/Y1 与模型一致', `${jWinY0} … ${jWinY1}`);
const jZ0 = constOf(java, 'PANEL_Z0_PX');
ok(Math.abs((jZ0 + jThick) - wz0) < 1e-6, '模型里的屏幕面 z = Java 的 PANEL_Z0_PX + THICKNESS_PX', `z ${wz0} / ${jZ0 + jThick}`);
ok(jPxPerBlock === PX_PER_BLOCK && jScale === BITMAP_SCALE, 'Java 的 PX_PER_BLOCK / BITMAP_SCALE 未变',
  `${jPxPerBlock} / ${jScale}`);

const quadZ = jZ0 + jThick - jGap;     // BER 四边形比模型面再往前 0.016px
console.log(`  BER 四边形所在面 z = ${jZ0} + ${jThick} − ${jGap} = ${f(quadZ, 3)}px（模型面 ${jZ0 + jThick}px 之前，避免 z-fighting）`);

// ---------------------------------------------------------------- 3. 位图尺寸 / 宽高比

console.log('\n== 3. 位图尺寸（每段现算，不拿旧宽度拉伸）==');
const segmentsList = [1, 2, 3, 4, 8];
for (const seg of segmentsList) {
  const bmpW = Math.round(seg * PX_PER_BLOCK * BITMAP_SCALE);
  const bmpH = Math.round((winH / 16) * PX_PER_BLOCK * BITMAP_SCALE);
  const winAspectSeg = (winW * seg) / winH;
  const ratio = bmpW / bmpH;
  const err = Math.abs(ratio / winAspectSeg - 1);
  ok(err < 0.01, `${seg} 段位图 ${bmpW}×${bmpH} 宽高比 = 窗口 ${f(winAspectSeg, 3)}:1`,
    `误差 ${f(err * 100, 3)}%`);
}

// ---------------------------------------------------------------- 4. 三张预览（真画一遍）

const javac = findTool('javac');
const javaBin = findTool('java');
const previews = [
  { name: '4seg', seg: 4, ratio: (winW * 4) / winH, label: '4 段' },
  { name: '8seg', seg: 8, ratio: (winW * 8) / winH, label: '8 段' },
  { name: '13to1', seg: null, ratio: 13, label: '13:1（设计稿比例）' },
];

const previewHeight = Math.round((winH / 16) * PX_PER_BLOCK * BITMAP_SCALE);   // = 455，和游戏里一致

if (process.argv.includes('--no-preview') || !javac || !javaBin) {
  notes.push(javac && javaBin ? '--no-preview：跳过实际出图'
    : '找不到 javac / java（设 JAVA_HOME 或加进 PATH）：跳过实际出图');
  console.log(`\n== 4. 预览出图（跳过：${notes[notes.length - 1]}）==`);
} else {
  console.log('\n== 4. 预览出图（PNG 尺寸从字节里读，不看代码算的数）==');
  const classes = path.join(REPO, 'temp/psd-lcd-fit-classes');
  fs.mkdirSync(classes, { recursive: true });
  fs.mkdirSync(OUT_DIR, { recursive: true });
  const sources = [
    path.join(PAINTER_DIR, 'PsdLcdPainter.java'),
    path.join(PAINTER_DIR, 'PsdLcdTypeface.java'),
    path.join(PAINTER_DIR, '..', 'XiamenStripPainter.java'),
    path.join(REPO, 'tools/psd-lcd/PsdLcdPreview.java'),
  ].filter((p) => fs.existsSync(p));
  execFileSync(javac, ['-d', classes, '-encoding', 'UTF-8', ...sources], {
    cwd: REPO, stdio: 'inherit',
  });

  for (const p of previews) {
    const out = path.join(OUT_DIR, `${p.name}.png`);
    execFileSync(javaBin, ['-cp', classes, 'dev.js.mtrheadlight.xiamen.pids.PsdLcdPreview',
      out, String(p.ratio), String(previewHeight), '-', '4'], { cwd: REPO, stdio: 'inherit' });

    const png = decodePng(out);
    rendered.push({ name: p.name, label: p.label, png });

    const expectW = p.seg == null ? Math.round(previewHeight * p.ratio)
      : Math.round(p.seg * PX_PER_BLOCK * BITMAP_SCALE);
    ok(png.width === expectW, `${p.label} 预览宽 = ${expectW}px（每段 16px × 65 倍）`, `实际 ${png.width}`);
    ok(png.height === previewHeight, `${p.label} 预览高 = ${previewHeight}px`, `实际 ${png.height}`);
    const winAspectSeg = p.seg == null ? p.ratio : (winW * p.seg) / winH;
    const err = Math.abs(png.width / png.height / winAspectSeg - 1);
    ok(err < 0.01, `${p.label} 预览宽高比 = 窗口宽高比`, `误差 ${f(err * 100, 3)}%`);

    // 第一行不能是纯黑 / 透明：位图首行就是内容首行，上面不允许有黑边
    const row = png.row0;
    ok(!row.isBlack, `${p.label} 位图第一行不是纯黑（上面没有黑边）`,
      `左上角 rgb(${row.r},${row.g},${row.b})，整行最亮 rgb(${row.maxR},${row.maxG},${row.maxB})`);
    const bgLike = Math.abs(row.r - 0x02) < 8 && Math.abs(row.g - 0x06) < 8 && Math.abs(row.b - 0xE0) < 12;
    ok(bgLike, `${p.label} 第一行是内容底色 0x0206E0（= 位图顶边对齐窗口顶边，没有留边）`,
      `rgb(${row.r},${row.g},${row.b})`);
  }
}

// ---------------------------------------------------------------- 5. 四顶点 + 映射

console.log('\n== 5. 屏幕四顶点（1 段，anchor 那一格的最小角为原点）==');
const planeZ = (wz0 - jGap) / 16;
const leftTop = [1.0, wy1 / 16, planeZ];      // facing=north：观察者在北侧，左手边是 +x（东）
const leftBottom = [1.0, wy0 / 16, planeZ];
const rightBottom = [0.0, wy0 / 16, planeZ];
const rightTop = [0.0, wy1 / 16, planeZ];
const pts = [['左上', leftTop], ['左下', leftBottom], ['右下', rightBottom], ['右上', rightTop]];
for (const [name, p] of pts) {
  console.log(`  ${name}  x=${f(p[0], 4)}  y=${f(p[1], 4)}  z=${f(p[2], 4)}`
    + `   (像素 ${f(p[0] * 16, 2)}, ${f(p[1] * 16, 2)}, ${f(p[2] * 16, 3)})`);
}

console.log('\n== 6. 位图 ↔ 窗口映射（facing=north，3 段）==');
const seg = 3;
const bmpW = Math.round(seg * PX_PER_BLOCK * BITMAP_SCALE);
const bmpH = previewHeight;
console.log(`  位图           ${bmpW} × ${bmpH} px`);
console.log(`  位图 (0,0)  ↔  窗口 左上  (x=${f(leftTop[0], 3)}, y=${f(leftTop[1], 4)}, z=${f(leftTop[2], 4)})`);
console.log(`  位图 (W,H)  ↔  窗口 右下  (x=${f(rightBottom[0], 3)}, y=${f(rightBottom[1], 4)}, z=${f(rightBottom[2], 4)})`);
console.log(`  每格映射       ${bmpW / seg}px 宽 × ${bmpH}px 高 = ${f(bmpW / seg / bmpH, 4)}:1 / 段`);
console.log(`  窗口下方      y ${f(wy0 / 16, 4)} 格 = 1.5px —— 四边形不会再往下伸到吊板下面`);

// ---------------------------------------------------------------- 7. 版式比例（设计稿 vs 代码）

console.log('\n== 7. 版式比例：PsdLcdPainter.java vs 设计稿（5383×779 逐像素量）==');
const painter = fs.readFileSync(PAINTER_JAVA, 'utf8');
const P = (name) => constOf(painter, name);

const layoutChecks = [
  ['左卡片 左缘', P('CARD_X0'), DESIGN.card.x0],
  ['左卡片 右缘', P('CARD_X1'), DESIGN.card.x1],
  ['左卡片 上沿', P('CARD_Y0'), DESIGN.card.y0],
  ['左卡片 下沿', P('CARD_Y1'), DESIGN.card.y1],
  ['标签列 左缘（开往 / Bound for）', P('LBL_X'), DESIGN.label.x0],
  ['终点站列 中线', P('DEST_CX'), DESIGN.dest.cx],
  ['大数字 右缘', P('NUM_RIGHT'), DESIGN.digit.right],
  ['分钟列 左缘', P('UNIT_X'), DESIGN.unit.x0],
  ['线路徽标 左缘', P('BADGE_X0'), DESIGN.badge.x0],
  ['线路徽标 右缘', P('BADGE_X1'), DESIGN.badge.x1],
  ['线路徽标 上沿', P('BADGE_Y0'), DESIGN.badge.y0],
  ['线路徽标 下沿', P('BADGE_Y1'), DESIGN.badge.y1],
  ['当前站 中线', P('STA_CX'), DESIGN.station.cx],
  ['当前站 中文上沿', P('STA_ZH_TOP'), DESIGN.station.y0],
  ['编组行 左缘', P('CAR_ROW_X0'), DESIGN.cars.x0],
  ['编组行 右缘', P('CAR_ROW_X1'), DESIGN.cars.x1],
  ['编组行 上沿', P('CAR_Y0'), DESIGN.cars.y0],
  ['编组行 下沿', P('CAR_Y1'), DESIGN.cars.y1],
  ['编组注记 左缘', P('NOTE_X'), DESIGN.note.x0],
  ['标语条 左缘', P('TICK_X0'), DESIGN.ticker.x0],
  ['标语条 右缘', P('TICK_X1'), DESIGN.ticker.x1],
  ['标语条 上沿', P('TICK_Y0'), DESIGN.ticker.y0],
  ['标语条 下沿', P('TICK_Y1'), DESIGN.ticker.y1],
  ['广告区 左缘', P('AD_X0'), DESIGN.ad.x0],
];
for (const [label, actual, design] of layoutChecks) {
  const diff = Math.abs(actual - design);
  ok(Number.isFinite(actual) && diff <= LAYOUT_TOL, `${label} = ${f(design, 4)}（设计稿）`,
    `代码 ${f(actual, 4)}，差 ${f(diff, 4)}`);
}

// 卡片不许高过编组行
const carRatio = P('CARD_PX_H') / P('CARD_PX_W');
const cardH = P('CAR_W') * carRatio;
const rowH = P('CAR_Y1') - P('CAR_Y0');
ok(cardH <= rowH + 1e-6, '卡片自然高度不超过编组行行高（卡片不会溢出到标语条上）',
  `卡片 ${f(cardH, 4)}h ≤ 行高 ${f(rowH, 4)}h`);

// 编组行右缘必须留在「当前站 / 广告区」左边
ok(P('CAR_ROW_X1') <= 0.78, '编组行右缘 ≤ 0.78（不许再挤到最右边）', `右缘 ${f(P('CAR_ROW_X1'), 4)}`);
ok(P('CAR_ROW_X1') < P('AD_X0'), '编组行右缘在广告区左边', `${f(P('CAR_ROW_X1'), 4)} < ${f(P('AD_X0'), 4)}`);
ok(P('TICK_X1') < P('AD_X0'), '标语条右缘在广告区左边', `${f(P('TICK_X1'), 4)} < ${f(P('AD_X0'), 4)}`);

// 编组行 / 当前站 两块矩形不能相交（上一轮就是这里压住了「董任」）
const carsBox = { x0: P('CAR_ROW_X0'), x1: P('CAR_ROW_X1'), y0: P('CAR_Y0'), y1: P('CAR_Y1') };
const staBox = {
  x0: P('STA_CX') - 0.06, x1: P('STA_CX') + 0.06,
  y0: P('STA_ZH_TOP'), y1: P('STA_EN_TOP') + P('STA_EN_INK'),
};
const xOverlap = carsBox.x0 < staBox.x1 && staBox.x0 < carsBox.x1;
const yOverlap = carsBox.y0 < staBox.y1 && staBox.y0 < carsBox.y1;
ok(!(xOverlap && yOverlap), '编组行与「当前站」那一列不重叠',
  `车 y ${f(carsBox.y0, 4)}…${f(carsBox.y1, 4)} vs 站 y ${f(staBox.y0, 4)}…${f(staBox.y1, 4)}`);

// ---------------------------------------------------------------- 8. 版式落到位图上（读像素）

if (rendered.length > 0) {
  console.log('\n== 8. 版式落到位图上（广告区 / 编组行，真读像素）==');
  for (const r of rendered) {
    const { png } = r;
    const W = png.width;
    const H = png.height;

    // 8.1 广告区：0.79…1.00 整屏高，且不是底色
    let bgInAd = 0;
    let samples = 0;
    for (let iy = 1; iy <= 19; iy++) {
      for (let ix = 1; ix <= 19; ix++) {
        const x = Math.round((0.79 + (1.00 - 0.79) * ix / 20) * W);
        const y = Math.round((iy / 20) * H);
        samples++;
        if (isBg(png.px(x, y))) bgInAd++;
      }
    }
    ok(bgInAd === 0, `${r.label} 广告区 0.79…1.00 不是纯底色（广告区画回来了）`,
      `${samples} 个采样点里底色 ${bgInAd} 个`);

    // 8.2 广告区是整屏高：中间那一列从上到下都不是底色
    let bgColumn = 0;
    const adCol = Math.round(0.90 * W);
    for (let y = 1; y < H; y += 2) {
      if (isBg(png.px(adCol, y))) bgColumn++;
    }
    ok(bgColumn === 0, `${r.label} 广告区从上到下占满整屏高`, `x=0.90 这一列底色像素 ${bgColumn}`);

    // 8.3 广告区的左缘 = 设计稿量到的 0.7197（±0.01），而且在 0.79 的左边
    //     扫描行选 y=0.79：这一条横带上只有底色和广告区（当前站在上面、编组行在下面、
    //     标语条从 0.828 才开始），所以从 0.60 往右扫到的第一个非底色像素就是广告区左缘。
    const adLeft = firstNonBgFrom(png, Math.round(0.6 * W), Math.round(0.79 * H));
    const adLeftNorm = adLeft / W;
    ok(Math.abs(adLeftNorm - DESIGN.ad.x0) <= LAYOUT_TOL, `${r.label} 广告区左缘 = ${DESIGN.ad.x0}（设计稿）`,
      `实际 ${f(adLeftNorm, 4)}`);
    ok(adLeftNorm < 0.79, `${r.label} 广告区从 0.72 起就开始了（0.79…1.00 全在广告区里面）`,
      `左缘 ${f(adLeftNorm, 4)} < 0.79`);

    // 8.4 编组行右缘 ≤ 0.78：在编组行那条带里，左边最后一个非底色像素
    const cy0 = Math.round((P('CAR_Y0') + 0.02) * H);
    const cy1 = Math.round((P('CAR_Y1') - 0.02) * H);
    let carsRight = 0;
    for (let y = cy0; y <= cy1; y += 2) {
      for (let x = adLeft - 1; x > carsRight; x--) {
        if (!isBg(png.px(x, y))) {
          if (x > carsRight) carsRight = x;
          break;
        }
      }
    }
    const carsRightNorm = carsRight / W;
    ok(carsRightNorm <= 0.78, `${r.label} 编组行右缘 ≤ 0.78（不再挤到最右边）`, `实际 ${f(carsRightNorm, 4)}`);
    ok(carsRightNorm <= P('CAR_ROW_X1') + 0.005, `${r.label} 编组行右缘 = 代码里的 ${f(P('CAR_ROW_X1'), 4)}`,
      `实际 ${f(carsRightNorm, 4)}`);

    // 8.5 编组行那条带里，广告区左边不许有东西压进「当前站」那一列之上：
    //     当前站下面那条横带（STA 下沿 … CAR_Y0）在桩站列范围内必须是底色
    const gapY0 = Math.round((staBox.y1 + 0.01) * H);
    const gapY1 = Math.round((carsBox.y0 - 0.01) * H);
    let dirty = 0;
    for (let y = gapY0; y <= gapY1; y += 2) {
      for (let x = Math.round(staBox.x0 * W); x <= Math.round(staBox.x1 * W); x += 2) {
        if (!isBg(png.px(x, y))) dirty++;
      }
    }
    ok(dirty === 0, `${r.label} 当前站与编组行之间是空的（两行不再叠在一起）`, `脏像素 ${dirty}`);
  }
}

// ---------------------------------------------------------------- 结果

console.log('');
if (failures.length > 0) {
  console.error(`FAILED (${failures.length}):`);
  for (const x of failures) console.error('  - ' + x);
  process.exit(1);
}
console.log('所有断言通过。');
if (process.env.KEEP_QUIET !== '1' && fs.existsSync(OUT_DIR)) {
  console.log('预览图：' + OUT_DIR);
}

// ---------------------------------------------------------------- 工具

function findTool(name) {
  const home = process.env.JAVA_HOME;
  if (home) {
    const p = path.join(home, 'bin', name + (process.platform === 'win32' ? '.exe' : ''));
    if (fs.existsSync(p)) return p;
  }
  for (const dir of (process.env.PATH || '').split(path.delimiter)) {
    const p = path.join(dir, name + (process.platform === 'win32' ? '.exe' : ''));
    try {
      if (fs.existsSync(p)) return p;
    } catch (e) { /* ignore */ }
  }
  return null;
}

/** 底色（0x0206E0）判定：三个通道都贴得很近才算。 */
function isBg(rgb) {
  return Math.abs(rgb.r - 0x02) <= 8 && Math.abs(rgb.g - 0x06) <= 8 && Math.abs(rgb.b - 0xE0) <= 12;
}

/** 某一行的 x 起点往右扫，第一个非底色像素（= 广告区左缘）。 */
function firstNonBgFrom(png, xStart, y) {
  for (let x = xStart; x < png.width; x++) {
    if (!isBg(png.px(x, y))) return x;
  }
  return png.width;
}

/**
 * 读 PNG：IHDR 尺寸 + 整张图解压后的像素（8bit、非隔行、RGB/RGBA）。
 * 只有先解出整张图，才能读「广告区 / 编组行」那些位置的像素。
 */
function decodePng(file) {
  const buf = fs.readFileSync(file);
  if (buf.readUInt32BE(0) !== 0x89504e47) throw new Error('not a png: ' + file);
  let off = 8;
  let width = 0;
  let height = 0;
  let depth = 0;
  let colorType = 0;
  let interlace = 0;
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
    throw new Error(`unsupported png: depth=${depth} colorType=${colorType} interlace=${interlace}`);
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

  // 第一行的统计（沿用旧断言：位图首行不是黑边 / 就是底色）
  let maxR = 0;
  let maxG = 0;
  let maxB = 0;
  for (let x = 0; x < width; x++) {
    maxR = Math.max(maxR, pix[x * bpp]);
    maxG = Math.max(maxG, pix[x * bpp + 1]);
    maxB = Math.max(maxB, pix[x * bpp + 2]);
  }

  return {
    width,
    height,
    bpp,
    row0: {
      r: pix[0], g: pix[1], b: pix[2], maxR, maxG, maxB,
      isBlack: maxR + maxG + maxB <= 12,
    },
    px(x, y) {
      const cx = Math.max(0, Math.min(width - 1, x));
      const cy = Math.max(0, Math.min(height - 1, y));
      const i = cy * stride + cx * bpp;
      return { r: pix[i], g: pix[i + 1], b: pix[i + 2] };
    },
  };
}

function paeth(a, b, c) {
  const p = a + b - c;
  const pa = Math.abs(p - a);
  const pb = Math.abs(p - b);
  const pc = Math.abs(p - c);
  if (pa <= pb && pa <= pc) return a;
  return pb <= pc ? b : c;
}
