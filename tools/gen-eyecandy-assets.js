#!/usr/bin/env node
// 生成 eyecandy 的资源样板：blockstate（四向 rotation）/ 物品模型 / 掉落表 / lang 条目。
//
// 两套素材 = 两份描述文件：
//   · first.json     厦门风格物件包 26 件 → 物品进 mtrlte:core「厦门风格物件」（不新建页签）
//   · nanputuo.json  南普陀 34 件        → 物品进新页签 mtrlte:nanputuo_decor
// 两个页签在 Java 端由 EyecandyBlocks.register() 挂条目，这里只负责「别的东西」（模型 / 掉落 / 名字）。
//
// 为什么用脚本：60 × 4 份文件全是同一个模板，手写容易出现「某个 key 少一份」这类
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

// 四个朝向：必须与 EyecandyBlock.ROTATION（HORIZONTAL_FACING）的四个值一一对应。
const ROTATIONS = ['north', 'east', 'south', 'west'];

// 厦门包英文名（英文 / 拼音缩写都行，这里按「别人看得懂」来写）。
const EN_XIAMEN = {
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

// 南普陀包英文名。
const EN_NANPUTUO = {
  book_stand: 'Book Stand',
  digital_display_onwall: 'Digital Display (Wall)',
  digital_display_stand: 'Digital Display (Standing)',
  jixiang_box: 'Incense Offering Box',
  money_box_1: 'Donation Box (Blessing)',
  money_box_2: 'Donation Box',
  sign_on_wall_1: 'Notice (Wall, Dharma Goods)',
  sign_on_wall_2: 'Notice (Wall, Respect the Dharma)',
  sign_on_wall_small: 'Notice (Wall, Do Not Shake)',
  sign_stand_1: 'Notice (Standing, Ethnic Unity)',
  sign_stand_2: 'Notice (Standing, Temple Entry Rules)',
  sign_stand_3: 'Notice (Standing, Polite Language)',
  sign_stand_4: 'Notice (Standing, Three Treasures)',
  sign_stand_5: 'Notice (Standing, Dharma Goods)',
  sign_stand_6: 'Notice (Standing, Guide Map)',
  sign_stand_7: 'Notice (Standing, Temple Booking)',
  sign_stand_8: 'Notice (Standing, Blank)',
  sign_stand_small_1: 'Small Notice (No Feeding)',
  sign_stand_small_2: 'Small Notice (Keep Off the Lawn)',
  sign_stand_small_3: 'Small Notice (Offer Incense Politely)',
  sign_stand_small_4: 'Small Notice (Entrance / Exit)',
  stone_zhu: 'Stone Pillar (Blank)',
  stone_zhu_warning_1: 'Stone Pillar (Do Not Sit, Style 1)',
  stone_zhu_warning_2: 'Stone Pillar (Do Not Sit, Style 2)',
  warning_stand_1: 'Warning Sign (No Visitors)',
  warning_stand_2: 'Warning Sign (Accessible Route)',
  warning_stand_3: 'Warning Sign (Entrance / Exit)',
  warning_stand_4: 'Warning Sign (Dharma Goods)',
  warning_stand_5: 'Warning Sign (No Feeding)',
  warning_stand_6: 'Warning Sign (Respect the Dharma)',
  warning_stand_7: 'Warning Sign (No Climbing)',
  window_1: 'Window, Style 1',
  window_2: 'Window, Style 2',
  zhixiang_stand: 'Incense Stand',
};

const PACKS = [
  { file: 'first.json', en: EN_XIAMEN, name: '厦门风格物件包' },
  {
    file: 'nanputuo.json', en: EN_NANPUTUO, name: '南普陀',
    tab: { key: 'itemGroup.mtrlte.nanputuo_decor', zh_cn: '南普陀装饰物件', en_us: 'Nanputuo Decorations' },
  },
];

// 已经废弃的页签（原来那 26 件单开的「厦门装饰物件」页），lang 里顺手删掉。
const RETIRED_TAB = 'itemGroup.mtrlte.xiamen_decor';

function writeIfChanged(file, text) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  if (fs.existsSync(file) && fs.readFileSync(file, 'utf8') === text) return false;
  fs.writeFileSync(file, text);
  return true;
}

function main() {
  let written = 0;
  let entries = 0;
  const perLocale = { zh_cn: new Map(), en_us: new Map() };
  const tabs = new Map();

  for (const pack of PACKS) {
    const first = JSON.parse(fs.readFileSync(path.join(EYECANDY, pack.file), 'utf8'));
    for (const key of Object.keys(first)) {
      entries++;
      const id = 'eyecandy_' + key.toLowerCase();
      const blockId = 'mtrlte:' + id;

      // 1) blockstate：几何由 BER 画，方块模型是空的（和闸机 / 售票机同一套做法）；
      //    但状态有 rotation 属性，四个变体都得写上，否则三个方向是「missing model」。
      const variants = {};
      for (const rotation of ROTATIONS) {
        variants['rotation=' + rotation] = { model: 'mtrlte:block/empty' };
      }
      if (writeIfChanged(path.join(BLOCKSTATES, id + '.json'), JSON.stringify({ variants }, null, 2) + '\n')) written++;

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

      perLocale.zh_cn.set(id, first[key].name);
      perLocale.en_us.set(id, pack.en[key] || first[key].name);
    }
    if (pack.tab) {
      tabs.set(pack.tab.key, pack.tab);
    }
    console.log(`${pack.name}: ${Object.keys(first).length} entries`);
  }

  // 4) lang：只补缺的键（已有的不覆盖，方便人手改名字），顺手删掉废弃页签。
  let langWritten = 0;
  for (const locale of ['zh_cn', 'en_us']) {
    const file = path.join(LANG, locale + '.json');
    const json = JSON.parse(fs.readFileSync(file, 'utf8'));
    let dirty = false;
    if (json[RETIRED_TAB]) {
      delete json[RETIRED_TAB];
      dirty = true;
    }
    for (const [key, tab] of tabs) {
      if (!json[key]) {
        json[key] = tab[locale];
        dirty = true;
      }
    }
    for (const [id, name] of perLocale[locale]) {
      for (const prefix of ['block.mtrlte.', 'item.mtrlte.']) {
        if (!json[prefix + id]) {
          json[prefix + id] = name;
          dirty = true;
        }
      }
    }
    if (dirty) {
      writeIfChanged(file, JSON.stringify(json, null, 2) + '\n');
      langWritten++;
    }
  }
  // 旋转提示（每个 eyecandy 物品的 tooltip，共用一条）。
  const tip = { zh_cn: '手持刷子右键可旋转（四向）', en_us: 'Right-click with the brush to rotate (4 directions)' };
  const tipKey = 'tooltip.mtrlte.eyecandy_rotation';
  for (const locale of ['zh_cn', 'en_us']) {
    const file = path.join(LANG, locale + '.json');
    const json = JSON.parse(fs.readFileSync(file, 'utf8'));
    if (!json[tipKey]) {
      json[tipKey] = tip[locale];
      writeIfChanged(file, JSON.stringify(json, null, 2) + '\n');
      langWritten++;
    }
  }

  console.log(`blocks/items/loot: ${entries} entries, ${written} resource file(s) updated, ${langWritten} lang file(s) updated`);
}

if (require.main === module) main();
module.exports = { EN_XIAMEN, EN_NANPUTUO, ROTATIONS };
