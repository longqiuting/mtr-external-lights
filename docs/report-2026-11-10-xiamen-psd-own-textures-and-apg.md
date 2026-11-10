# 屏蔽门：模组自带贴图 + 半高 APG 全套（2026-11-10）

只构建 **v1.20.1**，产物 `lte-1.0.23.jar`（已拷桌面 + 实例，未提交 git）。

---

## 一、全高 PSD 改成「模组自带贴图」

### 1. 换命名空间的方式：**mixin `@Redirect` 拦 `String.format`**（不是改 `Identifier` 构造）

MTR 的门扇渲染器 `mtr.render.RenderPSDAPGDoor#render` 里贴图 id 是死写格式串，共 15 处
`new Identifier(String)`（javap 3.6.4.2 生产 jar）：

```
  216: ldc "mtr:textures/block/light_%s.png"                     237: String.format  240: new class_2960
  283: ldc "mtr:textures/block/psd_door_end_%s_%s_2_%s.png"      337: format        340: new class_2960
  433: ldc "mtr:textures/block/apg_door_light_%s.png"            455: format        458: new class_2960
  553: ldc "mtr:textures/block/psd_door_end_%s_%s_1_%s.png"      608: format        611: new class_2960
  641: ldc "mtr:textures/block/psd_door_%s_%s_%s.png"            696: format        699: new class_2960
  734: ldc "mtr:textures/block/sign/door_not_in_use.png"         737: new class_2960
  769: ldc "mtr:textures/block/apg_door_%s_%s.png"               806: format        809: new class_2960
```

`RenderPsdDoorTextureMixin`：

```java
@Mixin(value = RenderPSDAPGDoor.class, remap = false)
@Redirect(method = "render",
          at = @At(value = "INVOKE", target = "Ljava/lang/String;format", remap = false),
          remap = false)
private static String lte$rewriteTextureId(String format, Object[] args) { ... }
```

**为什么不直接改 `Identifier` 构造**（先写过，编译期就废了）：
`new Identifier` 是 MC 类，生产 jar 里叫 `net/minecraft/class_2960`；MTR 未 remap，本模组没有它的
refmap 条目，`@At` 只能写 intermediary 名 —— 而注解处理器看的是 **yarn 名**的开发 jar，
直接报 `Cannot find target method "render(Lmtr/...;FLnet/minecraft/class_4587;...)"`，
注入只能靠运行期碰运气。`String.format` 是 **JDK 方法，开发/生产字节码一模一样**，
注入点在编译期就是确定的。代价：`method = "render"` 只按名字选（该类里只有真正的 render 与
它的 bridge；bridge 里没有 `String.format`，`defaultRequire: 0` 下跳过）。

### 2. 只作用于自己的门

命名空间不是全局替换，而是 **ThreadLocal**：

* `RenderPsdDoor.render` / `RenderPsdApgDoor.render` 在调 `super.render` 前
  `XiamenTextures.withNamespace("mtrlte", () -> …)`，结束后 `finally` 恢复；
* MTR 自己注册的 `RenderPSDAPGDoor` 实例从不设置 → `resolveActive` 原样返回，**一条 id 都不改**；
* 每个 `mtr:` id 先查 `mtrlte:` 版本在不在（`ResourceManager#getAllResources` 非空）：
  **缺图自动退回资源包那份**，不会出现紫黑格；结果按 id 缓存，资源包重载（`getResourceManager()`
  换实例）时整表作废；
* 总开关 `XiamenTextures.useModTextures`（`false` = 回到「跟着资源包走」）。

`RenderPsdDoor` 的贴图自检日志现在打的是**替换后的 id**（`[lte] psd door tex: mtrlte:... <- 栈顶包`）。

### 3. 拷进去的贴图（不压缩，直接复制）

`assets/mtrlte/textures/block/`，源 = `厦门地铁一号线车辆`（`psd_top.png` / `psd_top_edge.png`
取自 **绫洛市导视系统**）：

* 门扇图集：`psd_door_top_left_1`、`psd_door_top_right_1`、`psd_door_bottom_left_1`、
  `psd_door_bottom_right_1`、`psd_door_top_back_1`、`psd_door_bottom_back_1`（后两张原先缺）
* 端部门扇：`psd_door_end_{top,bottom}_{left,right}_{1,2}_1`（8 张）
* 玻璃：`psd_glass_{top,bottom}_{1,back_1}` + `psd_glass_diagonal_{top,bottom}_{1,back_1}`（8 张）
* 吊板：`psd_top`、`psd_top_edge`
* 门灯/牌：`light_on/off`、`sign/door_not_in_use.png` 原本就有

---

## 二、三个物件的名字（id 不变，只改 lang + tooltip）

| id | 中文 | English |
|---|---|---|
| `mtrlte:xiamen_psd_door` | 厦门地铁一号线全高屏蔽门（门扇） | Xiamen Line 1 Full-Height PSD (Door) |
| `mtrlte:xiamen_psd_glass` | 厦门地铁一号线全高屏蔽门（玻璃） | … (Glass) |
| `mtrlte:xiamen_psd_end` | 厦门地铁一号线全高屏蔽门（端部） | … (End) |

`PsdAPGItem#appendTooltip` 每个门物品都多一行 `tooltip.mtrlte.texture_source`
= **贴图：厦门地铁一号线** / "Textures: Xiamen Line 1"。

---

## 三、半高 APG 全套

### 1. 真实尺寸 / 宽度（javap 证据，不是猜）

`mtr.item.ItemPSDAPGBase$EnumPSDAPGType#<init>(ZZZ)` 的静态初始化：

```
PSD_1         : (isPSD=true,  isOdd=false, isLift=false)
PSD_2         : (true,  false, false)
APG           : (false, false, false)     ← 我们用的
LIFT_DOOR_1   : (false, false, true)
```

`ItemPSDAPGBase#useOnBlock` 里：

```
  27: Field item.isDoor            → width  = isDoor ? (isOdd ? 3 : 2) : 1
  33: Field type.isOdd             → APG isOdd=false ⇒ 宽度 2 格
  36: Field type.isPSD             → checkHeight = isPSD ? 3 : 2   ⇒ APG 2 格
  83: iconst_2                     → 竖向循环恒为 y=0..1（两种类型都是）
 291: Field type.isPSD; ifeq 322  → 只有 isPSD 才 setBlockState(pos.up(2), BlockPSDTop.getActualState(...))
```

结论：**APG = 门 2 宽 × 2 高、玻璃/端部 1 宽 × 2 高，y+0/y+1 两格，没有吊板**（`BlockPSDTop` 不出现），
例外是 `LIFT_DOOR_ODD_1` 那种 `isOdd`（3 宽，电梯门），与 APG 无关。

### 2. 继承关系（照 MTR 自己的薄壳抄）

| 本模组 | 父类 | 说明 |
|---|---|---|
| `ApgDoor` | `mtr.block.BlockAPGDoor` | 它本身只是 `BlockPSDAPGDoorBase` 的薄壳；继承它才能让 `BlockPSDAPGBase#isAPG()` 为真（MTR 用它决定**上段选中框** 9/16 vs 整格） |
| `TileEntityXiamenAPGDoor` | `BlockPSDAPGDoorBase.TileEntityPSDAPGDoorBase` | 列车 `setOpen` 调的就是它 |
| `ApgGlass` | `BlockPSDAPGGlassBase` | **故意不用** `BlockAPGGlass`：后者多一个 `arrow_direction` 属性，而 blockstate 只按 `facing/side/half` 写变体，照抄会「属性对不上 → 模型加载不到」 |
| `ApgEnd` | `mtr.block.BlockAPGGlassEnd` | 同样为 `isAPG()` 为真 |

### 3. 门扇渲染器

`RenderPsdApgDoor extends RenderPSDAPGDoor<TileEntityXiamenAPGDoor>`，`type = 2`。
javap 的分支证据：

```
 171: getfield type  → tableswitch { 0: 200, 1: 200, 2: 368 }   // 门灯：0/1 用 light_%s，2 用 apg_door_light_%s
 507: getfield type  → tableswitch { 2: 765 }                   // 765: ldc "mtr:textures/block/apg_door_%s_%s.png"
```

几何 / UV / `open` 平移 / 门灯 / 「暂停使用」牌全部是 MTR 那一份，只是换成 APG 的模型与贴图。

### 4. 新 id 与文件

| 类别 | 文件 |
|---|---|
| 方块 id | `mtrlte:xiamen_apg_door` / `mtrlte:xiamen_apg_glass` / `mtrlte:xiamen_apg_end` |
| BlockEntity 类型 | `mtrlte:xiamen_apg_door`（只有门扇需要） |
| blockstates | `assets/mtrlte/blockstates/xiamen_apg_door.json`（`minecraft:block/air`）、`xiamen_apg_glass.json`、`xiamen_apg_end.json`（后两个由 MTR 的 `apg_glass*.json` 逐字复制 + `mtr:` → `mtrlte:`） |
| 方块模型 | `assets/mtrlte/models/block/apg_glass_{left,right,middle,single}_base.json` + `apg_glass_{bottom,top}_{left,right,middle,single}.json` + `apg_glass_end_{bottom,top}.json`（14 个，MTR 原型 + 本模组贴图） |
| 物品模型 | `assets/mtrlte/models/item/xiamen_apg_{door,glass,end}.json` |
| 物品图标 | `assets/mtrlte/textures/item/apg_{door,glass,glass_end}.png`（源：桌面 `amtr_1_apg_*.png`；**实际是 64×64，不是任务里写的 16×16**） |
| 方块贴图 | `apg_door_{top,bottom,top_left,top_right,bottom_left,bottom_right}.png`、`apg_glass_{top,bottom,single_top,single_bottom}.png`（来自资源包）+ `apg_door_light_{on,off}.png`、`apg_side_triangle.png`（资源包没有，取自 MTR jar） |
| 掉落表 | `data/mtrlte/loot_tables/blocks/xiamen_apg_{door,glass,end}.json`（门：`half=lower,side=left`；玻璃/端部：`half=lower`） |
| 创造页签 | `mtrlte:core`（厦门风格物件）→ 标志、PSD 三件、APG 三件 |
| 语言 | `block.mtrlte.xiamen_apg_*` = 厦门地铁一号线半高屏蔽门（门扇 / 玻璃 / 端部）；`tooltip.mtrlte.apg_*` 写明「2 宽 × 2 高，无吊板」 |

顺带补了 PSD 那三件的掉落表（`data/mtrlte/loot_tables/blocks/xiamen_psd_*`，之前缺 = 挖了不掉）。

---

## 四、验证

* `gradle -p v1.20.1 clean build` → **BUILD SUCCESSFUL**（`lte-1.0.23.jar`，未提交 git）
* jar 内 `RenderPsdDoorTextureMixin` 的注解已核对：`@Redirect(method=["render"], at=@At(value="INVOKE",
  target="Ljava/lang/String;format", remap=false))`
* 新模型/blockstate 里 **0 处 `mtr:` 引用**，全部 `mtrlte:`，且引用的 png/json 逐个存在（脚本核对）
* `EnumPSDAPGType.APG` / `ItemPSDAPGBase#useOnBlock` / `RenderPSDAPGDoor#render` 的判据均来自 javap

**没启游戏**（按要求）。进游戏后看三件事：
1. `latest.log` 里 `[lte] psd door tex: mtrlte:textures/block/psd_door_*.png <- LTE`（不再是资源包名）；
2. 全高门帖图不透明像素正常、开合动画正常；
3. 半高门：2 宽 × 2 高、**头顶是空气**（没有吊板），列车进站自动开。
