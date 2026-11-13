#!/usr/bin/env node
/*
 * 校验厦门 2 号线（_l2）屏蔽门贴图是否齐全。
 *
 * 做什么：
 *   1. 从 blockstates / models 里递归收集所有 `*_l2` 贴图引用（含 parent 链）；
 *   2. 每个引用检查 textures/block/<name>.png 是否存在；
 *   3. 读 PNG 文件头（signature + IHDR）确认是合法 PNG；
 *      宽高必须与同名 `_l3` / 无后缀版一致（屏蔽门贴图有 64×64 / 128×128 / 256×256 /
 *      320×288 / 576×288 等多种画布，所以尺寸基准是"同组其他线"，不是固定 256）。
 *   4. 若 `_l2` 与 1 号线同名文件逐字节相同，给出"疑似未换图"警告。
 *
 * 用法：
 *   node tools/verify-line2-textures.js [资源根目录]
 *   默认根目录：v1.20.1/src/main/resources/assets/mtrlte
 *
 * 退出码：0 = 无缺口；1 = 有缺失 / 非法 PNG。
 */
const fs = require('fs');
const path = require('path');

const NS = 'mtrlte';
const DEFAULT_ROOT = path.join(__dirname, '..', 'v1.20.1', 'src', 'main', 'resources', 'assets', NS);

const args = process.argv.slice(2);
const positional = args.filter(a => !a.startsWith('--'));
const materialArg = (args.find(a => a.startsWith('--material=')) || '').split('=').slice(1).join('=');
const resourceRoot = positional[0] ? path.resolve(positional[0]) : DEFAULT_ROOT;

if (!fs.existsSync(resourceRoot)) {
  console.error(`资源目录不存在: ${resourceRoot}`);
  process.exit(1);
}

const BS = path.join(resourceRoot, 'blockstates');
const MODELS = path.join(resourceRoot, 'models');
const TEX = path.join(resourceRoot, 'textures');

function walk(dir, out = []) {
  if (!fs.existsSync(dir)) return out;
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (e.name.endsWith('.json')) out.push(p);
  }
  return out;
}

function splitId(id, defNs) {
  const i = id.indexOf(':');
  return i < 0 ? [defNs, id] : [id.slice(0, i), id.slice(i + 1)];
}

/* ---------- 1. 收集 `_l2` 贴图引用 ---------- */
// refs: texName -> Set("model 名:key")
const refs = new Map();
const externalNs = new Set(['minecraft', 'mtr', 'mtrsteamloco', 'nte', 'lambdynlights']);

function collectFromModel(modelPath, from, seen) {
  const key = modelPath;
  if (seen.has(key)) return;
  seen.add(key);
  const file = path.join(MODELS, modelPath + '.json');
  if (!fs.existsSync(file)) return; // 模型缺失不在本脚本职责内（check-psd-assets.js 管）
  let json;
  try {
    json = JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (e) {
    console.error(`!! JSON 解析失败: ${file}`);
    return;
  }
  if (json.parent) {
    const [pns, pp] = splitId(json.parent, 'minecraft');
    if (pns === NS && pp.startsWith('block/')) collectFromModel(pp, modelPath, seen);
  }
  for (const [k, v] of Object.entries(json.textures || {})) {
    if (typeof v !== 'string' || v.startsWith('#')) continue;
    const [tns, tp] = splitId(v, 'minecraft');
    if (tns !== NS) continue;
    if (!tp.startsWith('block/')) continue;
    const name = tp.slice('block/'.length);
    if (!name.endsWith('_l2')) continue;
    if (!refs.has(name)) refs.set(name, new Set());
    refs.get(name).add(`${modelPath}#${k}`);
  }
}

// 从每个 blockstate / 每个 model 出发（覆盖 blockstates 引用 + 直接引用的模型）
for (const f of walk(BS)) {
  let json;
  try {
    json = JSON.parse(fs.readFileSync(f, 'utf8'));
  } catch (e) {
    console.error(`!! JSON 解析失败: ${f}`);
    continue;
  }
  const from = path.relative(resourceRoot, f);
  const models = [];
  if (json.variants) {
    for (const variant of Object.values(json.variants)) {
      for (const v of (Array.isArray(variant) ? variant : [variant])) if (v && v.model) models.push(v.model);
    }
  }
  for (const part of json.multipart || []) {
    for (const apply of (Array.isArray(part.apply) ? part.apply : [part.apply])) if (apply && apply.model) models.push(apply.model);
  }
  for (const m of models) {
    const [mns, mp] = splitId(m, NS);
    if (mns === NS) collectFromModel(mp, from, new Set());
  }
}
// 兜底：直接扫描所有模型文件，避免漏掉没进 blockstate 的
for (const f of walk(MODELS)) {
  if (!path.basename(f).endsWith('_l2.json')) continue;
  collectFromModel('block/' + path.basename(f, '.json'), 'scan', new Set());
}

/* ---------- 2. PNG 校验 ---------- */
const PNG_SIG = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

function readPng(file) {
  const buf = fs.readFileSync(file);
  if (buf.length < 33 || !buf.subarray(0, 8).equals(PNG_SIG)) return { ok: false, why: '不是 PNG（signature 不匹配）' };
  const ihdrLen = buf.readUInt32BE(8);
  const ihdrType = buf.subarray(12, 16).toString('ascii');
  if (ihdrType !== 'IHDR' || ihdrLen !== 13) return { ok: false, why: `首块不是 IHDR（${ihdrType}/${ihdrLen}）` };
  const w = buf.readUInt32BE(16);
  const h = buf.readUInt32BE(20);
  const bitDepth = buf[24];
  const colorType = buf[25];
  if (w === 0 || h === 0) return { ok: false, why: `IHDR 宽高非法 ${w}x${h}` };
  if (buf.length < 8 + 25 + 12) return { ok: false, why: '文件被截断' };
  return { ok: true, w, h, bitDepth, colorType, bytes: buf.length };
}

/* ---------- 3. 报告 ---------- */
const names = [...refs.keys()].sort();
const missing = [];
const invalid = [];
const sizeWarn = [];
const sameAsLine1 = [];

for (const n of names) {
  const file = path.join(TEX, 'block', n + '.png');
  if (!fs.existsSync(file)) { missing.push(n); continue; }
  const info = readPng(file);
  if (!info.ok) { invalid.push(`${n}: ${info.why}`); continue; }
  // 尺寸基准：同组其他线（_l3 优先，其次无后缀的 1 号线版本）
  const ref = ['_l3', '_l4', '_l6', ''].map(s => path.join(TEX, 'block', n.replace(/_l2$/, '') + s + '.png')).find(p => fs.existsSync(p));
  if (ref) {
    const r = readPng(ref);
    if (r.ok && (r.w !== info.w || r.h !== info.h)) {
      sizeWarn.push(`${n}: ${info.w}x${info.h} 与 ${path.basename(ref)} 的 ${r.w}x${r.h} 不一致`);
    }
  }
  // 语义检查：_l2 与 1 号线同名图逐字节相同，通常意味着"线 2 用了 1 号线旧图"
  const l1 = path.join(TEX, 'block', n.replace(/_l2$/, '') + '.png');
  if (fs.existsSync(l1) && fs.readFileSync(l1).equals(fs.readFileSync(file))) sameAsLine1.push(n);
}

console.log(`资源根目录: ${resourceRoot}`);
console.log(`_l2 贴图引用: ${names.length} 个（去重名），来自 ${[...refs.values()].reduce((a, s) => a + s.size, 0)} 处 model#key`);
for (const n of names) console.log(`  ${n}  <-  ${[...refs.get(n)].join(', ')}`);
console.log('');

if (invalid.length) {
  console.log(`非法 PNG: ${invalid.length} 个`);
  for (const i of invalid) console.log('  ' + i);
}
if (sizeWarn.length) {
  console.log(`尺寸异常: ${sizeWarn.length} 个`);
  for (const i of sizeWarn) console.log('  ' + i);
}
if (sameAsLine1.length) {
  console.log(`警告: ${sameAsLine1.length} 个 _l2 贴图与 1 号线同名文件完全相同（疑似未换图）`);
  for (const i of sameAsLine1) console.log('  ' + i);
}

if (missing.length) {
  console.log('缺失清单:');
  for (const n of missing) console.log(`  ${n}.png  <-  ${[...refs.get(n)].join(', ')}`);
}
console.log('');
console.log(`缺 ${missing.length} 个`);

/* ---------- 4. 可选：素材覆盖率（--material=<目录>） ---------- */
if (materialArg) {
  const matDir = path.resolve(materialArg);
  if (!fs.existsSync(matDir)) {
    console.log(`\n[覆盖率] 素材目录不存在: ${matDir}`);
  } else {
    const matFiles = fs.readdirSync(matDir).filter(f => f.endsWith('.png')).sort();
    const landed = [];
    const shared = [];
    const mismatch = [];
    const orphan = [];
    for (const f of matFiles) {
      const base = f.replace(/\.png$/, '');
      const matPath = path.join(matDir, f);
      const l2Path = path.join(TEX, 'block', base + '_l2.png');
      const l1Path = path.join(TEX, 'block', base + '.png');
      if (fs.existsSync(l2Path) && fs.readFileSync(l2Path).equals(fs.readFileSync(matPath))) landed.push(f);
      else if (fs.existsSync(l2Path)) mismatch.push(`${f}: 已有 ${base}_l2.png 但与素材不同`);
      else if (fs.existsSync(l1Path) && fs.readFileSync(l1Path).equals(fs.readFileSync(matPath))) shared.push(f);
      else if (fs.existsSync(l1Path)) mismatch.push(`${f}: 仅有 ${base}.png 且与素材不同`);
      else orphan.push(`${f}: 仓库里既没有 ${base}_l2.png 也没有 ${base}.png`);
    }
    console.log('');
    console.log(`[覆盖率] 素材 ${matFiles.length} 个 png`);
    console.log(`  已作为 _l2 落盘且与素材一致: ${landed.length}`);
    console.log(`  与 1 号线共用（内容与素材逐字节相同）: ${shared.length}`);
    if (mismatch.length) {
      console.log(`  内容不一致: ${mismatch.length}`);
      for (const m of mismatch) console.log('    ' + m);
    }
    if (orphan.length) {
      console.log(`  真缺口（未落盘）: ${orphan.length}`);
      for (const o of orphan) console.log('    ' + o);
    }
    if (mismatch.length || orphan.length) {
      console.log('  （覆盖率仅供参考，不影响"缺 N 个"的判定；未被任何模型引用的素材可能本就属于 1 号线共用图）');
    }
  }
}

const failed = missing.length > 0 || invalid.length > 0;
if (failed) process.exitCode = 1;
