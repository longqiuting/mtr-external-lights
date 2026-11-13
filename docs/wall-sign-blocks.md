# 贴墙标识方块（mtrlte:wall_sign_*）

钉在墙面上的小标识牌：一格宽、14/16 格高、1/16 格厚，正面一张 logo。**只做了 1.20.1。**

和[站外标志](station-sign-blocks.md)（`mtrlte:station_sign_*`，16 格高的立地 / 挑墙大灯箱）**不是一回事**：
那个是进站口招牌，这个是墙上贴的标识板。

## 三个方块 = 一份几何 × 三张 logo

| id（`mtrlte:` 前缀省略） | 品牌 | 模型 | 贴图（都是现成的） |
|---|---|---|---|
| `wall_sign_xiamen` | 厦门地铁 | `block/wall_sign_xiamen` | `block/xiamen_logo` |
| `wall_sign_jiaomei` | 角美地铁 | `block/wall_sign_jiaomei` | `block/jiaomei_logo` |
| `wall_sign_lte` | LTE | `block/wall_sign_lte` | `block/lte_logo` |

几何只有一份 `models/block/wall_sign.json`；三个型号的模型文件只写 `parent` + `textures`。
**没有新增任何贴图**：三张 logo 就是 logo 方块（`mtrlte:xiamen_logo` / `jiaomei_logo` / `lte_logo`）用的那三张，
底板用现成的 `mtrlte:block/metal`。

## 几何从哪来：MTR 的「站名出入口」

MTR jar 里 `assets/mtr/models/block/station_name_entrance_short.json` / `_tall.json`
只有一个元素、`__comment: "Light"`：一块**零厚度的整面反光板**，
`from [0,4,0.05] → to [16,12,0.05]`（short，16×8）/ `[0,0,0.05] → [16,16,0.05]`（tall，16×16），
只有 north（`cullface: north`）/ south 两个面 —— 站名文字是 BER 另外画上去的。
（`station_name_wall.json` 本身是 `parent: block/air` + 一个 `particle` 贴图，几何全在 BER 里，抄不到东西。）

本模组的 `block/wall_sign.json` 照那块板做，两处改动：

1. **补上厚度**：`z 0 → 0.95`（再往前的 0.05 是 logo 层），所以它是一块真板子而不是一张纸，
   碰撞箱 / 框选就按 `z 0..0.95` 算（`WallSignBlock` 里的 `MIN_Z` / `MAX_Z`）；
2. **高度取 short 与 tall 之间**：`y 1 → 15`（14 格高，上下各留 1 格边距），
   正面 2 格边距内放 logo（`x 2..14, y 2..14`，正方形，贴图不拉伸）。

元素两个：

| 元素 | 范围 | 说明 |
|---|---|---|
| 底板 | `[0,1,0] → [16,15,0.95]` | `mtrlte:block/metal`，north 面 `cullface: north`（贴墙不画） |
| logo 面 | `[2,2,1] → [14,14,1]` | 零厚度平面、只画 south（朝外）面，`shade: false`，比底板再前 0.05 避免 z-fighting |

贴图是挖空的（logo 以外 alpha=0），挖空处透出底下的金属底板，所以客户端必须挂
`RenderLayer.getCutout()`（见 `XiamenClient`），否则空白处会画成黑块。

## 属性

- 亮度 **15**（`luminance(state -> 15)`）：沿用站外标志的灯箱算法 —— 底板就是 MTR 那块「Light」面。
- `nonOpaque()`：1/16 厚的板不是满方块，不这样邻居朝我们的面会被剔除。
- `requiresTool().strength(2.0F)` + `mineable/pickaxe` 标签，金属音效。
- 战利品表 `data/mtrlte/loot_tables/blocks/wall_sign_*.json`，正常掉自己。
- 没有方块实体、没有存档数据；方块与物品同 id，进「厦门风格物件」页签，
  排顺序上紧跟在两个 logo 方块（`xiamen_logo` / `jiaomei_logo`）后面
  —— 页签条目顺序 = `ItemGroupEvents` 回调的注册顺序，所以 `WallSignObjects.register()`
  必须排在 `LogoObjects.register()` 之后（见 `LteServer`）。

## `facing` = 墙在哪一侧

和站外标志挂墙款、原版告示牌 / 按钮同一套：点在墙的哪一面，`facing` 就是那一侧，
`facing` 那一格必须是实心方块（`isSideSolidFullSquare`），否则放不下；
墙没了会**掉物品**并变空气（站外标志只变空气，不掉物品 —— 这里补上了）。

- 模型空间里墙在 **north（-Z）** 一侧，牌面朝 +Z（south 面）；
- blockstate 旋转：`north=0 / east=90 / south=180 / west=270`（`WallSignBlock#blockRotation`），
  碰撞箱按 `(x, z) → (16 - z, x)` 预先算好四份。

## 3D 物品图标

`models/item/wall_sign_*.json` = `parent: mtrlte:block/wall_sign_*` + `display`。
`gui` 的 rotation 是 **`[30, 45, 0]`**，不是原版方块的 `[30, 225, 0]`：
225° 会把模型的 north 面（贴墙那一面、`cullface` 掉的背板）转向镜头，图标里就只能看到光秃秃的板背；
牌面在 south 面，45° 才正面朝外。

## 生成 / 校验

- `tools/gen-wall-sign-assets.js`：生成三个 blockstate、三个 item 模型、三张战利品表（几何模型是手写的）。
- `node tools/verify-wall-signs.js v1.20.1`：不启动游戏的静态校验 —— 模型包围盒按 blockstate 旋转后
  必须与 `WallSignBlock` 的 `VoxelShape` 常量一致、贴图引用必须都是 `mtrlte:` 且文件存在、
  模型里不得出现 `mtr:` 前缀、item 模型 / 战利品表 / 语言文件 / 标签齐全。
