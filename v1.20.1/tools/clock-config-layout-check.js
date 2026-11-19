#!/usr/bin/env node
/*
 * 时钟配置界面（ClockConfigScreen）的**布局自检** —— 离线复刻 init() 里的布局算术，
 * 检查：
 *   A. 底部 完成 / 取消 永远在屏幕内；
 *   B. 内容滚到底也能看到最后一行；
 *   C. **顶部那条固定的「刻度 / 数字」条**（TICK_ROW_SLOTS）首次打开就在屏幕里、能点到、
 *      不被标题栏 / 底部栏 / 滚动行盖住（含 480p）。
 *
 * 背景：
 *   1) 2026-11-19 修的那条：底部三颗按钮画在 super.render() 那一遍里，而那一遍被
 *      bodyTop…bodyBottom 的 scissor 包着 —— 按钮整颗落在裁剪区外，于是「看得见面板、看不见按钮」。
 *      视觉那半靠 drawFooter() 在裁剪之外重画一遍；这里检查的是另一半：**算术上它们确实在屏幕内**，
 *      且内容区（bodyTop…bodyBottom）永远不和底部栏重叠。
 *   2) 2026-11-19 晚的「刻度 / 数字」入口：用户找不到改刻度的地方。现在它独占内容区最上面一条
 *      固定栏（不滚），本脚本断言它在每种窗口高度下都完整落在 bodyTop…bodyBottom 里、
 *      在滚动行起点之上、且不和第一行重叠。
 *
 * 用法：
 *   node v1.20.1/tools/clock-config-layout-check.js
 *
 * 与 Java 侧一一对应的常量（ClockConfigScreen）：
 *   MAX_PANEL_W = 380, MAX_ROW_H = 22, MIN_ROW_H = 16, ROWS = 18, TICK_ROW_SLOTS = 1,
 *   HEADER_H = 26, FOOTER_H = 38, PAD = 8, RESET_W = 18, GAP = 4
 *   slotCount = rowCount + TICK_ROW_SLOTS
 *   rowH    = clamp((height - 4 - HEADER_H - FOOTER_H) / slotCount, MIN_ROW_H, MAX_ROW_H)
 *   panelH  = min(height - 4, max(HEADER_H + FOOTER_H + rowH*2, HEADER_H + slotCount*rowH + FOOTER_H))
 *   panelY  = max(2, (height - panelH) / 2)
 *   bodyTop = panelY + HEADER_H ;  bodyBottom = panelY + panelH - FOOTER_H
 *   rowsTop = bodyTop + TICK_ROW_SLOTS * rowH                     （固定刻度条之下）
 *   tick    = [bodyTop, bodyTop + rowH - 4]                        （按钮高 rowH-4）
 *   by      = panelY + panelH - 24（按钮高 20）
 *   visibleRows = floor((bodyBottom - rowsTop) / rowH)
 *   maxScroll   = max(0, rowCount - visibleRows)
 */
'use strict';

const MAX_PANEL_W = 380;
const MAX_ROW_H = 22;
const MIN_ROW_H = 16;
const ROWS = 18;
const TICK_ROW_SLOTS = 1;
const HEADER_H = 26;
const FOOTER_H = 38;
const PAD = 8;
const CONTENT_W = MAX_PANEL_W - 2 * PAD - 18 - 4;   // panelW − 2PAD − RESET_W − GAP

const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));

function layout(height, global) {
  const rowCount = ROWS + (global ? 1 : 0);
  const slotCount = rowCount + TICK_ROW_SLOTS;
  const available = Math.max(0, height - 4 - HEADER_H - FOOTER_H);
  const rowH = clamp(Math.floor(available / Math.max(1, slotCount)), MIN_ROW_H, MAX_ROW_H);
  const minPanelH = HEADER_H + FOOTER_H + rowH * 2;
  const panelH = Math.min(height - 4, Math.max(minPanelH, HEADER_H + slotCount * rowH + FOOTER_H));
  const panelY = Math.max(2, Math.floor((height - panelH) / 2));
  const bodyTop = panelY + HEADER_H;
  const bodyBottom = panelY + panelH - FOOTER_H;
  const rowsTop = bodyTop + TICK_ROW_SLOTS * rowH;
  const visibleRows = Math.max(1, Math.floor((bodyBottom - rowsTop) / rowH));
  const maxScroll = Math.max(0, rowCount - visibleRows);
  const buttonY = panelY + panelH - 24;
  const lastRowY = rowsTop + (rowCount - 1 - maxScroll) * rowH;
  // 固定刻度条：按钮 y = bodyTop、高 rowH-4；它的 ↺ 同高
  const tickY = bodyTop;
  const tickH = rowH - 4;
  const firstRowY = rowsTop;                       // 滚动区第一行（scrollRows = 0）
  return {
    rowCount, rowH, panelH, panelY, bodyTop, bodyBottom, rowsTop,
    visibleRows, maxScroll, buttonY, lastRowY,
    tickY, tickH, tickBottom: tickY + tickH, firstRowY,
  };
}

const HEIGHTS = [240, 300, 360, 480, 720, 1080];
let failed = 0;

for (const global of [false, true]) {
  console.log(`\n=== ${global ? '全局模式（19 行）' : '单块模式（18 行）'} ===`);
  for (const height of HEIGHTS) {
    const l = layout(height, global);
    const problems = [];
    // 1) 面板在屏幕内
    if (l.panelY < 0 || l.panelY + l.panelH > height) problems.push('面板越出屏幕');
    // 2) 底部栏永远在屏幕内
    if (l.buttonY < 0 || l.buttonY + 20 > height) problems.push('底部按钮越出屏幕');
    // 3) 底部栏在内容区之下（不和行重叠）
    if (l.buttonY < l.bodyBottom) problems.push('底部按钮压在内容区里');
    // 4) 内容区非空
    if (l.bodyBottom <= l.bodyTop) problems.push('内容区高度 <= 0');
    // 5) 滚到底能看到最后一行
    if (l.lastRowY + l.rowH > l.bodyBottom) problems.push('滚到底仍看不全最后一行');
    if (l.lastRowY < l.rowsTop) problems.push('最后一行滚过头');
    // 6) 滚到顶能看到第一行
    if (l.rowsTop + l.rowH > l.bodyBottom) problems.push('一行都放不下');
    // 7) 固定「刻度 / 数字」条：首次打开就在内容区里（不被标题/底栏挤掉）
    if (l.tickY < l.bodyTop) problems.push('刻度条跑到标题栏里');
    if (l.tickBottom > l.bodyBottom) problems.push('刻度条超出内容区（会被底部栏盖住）');
    // 8) 刻度条不被滚动行盖住：它必须整条在滚动区起点之上
    if (l.tickBottom > l.rowsTop) problems.push('刻度条和第一行重叠');
    // 9) 刻度条高度足够点（至少 10px，正常 12–18px）
    if (l.tickH < 10) problems.push('刻度条按钮太矮点不到（' + l.tickH + 'px）');
    // 10) 刻度条整行宽度够显示文字（按钮宽 = contentW，↺ 另占一列）
    if (CONTENT_W < 120) problems.push('刻度条太窄');
    if (problems.length) failed++;
    console.log(`  高 ${String(height).padStart(4)}px  行高 ${String(l.rowH).padStart(2)}px `
      + `面板 ${l.panelY}..${l.panelY + l.panelH}  内容 ${l.bodyTop}..${l.bodyBottom} `
      + `可视 ${String(l.visibleRows).padStart(2)}/${l.rowCount} 行  最多滚 ${l.maxScroll} 行  `
      + `刻度条 y=${l.tickY}..${l.tickBottom}(h=${l.tickH})  按钮 y=${l.buttonY}..${l.buttonY + 20}  `
      + (problems.length ? '❌ ' + problems.join(' / ') : '✅'));
  }
}

// 480p 单独再叫一次（用户点名的那档）
const p480 = layout(480, false);
const p480g = layout(480, true);
console.log('\n480p 汇总：');
console.log(`  单块 刻度条 ${p480.tickY}..${p480.tickBottom}（内容 ${p480.bodyTop}..${p480.bodyBottom}）`
  + ` 滚动 ${p480.maxScroll} 行  ${p480.tickBottom <= p480.rowsTop && p480.tickBottom <= p480.bodyBottom ? '✅ 首屏可见' : '❌'}`);
console.log(`  全局 刻度条 ${p480g.tickY}..${p480g.tickBottom}（内容 ${p480g.bodyTop}..${p480g.bodyBottom}）`
  + ` 滚动 ${p480g.maxScroll} 行  ${p480g.tickBottom <= p480g.rowsTop && p480g.tickBottom <= p480g.bodyBottom ? '✅ 首屏可见' : '❌'}`);

console.log(failed === 0
  ? '\n全部布局断言通过：底部栏在每种窗口高度下都完整可见，滚到底能看到最后一行，'
    + '「刻度 / 数字」固定条首屏可见且不与滚动行重叠。'
  : `\n${failed} 种情况不通过。`);
process.exit(failed === 0 ? 0 : 1);
