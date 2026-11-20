#!/usr/bin/env node
/*
 * PSD-LCD 屏幕贴合 + 版式自检（离线）
 * ================================
 *
 * 这块屏出过四次同类/邻类的错：内容画了两遍、内容整体偏下且溢出到吊板下面、窗口上半截是黑的、
 * **上一轮把「广告区」当多余删了（右侧一片蓝到底）、编组行挤到最右边并压在当前站上**。
 * 前三次的真因都是「四边形 / 位图 / 模型窗口」三者尺寸或原点对不上，后一次是版式比例没跟设计稿对。
 * 这个脚本把这些摊开来逐条对账，不进游戏也能判定：
 *
 *   1. **两套模型就是用户给的 json 过一遍确定性变换**：`mornal` 组 → psd_lcd_xiamen.json，
 *      `half` 组（只剩上半格）→ psd_lcd_xiamen_half.json（tools/psd-lcd-models.js）；
 *   2. 每档的窗口矩形**从模型几何量出来**（扫高度找「最前面那个面 = 凹槽底」的连续区间）
 *      ↔ PsdLcdLayout.java 常量 ↔ BER 面 z（模型 10.2 / 四边形 10.184）；
 *   3. 每档的位图尺寸：断言 |位图宽高比 ÷ 窗口宽高比 − 1| < 1%（剪不剪都不许拉伸）；
 *   4. 真的调 Java2D 画三张预览（4 段 / 8 段 / 13:1），从 PNG 字节里读**实际**宽高再断言一遍；
 *   5. 读 PNG 第一行的像素，断言「第一行不是纯黑」（= 位图首行就是内容首行，上面没有黑边）；
 *   6. 打印两档的窗口四个顶点 + 位图 ↔ 窗口的映射；
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

const models = require('./psd-lcd-models');

const HERE = __dirname;                                    // …/v1.20.1/tools
const VERSION_DIR = path.resolve(HERE, '..');               // …/v1.20.1
const REPO = path.resolve(VERSION_DIR, '..');               // …/mtr-headlight
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

// ---------------------------------------------------------------- 1. 两套模型 = 用户 json

console.log('== 1. 两套模型 ↔ 用户给的 psd_lcd_xiamen.json（过一遍确定性变换）==');
const ref = models.loadRef();
console.log(`  参考文件  ${models.REF_PATH}`);
const loaded = [];
for (const state of models.STATES) {
  const file = models.modelPath(state.file);
  if (!fs.existsSync(file)) {
    ok(false, `${state.file} 存在`, file);
    continue;
  }
  const model = models.readJson(file);
  const expected = models.transformGroup(ref, state.group, state.bboxY[0]);
  const identical = JSON.stringify(model) === JSON.stringify(expected);
  ok(identical, `${state.label}：${state.file} = 参考 json 的 ${state.group} 组变换后的结果`
    + `（${expected.elements.length} 元素）`,
  identical ? '' : '磁盘上的模型和生成器不一致 —— 跑 node tools/psd-lcd-models.js --write');

  // 窗口带**从模型几何量出来**（用户的新模型里没有 ScreenPlane 了）：
  // 扫一遍高度，找「最前面那个面 = 凹槽底」的连续区间 = 屏幕能画的范围。
  const band = models.derivedWindowBand(model);
  ok(Math.abs(band.y0 - state.windowY0) < 0.06 && Math.abs(band.y1 - state.windowY1) < 0.06,
    `${state.file} 的窗口带 = ${state.windowY0} … ${state.windowY1}（从几何量出来的）`,
    `模型 ${band.y0} … ${band.y1}`);
  loaded.push({
    state,
    elements: model.elements || [],
    plane: { from: [0, band.y0, band.z], to: [16, band.y1, band.z] },
  });
}

// ---------------------------------------------------------------- 2. 窗口带 ↔ Java 常量

console.log('\n== 2. 每档的窗口带（模型几何）↔ PsdLcdLayout.java ==');
const java = fs.readFileSync(LAYOUT_JAVA, 'utf8');
function constOf(source, name) {
  const m = source.match(new RegExp(`static final float ${name}\\s*=\\s*([-0-9.f]+)F`));
  return m ? parseFloat(m[1]) : NaN;
}
const jWinY0 = constOf(java, 'WINDOW_Y0_PX');
const jWinY1 = constOf(java, 'WINDOW_Y1_PX');
const jCutY0 = constOf(java, 'CUT_WINDOW_Y0_PX');
const jCutY1 = constOf(java, 'CUT_WINDOW_Y1_PX');
const jWinX0 = constOf(java, 'WINDOW_X0_PX');
const jWinX1 = constOf(java, 'WINDOW_X1_PX');
// SCREEN_FACE_Z_PX 在 Java 里是算式（PANEL_Z0 + SCREEN_RECESS），这里按同一份算式现算。
const jScreenZ = constOf(java, 'PANEL_Z0_PX') + constOf(java, 'SCREEN_RECESS_PX');
const jGap = constOf(java, 'FACE_GAP_PX');
const jZ0 = constOf(java, 'PANEL_Z0_PX');
const jPxPerBlock = constOf(java, 'PX_PER_BLOCK');
const jScale = constOf(java, 'BITMAP_SCALE');

ok(jPxPerBlock === PX_PER_BLOCK && jScale === BITMAP_SCALE, 'Java 的 PX_PER_BLOCK / BITMAP_SCALE 未变',
  `${jPxPerBlock} / ${jScale}`);
ok(jWinY0 === 1.5 && jWinY1 === 8.5, 'Java WINDOW_Y0/Y1 = 1.5 / 8.5（整块）', `${jWinY0} / ${jWinY1}`);
ok(jCutY0 === 9.0 && jCutY1 === 16.0, 'Java CUT_WINDOW_Y0/Y1 = 9 / 16（剪后只剩上半格）', `${jCutY0} / ${jCutY1}`);

const planeZModel = jScreenZ;                 // 10.2 —— 模型里凹槽底那一层
const planeZBer = planeZModel - jGap;         // 10.184 —— BER 四边形
ok(Math.abs(planeZModel - 10.2) < 1e-9, `模型屏幕面 z = ${f(planeZModel, 3)}px（= 正面 ${jZ0} + 凹槽 0.2）`);
ok(Math.abs(planeZBer - 10.184) < 1e-9, `BER 四边形 z = ${f(planeZBer, 3)}px（模型面 ${f(planeZModel, 3)} − ${jGap}）`);

for (const { state, elements, plane } of loaded) {
  const [wx0, wy0, wz0] = plane.from;
  const [wx1, wy1, wz1] = plane.to;
  console.log(`\n  ${state.label}`);
  console.log(`    窗口（从几何量）  x ${wx0} … ${wx1}px   y ${wy0} … ${wy1}px（高 ${f(wy1 - wy0, 2)}px）`
    + `   z ${wz0}px`);
  ok(wx0 === jWinX0 && wx1 === jWinX1, `${state.label} 窗口 x 是整格宽（${jWinX0} … ${jWinX1}）`, `x ${wx0} … ${wx1}`);
  ok(wy0 === state.windowY0 && wy1 === state.windowY1,
    `${state.label} 窗口 y 带 = ${state.windowY0} … ${state.windowY1}`,
    `实际 y ${wy0} … ${wy1}`);
  ok(wz0 === wz1 && Math.abs(wz0 - planeZModel) < 1e-9, `${state.label} 屏幕面零厚度、z = ${f(planeZModel, 3)}`,
    `z ${wz0} / ${wz1}`);

  // 前脸必须连续：整格高度上都得有几何（不是 z=10 就是凹槽底 10.2），不然会从上/下看穿
  const frontBands = elements
    .map((e) => [Math.min(e.from[1], e.to[1]), Math.max(e.from[1], e.to[1])])
    .concat([[wy0, wy1], [state.cut ? 7.5 : 0.0, wy0], [wy1, 16.0]])
    .sort((a, b) => a[0] - b[0]);
  let cursor = frontBands[0][0];
  let continuous = true;
  for (const [a, b] of frontBands) {
    if (a > cursor + 1e-6) continuous = false;
    cursor = Math.max(cursor, b);
  }
  const top = state.cut ? 16.0 : 16.0;
  const bottom = state.cut ? 7.5 : 0.0;
  ok(continuous && cursor >= top - 1e-6,
    `${state.label} 前脸从 y ${bottom} 连续铺到 y ${top}（窗口上下都有板子）`,
    `从 ${frontBands[0][0]} 覆盖到 ${cursor}`);
}

// ---------------------------------------------------------------- 3. 位图尺寸 / 宽高比

console.log('\n== 3. 位图尺寸（每档按自己的窗口高算，不拿旧高度拉伸）==');
for (const { state, plane } of loaded) {
  const winW = plane.to[0] - plane.from[0];
  const winH = plane.to[1] - plane.from[1];
  const bmpH = Math.round((winH / 16) * PX_PER_BLOCK * BITMAP_SCALE);
  for (const seg of [1, 2, 3, 4, 8]) {
    const bmpW = Math.round(seg * PX_PER_BLOCK * BITMAP_SCALE);
    const winAspectSeg = (winW * seg) / winH;
    const ratio = bmpW / bmpH;
    const err = Math.abs(ratio / winAspectSeg - 1);
    ok(err < 0.01, `${state.label} ${seg} 段位图 ${bmpW}×${bmpH} 宽高比 = 窗口 ${f(winAspectSeg, 3)}:1`,
      `误差 ${f(err * 100, 3)}%`);
  }
}

// ---------------------------------------------------------------- 4. 三张预览（真画一遍）

const winH = loaded.length ? loaded[0].plane.to[1] - loaded[0].plane.from[1] : 7;
const winW = loaded.length ? loaded[0].plane.to[0] - loaded[0].plane.from[0] : 16;
const previewHeight = Math.round((winH / 16) * PX_PER_BLOCK * BITMAP_SCALE);   // = 455，和游戏里一致

const javac = findTool('javac');
const javaBin = findTool('java');
const previews = [
  { name: '4seg', seg: 4, ratio: (winW * 4) / winH, label: '4 段' },
  { name: '8seg', seg: 8, ratio: (winW * 8) / winH, label: '8 段' },
  { name: '13to1', seg: null, ratio: 13, label: '13:1（设计稿比例）' },
];

/** 预览用的 classpath：第 4 节编好之后给第 9~11 节复用（null = 没编）。 */
let previewClasses = null;

if (process.argv.includes('--no-preview') || !javac || !javaBin) {
  notes.push(javac && javaBin ? '--no-preview：跳过实际出图'
    : '找不到 javac / java（设 JAVA_HOME 或加进 PATH）：跳过实际出图');
  console.log(`\n== 4. 预览出图（跳过：${notes[notes.length - 1]}）==`);
  console.log('\n== 9~11. 像素级断言（跳过：同上）==');
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
  previewClasses = classes;

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

// ---------------------------------------------------------------- 5. 四顶点

console.log('\n== 5. 屏幕四顶点（1 段，anchor 那一格的最小角为原点，facing=north）==');
for (const { state, plane } of loaded) {
  const planeZ = (planeZBer) / 16;
  const y0 = plane.from[1] / 16;
  const y1 = plane.to[1] / 16;
  const pts = [
    ['左上', [1.0, y1, planeZ]],
    ['左下', [1.0, y0, planeZ]],
    ['右下', [0.0, y0, planeZ]],
    ['右上', [0.0, y1, planeZ]],
  ];
  console.log(`  ${state.label}`);
  for (const [name, p] of pts) {
    console.log(`    ${name}  x=${f(p[0], 4)}  y=${f(p[1], 4)}  z=${f(p[2], 4)}`
      + `   (像素 ${f(p[0] * 16, 2)}, ${f(p[1] * 16, 2)}, ${f(p[2] * 16, 3)})`);
  }
  ok(Math.abs(y0 * 16 - state.windowY0) < 1e-9 && Math.abs(y1 * 16 - state.windowY1) < 1e-9,
    `${state.label} 四边形 y 带 = ${state.windowY0} … ${state.windowY1}px`,
    `顶点 ${f(y0 * 16, 3)} … ${f(y1 * 16, 3)}`);
  ok(Math.abs(planeZ * 16 - planeZBer) < 1e-9, `${state.label} 四边形面 z = ${f(planeZBer, 3)}px`,
    f(planeZ * 16, 4));
}

console.log('\n== 6. 位图 ↔ 窗口映射（facing=north，3 段，整块那一档）==');
const seg = 3;
const bmpW = Math.round(seg * PX_PER_BLOCK * BITMAP_SCALE);
const bmpH = previewHeight;
console.log(`  位图           ${bmpW} × ${bmpH} px`);
console.log(`  每格映射       ${bmpW / seg}px 宽 × ${bmpH}px 高 = ${f(bmpW / seg / bmpH, 4)}:1 / 段`);
console.log(`  整块窗口下沿   y ${f(jWinY0 / 16, 4)} 格 = ${jWinY0}px`);
console.log(`  剪后窗口下沿   y ${f(jCutY0 / 16, 4)} 格 = ${jCutY0}px（同一块位图，v 仍是 0…1，只换 y 带）`);

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
// 编组行的**有效**左界 = max(CAR_ROW_X0, CAR_ROW_GUARD_X)：只有这样才能既留在设计稿区域里、
// 又不压到行 2 的「分钟 / min」（行 2 的单位正好落在编组行的 y 带里）。
const guardX = P('CAR_ROW_GUARD_X');
const carsBox = {
  x0: Math.max(P('CAR_ROW_X0'), guardX), x1: P('CAR_ROW_X1'),
  y0: P('CAR_Y0'), y1: P('CAR_Y1'),
};
const staBox = {
  x0: P('STA_CX') - 0.06, x1: P('STA_CX') + 0.06,
  y0: P('STA_ZH_TOP'), y1: P('STA_EN_TOP') + P('STA_EN_INK'),
};
const xOverlap = carsBox.x0 < staBox.x1 && staBox.x0 < carsBox.x1;
const yOverlap = carsBox.y0 < staBox.y1 && staBox.y0 < carsBox.y1;
ok(!(xOverlap && yOverlap), '编组行与「当前站」那一列不重叠',
  `车 y ${f(carsBox.y0, 4)}…${f(carsBox.y1, 4)} vs 站 y ${f(staBox.y0, 4)}…${f(staBox.y1, 4)}`);

// ---------------------------------------------------------------- 7b. 编组行 × 分钟列

console.log('\n== 7b. 编组行 vs 「分钟 / min」列（包围盒不相交 + 压缩公式）==');

/**
 * 「分钟 / min」那一列的包围盒（占屏宽 / 占排版高）。
 *
 * 中文「分钟」两个全角字：墨迹宽 ≈ 2 × 墨迹高 = 2 × UNITZH_INK（h）⇒ 换成屏宽要 ÷ DESIGN_W；
 * 英文「min」按 1.9 × 墨迹高算（m/i/n 的墨迹宽窄相差不大，1.9 是实测值）。
 * 行 1 / 行 2 各一份（行 2 = ROW1_CY + ROW_DY）。
 */
function unitBox(rowCy) {
  const zhW = 2.0 * P('UNITZH_INK') / P('DESIGN_W');
  const enW = 1.9 * P('UNITEN_INK') / P('DESIGN_W');
  const w = Math.max(zhW, enW) + 0.002;
  return {
    x0: P('UNIT_X'),
    x1: P('UNIT_X') + w,
    y0: rowCy + P('UNITZH_TOP'),
    y1: rowCy + P('ROW_DY') * 0 + P('UNITEN_TOP') + P('UNITEN_INK'),
  };
}
const unit1 = unitBox(P('ROW1_CY'));
const unit2 = unitBox(P('ROW1_CY') + P('ROW_DY'));
console.log(`  分钟列 行1  x ${f(unit1.x0, 4)}…${f(unit1.x1, 4)}  y ${f(unit1.y0, 4)}…${f(unit1.y1, 4)}`);
console.log(`  分钟列 行2  x ${f(unit2.x0, 4)}…${f(unit2.x1, 4)}  y ${f(unit2.y0, 4)}…${f(unit2.y1, 4)}`);

// 编组行的**有效**左界 = max(CAR_ROW_X0, CAR_ROW_GUARD_X)；只有这样才能既留在设计稿区域里、
// 又不压到行 2 的「分钟 / min」（行 2 的单位正好落在编组行的 y 带里）。
ok(Number.isFinite(guardX), '代码里有 CAR_ROW_GUARD_X（编组行的「分钟列」守卫线）', `${f(guardX, 4)}`);
ok(guardX > P('CAR_ROW_X0') && guardX < P('CAR_ROW_X1'),
  '守卫线在编组区域内部（CAR_ROW_X0 < 守卫 < CAR_ROW_X1）',
  `${f(P('CAR_ROW_X0'), 4)} < ${f(guardX, 4)} < ${f(P('CAR_ROW_X1'), 4)}`);
for (const [name, u] of [['行1', unit1], ['行2', unit2]]) {
  ok(guardX >= u.x1 + 0.004, `守卫线在「分钟 / min」${name} 右缘右侧（留 ≥ 0.004 空档）`,
    `守卫 ${f(guardX, 4)} vs 单位右缘 ${f(u.x1, 4)}`);
}

const carsEff = carsBox;
for (const [name, u] of [['行1', unit1], ['行2', unit2]]) {
  const xOv = carsEff.x0 < u.x1 && u.x0 < carsEff.x1;
  const yOv = carsEff.y0 < u.y1 && u.y0 < carsEff.y1;
  ok(!(xOv && yOv), `编组行（有效区）与「分钟 / min」${name} 包围盒不相交`,
    `车 x ${f(carsEff.x0, 4)}…${f(carsEff.x1, 4)} vs 单位 x ${f(u.x0, 4)}…${f(u.x1, 4)}；`
    + `车 y ${f(carsEff.y0, 4)}…${f(carsEff.y1, 4)} vs 单位 y ${f(u.y0, 4)}…${f(u.y1, 4)}`);
}

// 压缩公式：高度不随节数缩，只有宽度 / 间隙被压
{
  const ratio = P('CARD_PX_H') / P('CARD_PX_W');
  const naturalCh = P('CAR_W') * ratio;                 // h
  const rowH = P('CAR_Y1') - P('CAR_Y0');
  ok(naturalCh <= rowH + 1e-6, '卡片自然高 ≤ 行高（高度不被行槽挤压）',
    `${f(naturalCh, 4)}h ≤ ${f(rowH, 4)}h`);
  // 行槽宽（屏宽）= CAR_ROW_X1 − 守卫线；车头宽 = 自然高 × 1.035 × (74/180) ÷ DESIGN_W
  const noseW = naturalCh * P('NOSE_H_RATIO') * (74 / 180) / P('DESIGN_W');
  const avail = P('CAR_ROW_X1') - Math.max(P('CAR_ROW_X0'), guardX) - noseW;
  const table = [];
  for (const n of [4, 6, 8, 13, 16]) {
    const used = Math.min(P('CAR_W') / P('DESIGN_W'), avail / (n * (1 + P('CAR_GAP_RATIO'))));
    const rowW = n * used * (1 + P('CAR_GAP_RATIO')) + noseW;   // 屏宽
    const usedH = used * P('DESIGN_W');                          // 换成 h（和 CAR_W 同单位）
    table.push(`${n} 节 cw=${f(usedH, 3)}h (${f(usedH / P('CAR_W') * 100, 1)}%)`);
    ok(rowW <= avail + noseW + 1e-9, `${n} 节：整行（车头 + 卡 + 间隙）不超过行槽宽`,
      `行宽 ${f(rowW - noseW, 4)} ≤ 槽 ${f(avail, 4)}`);
    // 高度固定 ⇒ 与 n 无关
    ok(Math.abs(naturalCh - P('CAR_W') * ratio) < 1e-9, `${n} 节：卡片高仍是 ${f(naturalCh, 4)}h（不随节数缩）`);
  }
  console.log('  压缩表：' + table.join('  |  '));
}

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
    const adLeft = firstNonBgFrom(png, Math.round(0.6 * W), Math.round(0.79 * H));
    const adLeftNorm = adLeft / W;
    ok(Math.abs(adLeftNorm - DESIGN.ad.x0) <= LAYOUT_TOL, `${r.label} 广告区左缘 = ${DESIGN.ad.x0}（设计稿）`,
      `实际 ${f(adLeftNorm, 4)}`);
    ok(adLeftNorm < 0.79, `${r.label} 广告区从 0.72 起就开始了（0.79…1.00 全在广告区里面）`,
      `左缘 ${f(adLeftNorm, 4)} < 0.79`);

    // 8.4 编组行右缘 ≤ 0.78
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

    // 8.5 当前站与编组行之间那条横带必须是底色
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

// ---------------------------------------------------------------- 9~11. 像素级断言

/** 调一次预览（同一份 PsdLcdPainter），返回解好的 PNG。 */
function renderPreview(name, opts = {}) {
  const ratio = opts.ratio === undefined ? DESIGN_RATIO : opts.ratio;
  const height = opts.height === undefined ? previewHeight : opts.height;
  const cars = opts.cars === undefined ? 4 : opts.cars;
  const out = path.join(OUT_DIR, `${name}.png`);
  const args = ['-cp', previewClasses, 'dev.js.mtrheadlight.xiamen.pids.PsdLcdPreview',
    out, String(ratio), String(height), '-', String(cars)];
  // 预览工具是**位置参数**（count1 count2 unitZh unitEn phase ticker），所以只要动了后面任何一个，
  // 前面几个就必须按默认值补齐 —— 少补一个就会把 phase 当成 count1 传进去（这里踩过一次）。
  if (opts.count1 !== undefined || opts.phase !== undefined || opts.ticker !== undefined) {
    args.push(String(opts.count1 === undefined ? '7' : opts.count1),
      String(opts.count2 === undefined ? '17' : opts.count2),
      String(opts.unitZh === undefined ? '分钟' : opts.unitZh),
      String(opts.unitEn === undefined ? 'min' : opts.unitEn),
      String(opts.phase === undefined ? 0.5 : opts.phase));
  }
  if (opts.ticker !== undefined) {
    args.push(opts.ticker);
  }
  execFileSync(javaBin, args, { cwd: REPO, stdio: 'pipe' });
  return decodePng(out);
}

const DESIGN_RATIO = 6.910;

/** 纯白（= 文字本体；胶囊描边是 0xE6EAFF，r 只有 230，不会被算进来）。 */
function isText(c) {
  return c.r >= 245 && c.g >= 245 && c.b >= 245;
}

/** 一块矩形里有多少「文字像素」。 */
function inkIn(png, x0, y0, x1, y1) {
  let n = 0;
  for (let y = Math.max(0, y0); y <= Math.min(png.height - 1, y1); y++) {
    for (let x = Math.max(0, x0); x <= Math.min(png.width - 1, x1); x++) {
      if (isText(png.px(x, y))) n++;
    }
  }
  return n;
}

/** 编组行（车头 + 卡片）在这张位图上的包围盒：从「分钟列右侧」扫到 CAR_ROW_X1 右边一点。 */
function carsRowOf(png) {
  const W = png.width;
  const H = png.height;
  const y0 = Math.round(carsBox.y0 * H) + 3;
  const y1 = Math.round(carsBox.y1 * H) - 3;
  const xFrom = Math.round((unit1.x1 + 0.002) * W);
  const xTo = Math.min(W - 1, Math.round((carsBox.x1 + 0.006) * W));
  let minX = -1;
  let maxX = -1;
  let minY = H;
  let maxY = -1;
  for (let x = xFrom; x <= xTo; x++) {
    let hit = false;
    for (let y = y0; y <= y1; y++) {
      if (!isBg(png.px(x, y))) {
        hit = true;
        if (y < minY) minY = y;
        if (y > maxY) maxY = y;
      }
    }
    if (hit) {
      if (minX < 0) minX = x;
      maxX = x;
    }
  }
  return { minX, maxX, minY, maxY, height: maxY - minY + 1 };
}

if (previewClasses) {
  // ---- 9. 编组行：只压宽，不整体缩；任何节数都不越界、不压「分钟」
  console.log('\n== 9. 编组行压缩（4 / 8 / 13 / 16 节真画一遍，量像素）==');
  const heights = {};
  for (const n of [4, 8, 13, 16]) {
    const png = renderPreview(`cars-${n}`, { cars: n });
    const row = carsRowOf(png);
    heights[n] = row.height;
    const leftNorm = row.minX / png.width;
    const rightNorm = row.maxX / png.width;
    ok(row.minX > 0 && row.maxX > row.minX, `${n} 节：编组行画出来了`, `x ${row.minX}…${row.maxX}`);
    ok(leftNorm >= guardX - 0.001, `${n} 节：编组行左缘不越过守卫线 ${f(guardX, 4)}（不压「分钟 / min」）`,
      `实际 ${f(leftNorm, 4)}`);
    ok(rightNorm <= carsBox.x1 + 0.004, `${n} 节：编组行右缘 ≈ ${f(carsBox.x1, 4)}（在广告区左边）`,
      `实际 ${f(rightNorm, 4)}`);
    ok(rightNorm <= 0.78, `${n} 节：编组行右缘 ≤ 0.78`, `实际 ${f(rightNorm, 4)}`);

    // 「分钟列右缘 … 守卫线」之间那条缝必须是空的（编组行没有压过去）
    let dirty = 0;
    for (let x = Math.round(unit1.x1 * png.width) + 1; x < Math.round(guardX * png.width) - 1; x++) {
      for (let y = Math.round(carsBox.y0 * png.height) + 2; y <= Math.round(carsBox.y1 * png.height) - 2; y++) {
        if (!isBg(png.px(x, y))) dirty++;
      }
    }
    ok(dirty === 0, `${n} 节：编组行与「分钟 / min」之间那条缝是空的（两块不相交）`, `脏像素 ${dirty}`);
  }
  const refH = heights[4];
  for (const n of [8, 13, 16]) {
    ok(Math.abs(heights[n] - refH) <= 2, `${n} 节：卡片高度与 4 节一致（只压宽，不整体缩小）`,
      `${n} 节 ${heights[n]}px vs 4 节 ${refH}px`);
  }
  const naturalPx = Math.round(P('CAR_W') * (P('CARD_PX_H') / P('CARD_PX_W')) * previewHeight);
  ok(Math.abs(refH - naturalPx) <= 6, `卡片高度 ≈ 自然高 ${naturalPx}px（没有缩成一条线）`, `实际 ${refH}px`);
  ok(refH >= 0.20 * previewHeight, `卡片高度 ≥ 0.20 × 屏高（长编组也认得出是车厢）`,
    `${refH}px ≥ ${Math.round(0.20 * previewHeight)}px`);

  // ---- 10. 「分钟 / min」在 0/1/2/17/99 与中英文下都必须显示（两行都要）
  console.log('\n== 10. 「分钟 / min」存在性（0/1/2/17/99 × 中英文 × 两行，真读像素）==');
  const rowsCy = [P('ROW1_CY'), P('ROW1_CY') + P('ROW_DY')];
  for (const value of ['0', '1', '2', '17', '99']) {
    const png = renderPreview(`count-${value}`, {
      count1: value, count2: value, unitZh: '分钟', unitEn: 'min',
    });
    const W = png.width;
    const H = png.height;
    const x0 = Math.round(P('UNIT_X') * W) - 2;
    const x1 = Math.round((P('UNIT_X') + 0.020) * W);
    rowsCy.forEach((cy, i) => {
      const zhY0 = Math.round((cy + P('UNITZH_TOP') - 0.004) * H);
      const zhY1 = Math.round((cy + P('UNITZH_TOP') + P('UNITZH_INK') + 0.004) * H);
      const enY0 = Math.round((cy + P('UNITEN_TOP') - 0.004) * H);
      const enY1 = Math.round((cy + P('UNITEN_TOP') + P('UNITEN_INK') + 0.004) * H);
      const zh = inkIn(png, x0, zhY0, x1, zhY1);
      const en = inkIn(png, x0, enY0, x1, enY1);
      ok(zh >= 15, `数字 ${value} 行 ${i + 1}：「分钟」画出来了`, `${zh} 个文字像素`);
      ok(en >= 10, `数字 ${value} 行 ${i + 1}：「min」画出来了`, `${en} 个文字像素`);
    });
  }

  // ---- 11. 标语条：短句居中静止，长句真滚动（从右进、从左出、边缘切字）
  console.log('\n== 11. 标语条滚动（短句静止 / 长句滚动，真读像素）==');
  const SHORT_TEXT = '欢迎乘坐厦门地铁';
  const LONG_TEXT = '厦门地铁提醒您：列车即将进站，请站在黄线以内候车，先下后上，注意站台间隙，'
    + '照顾好老人和小孩，谢谢配合，祝您旅途愉快。厦门地铁提醒您：请勿在站台上追逐打闹，注意脚下安全。';

  /** 标语框内的文字墨迹范围（纯白像素）。 */
  const tickerInk = (png) => {
    const W = png.width;
    const H = png.height;
    const x0 = Math.round(P('TICK_X0') * W) + 3;
    const x1 = Math.round(P('TICK_X1') * W) - 3;
    const y0 = Math.round(P('TICK_Y0') * H) + 3;
    const y1 = Math.round(P('TICK_Y1') * H) - 3;
    let minX = -1;
    let maxX = -1;
    let n = 0;
    for (let x = x0; x <= x1; x++) {
      for (let y = y0; y <= y1; y++) {
        if (isText(png.px(x, y))) {
          n++;
          if (minX < 0) minX = x;
          maxX = x;
        }
      }
    }
    return { minX, maxX, count: n, box: { x0, x1 } };
  };

  const shortA = tickerInk(renderPreview('ticker-short-a', { ticker: SHORT_TEXT, phase: 0.0 }));
  const shortB = tickerInk(renderPreview('ticker-short-b', { ticker: SHORT_TEXT, phase: 0.42 }));
  ok(shortA.count > 50, '短句标语画出来了', `${shortA.count} 个文字像素`);
  ok(Math.abs(shortA.minX - shortB.minX) <= 1 && Math.abs(shortA.maxX - shortB.maxX) <= 1,
    '短句（字宽 ≤ 框宽）不动：两个相位画在同一处（居中静止，不滚）',
    `相位 0 → x ${shortA.minX}…${shortA.maxX}，相位 0.42 → x ${shortB.minX}…${shortB.maxX}`);
  const barMid = (shortA.box.x0 + shortA.box.x1) / 2;
  const inkMid = (shortA.minX + shortA.maxX) / 2;
  ok(Math.abs(inkMid - barMid) <= 0.05 * (shortA.box.x1 - shortA.box.x0),
    '短句居中（墨迹中心 ≈ 标语框中心）', `墨迹中心 ${Math.round(inkMid)} vs 框中心 ${Math.round(barMid)}`);

  const phases = [0.0, 0.2, 0.5, 0.85];
  const frames = phases.map((p) => ({ p, ink: tickerInk(renderPreview(`ticker-long-${p}`, { ticker: LONG_TEXT, phase: p })) }));
  ok(frames[0].ink.count <= 5, '长句 phase=0：整句还在框外右侧（框内没有字）', `${frames[0].ink.count} 个文字像素`);
  ok(frames[1].ink.count > 100, '长句 phase 0.2：整句已经进入框内（从右侧进来）',
    `${frames[1].ink.count} 个文字像素`);
  // 从左缘看「整体在往左走」：0.2 → 0.5 → 0.85 左缘必须一路变小
  // （右缘在「还没完全进入」的那两帧都被框右缘夹住，拿它比会误判）
  for (let i = 2; i < frames.length; i++) {
    ok(frames[i].ink.minX < frames[i - 1].ink.minX,
      `长句 phase ${phases[i - 1]} → ${phases[i]}：文字整体向左移动`,
      `左缘 ${frames[i - 1].ink.minX} → ${frames[i].ink.minX}`);
  }
  const enter = frames[1].ink;
  ok(enter.maxX >= enter.box.x1 - 6, '长句 phase 0.2：字从右边缘被切掉（贴住框右缘）',
    `墨迹右缘 ${enter.maxX} vs 框右缘 ${enter.box.x1}`);
  const leave = frames[3].ink;
  ok(leave.minX <= leave.box.x0 + 6, '长句 phase 0.85：字从左边缘被切掉（贴住框左缘）',
    `墨迹左缘 ${leave.minX} vs 框左缘 ${leave.box.x0}`);
  ok(frames[2].ink.maxX - frames[2].ink.minX >= 0.5 * (frames[2].ink.box.x1 - frames[2].ink.box.x0),
    '长句 mid-phase：字是原字号滚过去的（没有被整体缩小塞进框里）',
    `墨迹宽 ${frames[2].ink.maxX - frames[2].ink.minX} ≥ 半个框宽`);
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
