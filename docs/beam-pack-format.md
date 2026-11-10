# 光柱配置（beam pack）格式

一个车型一束光柱的位置、方向、长度、粗细、颜色、透明度，全部写在 JSON 里。本模组读三种层级
的文件，写同一个 schema：

| 层级 | 文件名 | 作用范围 | 什么时候用 |
|---|---|---|---|
| **key** | `assets/lte/beam3D/<key>.json` | 一个涂装 | 某个涂装和别的涂装不一样（灯位不同、要单独调） |
| **base** | `assets/lte/beam3D/<baseTrainType>.json` | 一个运行时车型 id | 手头只有渲染器报的 id（`train_12_2`），或者一个基础车型的所有涂装共用 |
| **shared** | `assets/lte/beam3D/_shared.json` | **整个资源包** | 一个公交车 5 个涂装、一条线 8 种编组 —— 只写一份 |

三份文件可以同时存在：越具体的越优先。

```text
<key>.json  →  <baseTrainType>.json  →  _shared.json  →  config/lte_beams/<key>.json
```

---

## 1. 文件放哪

```text
<资源包>/
├─ pack.mcmeta
└─ assets/
   ├─ lte/beam3D/            ← 约定的主路径（目录名照写 beam3D；小写 beam3d 也认）
   │  ├─ _shared.json        ← 包级共用：本包所有车型
   │  ├─ xml6125jevd0c_normal.json
   │  └─ train_12_2.json
   └─ mtr/
      ├─ mtr_custom_resources.json   ← 「同包」判定的依据
      └─ xml6125jevd0c/...
```

- 作者的命名空间也认：`assets/<你的命名空间>/beam3D/<id>.json`（`assets/lte/` 优先）。
- 旧路径 `assets/<ns>/lte_beams/<id>.json` 仍可读，优先级低于 `beam3D/`。
- 文件名**大小写不敏感**；`<key>.json`、它的小写形态、`mtr_custom_train_<key>.json` 都试。
- 文件不一定非在包里：桌面上 `<key>.json`（编辑器「导出 JSON」的落点）优先级最高，方便边调边看；
  确认后再挪进包。桌面不可用时退回 `config/lte_beams/`。

### 完整查找顺序

| # | 位置 | 日志里的 `source` |
|---|---|---|
| 1 | 桌面 `<key>.json` → `<baseTrainType>.json` | `key` / `base` |
| 2 | 包内 `assets/lte/beam3D/<key>.json`（只在**声明了这个 key 的包**里找） | `key` |
| 3 | 包内 `assets/lte/beam3D/<baseTrainType>.json`（所有已启用包） | `base` |
| 4 | 包内 `_shared.json`（只在**声明了这辆车的包**里找，见 §2） | `shared` |
| 5 | `config/lte_beams/<key>.json`（老版本导出的落点） | `config` |

同一个位置里：`beam3D/` 压 `lte_beams/`，`assets/lte/` 压作者命名空间，**后加载的包赢**。
都没有时不算错，只是没有声明表 —— 光柱退回自动识别的一头一尾。

命中 / 落空各打一行，每个车型只打一次（换车、换文件、F3+T 之后才再打）：

```text
[lte] beam pack: 厦门金旅:assets/lte/beam3D/_shared.json id=train_12_2 beams=4 source=shared
[lte] beam pack: miss id=SP1900 tried=[sp1900.json, mtr_custom_train_sp1900.json]
```

---

## 2. 什么算「同一个包」

`_shared.json` 是**包级**的：它只对**这个包自己声明的车型**生效。判定在
`BeamPackScope`（`v1.20.1/.../BeamPackScope.java`）里，做法是扫包内的
`assets/mtr/mtr_custom_resources.json`，把 `custom_trains` 的每个条目展开成一组 id：

| 来源 | 例子 |
|---|---|
| `custom_trains.<key>` 的 **key** | `xml6125jevd0c_normal` |
| MTR 的**注册 id** | `mtr_custom_train_xml6125jevd0c_normal` |
| 条目里写的 `base_train_type`（涂装包） | `train_12_2`（`base_xxx` 还会按 MTR 的规则换成 `train_xxx`） |
| **`model_properties` 生成的运行时 id** | `train_12_2` ← `transport_mode` + `length` + `width` |

最后一行是关键：MTR 对每个自带模型的车型会用
`String.format("%s_%s_%s", transport_mode, length, width)` 生成一个运行时 id
（`{ "transport_mode":"TRAIN","length":12,"width":2 }` → `train_12_2`），渲染器报给模组的就是它，
跟包里的 key 毫无字面关系。所以本模组会读那份 `model_properties` 反算回去 —— 于是
`train_12_2` 能对上这个包里的 5 个涂装 key，`_shared.json` 才会生效。

反过来也成立：**别的包的车、MTR 内置车（`SP1900` 之类）不受影响**，即使它们和这个包同时启用。
「只有 `_shared.json`、没有 `mtr_custom_resources.json`」的包不算声明任何车型，它的共用配置
不会外溢到别人的车上。

---

## 3. schema

```json
{
  "version": 1,
  "id": "_shared",
  "beams": [ { … }, { … } ]
}
```

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `version` | int | 否 | 格式版本，当前 `1`。比本模组新时按 1 读并留一条 note，不报错 |
| `id` | string | 否 | 内容里的车型名，**只供人看**。按哪个 id 生效由**文件名**决定 |
| `beams` | array | 是 | 光柱数组。**空数组 = 这个车型不要光柱**（合法结果） |
| `_comment` | array/string | 否 | 忽略；导出的文件用它写格式说明 |

### `beams[]`

| 字段 | 类型 | 单位 | 默认值 | 说明 |
|---|---|---|---|---|
| `type` | string | — | `"head"` | `head` / `tail` / 自定义名字。**决定方向**（见 §4）与合并规则 |
| `color` | string/number | — | 头灯 `#FFF3D6`，尾灯 `#FF2A1A` | `#RRGGBB`、`RRGGBB`、`0xRRGGBB`、8 位 `AARRGGBB`（透明通道被丢掉）都行 |
| `pos` | `[x,y,z]` | 格（方块） | 无 → 这条画不出来 | 灯口位置，**车体局部模型空间**（见 §4） |
| `rot` | `[yaw,pitch,roll]` | 度 | `[0,0,0]` | 相对车头方向的额外偏转 |
| `length` | number | 格 | 头灯 16、尾灯 8 | 光柱长度，必须 > 0 |
| `width` | number | 格 | 3.0 | 光锥**直径**（末端粗细），必须 > 0 |
| `alpha` | number | 0..1 | 0.16 | 光柱峰值不透明度，超出范围会被夹紧 |

容错：字段写错只丢那一项、其余照读（`color` 不是 6 位十六进制 → 用默认色；`pos`/`rot` 不是
3 个数字 → 当没写；`length`/`width` ≤ 0 → 当没写）。整份文件解析不了才会被跳过，并在日志里
留一条 `[lte] beam pack: … cannot parse …`。

**只想改颜色**时可以不写 `pos`：条目会叠在自动识别的那对灯上（这时图形位置仍来自模型）。
一旦写了 `pos`，这个 `type` 的几何就由文件说了算。

---

## 4. 坐标约定（最容易错的地方）

- **车头朝模型空间 −Z。** 头灯在 `z < 0` 那一端，尾灯在 `z > 0` 那一端。写反了的现象是：
  光柱从车尾冒出来、朝车头射。
- `pos` 是**车体局部模型空间**（模型自己的坐标轴，单位格），**不是世界坐标**，也不随车转向变化；
  每节车厢都按同一份局部坐标画。
- `pos = [横向, 高度, 沿车长]`：
  - `pos[0]` 横向：车体中心线为 0，**左负右正**（看向车头时）。
  - `pos[1]` 高度：相对车体参考面，向上为正。
  - `pos[2]` 沿车长：**负 = 车头端**，正 = 车尾端。车长 12 格的车，车头端面就是 `z ≈ -6`。
- **左右灯各写一条**：没有自动左右镜像。一辆公交车两个前大灯 = 两条 `head`，两个尾灯 = 两条 `tail`。
- 长度轴是 X 的模型（`X` 为车长的老资源包）由渲染器另算，`pos` 仍按模型自己的轴写。
- `rot` 是度数 `[yaw, pitch, roll]`，基准方向是「车头 −Z」：`yaw` 绕竖直轴，
  正 = 转向车体右侧；`pitch` 正 = 向下俯；`roll` 绕光柱自身轴（光锥是对称的，一般不写）。
  尾灯整条方向取反，`rot` 不需要再加 180°。

典型一段（Xiamen 12 米公交，车头端面 `z = -7.056`，尾灯 `z = +6.068`）：

```json
{ "type": "head", "color": "#FFF3D6", "pos": [ 1.099, 0.369, -7.056],
  "rot": [0,0,0], "length": 18.0, "width": 3.2, "alpha": 0.30 }
{ "type": "tail", "color": "#FF6A5A", "pos": [-1.176, 0.060,  6.068],
  "rot": [0,0,0], "length":  8.0, "width": 2.6, "alpha": 0.26 }
```

---

## 5. 三层文件怎么选

| 情况 | 用哪个 |
|---|---|
| 一个包只有一种车 / 只有一份灯位 | `_shared.json`，一行都不用重复 |
| 5 个涂装灯位全一样，只有一处想微调 | `_shared.json` + 那一个涂装的 `<key>.json`（整份覆盖，不做逐字段合并） |
| 每个涂装灯位都不同 | 各自 `<key>.json`，不写 `_shared.json` |
| 不是自定义车（MTR 内置车型），或者想给一个基础车型的所有涂装统一 | `<baseTrainType>.json` |
| 想边调边看、还没进包 | 桌面 `<key>.json`（优先级最高，调完再挪进包） |

注意两点：

1. **同一层不合并**：找到 `<key>.json` 就整份用它，不会再去和 `_shared.json` 逐字段拼。
   想要「共用 + 一处不同」，就把那一处整份写出来（编辑器打开该涂装 → 导出 → 改名成 `<key>.json`）。
2. **`_shared.json` 只对本包声明的车生效**（§2）。一个包里既有自定义车又有内置车的灯光配置时，
   内置车那份必须单独写 `<baseTrainType>.json`。

---

## 6. 完整示例：一辆公交车的 5 个涂装

```text
公交包/
├─ pack.mcmeta
└─ assets/
   ├─ lte/beam3D/
   │  ├─ _shared.json                  ← 5 个涂装共用这 4 束
   │  └─ xml6125jevd0c_repair.json     ← 彩蛋车灯位不同，单独一份
   └─ mtr/
      ├─ mtr_custom_resources.json
      └─ xml6125jevd0c/
         ├─ xml6125jevd0c.bbmodel
         ├─ xml6125jevd0c.json          ← model_properties：transport_mode/length/width
         └─ xml6125jevd0c*.png          ← 5 张涂装贴图
```

`assets/mtr/mtr_custom_resources.json`（只列关键字段，5 个涂装同一套模型 = 同一个运行时 id）：

```json
{
  "custom_trains": {
    "xml6125jevd0c":          { "name": "…D007", "texture_id": "mtr:xml6125jevd0c/xml6125jevd0c",
                                "model": "mtr:xml6125jevd0c/xml6125jevd0c.bbmodel",
                                "model_properties": "mtr:xml6125jevd0c/xml6125jevd0c.json" },
    "xml6125jevd0c_cloud":    { "…": "同上，只换 texture_id" },
    "xml6125jevd0c_normal":   { "…": "同上，只换 texture_id" },
    "xml6125jevd0c_repair":   { "…": "同上，只换 texture_id" },
    "xml6125jevd0c_notclear": { "…": "同上，只换 texture_id" }
  }
}
```

`model_properties` 里是 `"transport_mode":"TRAIN","length":12,"width":2` →
MTR 给这辆车生成的运行时 id 是 **`train_12_2`**，5 个涂装共用它。

`assets/lte/beam3D/_shared.json`（两份文件就够了：一个前脸两个大灯、一个尾部两个尾灯）：

```json
{
  "version": 1,
  "id": "_shared",
  "_comment": [
    "LTE beam pack — shared profile for every vehicle declared by THIS pack.",
    "pos is [lateral, up, along] in model space; the front of an MTR vehicle is -Z.",
    "Write one beam per lamp: there is no automatic left/right mirroring."
  ],
  "beams": [
    { "type": "head", "color": "#FFF3D6", "pos": [ 1.099, 0.369, -7.056], "rot": [0,0,0],
      "length": 18.0, "width": 3.2, "alpha": 0.30 },
    { "type": "head", "color": "#FFF3D6", "pos": [-0.968, 0.386, -7.055], "rot": [0,0,0],
      "length": 18.0, "width": 3.2, "alpha": 0.30 },
    { "type": "tail", "color": "#FF6A5A", "pos": [-1.176, 0.060,  6.068], "rot": [0,0,0],
      "length":  8.0, "width": 2.6, "alpha": 0.26 },
    { "type": "tail", "color": "#FF6A5A", "pos": [ 1.089, 0.062,  6.068], "rot": [0,0,0],
      "length":  8.0, "width": 2.6, "alpha": 0.26 }
  ]
}
```

彩蛋车灯位不同，单独写一份（整份覆盖，不继承 `_shared.json`）：

`assets/lte/beam3D/xml6125jevd0c_repair.json`

```json
{
  "version": 1,
  "id": "xml6125jevd0c_repair",
  "beams": [
    { "type": "head", "color": "#FFE9B0", "pos": [ 1.10, 0.52, -7.056], "length": 20.0,
      "width": 3.4, "alpha": 0.32 },
    { "type": "head", "color": "#FFE9B0", "pos": [-0.97, 0.52, -7.056], "length": 20.0,
      "width": 3.4, "alpha": 0.32 },
    { "type": "tail", "color": "#FF4A3A", "pos": [-1.18, 0.06,  6.068], "length":  8.0,
      "width": 2.6, "alpha": 0.26 },
    { "type": "tail", "color": "#FF4A3A", "pos": [ 1.09, 0.06,  6.068], "length":  8.0,
      "width": 2.6, "alpha": 0.26 }
  ]
}
```

游戏里第一次看到其中一个涂装时：

```text
[lte] beam pack: 公交包:assets/lte/beam3D/_shared.json id=train_12_2 beams=4 source=shared
```

编辑器列表里选中 `xml6125jevd0c_repair` 打开 → 读的是它自己那份；其余 4 个涂装（以及未单独
声明的任何本包车型）都拿 `_shared.json`。

---

## 7. 排错

| 现象 | 看日志 / 原因 |
|---|---|
| 光柱没变 | 有没有 `[lte] beam pack: … source=…`？没有就是没读到，找 `miss` 那行 |
| `miss id=… tried=[…]` | 那些文件名都不存在。`tried` 里没有你写的名字 → 文件名或目录不对（`beam3D` vs `beam3d`、少一层 `assets/`） |
| `_shared.json` 不生效 | 包里的 `mtr_custom_resources.json` 里有没有这辆车？日志里 source 是 `base` 而不是 `shared` 说明更具体的那层先命中了 |
| 光柱从车尾出来 | `pos[2]` 的符号反了：车头是 **−Z** |
| 只看到一束 | 左右灯要各写一条，没有自动镜像 |
| 文件在包里但没生效 | `[lte] beam pack: file/<包> contains beam3D files but is NOT enabled …` —— 资源包列表里没勾 |

改完文件按 **F3+T**（或装/卸包）重载资源包，模组会丢掉缓存重读；不用重启游戏。
