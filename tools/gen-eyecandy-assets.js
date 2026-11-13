#!/usr/bin/env node
// 生成 eyecandy 的资源样板：blockstate（单变体，无朝向）/ 物品模型 / 掉落表 / lang 条目。
//
// 两套素材 = 两份描述文件：
//   · first.json     厦门风格物件包 25 件 → 物品进 mtrlte:core「厦门风格物件」（不新建页签）
//   · nanputuo.json  南普陀 34 件        → 物品进新页签 mtrlte:nanputuo_decor
// 两个页签在 Java 端由 EyecandyBlocks.register() 挂条目，这里只负责「别的东西」（模型 / 掉落 / 名字）。
// 描述文件里删掉的条目，重跑本脚本会把它的 blockstate / 物品模型 / 掉落表 / 图标 / lang 一起清掉。
//
// 为什么用脚本：59 × 4 份文件全是同一个模板，手写容易出现「某个 key 少一份」这类
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
const ITEM_TEX = path.join(RES, 'assets', 'mtrlte', 'textures', 'item');

// 方块默认没有朝向属性，blockstate 只有一个变体（键是空串）。
// 例外：BRT 屏蔽门左右两扇 + 栏杆那几个 key 有 facing 属性（放置时按玩家朝向定），
// blockstate 要写满四个变体。
// 这张表和 Java 端 EyecandyBlocks.DIRECTIONAL_KEYS 必须一致。
const DIRECTIONAL_KEYS = new Set([
  'xiamen_brt_psd_right', 'xiamen_brt_psd_left',
  'xiamen_brt_railing_middle', 'xiamen_brt_railing_left', 'xiamen_brt_railing_right',
  'xiamen_brt_railing_middle_plain',
  'msd_corner_railing', 'xiamen_brt_railing_corner',
]);

function isDirectional(key) {
  return DIRECTIONAL_KEYS.has(key);
}

// 厦门包英文名（key = 中文名的英译，这张表只给 en_us 的显示名）。
const EN_XIAMEN = {
  xiamen_metro_100m_sign: 'Xiamen Metro 100 m Sign',
  xiamen_brt_psd_right: 'Xiamen BRT Platform Door (Right)',
  xiamen_brt_psd_left: 'Xiamen BRT Platform Door (Left)',
  xiamen_brt_railing_middle: 'Xiamen BRT Railing (Middle)',
  xiamen_brt_railing_left: 'Xiamen BRT Railing (Left)',
  xiamen_brt_railing_right: 'Xiamen BRT Railing (Right)',
  xiamen_brt_railing_middle_plain: 'Xiamen BRT Railing (Middle, Untextured)',
  xiamen_bin: 'Xiamen Style Bin',
  stone_bollard: 'Generic Stone Bollard',
  xiamen_metro_pm_kiosk: 'Xiamen Metro P+M Kiosk',
  xiamen_brt_fire_cabinet: 'Xiamen BRT Fire Equipment Cabinet',
  msd_corner_railing: 'Corner Railing (MSD)',
  xiamen_brt_railing_corner: 'Xiamen BRT Railing (Corner)',
  xiamen_metro_200m_sign: 'Xiamen Metro 200 m Sign',
  shared_bike: 'Shared Bike',
  shared_bike_luotianyi: 'Shared Bike (Luo Tianyi Blue)',
  shared_bike_lezhengling: 'Shared Bike (Yuezheng Ling Red)',
  shared_bike_lezhenglongya: 'Shared Bike (Yuezheng Longya Green)',
  shared_bike_yanhe: 'Shared Bike (Yanhe Blue)',
  shared_bike_zhiyumoke: 'Shared Bike (Zhiyu Moke Blue)',
  shared_bike_moqingxian: 'Shared Bike (Mo Qingxian Yellow)',
  shared_bike_hatsunemiku: 'Shared Bike (Hatsune Miku Blue)',
  bridge_pier_20: 'Bridge Pier (20 Blocks)',
  tianjin_rolling_shutter_corner: 'Corner Roller Shutter (Tianjin)',
  tianjin_rolling_shutter_corner_bottom: 'Corner Roller Shutter Bottom (Tianjin)',
};

// 南普陀包英文名（key = nanputuo_ + 中文名的英译）。
const EN_NANPUTUO = {
  nanputuo_book_stand: 'Book Stand',
  nanputuo_digital_display_onwall: 'Digital Display (Wall)',
  nanputuo_digital_display_stand: 'Digital Display (Standing)',
  nanputuo_jixiang_box: 'Incense Offering Box',
  nanputuo_money_box_1: 'Donation Box (Blessing)',
  nanputuo_money_box_2: 'Donation Box',
  nanputuo_sign_on_wall_1: 'Notice (Wall, Dharma Goods)',
  nanputuo_sign_on_wall_2: 'Notice (Wall, Respect the Dharma)',
  nanputuo_sign_on_wall_small: 'Notice (Wall, Do Not Shake)',
  nanputuo_sign_stand_1: 'Notice (Standing, Ethnic Unity)',
  nanputuo_sign_stand_2: 'Notice (Standing, Temple Entry Rules)',
  nanputuo_sign_stand_3: 'Notice (Standing, Polite Language)',
  nanputuo_sign_stand_4: 'Notice (Standing, Three Treasures)',
  nanputuo_sign_stand_5: 'Notice (Standing, Dharma Goods)',
  nanputuo_sign_stand_6: 'Notice (Standing, Guide Map)',
  nanputuo_sign_stand_7: 'Notice (Standing, Temple Booking)',
  nanputuo_sign_stand_8: 'Notice (Standing, Blank)',
  nanputuo_sign_stand_small_1: 'Small Notice (No Feeding)',
  nanputuo_sign_stand_small_2: 'Small Notice (Keep Off the Lawn)',
  nanputuo_sign_stand_small_3: 'Small Notice (Offer Incense Politely)',
  nanputuo_sign_stand_small_4: 'Small Notice (Entrance / Exit)',
  nanputuo_stone_zhu: 'Stone Pillar (Blank)',
  nanputuo_stone_zhu_warning_1: 'Stone Pillar (Do Not Sit, Style 1)',
  nanputuo_stone_zhu_warning_2: 'Stone Pillar (Do Not Sit, Style 2)',
  nanputuo_warning_stand_1: 'Warning Sign (No Visitors)',
  nanputuo_warning_stand_2: 'Warning Sign (Accessible Route)',
  nanputuo_warning_stand_3: 'Warning Sign (Entrance / Exit)',
  nanputuo_warning_stand_4: 'Warning Sign (Dharma Goods)',
  nanputuo_warning_stand_5: 'Warning Sign (No Feeding)',
  nanputuo_warning_stand_6: 'Warning Sign (Respect the Dharma)',
  nanputuo_warning_stand_7: 'Warning Sign (No Climbing)',
  nanputuo_window_1: 'Window, Style 1',
  nanputuo_window_2: 'Window, Style 2',
  nanputuo_zhixiang_stand: 'Incense Stand',
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

/** lang 文件按 key 排序写回（补进去的新 id 不会堆在文件末尾，diff 只看真正改动的行）。 */
function langJson(json) {
  const sorted = {};
  for (const key of Object.keys(json).sort()) sorted[key] = json[key];
  return JSON.stringify(sorted, null, 2) + '\n';
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
      //    栏杆那几个 key 有 facing 属性（放置时按玩家朝向定、之后不能转），所以要写满四个变体，
      //    少一个变体原版会把状态判成「未知」直接报错；其余物件只有一个变体 ""。
      const variants = isDirectional(key)
        ? Object.fromEntries(['north', 'east', 'south', 'west']
            .map((f) => ['facing=' + f, { model: 'mtrlte:block/empty' }]))
        : { '': { model: 'mtrlte:block/empty' } };
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

  // 4) 清理孤儿：生成器只写不删，描述文件里删掉的条目（曾经那件「绫洛市轨道交通线网图」
  //    key12 就是用户要求整件删掉的）会留下 blockstate / 物品模型 / 掉落表 / 图标没人收尸。
  //    只扫 eyecandy_ 前缀的生成物，别的资源一概不碰 —— 这样重跑生成器就等于「资源与描述对齐」。
  const expected = new Set(perLocale.zh_cn.keys());
  const retired = new Set();
  const PRUNE = [
    { dir: BLOCKSTATES, ext: '.json' },
    { dir: ITEM_MODELS, ext: '.json' },
    { dir: LOOT, ext: '.json' },
    { dir: ITEM_TEX, ext: '.png' },
  ];
  for (const target of PRUNE) {
    if (!fs.existsSync(target.dir)) continue;
    for (const name of fs.readdirSync(target.dir)) {
      if (!name.startsWith('eyecandy_') || !name.endsWith(target.ext)) continue;
      const id = name.slice(0, -target.ext.length);
      if (expected.has(id)) continue;
      fs.unlinkSync(path.join(target.dir, name));
      retired.add(id);
      written++;
    }
  }
  if (retired.size) {
    console.log(`retired: ${[...retired].join(', ')} (files deleted)`);
  }

  // 5) lang：只补缺的键（已有的不覆盖，方便人手改名字），顺手删掉废弃页签与已删条目的键。
  let langWritten = 0;
  for (const locale of ['zh_cn', 'en_us']) {
    const file = path.join(LANG, locale + '.json');
    const json = JSON.parse(fs.readFileSync(file, 'utf8'));
    let dirty = false;
    if (json[RETIRED_TAB]) {
      delete json[RETIRED_TAB];
      dirty = true;
    }
    // 已删条目的名字（block. / item. 两份）也一起清掉 —— 按「id 不在描述文件里」判定，
    // 不依赖孤儿文件还在不在（文件可能早就手删了）。
    for (const key of Object.keys(json)) {
      const m = /^(?:block|item)\.mtrlte\.(eyecandy_.+)$/.exec(key);
      if (m && !expected.has(m[1])) {
        delete json[key];
        dirty = true;
      }
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
      writeIfChanged(file, langJson(json));
      langWritten++;
    }
  }
  // 旋转提示那条 lang 键已随 ROTATION 属性一起删了：顺手清掉，免得留着一条没人用的文案。
  const retiredTipKey = 'tooltip.mtrlte.eyecandy_rotation';
  for (const locale of ['zh_cn', 'en_us']) {
    const file = path.join(LANG, locale + '.json');
    const json = JSON.parse(fs.readFileSync(file, 'utf8'));
    if (json[retiredTipKey]) {
      delete json[retiredTipKey];
      writeIfChanged(file, JSON.stringify(json, null, 2) + '\n');
      langWritten++;
    }
  }

  console.log(`blocks/items/loot: ${entries} entries, ${written} resource file(s) updated, ${langWritten} lang file(s) updated`);
}

if (require.main === module) main();
module.exports = { EN_XIAMEN, EN_NANPUTUO };
