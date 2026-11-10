# 3D 光束编辑器（`/mtr_lte beam3d`）

> 适用：LTE 1.0.0 · Minecraft 1.20.1 / Fabric · MTR 3.6.x

## 打开

```
/mtr_lte beam3d                 # 默认选「世界里正在跑的车型」，没有就选资源包第一辆
/mtr_lte beam3d <车型id>        # 直接打开指定车型（MTR 的 baseTrainType）
```

前提：客户端装好 MTR 与资源包。编辑器**直接读资源包注册表**（`TrainClientRegistry`），
不要求这列车现在在世界上。

## 界面

```
┌────────────────────────────────────────────────────────────────────┐
│ [选择][移动][旋转][颜色]                                           │
├──────────────┬──────────────────────────────────┬──────────────────┤
│ 车型列表      │  真实列车模型 + 光束线框          │ 添加光柱          │
│ ● = 世界里在用│                                  │ 复制              │
│              │                                  │ 删除              │
│              │                                  │ 类型（头/尾）     │
│              │                                  │ 导出 JSON         │
│              │                                  │ 关闭              │
│              │                                  │ ── 颜色 ──        │
│              │                                  │ R / G / B 滑块    │
│              │                                  │ ── 几何 ──        │
│              │                                  │ 长 / 宽 / 透明    │
└──────────────┴──────────────────────────────────┴──────────────────┘
```

- **空白处左键拖动** = 转视角；**右键拖动** = 平移；**滚轮** = 缩放。
- **左键点光束** = 选中；按在光束上拖动 = 用当前工具编辑：
  - 选择：只选中，拖动仍然是转视角
  - 移动：拖动把光束沿屏幕平面移动（3 个轴一起算，正交相机下精确）
  - 旋转：左右 = 偏航（yaw），上下 = 俯仰（pitch）
  - 颜色：选中后用右侧 RGB 滑块改色
- 快捷键：`1`–`4` 切工具，`Delete` 删除选中束，`Ctrl+D` 复制选中束，`Esc` 关闭。
- 改动**立刻写运行时层**（世界里马上生效），但**不落盘**；只有「导出 JSON」会写文件。

## 坐标约定（车头朝 -Z）

模型的 `pos` / `rot` 全部在 **MTR 资源包的建模空间**里，和 `VehicleLights` / `TrainPose` 同一套：

```
x = 横向（车体右方 = +X）    y = 向上（车厢参考点起算）    z = 沿车长，负 = 车头
```

预览的朝向必须和游戏里一致，而 MTR 的世界渲染器（`JonModelTrainRenderer#renderCar`）
在 `model.render` 之前压的是 `Ry(π+Θ)·Rx(π+pc)`（Θ = 车身偏航、pc = 坡度）。预览要的是
Θ = 0，于是 `Ry(π)·Rx(π) = Rz(π)` —— 也就是说模型**要**一次 180°，否则整台车上下颠倒。
`TrainPreview` 里两个开关各是一个 180°：

| 开关 | 矩阵 | 效果 | 当前值 |
|---|---|---|---|
| `MODEL_FLIP_Y180` | `Ry(π)` | 前后对调 + 左右镜像（不改变“上下”） | `false` |
| `MODEL_FLIP_Z180` | `Rz(π)` | 上下翻转 + 左右镜像 = MTR 世界渲染器的 Θ=0 情形 | **`true`** |

翻转在 `Camera#rotation()` 里是最内层（`Rx(pitch)·Ry(yaw)·翻转`），所以模型、光束线框、坐标轴手柄、
拾取、拖拽全走**同一个**矩阵 —— 翻了车，光标也跟着翻，不会出现“点不准”。

光束方向由 `rot = [yaw, pitch, roll]`（度）给出，**零旋转 = 该类型的出厂方向**：

| 类型 | 出厂方向 | 公式 |
|---|---|---|
| `head` | 车头 **-Z** | `dir = (-sin(yaw)·cos(pitch), -sin(pitch), -cos(yaw)·cos(pitch))` |
| `tail` | 车尾 **+Z** | `dir = -dir(head)`（逐分量取反，**连 y 一起反**） |

尾灯是**车头方向取反**，和 world 端逐位一致（`ProtoBeamCone#emitSlot` 对尾端做
`dir.multiply(-1)`，`BeamGeom#axis` 就是结果）。注意“取反”和“yaw 再加 180°”在带俯仰时不同：
后者只反 x/z，尾灯的 pitch 符号会反过来、导出到运行时就和光柱对不上。`lengthAxis = X` 的模型
沿 X 排，符号规则相同。

## 轴向吸附 + 步进

选中一束后，视口左下角出现六个按钮（也可用快捷键）。表里是**头灯**的 rot；尾灯因为出厂方向
已经是车头取反，同一份“世界方向”要写成另一组角度（`BeamSnap#apply` 自己算，玩家只管按按钮）：

| 按钮 | 快捷键 | 头灯 yaw / pitch | 尾灯 yaw / pitch | 世界方向 |
|---|---|---|---|---|
| 前 -Z | `F` | 0° / 0° | 180° / 0° | (0, 0, -1) |
| 后 +Z | `B` | 180° / 0° | 0° / 0° | (0, 0, +1) |
| 上 +Y | `U` | 0° / -90° | 0° / +90° | (0, +1, 0) |
| 下 -Y | `D` | 0° / +90° | 0° / -90° | (0, -1, 0) |
| 左 -X | `L` | +90° / 0° | -90° / 0° | (-1, 0, 0) |
| 右 +X | `R` | -90° / 0° | +90° / 0° | (+1, 0, 0) |

注意 `pitch` 的正负：公式里是 `-sin(pitch)`，所以**抬头是负 pitch**（和旋转工具上下拖动同向）。

## XYZ 坐标轴手柄（移动模式）

选中一束并切到「移动」后，它的 `pos` 上长出三根 2 格的坐标轴：

```
X 红   Y 绿   Z 蓝        每根末端有个两笔小箭头（指正方向）
```

- **悬停**某根轴 → 该轴加粗高亮；**按住拖动** → 光束**只沿这一根轴**移动，另两个分量锁死。
- 位移不是“像素差 ÷ scale”，而是 **鼠标射线 × 轴向直线的最近点投影**：
  正交相机下 `pixel = centre + scale·(Rx·v, −Ry·v)`，所以视方向 `(0,0,1)` 的原像就是旋转矩阵的
  第三行 `u`、该像素的射线起点是 `vx·R第0行 + vy·R第1行`；再解两直线最近点
  `t = (b·(u·w) − dir·w) / (1 − b²)`（`w = base − O`，`b = dir·u`）。
  拖拽取 `t − t按下`，光标和轴上的点始终贴着走，斜着看也不会越拖越偏。
- 某根轴几乎正对相机（屏幕上是点，`1 − b² → 0`）时解退化，这一帧直接忽略 —— 换根轴或转一下视角。
- `Shift` 仍然是 **0.5 格步进**：结果量化到 0.5 的整数倍（和自由移动同一个 `BeamSnap#stepped`）。
- 多节预览时手柄按节各画一套，命中的是哪一节就按哪一节的偏移拖（车体局部坐标不变）。
- 手柄优先于光束本体：它长在光束起点上，不先判它的话永远点不到。想抓光束本身，抓它的中后段。

移动工具下**按住 Shift 拖动** = 0.5 格步进：位移以按下时那一点为基准重新量化（不是每帧各自取整，
否则 0.02 格的小位移会被吃掉、光束根本拖不动）。

## 导出格式

导出路径：`<桌面>/<key>.json`（桌面取不到时退回 `config/lte_beams/<key>.json`；一车一文件）。

- **`key` = `mtr_custom_resources.json` 里 `custom_trains.<key>` 的键**，也是
  `assets/mtr/<key>/` 的目录名。MTR 的注册 id 是 `mtr_custom_train_<key>`，导出时会剥掉
  前缀 —— 例如注册 id `mtr_custom_train_xmq6127agbevl3` → 文件 `xmq6127agbevl3.json`。
- 文件名里的非法字符会被换成 `_`；读取时同时接受 `<key>.json`、
  `mtr_custom_train_<key>.json` 和它们的小写形态（大小写不敏感）。

要发布时把它放到资源包：

```
assets/lte/beam3D/<key>.json
```

读取优先级（`BeamPackWriter#read`）：

1. `<桌面>/<key>.json` —— 编辑器上次导出的工作副本；
2. 已加载的资源包 `assets/lte/beam3D/<key>.json`（其它命名空间的 `assets/<ns>/beam3D/` 也认）；
3. 已加载的资源包旧路径 `assets/<ns>/lte_beams/<key>.json`（兼容）；
4. `config/lte_beams/<key>.json`（旧版本导出留下的文件）。

> 资源管理器只接受 `[a-z0-9/._-]` 的路径，`beam3D` 的大写 `D` 不是合法 Identifier，
> 所以扫描用的是 `ResourcePack#openRoot`（按路径段读包根），不是 `getAllResources`。

文件内容：

```json
{
  "version": 1,
  "_comment": ["..."],
  "id": "xmq6127agbevl3",
  "beams": [
    {
      "type": "head",
      "color": "#FFF3D6",
      "pos": [0.85, 1.2, 10.8],
      "rot": [0.0, 0.0, 0.0],
      "length": 16.0,
      "width": 3.0,
      "alpha": 0.16
    }
  ]
}
```

| 字段 | 含义 | 对应运行时 |
|---|---|---|
| `type` | `head` / `tail` / 包自定义 | 光束类型 |
| `color` | `#RRGGBB` | `BeamGeom.rgb` |
| `pos` | 车体局部模型空间 `[横向, 高度, 纵向]`（格） | `BeamGeom.origin` 的来源 |
| `rot` | 度 `[yaw, pitch, roll]` | `BeamGeom.axis` 的来源 |
| `length` | 格 | `BeamGeom.length` |
| `width` | 光锥直径（格） | 渲染宽度 |
| `alpha` | 0..1（光柱峰值透明度） | 渲染透明度 |

`BeamGeom` 的 `origin/axis` 是派生值，不在文件里存；文件里存的是作者能改的输入，
所以导出文件可以被编辑器重新打开（桌面上的 `<key>.json` 存在时优先读它）。

## 诊断

`/mtr_lte beam3d`「什么都没发生」时，按下面三行日志定位（都在 `logs/latest.log`）：

```
[lte] commands registered: /mtr_lte beam3d=true      # 字面量挂上了；false = 指令注册本身没走到
[lte] beam3d: vehicles=N vehicleId=... loaded=...    # 构造器一条、init() 一条（N 是车型目录大小）
[lte] beam3d: no vehicles (pack=none builtinPruned=... trainsInWorld=...)   # 只有空目录时打
```

- 只有第一条 → `setScreen` 没走到指令这一层。
- 有构造器那条、没有 `init()` 那条 → `init()` 里抛异常（红色聊天提示会给出异常类名 + 首行）。
- 两条都有、`vehicles=0` → 车型目录为空：资源包没加载，或者 `disableBuiltInTrains=true`
  把 MTR 内置车型从注册表里裁掉了（本模组的 `BuiltInTrains#prune`）。这时屏幕上会画
  「没有可用车型（资源包未加载？）」+ 关闭按钮，不再是空白窗；`/mtr_lte beam3d <车型id>`
  仍然能打开指定车型。

## 车型名 / 导入未加载的包 / 多节预览

### 列表显示的是「名字」，不是内部 id

名字来源（实证，不是猜的）：

| 位置 | 内容 |
|---|---|
| 资源包内路径 | **`assets/mtr/mtr_custom_resources.json`** —— MTR 的 `CustomResources#reload` 里写死的资源 id 只有 `mtr:mtr_custom_resources.json` 这一个，没有第二个路径 |
| 结构 | `{ "custom_trains": { "<key>": { "name": "...", ... } } }` |
| `name` 缺失时 | MTR 用条目 key 当默认值（`getOrDefault(obj, "name", key)`） |
| 注册 id | `"mtr_custom_train_" + key`（`CustomResources` 的字符串拼接常量就是 `mtr_custom_train_\u0001`） |
| 进游戏的路径 | `TrainClientRegistry#register` 把 `name` 包成 `Text.translatable(name)` 塞进 `TrainProperties#name` |

所以编辑器解析名字的顺序是：

1. `TrainClientRegistry.getTrainProperties(id).name` → 用 `Language.getInstance().get(key)` 解析
   （可翻译文本的 `getString()` 只返回键，必须先过语言表，这才是游戏里渲染的那一步），
   查不到译文就用原文 —— 包里 `name` 写字面名字时就是这个分支，等价于原样显示。
2. 注册表里没有（内置车型被 `disableBuiltInTrains` 裁掉、或包刚导入还没注册）→ 直接读上面那份
   json 的 `custom_trains.*.name`。
3. 都没有 → 去掉 `mtr_custom_train_` 前缀的 id。

搜索框同时匹配**名字 / id / 包名**，所以按 id 找车的习惯不变；视口左上角显示
`包名 · 名字 (id)`。

### 导入一个「游戏没加载」的资源包

左栏最下方「导入资源包…」→ 选 zip 或文件夹：

- 自动列出 `<game>/resourcepacks/` 与 `config/lte_packs/`（各两层）里**真的带
  `mtr_custom_resources.json`** 的包，点一下填路径；
- 也可以直接粘任意绝对路径；
- 读的是包内 `assets/mtr/mtr_custom_resources.json`（找不到再扫任意层的同名文件，兼容老包），
  车型 id 用与 MTR 一致的 `mtr_custom_train_<key>`，列表里用偏蓝的字色区分；
- 模型：`model` / `model_properties` 是资源路径（如 `mypack:models/train/x.bbmodel`），
  映射成 `assets/<ns>/<path>`，直接 `new DynamicTrainModel(modelJson, propsJson, doorAnimationType)`
  —— 这个构造器是 public 的，**不需要把包注册进 MTR** 就能预览；
- 贴图：`texture_id + ".png"` 从包里读出 PNG，用 `NativeImageBackedTexture` 挂到贴图管理器上
  （合成 id `mtrlte:import/...`），关编辑器时释放；
- 名字：优先包自带 `assets/<ns>/lang/{zh_cn,en_us}.json`（未加载的包在游戏语言表里查不到）。

导出（导入的车型）：

| 包类型 | 写到哪 |
|---|---|
| 文件夹包 | `<包>/assets/<ns>/beam3D/<key>.json`（自动建目录，写完立刻读回核对束数） |
| zip | `<桌面>/<key>.json`（桌面不可用时 `config/lte_beams/`），提示「解压后把 `beam3D` 放进 `<包>/assets/<ns>/beam3D/`」——**不改 zip** |

`<ns>` 取 `texture_id`（退化到 `model` / `model_properties`）的命名空间，跳过 `minecraft`，都没有就用 `mtr`。

两类包都支持：

| 包的写法 | 模型从哪来 |
|---|---|
| 整车包：`model` + `model_properties` | 直接读包内文件构造 `DynamicTrainModel` |
| 涂装包：只有 `texture_id` + `base_train_type` | 复用**基础车型**已加载的模型，贴图用包里那张（与 MTR 注册时 `new JonModelTrainRenderer(baseModel, 本包 texture)` 一致） |

### 多节车厢预览

右栏「车厢 ×N」（1…8 循环），只影响预览，**不影响导出的 JSON**（光束坐标始终是单车局部的）。

- 间距：`VehicleLights.Layout#sectionLen` = 模型沿长度轴的几何跨度 / `sections`，
  一个 bbmodel 里塞了几节车时会自动拆开；量不出（≤0.5 格）再试 `length`，都不行才用 **10 格**；
- 方向：`Layout#lengthAxis`（`X` 或 `Z`）；
- 摆放：以视口中心对称，第 i 节偏移 `(i - (N-1)/2) * 间距`，`carIndex / trainCars` 照实传给模型；
- 光束按节重复画（同一份车体局部坐标），换节数时自动按整列重新取景；
- 视口右上角显示「编组 N 节 · 间距 X.X 格」。

## 相关代码

| 文件 | 作用 |
|---|---|
| `v1.20.1/.../BeamEditor3DScreen.java` | 界面、工具、拖拽、滑块、车型列表 |
| `v1.20.1/.../TrainPreview.java` | 在 GUI 里渲染 MTR 模型 + 正交反投影拾取 |
| `v1.20.1/.../BeamPackWriter.java` | `config/lte_beams/<id>.json` 的读写 |
| `v1.20.1/.../TrainNames.java` | 车型名：`TrainProperties#name` → 语言表 → 包里 json 的 `name` |
| `v1.20.1/.../CustomPack.java` | 读一个未加载的 zip/文件夹包：json、模型、贴图、导出到包内 |
| `v1.20.1/.../PackImportScreen.java` | 「导入资源包」页：自动扫 + 路径框 |
| `common/.../BeamSession.java` | 光束会话（自动识别 + 覆盖合并、增删改、运行时 put） |
| `common/.../VehicleLightJson.java` | 光束字段的解析/序列化（复用同一套字段名） |
