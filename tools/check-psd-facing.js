#!/usr/bin/env node
// 小仿真：屏蔽门 / 端门 / 栏杆「默认放置朝向」到底贴哪条边。
//
// 目的：不启动游戏就能验证「模型与碰撞盒落在哪一侧」，用来防「又搞反了」这类回归。
//
// 三套朝向约定都从字节码抄下来，不是猜的：
//   · MTR 屏蔽门（libs/MTR-fabric-1.20.1-3.6.4.2-named.jar）
//       BlockDirectionalDoubleBlockBase#getPlacementState：
//           getHorizontalPlayerFacing() → FACING（没有 getOpposite）
//       BlockPSDAPGBase#getOutlineShape：
//           IBlock.getVoxelShapeByDirection(0,0,0,16,h,4,FACING)
//       IBlock$1 静态块：NORTH→1, EAST→2, SOUTH→3, WEST→4（= switch 的 case 号）
//       case 1 = 原样；case 2 = (16-z2, y1, x1, 16-z1, y2, x2)；case 3 / 4 同表。
//   · 方速门（FangsuPsdDoorBlock）：碰撞 shape.close 烘成 4 份，每份 = shapeQuarterTurns 步
//       (x,z)→(16-z,x)；模型 = RotationAxis.NEGATIVE_Y × blockRotation 度（块中心为轴）。
//       shapeQuarterTurns：NORTH 2 / SOUTH 0 / WEST 1 / EAST 3（= blockRotation + 180°）。
//   · eyecandy 栏杆（EyecandyDirectionalBlock）：同一套 rot90 与 blockRotation。
//
// 用法：node tools/check-psd-facing.js
'use strict';

const FACINGS = ['north', 'east', 'south', 'west'];

/** 一步 rot90：模型空间 (x, z) → (16 − z, x)（= MC 的 y=90 / RotationAxis.NEGATIVE_Y 90°）。 */
function rot90(box) {
  const [x1, y1, z1, x2, y2, z2] = box;
  return [16 - z2, y1, x1, 16 - z1, y2, x2];
}

function rotSteps(box, steps) {
  let b = box;
  for (let i = 0; i < ((steps % 4) + 4) % 4; i++) b = rot90(b);
  return b;
}

/** MTR IBlock.getVoxelShapeByDirection：case 号见 IBlock$1（NORTH→1, EAST→2, SOUTH→3, WEST→4）。 */
function mtrShape(box, facing) {
  const [x1, y1, z1, x2, y2, z2] = box;
  switch (facing) {
    case 'north': // case 1：原样
      return [x1, y1, z1, x2, y2, z2];
    case 'east': // case 2：(16−z2, y1, x1, 16−z1, y2, x2)
      return [16 - z2, y1, x1, 16 - z1, y2, x2];
    case 'south': // case 3：180°（16−x2, y1, 16−z2, 16−x1, y2, 16−z1）
      return [16 - x2, y1, 16 - z2, 16 - x1, y2, 16 - z1];
    case 'west': // case 4：(z1, y1, 16−x2, z2, y2, 16−x1)
      return [z1, y1, 16 - x2, z2, y2, 16 - x1];
    default:
      return [0, 0, 0, 16, 16, 16];
  }
}

/** 方速 / eyecandy：blockRotation（= 模型转角）→ 步数。 */
const BLOCK_ROTATION = { north: 0, east: 90, south: 180, west: 270 };
const SHAPE_QUARTER_TURNS = { north: 2, east: 3, south: 0, west: 1 };

/** 盒子贴在方块哪条边（只看水平面；薄的那一维落在 0 侧 = 北 / 西，16 侧 = 南 / 东）。 */
function edge(box) {
  const [x1, , z1, x2, , z2] = box;
  const thinZ = z2 - z1 <= 6;
  const thinX = x2 - x1 <= 6;
  if (thinZ && z1 <= 0.5) return '北边 z≈0（对面 / 背离朝北的玩家）';
  if (thinZ && z2 >= 15.5) return '南边 z≈16（靠朝北的玩家这一侧）';
  if (thinX && x1 <= 0.5) return '西边 x≈0';
  if (thinX && x2 >= 15.5) return '东边 x≈16';
  if (thinZ) return `z ${z1}..${z2}（居中）`;
  if (thinX) return `x ${x1}..${x2}（居中）`;
  return '整格';
}

const fmt = (b) => '[' + b.map((v) => (Math.round(v * 100) / 100)).join(', ') + ']';

function main() {
  console.log('== MTR 原版屏蔽门（门扇 4 单位厚：IBlock.getVoxelShapeByDirection(0,0,0,16,16,4,FACING)）==');
  console.log('   FACING = ctx.getHorizontalPlayerFacing()（不取反）\n');
  for (const facing of FACINGS) {
    const box = mtrShape([0, 0, 0, 16, 16, 4], facing);
    console.log(`   玩家朝 ${facing.padEnd(5)} → FACING=${facing.padEnd(5)} → 碰撞 ${fmt(box)}  ${edge(box)}`);
  }
  console.log('\n   反面教材（旧写法 .getOpposite()）：');
  const flip = { north: 'south', east: 'west', south: 'north', west: 'east' };
  for (const facing of FACINGS) {
    const box = mtrShape([0, 0, 0, 16, 16, 4], flip[facing]);
    console.log(`   玩家朝 ${facing.padEnd(5)} → FACING=${flip[facing].padEnd(5)} → 碰撞 ${fmt(box)}  ← 翻到玩家这一侧`);
  }

  console.log('\n== 方速门（模型门扇 z 0..2，碰撞 shape.close = [0,0,14,16,48,15]）==');
  console.log('   两份必须重合：模型走 blockRotation，碰撞走 shapeQuarterTurns（= +180°）\n');
  for (const facing of FACINGS) {
    const model = rotSteps([0, 0, 0, 16, 48, 2], BLOCK_ROTATION[facing] / 90);
    const box = rotSteps([0, 0, 14, 16, 48, 15], SHAPE_QUARTER_TURNS[facing]);
    const same = Math.abs(model[2] - box[2]) <= 2.5;
    console.log(`   玩家朝 ${facing.padEnd(5)} → FACING=${facing.padEnd(5)} 模型 ${fmt(model)}\n` +
      `                             碰撞 ${fmt(box)}  ${same ? '✓ 重合' : '✗ 错开'}`);
  }

  console.log('\n== eyecandy 栏杆 xiamen_brt_railing_middle（几何 x ≈ −8..9.6、z 7.25..8.75，长条横在方块里）==');
  for (const facing of FACINGS) {
    const box = rotSteps([-7.98, 0, 7.25, 9.58, 22, 8.75], BLOCK_ROTATION[facing] / 90);
    const thin = box[3] - box[0] < box[5] - box[2] ? 'x 方向薄 → 走向沿 z（南北）' : 'z 方向薄 → 走向沿 x（东西）';
    console.log(`   玩家朝 ${facing.padEnd(5)} → FACING=${facing.padEnd(5)} 盒 ${fmt(box)}  ${thin}`);
  }
}

main();
