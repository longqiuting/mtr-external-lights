#!/usr/bin/env node
/*
 * PSD-LCD 模型的「用户原样」对账工具
 * =================================
 *
 * 这块吊板的两套几何都**不是**我们自己画的，是用户从 Blockbench 里给的
 * `psd_lcd_xiamen.json` 的两组（`normal` / `half`）—— 所以判断「模型对不对」只有一个标准：
 * 和那份 json 的对应组**逐字段相等**，一个数字都不许改。
 *
 *   normal 组（element 0..7）   →  models/block/psd_lcd_xiamen.json        （整块：窗口 y 1.5…8.5）
 *   half   组（element 8..14）  →  models/block/psd_lcd_xiamen_half.json   （只剩上半格：窗口 y 9…16）
 *
 * 参考文件放在 tools/psd/psd_lcd_xiamen.json（用户给的导出，原封不动）；
 * 想拿别的副本对账就设环境变量 PSD_LCD_REF=<path>。
 *
 * 只做搬运 / 比较，不写文件；两个自检脚本（psd-lcd-facing-check.js / psd-lcd-fit-check.js）共用。
 */
'use strict';

const fs = require('fs');
const path = require('path');

const HERE = __dirname;                          // …/v1.20.1/tools
const VERSION_DIR = path.resolve(HERE, '..');

/** 用户给的 Blockbench 导出（两组的唯一真源）。 */
const REF_PATH = process.env.PSD_LCD_REF
  || path.join(HERE, 'psd', 'psd_lcd_xiamen.json');

const MODEL_DIR = path.join(VERSION_DIR, 'src/main/resources/assets/mtrlte/models/block');

/** 方块方块状态里的两档 ↔ 两份模型 ↔ 参考 json 里的组名。 */
const STATES = [
  {
    cut: false,
    key: 'half_cut=false',
    model: 'mtrlte:block/psd_lcd_xiamen',
    file: 'psd_lcd_xiamen.json',
    group: 'normal',
    windowY0: 1.5,
    windowY1: 8.5,
    label: '整块（half_cut=false）',
  },
  {
    cut: true,
    key: 'half_cut=true',
    model: 'mtrlte:block/psd_lcd_xiamen_half',
    file: 'psd_lcd_xiamen_half.json',
    group: 'half',
    windowY0: 9.0,
    windowY1: 16.0,
    label: '只剩上半格（half_cut=true）',
  },
];

/** 参与比对的所有字段（多一个少一个都算不一致）。 */
const ELEMENT_FIELDS = ['name', 'from', 'to', 'rotation', 'faces'];

function readJson(file) {
  return JSON.parse(fs.readFileSync(file, 'utf8'));
}

/** 参考 json（用户原件）。 */
function loadRef(file = REF_PATH) {
  const ref = readJson(file);
  if (!Array.isArray(ref.elements)) {
    throw new Error('参考 json 里没有 elements: ' + file);
  }
  return ref;
}

/** 参考 json 里某一组（normal / half）的元素，按 children 顺序取。 */
function groupElements(ref, groupName) {
  const group = (ref.groups || []).find((g) => g.name === groupName);
  if (!group) {
    throw new Error('参考 json 里没有组 ' + groupName);
  }
  return group.children.map((i) => ref.elements[i]);
}

/** 模型文件路径。 */
function modelPath(file) {
  return path.join(MODEL_DIR, file);
}

/** 递归按 key 排序，只为「值相同但 key 顺序不同」不算差异。 */
function canonical(value) {
  if (Array.isArray(value)) {
    return value.map(canonical);
  }
  if (value && typeof value === 'object') {
    const out = {};
    for (const key of Object.keys(value).sort()) {
      out[key] = canonical(value[key]);
    }
    return out;
  }
  return value;
}

const same = (a, b) => JSON.stringify(canonical(a)) === JSON.stringify(canonical(b));

/**
 * 一个模型 vs 参考 json 的某一组：逐 element、逐字段比对。
 *
 * @returns {{ok: boolean, total: number, diffs: string[]}}
 */
function compareModelToGroup(model, expected) {
  const diffs = [];
  const actual = model.elements || [];
  let checked = 0;
  if (actual.length !== expected.length) {
    diffs.push(`element 个数 ${actual.length}，参考组 ${expected.length}`);
  }
  const n = Math.min(actual.length, expected.length);
  for (let i = 0; i < n; i++) {
    const a = actual[i];
    const e = expected[i];
    for (const field of ELEMENT_FIELDS) {
      checked++;
      if (!same(a[field], e[field])) {
        diffs.push(`element[${i}] ${e.name || ''}.${field}: 模型 ${JSON.stringify(a[field])} `
          + `≠ 参考 ${JSON.stringify(e[field])}`);
      }
    }
  }
  return { ok: diffs.length === 0, total: checked, diffs };
}

module.exports = {
  REF_PATH,
  MODEL_DIR,
  STATES,
  ELEMENT_FIELDS,
  readJson,
  loadRef,
  groupElements,
  modelPath,
  compareModelToGroup,
  canonical,
};
