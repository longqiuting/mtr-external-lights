#!/usr/bin/env node
// 小仿真：方速屏蔽门玻璃「邻居自动连接」规则表（glass.auto[]）到底怎么算。
//
// 目的：不启动游戏就能验证
//   1. 规则表的判定表（左边是什么 × 右边是什么 → 左右两半各是什么形态）；
//   2. 方速的求值顺序（规则表倒序、命中就覆盖）和我们的求值顺序（正序取第一条命中）等价；
//   3. 一排「门 | 玻璃 玻璃 玻璃 | 门」摆下去，每块玻璃自动连成什么；
//   4. 每种形态在北向下的碰撞盒（两半的并集、端头留空）到底长什么样。
//
// 规则来源：v1.20.1/src/main/resources/assets/mtrlte/fangsu_psd/screendoor/xiamen/high.json 的
//           glass.auto[]；求值语义来自反编译的
//           com.fangsu.blockEntities.BlockEntityScreendoorGlass#recomputeAuto（规则表倒序遍历、
//           每条命中的规则覆盖一次 ⇒ 等价于正序取第一条命中）。
//
// 用法：node tools/sim-psd-glass-auto.js

'use strict';

const fs = require('fs');
const path = require('path');

const ASSET = path.join(__dirname, '..', 'v1.20.1', 'src', 'main', 'resources', 'assets', 'mtrlte',
  'fangsu_psd', 'screendoor', 'xiamen', 'high.json');

const DOOR = 'SCREENDOOR_DOOR';
const GLASS = 'SCREENDOOR_GLASS';
const NULL = 'NULL';

const json = JSON.parse(fs.readFileSync(ASSET, 'utf8'));
const glass = json.glass;

/** 两侧的形态列表：跳过 id == "auto" 那条（它只有 text / auto，没有 subModel / shape）。 */
function forms(side) {
  return (glass[side] || []).filter((e) => e.subModel && e.shape);
}
const LEFT_FORMS = forms('left');
const RIGHT_FORMS = forms('right');
const RULES = glass.auto || [];

/** 一条规则对某一侧的作用（null = 不改这一侧）。 */
const pick = (rule, side) => (rule[side] === undefined ? null : rule[side]);

/** 条件求值：if 是字符串 + 同级 is/not，或者 if 是对象（多条「与」）。 */
function ruleMatches(rule, l, r) {
  const get = (key) => (key === 'LEFT_BLOCK' ? l : key === 'RIGHT_BLOCK' ? r : NULL);
  const iff = rule.if;
  if (iff === undefined) return false;
  if (typeof iff === 'string') {
    if (iff === 'TRUE') return true;
    const target = get(iff);
    if (rule.is !== undefined) return target === rule.is;
    if (rule.not !== undefined) return target !== rule.not;
    return true;
  }
  if (typeof iff === 'object') {
    for (const [key, cond] of Object.entries(iff)) {
      const target = get(key);
      if (cond.is !== undefined && target !== cond.is) return false;
      if (cond.not !== undefined && target === cond.not) return false;
    }
    return true;
  }
  return false;
}

/** 我们的求值：正序取第一条命中、且写了这一侧的规则。 */
function resolveFirstMatch(l, r, side) {
  for (const rule of RULES) {
    if (!ruleMatches(rule, l, r)) continue;
    const form = pick(rule, side);
    if (form) return form;
  }
  return 'glass';
}

/** 方速的求值：从最后一条往前遍历，每条命中的都覆盖（后写入的是更靠前的规则）。 */
function resolveFangsu(l, r, side) {
  let out = null;
  for (let i = RULES.length - 1; i >= 0; i--) {
    const rule = RULES[i];
    if (!ruleMatches(rule, l, r)) continue;
    const form = pick(rule, side);
    if (form) out = form;
  }
  return out === null ? 'glass' : out;
}

const label = (v) => (v === DOOR ? '门扇' : v === GLASS ? '玻璃' : '空');
const pad = (s, n) => s + ' '.repeat(Math.max(0, n - [...s].reduce((a, c) => a + (c.charCodeAt(0) > 255 ? 2 : 1), 0)));

// ---------------------------------------------------------------- 1. 判定表

console.log('== 1. 邻居判定表（左右两个方向各三种可能）==\n');
console.log(`   ${pad('左邻居', 8)}${pad('右邻居', 8)}${pad('左半形态', 16)}${pad('右半形态', 16)}与方速一致`);
for (const l of [DOOR, GLASS, NULL]) {
  for (const r of [DOOR, GLASS, NULL]) {
    const left = resolveFirstMatch(l, r, 'left');
    const right = resolveFirstMatch(l, r, 'right');
    const same = resolveFangsu(l, r, 'left') === left && resolveFangsu(l, r, 'right') === right;
    console.log(`   ${pad(label(l), 8)}${pad(label(r), 8)}${pad(left, 16)}${pad(right, 16)}${same ? '✓' : '✗ 不一致'}`);
  }
}

console.log('\n   归纳（只看「有没有邻居」，邻居是门扇还是玻璃另算）：');
for (const l of [true, false]) {
  const row = [];
  for (const r of [true, false]) {
    const left = resolveFirstMatch(l ? GLASS : NULL, r ? GLASS : NULL, 'left');
    const right = resolveFirstMatch(l ? GLASS : NULL, r ? GLASS : NULL, 'right');
    row.push(`右${r ? '有' : '无'} → ${left}/${right}`);
  }
  console.log(`   左${l ? '有' : '无'}：${row.join('   ')}`);
}

// ---------------------------------------------------------------- 2. 一排玻璃

console.log('\n== 2. 一排：门 | 玻璃 玻璃 玻璃 | 门（同向，north）==\n');
const row = [DOOR, GLASS, GLASS, GLASS, DOOR];
for (let i = 0; i < row.length; i++) {
  const l = i > 0 ? row[i - 1] : NULL;
  const r = i + 1 < row.length ? row[i + 1] : NULL;
  if (row[i] === DOOR) {
    console.log(`   [${i}] 左=${pad(label(l), 4)}右=${pad(label(r), 4)} 门扇（不是玻璃，不参与 auto）`);
    continue;
  }
  const left = resolveFirstMatch(l, r, 'left');
  const right = resolveFirstMatch(l, r, 'right');
  const boxes = [...shapeOf(left, 'left'), ...shapeOf(right, 'right')];
  console.log(`   [${i}] 左=${pad(label(l), 4)}右=${pad(label(r), 4)} 玻璃 → ${pad(left, 9)}/${pad(right, 9)}`
    + `碰撞 ${boxes.map(fmt).join(' ∪ ')}`);
}

// ---------------------------------------------------------------- 3. 碰撞盒

/** 方速的 shape 变换：每转 90° 一次 (x, z) → (16 − z, x)；玻璃在北向转 2 步（= 模型差 180°）。 */
function rot90(box) {
  const [x1, y1, z1, x2, y2, z2] = box;
  return [16 - z2, y1, x1, 16 - z1, y2, x2];
}
function shapeOf(form, side) {
  const list = side === 'left' ? LEFT_FORMS : RIGHT_FORMS;
  const entry = list.find((f) => f.id === form);
  if (!entry) return [];
  return entry.shape.map((b) => {
    let box = b;
    for (let i = 0; i < 2; i++) box = rot90(box);
    return [
      Math.max(0, box[0]), box[1], Math.max(0, box[2]),
      Math.min(16, box[3]), box[4], Math.min(16, box[5]),
    ];
  });
}
function fmt(b) {
  return `[${b.map((v) => Math.round(v * 100) / 100).join(', ')}]`;
}

console.log('\n== 3. 北向下每种形态的碰撞盒（10 = 1 格；y 不裁 = 3 格高）==\n');
for (const side of ['left', 'right']) {
  console.log(`   ${side === 'left' ? '左半（素材 shape x 8..16；北向转 2 步后落在 x 0..8 = 西半）'
      : '右半（素材 shape x 0..8；北向转 2 步后落在 x 8..16 = 东半）'}：`);
  for (const form of forms(side).map((f) => f.id)) {
    const boxes = shapeOf(form, side);
    console.log(`     ${pad(form, 10)} ${boxes.map(fmt).join(' ∪ ')}`);
  }
}

// ---------------------------------------------------------------- 4. 刷子循环

console.log('\n== 4. 刷子循环（= 方速那个下拉框的顺序，auto 在第一位）==\n');
const cycle = ['auto', ...forms('left').map((f) => f.id)];
console.log(`   ${cycle.join(' → ')} → ${cycle[0]}`);
console.log('   点左半换左半、点右半换右半；潜行 + 刷子 = 整排退回 auto 重连。');
