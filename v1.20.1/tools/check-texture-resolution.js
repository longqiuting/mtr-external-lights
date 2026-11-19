#!/usr/bin/env node
/*
 * 贴图分辨率校验 / 自动还原
 * ===========================
 *
 * 背景（2026-11-19）：以前为了压 jar 体积，一批贴图被**缩放**过（4096² 缩成 1024² 之类），
 * 画到方块上明显糊。本脚本把仓库里每一张目标贴图的**像素尺寸**和它的**原图**比一遍：
 *
 *   - 尺寸一致        → 记进「一致」表
 *   - 目标比源图小    → 记进「被缩小」表，并**立刻用源图覆盖回去**（原尺寸、不重编码）
 *   - 目标比源图大    → 记进「被放大」表（只报告，不覆盖 —— 放大过就不是原图了，得人工确认）
 *   - 找不到源图      → 记进「跳过」表（不算失败）
 *
 * 跑完打印 `0 downscaled` 才算通过（退出码 0）。
 *
 * 用法：
 *   node v1.20.1/tools/check-texture-resolution.js            # 校验 + 自动还原
 *   node v1.20.1/tools/check-texture-resolution.js --dry-run  # 只报告，不动文件
 *   node v1.20.1/tools/check-texture-resolution.js --verbose  # 连「一致」的也逐行打印
 *
 * 源图根目录默认写死在本机（用户素材盘），可用环境变量覆盖：
 *   LTE_MATERIALS_DIR   —— 素材库（默认 C:/Users/AD/Desktop/素材）
 *   LTE_RESOURCEPACKS   —— 资源包目录（默认 …/PCL/.minecraft/versions/1.20.1-Fabric 0.19.3/resourcepacks）
 */
'use strict';

const fs = require('fs');
const path = require('path');

// ---------------------------------------------------------------- 路径

const HERE = __dirname;                                   // …/v1.20.1/tools
const VERSION_DIR = path.resolve(HERE, '..');             // …/v1.20.1
const TEX = path.join(VERSION_DIR, 'src/main/resources/assets/mtrlte/textures');

const MATERIALS = process.env.LTE_MATERIALS_DIR
  || 'C:/Users/AD/Desktop/素材';
const PACKS = process.env.LTE_RESOURCEPACKS
  || 'C:/Users/AD/Desktop/PCL/.minecraft/versions/1.20.1-Fabric 0.19.3/resourcepacks';

/** 两个装饰物件包（eyecandies 的来源）。 */
const EYECANDY_PACKS = [
  path.join(PACKS, '厦门南普陀相关装饰物件/assets/mtrsteamloco/eyecandies'),
  path.join(PACKS, '厦门风格物件包/assets/mtrsteamloco/eyecandies'),
];
/** 另有 1 张（metro_sign）来自第三个包，一起写在这里。 */
const EYECANDY_EXTRA = path.join(PACKS, '绫洛市导视系统/assets/mtrlte/textures/eyecandies');

// ---------------------------------------------------------------- 目标清单（块 / 物品）

/** textures/block 下要校验的（名字 → 源图，相对 MATERIALS）。 */
const BLOCK_SOURCES = {
  brt_barrier: '厦门地铁闸机/brt_barrier.png',
  ticket_barrier: '厦门地铁闸机/ticket_barrier.png',
  amtr_ticket: '厦门地铁售票机/amtr_ticket.png',
  amtr_ticket_closed: '厦门地铁售票机/amtr_ticket_closed.png',
  balance_machine: '厦门地铁余额查询机/balance_machine.png',
  liquid_scanner: '液体检测机/liquid_scanner.png',
  charger: '共享充电宝机器/机器/charger.png',
  dep_on_wall: '挂墙发车倒数器/dep_on_wall.png',
  pids_xiamen_metro: '厦门pids/textures/block/pids_xiamen_metro.png',
  pids_xiamen_brt: '厦门pids/textures/block/pids_xiamen_brt.png',
  railing: '墙上扶手/railing.png',
};

/** textures/item 下要校验的（名字 → 源图，相对 MATERIALS）。 */
const ITEM_SOURCES = {
  dep_on_wall: '挂墙发车倒数器/item/dep_on_wall.png',
  dep_on_roof: '挂墙发车倒数器/item/dep_on_roof.png',
};

// ---------------------------------------------------------------- PNG 尺寸

/** 读 PNG 的 IHDR（不用任何依赖）。只认 png，别的格式直接报错。 */
function pngSize(file) {
  const b = fs.readFileSync(file);
  const sig = '89504e470d0a1a0a';
  if (b.length < 24 || b.subarray(0, 8).toString('hex') !== sig) {
    throw new Error('不是 PNG：' + file);
  }
  return { w: b.readUInt32BE(16), h: b.readUInt32BE(20), bytes: b.length };
}

/** 找第一个存在的文件。 */
function firstExisting(list) {
  for (const p of list) {
    if (p && fs.existsSync(p)) return p;
  }
  return null;
}

// ---------------------------------------------------------------- 校验

const dryRun = process.argv.includes('--dry-run');
const verbose = process.argv.includes('--verbose');

const rows = [];   // {group,name,target,source,tw,th,sw,sh,verdict}
let restored = 0;

function check(group, name, target, sourceCandidates) {
  const targetPath = target;
  if (!fs.existsSync(targetPath)) {
    rows.push({ group, name, verdict: 'missing-target' });
    return;
  }
  const source = firstExisting(sourceCandidates);
  if (!source) {
    rows.push({ group, name, target: targetPath, verdict: 'no-source' });
    return;
  }
  const t = pngSize(targetPath);
  const s = pngSize(source);
  let verdict;
  let fixed = false;
  if (t.w === s.w && t.h === s.h) {
    verdict = 'same';
  } else if (t.w < s.w || t.h < s.h) {
    verdict = 'downscaled';
    if (!dryRun) {
      // 原样拷贝（不重新编码，保住源图的每一个字节）
      fs.copyFileSync(source, targetPath);
      const after = pngSize(targetPath);
      if (after.w !== s.w || after.h !== s.h) {
        throw new Error('还原失败：' + targetPath);
      }
      restored += 1;
      fixed = true;
    }
  } else {
    verdict = 'upscaled';
  }
  rows.push({
    group, name, target: targetPath, source,
    tw: t.w, th: t.h, sw: s.w, sh: s.h, verdict, fixed,
  });
}

// ---- textures/block
for (const [name, rel] of Object.entries(BLOCK_SOURCES)) {
  check('block', name + '.png', path.join(TEX, 'block', name + '.png'),
    [path.join(MATERIALS, rel)]);
}

// ---- textures/item
for (const [name, rel] of Object.entries(ITEM_SOURCES)) {
  check('item', name + '.png', path.join(TEX, 'item', name + '.png'),
    [path.join(MATERIALS, rel)]);
}

// ---- textures/eyecandies（两个包 + 第三包的 metro_sign；只在目标目录里存在的才校验）
const eyeDir = path.join(TEX, 'eyecandies');
if (fs.existsSync(eyeDir)) {
  for (const file of fs.readdirSync(eyeDir).filter((f) => f.endsWith('.png')).sort()) {
    check('eyecandies', file, path.join(eyeDir, file),
      EYECANDY_PACKS.map((d) => path.join(d, file)).concat(path.join(EYECANDY_EXTRA, file)));
  }
}

// ---------------------------------------------------------------- 报告

function fmt(r) {
  const dim = (w, h) => `${w}×${h}`;
  return `  ${r.name.padEnd(30)} ${dim(r.tw, r.th).padEnd(11)} ← ${dim(r.sw, r.sh).padEnd(11)} ${r.source}`;
}

const byVerdict = (v) => rows.filter((r) => r.verdict === v);

const same = byVerdict('same');
const down = byVerdict('downscaled');
const up = byVerdict('upscaled');
const noSrc = byVerdict('no-source');
const noTarget = byVerdict('missing-target');

console.log('贴图分辨率校验 —— 目标 ' + path.relative(path.resolve(VERSION_DIR, '..'), TEX));
console.log(`源图：素材库 ${MATERIALS}`);
console.log(`      装饰包 ${EYECANDY_PACKS[0]}`);
console.log(`             ${EYECANDY_PACKS[1]}`);
console.log(`             ${EYECANDY_EXTRA}`);
console.log(dryRun ? '（--dry-run：只报告，不覆盖文件）\n' : '');

console.log(`\n== 一致（${same.length} 张）==`);
if (verbose) {
  for (const r of same) console.log(fmt(r));
} else {
  console.log('  ' + same.map((r) => r.name).join(', '));
}

console.log(`\n== 被缩小（${down.length} 张）==`);
if (down.length === 0) {
  console.log('  （无）');
} else {
  for (const r of down) {
    console.log(fmt(r) + (r.fixed ? '   → 已用源图覆盖' : '   → 需要还原'));
  }
}

if (up.length) {
  console.log(`\n== 被放大（${up.length} 张，只报告不覆盖）==`);
  for (const r of up) console.log(fmt(r));
}

if (noSrc.length) {
  console.log(`\n== 跳过：找不到源图（${noSrc.length} 张）==`);
  for (const r of noSrc) console.log('  ' + r.name + '  ' + (r.target || ''));
}

if (noTarget.length) {
  console.log(`\n== 跳过：目标文件不存在（${noTarget.length} 张）==`);
  for (const r of noTarget) console.log('  ' + r.group + '/' + r.name);
}

// 装饰包里我们没随包发布的 png（信息性，不算失败）
const shipped = new Set(fs.existsSync(eyeDir) ? fs.readdirSync(eyeDir) : []);
const unshipped = [];
for (const dir of EYECANDY_PACKS) {
  if (!fs.existsSync(dir)) continue;
  for (const f of fs.readdirSync(dir)) {
    if (f.endsWith('.png') && !shipped.has(f)) unshipped.push(f);
  }
}
if (unshipped.length) {
  console.log(`\n== 装饰包里有、本仓库没发布的（${unshipped.length} 张，仅提示）==`);
  console.log('  ' + unshipped.join(', '));
}

console.log('');
const remaining = rows.filter((r) => r.verdict === 'downscaled' && !r.fixed).length;
if (dryRun) {
  console.log(`${remaining} downscaled（dry-run，未改动任何文件）`);
} else {
  console.log(`${remaining} downscaled` +
    (restored ? `（本次从源图还原 ${restored} 张，重跑应为 0）` : ''));
}
// 退出码：只要「还原后仍然被缩小」= 0 张就通过
process.exit(remaining === 0 ? 0 : 1);
