#!/usr/bin/env node
// 生成 26 个 eyecandy 的资源样板：blockstate / 物品模型 / 掉落表 / lang 条目。
//
// 为什么用脚本：26 × 4 份文件全是同一个模板，手写容易出现「某个 key 少一份」这类
// 「不开游戏看不出来」的错误（tools/verify-eyecandies.js 就是查这个的）。
// 本脚本幂等：内容没变就不改文件，lang 里已有的键不覆盖。
//
// 用法：node tools/gen-eyecandy-assets.js
'use strict';

const fs = require('fs');
const path = require('path');

const RES = path.resolve(__dirname, '..', 'v1.20.1', 'src', 'main', 'resources');
const EYECANDY = path.join(RES, 'assets', 'mtrlte', 'eyecandies');
const BLOCKSTATES = path.join(RES, 'assets', 'mtrlte', 'blockstates');
const ITEM_MODELS = path.join(RES, 'assets', 'mtrlte', 'models', 'item');
const LOOT = path.join(RES, 'data', 'mtrlte', 'loot_tables', 'blocks');
const LANG = path.join(RES, 'assets', 'mtrlte', 'lang');

// 英文名（英文 / 拼音缩写都行，这里按「别人看得懂」来写）。
const EN = {
  key1: 'Xiamen Metro 100 m Sign',
  key2: 'Xiamen BRT Platform Door (Right)',
  key3: 'Xiamen BRT Platform Door (Left)',
  key4: 'Xiamen BRT Railing (Middle)',
  key5: 'Xiamen BRT Railing (Left)',
  key6: 'Xiamen BRT Railing (Right)',
  key7: 'Xiamen BRT Railing (Middle, Untextured)',
  key8: 'Xiamen Style Bin',
  key9: 'Generic Stone Bollard',
  key10: 'Xiamen Metro P+M Kiosk',
  key11: 'Xiamen BRT Fire Equipment Cabinet',
  key12: 'Lingluo Rail Network Map',
  key13: 'Corner Railing (MSD)',
  key14: 'Xiamen BRT Railing (Corner)',
  key1145: 'Xiamen Metro 200 m Sign',
  bike: 'Shared Bike',
  bike2: 'Shared Bike (Luo Tianyi Blue)',
  bike3: 'Shared Bike (Yuezheng Ling Red)',
  bike4: 'Shared Bike (Yuezheng Longya Green)',
  bike5: 'Shared Bike (Yanhe Blue)',
  bike6: 'Shared Bike (Zhiyu Moke Blue)',
  bike7: 'Shared Bike (Mo Qingxian Yellow)',
  bike8: 'Shared Bike (Hatsune Miku Blue)',
  qiaodun20: 'Bridge Pier (20 Blocks)',
  rolling_turning: 'Corner Roller Shutter (Tianjin)',
  rolling_bottom_turning: 'Corner Roller Shutter Bottom (Tianjin)',
};

const TAB_NAME = { zh_cn: '厦门装饰物件', en_us: 'Xiamen Decorations' };

function writeIfChanged(file, text) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  if (fs.existsSync(file) && fs.readFileSync(file, 'utf8') === text) return false;
  fs.writeFileSync(file, text);
  return true;
}

function main() {
  const first = JSON.parse(fs.readFileSync(path.join(EYECANDY, 'first.json'), 'utf8'));
  const keys = Object.keys(first);
  let written = 0;

  for (const key of keys) {
    const id = 'eyecandy_' + key.toLowerCase();
    const blockId = 'mtrlte:' + id;

    // 1) blockstate：几何由 BER 画，方块模型是空的（和闸机 / 售票机同一套做法）。
    const blockstate = {
      variants: { '': { model: 'mtrlte:block/empty' } },
    };
    if (writeIfChanged(path.join(BLOCKSTATES, id + '.json'), JSON.stringify(blockstate, null, 2) + '\n')) written++;

    // 2) 物品模型：item/generated + layer0 = 离线渲染出来的 128×128 等轴测图标。
    const itemModel = {
      parent: 'item/generated',
      textures: { layer0: 'mtrlte:item/' + id },
    };
    if (writeIfChanged(path.join(ITEM_MODELS, id + '.json'), JSON.stringify(itemModel, null, 2) + '\n')) written++;

    // 3) 掉落表：敲掉掉自己。
    const loot = {
      type: 'minecraft:block',
      pools: [{
        rolls: 1,
        entries: [{ type: 'minecraft:item', name: blockId }],
        conditions: [{ condition: 'minecraft:survives_explosion' }],
      }],
    };
    if (writeIfChanged(path.join(LOOT, id + '.json'), JSON.stringify(loot, null, 2) + '\n')) written++;
  }

  // 4) lang：只补缺的键（已有的不覆盖，方便人手改名字）。
  let langWritten = 0;
  for (const locale of ['zh_cn', 'en_us']) {
    const file = path.join(LANG, locale + '.json');
    const json = JSON.parse(fs.readFileSync(file, 'utf8'));
    let dirty = false;

    const tabKey = 'itemGroup.mtrlte.xiamen_decor';
    if (!json[tabKey]) {
      json[tabKey] = TAB_NAME[locale];
      dirty = true;
    }
    for (const key of keys) {
      const id = 'eyecandy_' + key.toLowerCase();
      const name = locale === 'zh_cn' ? first[key].name : (EN[key] || first[key].name);
      for (const prefix of ['block.mtrlte.', 'item.mtrlte.']) {
        if (!json[prefix + id]) {
          json[prefix + id] = name;
          dirty = true;
        }
      }
    }
    if (dirty) {
      // 保留原来的键序与 2 空格缩进；只多出新增的那几行。
      writeIfChanged(file, JSON.stringify(json, null, 2) + '\n');
      langWritten++;
    }
  }

  console.log(`blocks/items/lang: ${keys.length} entries, ${written} resource file(s) updated, ${langWritten} lang file(s) updated`);
}

if (require.main === module) main();
module.exports = { EN, TAB_NAME };
