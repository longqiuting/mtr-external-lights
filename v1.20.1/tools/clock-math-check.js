#!/usr/bin/env node
/*
 * 时钟几何自检 —— 离线复刻 ClockTime / ClockRenderer 里的角度与半径公式，
 * 把四个时刻的三根指针算成「角度 + 端点坐标」，用来对着游戏里看到的钟面核对。
 *
 * 用法：
 *   node v1.20.1/tools/clock-math-check.js
 *
 * 与 Java 侧一一对应的三处：
 *   v1.20.1/src/main/java/dev/js/mtrheadlight/clock/ClockTime.java
 *     hourFraction  = ((h % 12) + m/60 + s/3600) / 12     ← 12 点 = 0
 *     minuteFraction= (m + s/60) / 60
 *     secondFraction= s / 60
 *   v1.20.1/src/main/java/dev/js/mtrheadlight/clock/ClockRenderer.java
 *     R = max(hourLen, minuteLen, secondLen) * 1.08
 *     端点 = ( sin(2πf)·L , cos(2πf)·L )                  ← 2D 钟面坐标，x 向右、y 向上
 *     角度 = f·360°，从 12 点方向顺时针
 */

'use strict';

/** 与 ClockConfig 的默认值一致。 */
const LENGTHS = {
  hour: 0.20,   // 格
  minute: 0.28,
  second: 0.33,
};

/** 钟面半径：最长那根针 × 1.08（ClockRenderer#render）。 */
const R = Math.max(LENGTHS.hour, LENGTHS.minute, LENGTHS.second) * 1.08;

const TWO_PI = Math.PI * 2;

function wrapSeconds(seconds) {
  let s = seconds % 86400;
  if (s < 0) {
    s += 86400;
  }
  return s;
}

function hourFraction(secondsOfDay) {
  const s = wrapSeconds(secondsOfDay);
  const h12 = (s / 3600) % 12;
  return h12 / 12;
}

function minuteFraction(secondsOfDay) {
  return (wrapSeconds(secondsOfDay) % 3600) / 3600;
}

function secondFraction(secondsOfDay) {
  return (wrapSeconds(secondsOfDay) % 60) / 60;
}

/** 12 小时制钟点（12 点 / 0 点都算 12）。 */
function hour12(secondsOfDay) {
  const h = Math.floor(wrapSeconds(secondsOfDay) / 3600) % 12;
  return h === 0 ? 12 : h;
}

function endpoint(fraction, length) {
  const th = fraction * TWO_PI;
  return {
    x: Math.sin(th) * length,
    y: Math.cos(th) * length,
    deg: fraction * 360,
  };
}

function fmt(v, digits = 4) {
  return (Math.abs(v) < 1e-12 ? 0 : v).toFixed(digits);
}

/* 方块局部空间：钟心 (0.5,0.5,0.5)，FACING=south 时 n=+Z、right=+X、up=+Y。
   照着 ClockRenderer.Frame 的基向量公式算出来，用来核对「面内坐标 → 世界坐标」这一步。 */
function localPoint(x, y, depth) {
  return { x: 0.5 + x, y: 0.5 + y, z: 0.5 + depth };
}

const CASES = [
  { label: '12:00', h: 12, m: 0, s: 0 },
  { label: '3:00', h: 3, m: 0, s: 0 },
  { label: '7:35', h: 7, m: 35, s: 0 },
  { label: '23:59', h: 23, m: 59, s: 0 },
];

/** 用户需求里的公式（((h%12)+10)/12）—— 只用来演示它与「12 点 = 0」的差别。 */
function specSketch(secondsOfDay) {
  const h = Math.floor(wrapSeconds(secondsOfDay) / 3600);
  return ((h % 12) + 10) / 12;
}

console.log('时钟几何自检 —— 默认长度 时针 ' + LENGTHS.hour + ' / 分针 ' + LENGTHS.minute
  + ' / 秒针 ' + LENGTHS.second + ' 格');
console.log('钟面半径 R = max(三针长度) × 1.08 = ' + R.toFixed(4) + ' 格');
console.log('角度约定：从 12 点方向起、顺时针为正；端点坐标是钟面 2D 坐标（x 向右、y 向上）\n');

for (const c of CASES) {
  const seconds = c.h * 3600 + c.m * 60 + c.s;
  const fh = hourFraction(seconds);
  const fm = minuteFraction(seconds);
  const fs = secondFraction(seconds);

  const eh = endpoint(fh, LENGTHS.hour);
  const em = endpoint(fm, LENGTHS.minute);
  const es = endpoint(fs, LENGTHS.second);

  console.log('=== ' + c.label + ' ===  (一天内第 ' + seconds + ' 秒, 12 小时制钟点 '
    + hour12(seconds) + ')');
  console.log('  时针  f=' + fmt(fh) + '  角度=' + fmt(eh.deg, 2) + '°  '
    + '端点=(' + fmt(eh.x) + ', ' + fmt(eh.y) + ')  长度=' + LENGTHS.hour);
  console.log('  分针  f=' + fmt(fm) + '  角度=' + fmt(em.deg, 2) + '°  '
    + '端点=(' + fmt(em.x) + ', ' + fmt(em.y) + ')  长度=' + LENGTHS.minute);
  console.log('  秒针  f=' + fmt(fs) + '  角度=' + fmt(es.deg, 2) + '°  '
    + '端点=(' + fmt(es.x) + ', ' + fmt(es.y) + ')  长度=' + LENGTHS.second);

  // facet=south（钟面朝 +Z）时，钟面坐标 (x,y) 就是方块局部空间的 (+x,+y) 偏移。
  const ph = localPoint(eh.x, eh.y, 0.478);
  console.log('  → FACING=south 时针端点（方块局部 0..1）: ('
    + fmt(ph.x) + ', ' + fmt(ph.y) + ', ' + fmt(ph.z) + ')');

  const sketch = specSketch(seconds);
  console.log('  （附：需求草稿式 ((h%12)+10)/12 = ' + fmt(sketch) + '，本实现取 '
    + fmt(fh) + ' —— 12 点=0 的语义）');
  console.log('');
}

// ---- 断言：四个时刻的关键值，防止以后改公式时悄悄跑偏 ----
const checks = [
  ['12:00 时针朝上', hourFraction(12 * 3600), 0],
  ['3:00 时针正右', hourFraction(3 * 3600), 0.25],
  ['6:00 时针正下', hourFraction(6 * 3600), 0.5],
  ['23:59 时针 ≈ 12 点', hourFraction(23 * 3600 + 59 * 60), (11 + 59 / 60) / 12],
  ['12:00 分针朝上', minuteFraction(12 * 3600), 0],
  ['7:35 分针 = 35/60', minuteFraction(7 * 3600 + 35 * 60), 35 / 60],
  ['秒针 30 秒 = 0.5', secondFraction(30), 0.5],
];

let failed = 0;
for (const [name, got, want] of checks) {
  const ok = Math.abs(got - want) < 1e-9;
  if (!ok) {
    failed++;
    console.log('FAIL ' + name + ': got ' + got + ', want ' + want);
  }
}
console.log(failed === 0
  ? '全部 ' + checks.length + ' 项断言通过。'
  : failed + ' 项断言失败。');
process.exit(failed === 0 ? 0 : 1);
