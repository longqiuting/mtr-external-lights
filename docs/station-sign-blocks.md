# 站外标志方块（mtrlte:station_sign_*）

进站口那种「XX 地铁」灯箱牌。**只做了 1.20.1**，没有往 1.18.2 / 1.19.2 / 1.19.4 抄。

## 四个方块 = 两个模型 × 两张贴图

| id（`mtrlte:` 前缀省略） | 款 | 品牌 | 模型 | 贴图 |
|---|---|---|---|---|
| `station_sign_wall_xiamen` | 挂墙 | 厦门地铁 | `block/station_sign_wall` | `block/station_sign_xiamen` |
| `station_sign_pole_xiamen` | 杆上 | 厦门地铁 | `block/station_sign_pole` | `block/station_sign_xiamen` |
| `station_sign_wall_jiaomei` | 挂墙 | 角美地铁 | `block/station_sign_wall_jiaomei` | `block/station_sign_jiaomei` |
| `station_sign_pole_jiaomei` | 杆上 | 角美地铁 | `block/station_sign_pole_jiaomei` | `block/station_sign_jiaomei` |

几何只有两份（Blockbench 的 `sign_on_wall.json` / `sign_on_pole.json`）；角美那两份模型文件只写
`parent` + `textures`，几何完全继承厦门那份 —— 所以「两模型两贴图」就能凑出四个方块。

## 属性

- 亮度 **15**（`luminance(state -> 15)`）：灯箱牌自发光。
- `nonOpaque()` + 客户端 `RenderLayer.getCutout()`：贴图有大量 alpha=0，挂 solid 层会画成黑块。
- `requiresTool().strength(2.0F)` + `mineable/pickaxe` 标签，金属音效。
- 没有方块实体、没有存档数据；方块与物品同 id，进「厦门风格物件」页签。

## 模型空间与 `facing`

- **pole（杆上）**：牌面 14×14，占 `x 0..16, y 0..16, z 5.4..10.6`，两面朝 ±Z。
  `facing` = 牌面朝向，放置时朝玩家；整格高、自立，不需要支撑。
- **wall（挂墙）**：同一块牌子绕 Y 转 90°，牌面朝 ±X，支架伸到 `z -2..0`，
  也就是 **墙在模型空间的 north（-Z）一侧**。
  `facing` = **墙在哪一侧**（那一格必须是实心方块）；点墙的某一侧挂上去，
  `facing` 就是那一侧的反方向。墙没了就变空气（同原版告示牌）。

两款的 blockstate 旋转约定一致：`north=0 / east=90 / south=180 / west=270`
（`StationSignBlock#blockRotation`，与 `dep_on_wall` 同一套），
碰撞箱按 `(x, z) → (16 - z, x)` 预先算好四份。

## 贴图

贴图是 2048×2048 的 UV 图集，但 UV 是 **16 格制**（美术图上内容 bbox 是 `0..10.04`，
模型 UV 最大 `10.05`，一一对应），所以模型 UV **不需要缩放**，直接是原版 16 格 UV。

正面采样区 `uv (0..3.5, 3.5..7)` 就是「logo + 地铁」那一块。两张图都是美术直接给的整图，
**像素一个没改**：

| 文件 | 素材原文件 | sha256（前 8 位） | 正面内容 |
|---|---|---|---|
| `textures/block/station_sign_xiamen.png` | `amtr_sign.png` | `b63b2ed0` | 红色「厦门地铁」logo + 地铁 |
| `textures/block/station_sign_jiaomei.png` | `jmtr_sign.png` | `0c63c7a1` | 「JMRT 角美地铁」粉色斜杠 logo |

两张图共用同一套图集布局（同一 Blockbench 工程导出），所以 UV / 模型完全不用动，
换图就是纯替换 png。

## 物品模型（3D）

四个物品都**不是** `item/generated` 的平面贴图，而是直接渲染方块模型：

- `models/item/station_sign_{wall,pole}_{xiamen,jiaomei}.json` → `parent` = 对应的
  `mtrlte:block/station_sign_*`，几何、贴图全部来自方块模型；
- 文件里显式写了 `display`（`gui` / `ground` / `fixed` / 双手第一人称 / 第三人称），
  数值与原版 `minecraft:block/block` 一致 —— 和本模组 `pids_xiamen_pole` 的
  物品模型同一套写法，不依赖父模型链去继承变换。
- 所以物品栏里是**等轴 30°/225° 的 3D 灯箱牌**（能看见牌面厚度和抱杆），
  手里拿的也是方块模型，不是一张贴图。

