#!/usr/bin/env node
/*
 * 时钟配置界面（ClockConfigScreen）的**布局自检** —— 离线复刻 init() 里的布局算术，
 * 检查「底部 完成 / 取消 永远在屏幕内、内容滚到底也能看到最后一行」。
 *
 * 背景（2026-11-19 修的那条）：底部三颗按钮画在 super.render() 那一遍里，而那一遍被
 * bodyTop…bodyBottom 的 scissor 包着 —— 按钮整颗落在裁剪区外，于是「看得见面板、看不见按钮」。
 * 视觉那半靠 drawFooter() 在裁剪之外重画一遍；这里检查的是另一半：**算术上它们确实在屏幕内**，
 * 且内容区（bodyTop…bodyBottom）永远不和底部栏重叠。
 *
 * 用法：
 *   node v1.20.1/tools/clock-config-layout-check.js
 *
 * 与 Java 侧一一对应的常量（ClockConfigScreen）：
 *   MAX_PANEL_W = 380, MAX_ROW_H = 22, MIN_ROW_H = 16, ROWS = 18,
 *   HEADER_H = 26, FOOTER_H = 38, PAD = 8, RESET_W = 18, GAP = 4
 *   rowH    = clamp((height - 4 - HEADER_H - FOOTER_H) / rowCount, MIN_ROW_H, MAX_ROW_H)
 *   panelH  = min(height - 4, max(HEADER_H + FOOTER_H + rowH, HEADER_H + rowCount*rowH + FOOTER_H))
 *   panelY  = max(2, (height - panelH) / 2)
 *   bodyTop = panelY + HEADER_H ;  bodyBottom = panelY + panelH - FOOTER_H
 *   by      = panelY + panelH - 24（按钮高 20）
 *   visibleRows = floor((bodyBottom - bodyTop) / rowH)
 *   maxScroll   = max(0, rowCount - visibleRows)
 */
'use strict';

const MAX_PANEL_W = 380;
const MAX_ROW_H = 22;
const MIN_ROW_H = 16;
const ROWS = 18;
const HEADER_H = 26;
const FOOTER_H = 38;
const PAD = 8;

const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));

function layout(height, global) {
  const rowCount = ROWS + (global ? 1 : 0);
  const available = Math.max(0, height - 4 - HEADER_H - FOOTER_H);
  const rowH = clamp(Math.floor(available / Math.max(1, rowCount)), MIN_ROW_H, MAX_ROW_H);
  const minPanelH = HEADER_H + FOOTER_H + rowH;
  const panelH = Math.min(height - 4, Math.max(minPanelH, HEADER_H + rowCount * rowH + FOOTER_H));
  const panelY = Math.max(2, Math.floor((height - panelH) / 2));
  const bodyTop = panelY + HEADER_H;
  const bodyBottom = panelY + panelH - FOOTER_H;
  const visibleRows = Math.max(1, Math.floor((bodyBottom - bodyTop) / rowH));
  const maxScroll = Math.max(0, rowCount - visibleRows);
  const buttonY = panelY + panelH - 24;
  const lastRowY = bodyTop + (rowCount - 1 - maxScroll) * rowH;
  return { rowCount, rowH, panelH, panelY, bodyTop, bodyBottom, visibleRows, maxScroll, buttonY, lastRowY };
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
    if (l.lastRowY < l.bodyTop) problems.push('最后一行滚过头');
    // 6) 滚到顶能看到第一行
    if (l.bodyTop + l.rowH > l.bodyBottom) problems.push('一行都放不下');
    if (problems.length) failed++;
    console.log(`  高 ${String(height).padStart(4)}px  行高 ${String(l.rowH).padStart(2)}px `
      + `面板 ${l.panelY}..${l.panelY + l.panelH}  内容 ${l.bodyTop}..${l.bodyBottom} `
      + `可视 ${String(l.visibleRows).padStart(2)}/${l.rowCount} 行  最多滚 ${l.maxScroll} 行 `
      + `按钮 y=${l.buttonY}..${l.buttonY + 20}  `
      + (problems.length ? '❌ ' + problems.join(' / ') : '✅'));
  }
}

console.log(failed === 0
  ? '\n全部布局断言通过：底部栏在每种窗口高度下都完整可见，滚到底能看到最后一行。'
  : `\n${failed} 种情况不通过。`);
process.exit(failed === 0 ? 0 : 1);
