# 内置车型头灯灯位表（`builtin_lights.json`）

状态：已生成并接入。**95 / 114** 个内置车型的灯位已写死进资源表；**19 个无法确定**，见第 4 节，需要手动配置。

## 0. 先说清楚一件事

MTR 3.6.4.2 的 jar 里 **没有** `assets/mtr/**/train/*.json`。内置车型不是资源包 JSON，而是手写的 Java 模型类
（`mtr.model.ModelATrain`、`ModelSP1900` ……），由 `TrainClientRegistry` 注册。114 这个数字是
`mtr.data.TrainType` 的枚举常量数：其中 **106 个** 绑定 Java 模型，**8 个**（矿车 + 6 种船 + 飞行矿车）直接用原版模型。

好消息是：手写模型里灯的分组字段名确实是统一的 —— `headlights` / `tail_lights`（少数变体写作
`headlight` / `tail_light`），和资源包作者的习惯一致。所以“按分组名识别”这条路对内置车同样成立，只是数据源
从 JSON 换成了类里的 `ModelMapper` 字段。

## 1. 提取方法

1. **车型 → 模型类**：`javap -c mtr.client.TrainClientRegistry`，解析 106 条
   `register(TrainType.X, ..., new ModelY(...))` 调用；id 就是枚举名小写（`A_TRAIN_TCL` → `a_train_tcl`）。
2. **找灯组**：CFR 反编译 `mtr/model/Model*.java`，只认名字为 `headlights`/`tail_lights`（含单数、
   `head_lamp`）的根 `ModelMapper`；`roof_light`、`door_light`、`window_light` 等一律不算。
3. **算位置**：写了一个离线探针（不启动游戏），实例化真实模型类，只渲染灯组，用一个捕获型
   `VertexConsumer` 收下每个 cuboid 的顶点，换算成方块。`ModelMapper.render` 会把部件 pivot 重置到
   `(0,0,position)`，所以事后把模型作者写的 pivot y（通常是 24 px = 1.5 格，即车厢地板）加回去 ——
   这与资源包模型 `DynamicTrainModel.PartInfo` 的高度约定一致。
4. **前后端**：每个模型的两端各渲染一次，并按 `renderHeadPosition1/2` 里实际用的是
   `renderOnce` 还是 `renderOnceFlipped` 选择结果（翻转会镜像 x/z）。
5. **清洗**：丢掉 y 不在 0.15..3.4、|x| > 2.2 的 cuboid（牌照灯、地板灯、反光条），按位置去重，
   每侧最多保留 2 个最外侧灯；没有 `tail_lights` 的模型把 head 镜像到车尾。

坐标约定（与 `VehicleLights` / `TrainPose` 一致）：`x` 横向、`y` 从车厢参考点向上、`z` 沿车长
（**负 = 车头端**），`w/h` 是灯箱宽高，单位都是方块。数组里**一个元素 = 一盏灯**，左右成对时保持两条。

## 2. 结果

* 写入 **95** 个车型：**75** 个两灯（左右各一），**20** 个四灯（左右各两，多为带转角灯的车头）。
* 灯高范围：0.668 .. 1.836 格，全部落在车厢腰线附近，没有 y 越界的条目。
* 左右对称性检查：95 条中 94 条首尾 z 基本互为相反数；唯一偏差较大的是 `mpl_85`（头 z≈-10.43，尾 z≈9.07，
  该模型尾灯本来就不对称），不影响光柱起点。

示例（节选）：

```json
"a_train_tcl": {
  "head": [
    {"x":-0.912,"y":1.133,"z":-14.027,"w":0.382,"h":0.235},
    {"x":-0.625,"y":1.133,"z":-14.088,"w":0.25,"h":0.235},
    {"x":0.625,"y":1.133,"z":-14.088,"w":0.25,"h":0.235},
    {"x":0.912,"y":1.133,"z":-14.027,"w":0.382,"h":0.235}
  ],
  "tail": [
    {"x":-0.914,"y":1.13,"z":14.026,"w":0.382,"h":0.235},
    {"x":0.914,"y":1.13,"z":14.026,"w":0.382,"h":0.235}
  ]
}
```

```json
"c_train": {
  "head": [
    {"x":-0.596,"y":0.961,"z":-13.44,"w":0.248,"h":0.251},
    {"x":0.596,"y":0.961,"z":-13.44,"w":0.248,"h":0.251}
  ],
  "tail": [
    {"x":-0.689,"y":0.93,"z":13.427,"w":0.434,"h":0.313},
    {"x":0.689,"y":0.93,"z":13.427,"w":0.434,"h":0.313}
  ]
}
```

```json
"sp1900": {
  "head": [
    {"x":-0.699,"y":0.678,"z":-12.99,"w":0.249,"h":0.376},
    {"x":-0.531,"y":0.658,"z":-13.037,"w":0.438,"h":0.352},
    {"x":0.531,"y":0.658,"z":-13.037,"w":0.438,"h":0.352},
    {"x":0.699,"y":0.678,"z":-12.99,"w":0.249,"h":0.376}
  ],
  "tail": [
    {"x":-0.699,"y":0.678,"z":12.99,"w":0.249,"h":0.376},
    {"x":-0.531,"y":0.658,"z":13.037,"w":0.438,"h":0.352},
    {"x":0.531,"y":0.658,"z":13.037,"w":0.438,"h":0.352},
    {"x":0.699,"y":0.678,"z":12.99,"w":0.249,"h":0.376}
  ]
}
```

## 3. 代码接入

* `dev.js.mtrheadlight.BuiltinLights`：启动时从 `/assets/mtrlte/builtin_lights.json` 读表；
  按 `train.trainId` 精确匹配（小写、去掉 `mtr:` 前缀）。
* `VehicleLights.layoutFor(TrainClient)`：**有 `DynamicTrainModel` 时照旧走原有启发式**；没有动态模型时
  才查这张表。这样资源包车型永远不会被内置表抢走，内置车型则不再返回 `null`。
* `ProtoBeamCone`：原来 `modelOf(train) == null` 直接跳过（内置车永远没有光柱）；现在改为“没有动态模型时
  尝试内置表，表里也没有才跳过”。
* 优先级：本表只用于“没有 `DynamicTrainModel` 的内置车”。资源包车型有动态模型，永远走原有启发式，
  不会被这张表抢走；`lte_vehicle_lights.json` 的运行时覆盖仍按它自己的链路生效。

## 4. 无法确定 —— 需要手动配置（19 个）

这 19 个车型**不会**出现在光柱逻辑里（`layoutFor` 返回 null），直到补上位置。

| 车型 id | 模型 | 原因 | 说明 / 建议 |
|---|---|---|---|
| `minecart` | `—` | 原版模型 | MTR 用原版 minecart/boat 模型，没有车灯几何；想要光柱只能手写 |
| `oak_boat` | `—` | 原版模型 | MTR 用原版 minecart/boat 模型，没有车灯几何；想要光柱只能手写 |
| `spruce_boat` | `—` | 原版模型 | MTR 用原版 minecart/boat 模型，没有车灯几何；想要光柱只能手写 |
| `birch_boat` | `—` | 原版模型 | MTR 用原版 minecart/boat 模型，没有车灯几何；想要光柱只能手写 |
| `jungle_boat` | `—` | 原版模型 | MTR 用原版 minecart/boat 模型，没有车灯几何；想要光柱只能手写 |
| `acacia_boat` | `—` | 原版模型 | MTR 用原版 minecart/boat 模型，没有车灯几何；想要光柱只能手写 |
| `dark_oak_boat` | `—` | 原版模型 | MTR 用原版 minecart/boat 模型，没有车灯几何；想要光柱只能手写 |
| `ngong_ping_360_crystal` | `ModelNgongPing360` | 无灯组 | 模型里完全没有 light/lamp 字段（只有车厢结构），缆车灯需要目测 |
| `ngong_ping_360_crystal_rht` | `ModelNgongPing360` | 无灯组 | 模型里完全没有 light/lamp 字段（只有车厢结构），缆车灯需要目测 |
| `ngong_ping_360_crystal_plus` | `ModelNgongPing360` | 无灯组 | 模型里完全没有 light/lamp 字段（只有车厢结构），缆车灯需要目测 |
| `ngong_ping_360_crystal_plus_rht` | `ModelNgongPing360` | 无灯组 | 模型里完全没有 light/lamp 字段（只有车厢结构），缆车灯需要目测 |
| `ngong_ping_360_normal_red` | `ModelNgongPing360` | 无灯组 | 模型里完全没有 light/lamp 字段（只有车厢结构），缆车灯需要目测 |
| `ngong_ping_360_normal_red_rht` | `ModelNgongPing360` | 无灯组 | 模型里完全没有 light/lamp 字段（只有车厢结构），缆车灯需要目测 |
| `ngong_ping_360_normal_orange` | `ModelNgongPing360` | 无灯组 | 模型里完全没有 light/lamp 字段（只有车厢结构），缆车灯需要目测 |
| `ngong_ping_360_normal_orange_rht` | `ModelNgongPing360` | 无灯组 | 模型里完全没有 light/lamp 字段（只有车厢结构），缆车灯需要目测 |
| `ngong_ping_360_normal_light_blue` | `ModelNgongPing360` | 无灯组 | 模型里完全没有 light/lamp 字段（只有车厢结构），缆车灯需要目测 |
| `ngong_ping_360_normal_light_blue_rht` | `ModelNgongPing360` | 无灯组 | 模型里完全没有 light/lamp 字段（只有车厢结构），缆车灯需要目测 |
| `a320` | `ModelA320` | 无灯组 | 模型只有 end_light / left_light_r1 / right_light_r1（航行灯），不是 headlights 命名 |
| `flying_minecart` | `—` | 原版模型 | MTR 用原版 minecart/boat 模型，没有车灯几何；想要光柱只能手写 |

### 手动补法

把下面这种条目补全后发回来即可（或直接编辑 `builtin_lights.json` 再重新构建）：
`x` 横向、`y` 高度、`z` 沿车长（负 = 车头）、`w/h` 灯箱宽高，单位方块；一辆车两盏灯就写两条，
不对称的位置各写各的。

```json
"a320": {
  "head": [
    {"x": -0.9, "y": 1.2, "z": -12.0, "w": 0.3, "h": 0.25},
    {"x":  0.9, "y": 1.2, "z": -12.0, "w": 0.3, "h": 0.25}
  ],
  "tail": [
    {"x": -0.9, "y": 1.2, "z":  12.0, "w": 0.3, "h": 0.25},
    {"x":  0.9, "y": 1.2, "z":  12.0, "w": 0.3, "h": 0.25}
  ]
}
```

缆车（Ngong Ping 360）和 A320 建议先在游戏里用光束编辑器（`BeamSession`）对一下实际灯面，再把数值抄回来。
矿车/船这 8 个原版模型没有灯面可对，想要光柱只能按车体尺寸估一个位置。

## 5. 匹配依据（全部 95 条）

“分组名”= 模型里有 `headlights`/`tail_lights` 字段；“翻转”= 该端渲染用了 `renderOnceFlipped`；
“镜像”= 模型没有 tail 组，用 head 对称到车尾。**没有任何车型使用“按尺寸估算”**——尺寸只用于过滤
离群 cuboid（y 0.15..3.4、|x| ≤ 2.2），所有写入的位置都来自模型真实灯箱。

| 车型 id | 模型 | 灯数 | y | 依据 |
|---|---|---|---|---|
| `sp1900` | `ModelSP1900` | 4 | 0.668 | group headlights/tail_lights + head end flipped |
| `sp1900_small` | `ModelSP1900Small` | 4 | 0.668 | group headlights/tail_lights + head end flipped |
| `sp1900_mini` | `ModelSP1900Mini` | 4 | 0.668 | group headlights/tail_lights + head end flipped |
| `c1141a` | `ModelSP1900` | 4 | 0.668 | group headlights/tail_lights + head end flipped |
| `c1141a_small` | `ModelSP1900Small` | 4 | 0.668 | group headlights/tail_lights + head end flipped |
| `c1141a_mini` | `ModelSP1900Mini` | 4 | 0.668 | group headlights/tail_lights + head end flipped |
| `m_train` | `ModelMTrain` | 2 | 1.563 | group headlights/tail_lights + tail end flipped |
| `m_train_small` | `ModelMTrainSmall` | 2 | 1.563 | group headlights/tail_lights + tail end flipped |
| `m_train_mini` | `ModelMTrainMini` | 2 | 1.563 | group headlights/tail_lights + tail end flipped |
| `cm_stock` | `ModelCMStock` | 2 | 1.724 | group headlights/tail_lights + tail end flipped |
| `cm_stock_small` | `ModelCMStockSmall` | 2 | 1.724 | group headlights/tail_lights + tail end flipped |
| `cm_stock_mini` | `ModelCMStockMini` | 2 | 1.724 | group headlights/tail_lights + tail end flipped |
| `mlr` | `ModelMLR` | 4 | 1.063 | group headlights/tail_lights + head end flipped |
| `mlr_small` | `ModelMLRSmall` | 4 | 1.063 | group headlights/tail_lights + head end flipped |
| `mlr_mini` | `ModelMLRMini` | 4 | 1.063 | group headlights/tail_lights + head end flipped |
| `mlr_christmas` | `ModelMLR` | 4 | 1.063 | group headlights/tail_lights + head end flipped |
| `mlr_christmas_small` | `ModelMLRSmall` | 4 | 1.063 | group headlights/tail_lights + head end flipped |
| `mlr_christmas_mini` | `ModelMLRMini` | 4 | 1.063 | group headlights/tail_lights + head end flipped |
| `e44` | `ModelE44` | 2 | 1.216 | group headlights/tail_lights + head end flipped + head mirrored to rear |
| `e44_mini` | `ModelE44Mini` | 2 | 1.216 | group headlights/tail_lights + head end flipped + head mirrored to rear |
| `r_train` | `ModelRTrain` | 2 | 0.967 | group headlights/tail_lights + tail end flipped |
| `r_train_small` | `ModelRTrainSmall` | 2 | 0.967 | group headlights/tail_lights + tail end flipped |
| `r_train_mini` | `ModelRTrainMini` | 2 | 0.967 | group headlights/tail_lights + tail end flipped |
| `drl` | `ModelDRL` | 2 | 1.144 | group headlights/tail_lights + tail end flipped |
| `k_train` | `ModelKTrain` | 2 | 1.069 | group headlights/tail_lights + tail end flipped |
| `k_train_small` | `ModelKTrainSmall` | 2 | 1.069 | group headlights/tail_lights + tail end flipped |
| `k_train_mini` | `ModelKTrainMini` | 2 | 1.069 | group headlights/tail_lights + tail end flipped |
| `k_train_tcl` | `ModelKTrain` | 2 | 1.069 | group headlights/tail_lights + tail end flipped |
| `k_train_tcl_small` | `ModelKTrainSmall` | 2 | 1.069 | group headlights/tail_lights + tail end flipped |
| `k_train_tcl_mini` | `ModelKTrainMini` | 2 | 1.069 | group headlights/tail_lights + tail end flipped |
| `k_train_ael` | `ModelKTrain` | 2 | 1.069 | group headlights/tail_lights + tail end flipped |
| `k_train_ael_small` | `ModelKTrainSmall` | 2 | 1.069 | group headlights/tail_lights + tail end flipped |
| `k_train_ael_mini` | `ModelKTrainMini` | 2 | 1.069 | group headlights/tail_lights + tail end flipped |
| `c_train` | `ModelCTrain` | 2 | 0.961 | group headlights/tail_lights + tail end flipped |
| `c_train_small` | `ModelCTrainSmall` | 2 | 0.961 | group headlights/tail_lights + tail end flipped |
| `c_train_mini` | `ModelCTrainMini` | 2 | 0.961 | group headlights/tail_lights + tail end flipped |
| `s_train` | `ModelSTrain` | 2 | 0.938 | group headlights/tail_lights + tail end flipped |
| `s_train_small` | `ModelSTrainSmall` | 2 | 0.938 | group headlights/tail_lights + tail end flipped |
| `s_train_mini` | `ModelSTrainMini` | 2 | 0.938 | group headlights/tail_lights + tail end flipped |
| `a_train_tcl` | `ModelATrain` | 4 | 1.133 | group headlights/tail_lights + tail end flipped |
| `a_train_tcl_small` | `ModelATrainSmall` | 4 | 1.133 | group headlights/tail_lights + tail end flipped |
| `a_train_tcl_mini` | `ModelATrainMini` | 4 | 1.133 | group headlights/tail_lights + tail end flipped |
| `a_train_ael` | `ModelATrain` | 4 | 1.133 | group headlights/tail_lights + tail end flipped |
| `a_train_ael_mini` | `ModelATrainMini` | 4 | 1.133 | group headlights/tail_lights + tail end flipped |
| `light_rail_1` | `ModelLightRail` | 2 | 1.219 | group headlights/tail_lights |
| `light_rail_1_rht` | `ModelLightRail` | 2 | 1.219 | group headlights/tail_lights |
| `light_rail_1r` | `ModelLightRail` | 2 | 1.219 | group headlights/tail_lights |
| `light_rail_1r_rht` | `ModelLightRail` | 2 | 1.219 | group headlights/tail_lights |
| `light_rail_2` | `ModelLightRail` | 2 | 1.219 | group headlights/tail_lights |
| `light_rail_2r` | `ModelLightRail` | 2 | 1.219 | group headlights/tail_lights |
| `light_rail_2_rht` | `ModelLightRail` | 2 | 1.219 | group headlights/tail_lights |
| `light_rail_2r_rht` | `ModelLightRail` | 2 | 1.219 | group headlights/tail_lights |
| `light_rail_3` | `ModelLightRail` | 2 | 1.219 | group headlights/tail_lights |
| `light_rail_3_rht` | `ModelLightRail` | 2 | 1.219 | group headlights/tail_lights |
| `light_rail_3r` | `ModelLightRail` | 2 | 1.219 | group headlights/tail_lights |
| `light_rail_3r_rht` | `ModelLightRail` | 2 | 1.219 | group headlights/tail_lights |
| `light_rail_4` | `ModelLightRail` | 2 | 1.219 | group headlights/tail_lights |
| `light_rail_4_rht` | `ModelLightRail` | 2 | 1.219 | group headlights/tail_lights |
| `light_rail_5` | `ModelLightRail` | 2 | 1.219 | group headlights/tail_lights |
| `light_rail_5_rht` | `ModelLightRail` | 2 | 1.219 | group headlights/tail_lights |
| `light_rail_1r_old` | `ModelLightRail` | 2 | 1.219 | group headlights/tail_lights |
| `light_rail_1r_old_rht` | `ModelLightRail` | 2 | 1.219 | group headlights/tail_lights |
| `light_rail_4_old` | `ModelLightRail` | 2 | 1.219 | group headlights/tail_lights |
| `light_rail_4_old_rht` | `ModelLightRail` | 2 | 1.219 | group headlights/tail_lights |
| `light_rail_5_old` | `ModelLightRail` | 2 | 1.219 | group headlights/tail_lights |
| `light_rail_5_old_rht` | `ModelLightRail` | 2 | 1.219 | group headlights/tail_lights |
| `light_rail_1_orange` | `ModelLightRail` | 2 | 1.219 | group headlights/tail_lights |
| `light_rail_1_orange_rht` | `ModelLightRail` | 2 | 1.219 | group headlights/tail_lights |
| `light_rail_1r_orange` | `ModelLightRail` | 2 | 1.219 | group headlights/tail_lights |
| `light_rail_1r_orange_rht` | `ModelLightRail` | 2 | 1.219 | group headlights/tail_lights |
| `light_rail_2_orange` | `ModelLightRail` | 2 | 1.219 | group headlights/tail_lights |
| `light_rail_2_orange_rht` | `ModelLightRail` | 2 | 1.219 | group headlights/tail_lights |
| `light_rail_3_orange` | `ModelLightRail` | 2 | 1.219 | group headlights/tail_lights |
| `light_rail_3_orange_rht` | `ModelLightRail` | 2 | 1.219 | group headlights/tail_lights |
| `light_rail_4_orange` | `ModelLightRail` | 2 | 1.219 | group headlights/tail_lights |
| `light_rail_4_orange_rht` | `ModelLightRail` | 2 | 1.219 | group headlights/tail_lights |
| `light_rail_5_orange` | `ModelLightRail` | 2 | 1.219 | group headlights/tail_lights |
| `light_rail_5_orange_rht` | `ModelLightRail` | 2 | 1.219 | group headlights/tail_lights |
| `london_underground_d78` | `ModelLondonUndergroundD78` | 2 | 1.625 | group headlights/tail_lights + head end flipped |
| `london_underground_d78_mini` | `ModelLondonUndergroundD78Mini` | 2 | 1.625 | group headlights/tail_lights + head end flipped |
| `london_underground_1995` | `ModelLondonUnderground1995` | 2 | 1.0 | group headlights/tail_lights + head end flipped |
| `london_underground_1996` | `ModelLondonUnderground1995` | 2 | 1.0 | group headlights/tail_lights + head end flipped |
| `r179` | `ModelR179` | 4 | 1.047 | group headlights/tail_lights + tail end flipped |
| `r179_mini` | `ModelR179Mini` | 4 | 1.047 | group headlights/tail_lights + tail end flipped |
| `r211` | `ModelR211` | 2 | 1.0 | group headlights/tail_lights + tail end flipped |
| `r211_mini` | `ModelR211Mini` | 2 | 1.0 | group headlights/tail_lights + tail end flipped |
| `r211t` | `ModelR211` | 2 | 1.0 | group headlights/tail_lights + tail end flipped |
| `r211t_mini` | `ModelR211Mini` | 2 | 1.0 | group headlights/tail_lights + tail end flipped |
| `class_377_southern` | `ModelClass377` | 2 | 1.157 | group headlights/tail_lights + head end flipped |
| `class_802_gwr` | `ModelClass802` | 2 | 0.715 | group headlights/tail_lights + head end flipped |
| `class_802_gwr_mini` | `ModelClass802Mini` | 2 | 0.715 | group headlights/tail_lights + head end flipped |
| `class_802_tpe` | `ModelClass802` | 2 | 0.715 | group headlights/tail_lights + head end flipped |
| `class_802_tpe_mini` | `ModelClass802Mini` | 2 | 0.715 | group headlights/tail_lights + head end flipped |
| `mpl_85` | `ModelMPL85` | 4 | 1.836 | group headlights/tail_lights |
| `br_423` | `ModelBR423` | 2 | 0.938 | group headlights/tail_lights + tail end flipped |

## 6. 重新生成

输入数据已随仓库保存，不依赖反编译：

* `tools/builtin-train-types.json` —— 114 个 TrainType → 模型类/构造参数
* `tools/builtin-model-lights.json` —— 67 个模型变体的已选灯箱（head/tail + 依据）
* `tools/extract-builtin-lights.js` —— 合并成资源表并打印 19 个不确定项

```bash
node tools/extract-builtin-lights.js
```
