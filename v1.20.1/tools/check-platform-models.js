#!/usr/bin/env node
// 大理石站台的模型接线自检（1.20.1）。
//
// 目的：证明「屏蔽门那一条站台变灰」不是模型被换成了 MTR 的 platform_psd，并且以后别再被换。
// 做法：把 blockstate/marble_platform.json 的 multipart 规则，对着
//   facing   ∈ {north, east, south, west}
//   door_type∈ {none, psd, apg}       ← 继承自 mtr.block.BlockPlatform
//   side     ∈ {0, 1, 2, 3, 4}        ← 站台面上的箭头
// 的<b>全部 60 个状态</b>逐一求值，列出每个状态命中的模型集合，并要求：
//   1. 每个状态至少命中一个模型（否则 MC 会渲染 MissingModel → 黑紫方块）；
//   2. 命中的模型全部是 mtrlte:block/*，<b>一条都不能落到 mtr: 上</b>
//      （落到 mtr: 就等于让 MTR 的灰色站台顶盖顶掉大理石贴图）；
//   3. 每个被引用的模型文件都存在，且贴图也都存在。
//
// 用法：node tools/check-platform-models.js      （在 v1.20.1 目录下）
const fs = require('fs');
const path = require('path');

const SRC = 'src/main/resources/assets/mtrlte';
const BS = path.join(SRC, 'blockstates/marble_platform.json');

const FACINGS = ['north', 'east', 'south', 'west'];
const DOOR_TYPES = ['none', 'psd', 'apg'];
const SIDES = ['0', '1', '2', '3', '4'];

const blockstate = JSON.parse(fs.readFileSync(BS, 'utf8'));
if (!Array.isArray(blockstate.multipart)) {
  console.error('✗ marble_platform.json 不是 multipart —— 本脚本只认 multipart');
  process.exit(1);
}

/** multipart 的一条 when 是否匹配某个状态（未列出的属性 = 通配，与 MC 的语义一致）。 */
function matches(when, state) {
  for (const [k, v] of Object.entries(when || {})) {
    if (state[k] !== v) return false;
  }
  return true;
}

const problems = [];
const modelCache = new Map();
const rows = [];

for (const facing of FACINGS) {
  for (const doorType of DOOR_TYPES) {
    for (const side of SIDES) {
      const state = { facing, door_type: doorType, side };
      const hits = blockstate.multipart
        .filter((part) => matches(part.when, state))
        .map((part) => part.apply);
      if (hits.length === 0) {
        problems.push(`状态 ${JSON.stringify(state)} 没有任何模型（MissingModel）`);
        continue;
      }
      const models = hits.map((a) => a.model);
      for (const [i, m] of models.entries()) {
        if (!m.startsWith('mtrlte:')) {
          problems.push(`状态 ${JSON.stringify(state)} 命中非本模组模型 ${m}`);
          continue;
        }
        const file = path.join(SRC, 'models/block', m.slice('mtrlte:block/'.length) + '.json');
        if (!modelCache.has(file)) {
          if (!fs.existsSync(file)) {
            problems.push(`模型文件不存在: ${file}（状态 ${JSON.stringify(state)}）`);
            modelCache.set(file, false);
          } else {
            const model = JSON.parse(fs.readFileSync(file, 'utf8'));
            let ok = true;
            for (const [key, tex] of Object.entries(model.textures || {})) {
              if (typeof tex !== 'string' || !tex.includes(':')) continue;
              const [ns, p] = tex.split(':');
              const texPath = path.join('src/main/resources/assets', ns, 'textures', p + '.png');
              if (!fs.existsSync(texPath)) {
                problems.push(`${m}: 贴图 #${key}=${tex} 不存在（${texPath}）`);
                ok = false;
              }
            }
            modelCache.set(file, ok);
          }
        }
      }
      rows.push({ state, models, rotate: hits.map((a) => a.y || 0) });
    }
  }
}

// ---------------------------------------------------------------- 输出表
const base = 'mtrlte:block/marble_platform';
console.log('mtrlte:marble_platform —— 60 个状态逐一求值（facing × door_type × side）');
console.log('');
console.log('| facing | door_type | side | 命中模型 | y 旋转 |');
console.log('|---|---|---|---|---|');
for (const r of rows) {
  const label = r.models
    .map((m) => (m === base ? '`本体`' : '`' + m.slice('mtrlte:block/'.length) + '`'))
    .join(' + ');
  const arrows = r.models.filter((m) => m !== base).length;
  const note = arrows === 0 ? '（无门 / 门不在这一侧：只有大理石本体）' : '';
  console.log(`| ${r.state.facing} | ${r.state.door_type} | ${r.state.side} | ${label} ${note} | ${r.rotate.join(' / ')} |`);
}

const withArrow = rows.filter((r) => r.models.length > 1).length;
console.log('');
console.log(`共 ${rows.length} 个状态；其中 ${withArrow} 个叠了箭头层，${rows.length - withArrow} 个只有本体。`);
console.log(`引用的模型: ${[...new Set(rows.flatMap((r) => r.models))].join(', ')}`);

if (problems.length) {
  console.error(`\n✗ ${problems.length} 个问题：`);
  for (const p of problems) console.error('  - ' + p);
  process.exit(1);
}
console.log('\n✓ 60/60 状态全部解析到本模组的模型，没有一条落到 mtr: 的灰色站台模型上');
