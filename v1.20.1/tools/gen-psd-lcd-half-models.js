#!/usr/bin/env node
/*
 * PSD-LCD 半格模型生成器
 * ======================
 *
 * 整格模型 `psd_lcd_xiamen.json` 的箱体沿「铺开轴」是 model x 0…16（整格宽）。
 * 剪刀把面板剪成半格以后，模型只占其中一半：
 *
 *   half=left   → 保留 model x  8…16   （观察者看来的左半：基础朝向朝北时 +x 是屏幕左边）
 *   half=right  → 保留 model x  0…8    （观察者看来的右半）
 *
 * 两份半格模型**从整格模型生成**，不手抄：以后改整格模型的板厚 / 凹槽 / 倒角，
 * 重跑一次这个脚本就同步了。变换规则只有两条：
 *
 *   1. 每个 element 的 from[0] / to[0] 夹到保留区间；
 *   2. up / down / north / south 四个面的 uv 的 u 按同一比例跟着切
 *      （这四面上 u 与 +x 同向；east / west 面是板厚那两面，u 只有 0…6，不动）。
 *
 * 用法（仓库根目录）：
 *   node v1.20.1/tools/gen-psd-lcd-half-models.js
 *   node v1.20.1/tools/gen-psd-lcd-half-models.js --check   # 只校验现有文件是否与生成结果一致
 *
 * 退出码：0 = 生成/校验通过；1 = --check 时文件落后（改了整格模型忘了重跑）。
 */
'use strict';

const fs = require('fs');
const path = require('path');

const HERE = __dirname;
const VERSION_DIR = path.resolve(HERE, '..');
const MODEL_DIR = path.join(VERSION_DIR, 'src/main/resources/assets/mtrlte/models/block');
const SOURCE = path.join(MODEL_DIR, 'psd_lcd_xiamen.json');

/** 保留段（model 空间 x，16 分之格）。 */
const HALVES = [
  { suffix: 'left', x0: 8, x1: 16, note: '半格（观察者左半）：model x 8…16' },
  { suffix: 'right', x0: 0, x1: 8, note: '半格（观察者右半）：model x 0…8' },
];

/** 这四面上 u 与 +x 同向，需要跟着切；east / west 是板厚面，不动。 */
const X_FACES = new Set(['up', 'down', 'north', 'south']);

const round = (v) => Math.round(v * 10000) / 10000;

/** u 在保留段里的对应位置：按 16 分之格线性映射。 */
function remapU(u, x0, x1) {
  const scale = (x1 - x0) / 16;
  return round(x0 + u * scale);
}

function clamp(v, lo, hi) {
  return Math.min(hi, Math.max(lo, v));
}

function buildHalf(source, spec) {
  const model = JSON.parse(JSON.stringify(source));
  const { x0, x1, suffix, note } = spec;

  model.credit = String(model.credit || '')
    + ` Half-width variant (${note}), generated from psd_lcd_xiamen.json by`
    + ' tools/gen-psd-lcd-half-models.js - only the run axis is cut, the section'
    + ' (6px body / 0.5px groove / 1.5..8.5 window band) is untouched.';

  for (const element of model.elements || []) {
    element.from = [x0, element.from[1], element.from[2]];
    element.to = [x1, element.to[1], element.to[2]];
    if (element._comment) {
      element._comment = `${element._comment} [half=${suffix}: x ${x0}..${x1}]`;
    }
    for (const [faceName, face] of Object.entries(element.faces || {})) {
      if (!X_FACES.has(faceName) || !Array.isArray(face.uv)) {
        continue;
      }
      const uv = face.uv;
      // uv = [u0, v0, u1, v1]（16 分之格）；u0 < u1 在这一套模型里恒成立。
      face.uv = [remapU(uv[0], x0, x1), uv[1], remapU(uv[2], x0, x1), uv[3]];
    }
    // rotation.origin 的 x 分量对 axis=x 的旋转没有影响，保持原样。
  }
  return model;
}

function main() {
  const check = process.argv.includes('--check');
  const source = JSON.parse(fs.readFileSync(SOURCE, 'utf8'));
  let failed = false;

  for (const spec of HALVES) {
    const target = path.join(MODEL_DIR, `psd_lcd_xiamen_half_${spec.suffix}.json`);
    const text = JSON.stringify(buildHalf(source, spec), null, '\t') + '\n';
    const existing = fs.existsSync(target) ? fs.readFileSync(target, 'utf8') : null;
    if (check) {
      if (existing !== text) {
        console.log(`  FAIL  ${path.basename(target)} is stale - re-run without --check`);
        failed = true;
      } else {
        console.log(`  ok    ${path.basename(target)}`);
      }
      continue;
    }
    fs.writeFileSync(target, text);
    console.log(`  wrote ${path.relative(VERSION_DIR, target)} (x ${spec.x0}..${spec.x1})`);
  }
  process.exit(failed ? 1 : 0);
}

main();
