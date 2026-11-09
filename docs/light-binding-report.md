# 灯位绑定（正式实现）

日期：2026-11-09　代码：`common/.../VehicleLights.java`（绑定）、`common/.../ProtoBeamCone.java`（光柱）、
`common/.../LightController.java`（诊断行）、`tools/check-light-binding.js`（离线校验）

不再有 PROTOTYPE/TODO：`VehicleLights` 现在就是光柱的唯一灯位来源。

---

## 1. 数据从哪来（运行期，无需解析资源包）

| 需要的东西 | 来源 |
|---|---|
| 组名 → 几何 | `DynamicTrainModel.partsInfo`：`Map<String, Set<PartInfo>>`，`PartInfo` 字段私有，反射读 |
| 每个放置（一节车/一对灯） | `PartInfo` 的 **`origin + offset`**＝该 cube 的中心（`origin` 只是**轴心**，导出器常把几十个 cube 共用一个轴心，单读 origin 会全部塌到一点） |
| 车高（判断是否在车顶） | `PartInfo.height`：用它把 Y 包围盒从"中心点"扩成"实体范围" |
| `stage` / `render_condition` | `DynamicTrainModel.properties`（就是模型 JSON） |
| 每节车厢的位置与朝向 | `TrainPose`（`carStart/carEnd/carForward`），`spacing` 参与分节 |

**`stage` 不是车灯判据**：真实 318 个车型里 225 个的 head/tail 组只出现在 `ALWAYS_ON_LIGHTS`，
`LIGHTS` 里主要是门灯/车顶灯/车内照明。`render_condition` 只区分门开/门关几何，不判亮灭。
所以 `stage` 只当**排序权重**，不当过滤条件。

## 2. 组名匹配规则（白名单）

```
HEAD: headlight, headlights, head_light, head_lights, headlamp, head_lamp,
      lighthead, head_led, 前照, 头灯, toudeng, qiandeng
TAIL: taillight, taillights, tail_light, tail_lights, weideng, 尾灯
```
（`deng` 单独一词**不用**——它会把 `weideng` 也匹配成头灯，原型里的这个问题已删掉。）

名字命中后还有两道**黑名单**：

1. **词表**（每条都来自真实反例）：
   `always_on / alwayson / always / interior / door / roof / window / side / cabin / 车内 / 门 / 顶 / 窗 /
   logo / display / lcd / tv / screen / plate / 牌照 / reading / cargo / under / step / handrail /
   spot / marker / translucent / stablization / stabilization`，
   外加后缀 `_off` / `_closed`（灭灯那套几何）。
   作用：`roof_head_light`（56 个车型，车顶灯带）、`doorlight_on/off`（295 组）、`taillight|INTERIOR`
   （10 个车型，灯罩/内饰层）全部在这层被清掉。
2. **几何**：
   - **不在车顶**：组中心高于模型实体高度（`minY..maxY`，用 `PartInfo.height` 扩过）的 **95%** → 视为车顶灯带，丢弃。
     阈值定在 95% 是因为真实数据里机车/公交的大灯常贴在顶线下沿（例：YRC DL50 的 `head_light` 在 0.92 处，
     是真灯，80% 会误杀）；真正的车顶组名里带 `roof`，第一层就没了，这层只兜底。
   - **靠端面**：组到自己所在**车厢**端面的距离 ≤ `max(1.2, 0.35 × 单个车长)`。
     不满足时**不丢**，降到 L3（见下）并用 `inset` 钳到 1.5 格——这是真实数据里"灯在斜坡鼻锥上"的情况。

### 匹配等级

| 级别 | 判据 | 结果 |
|---|---|---|
| L2 | 名字命中 + 黑名单 + 两道几何全过 | 用该组的实测位置 |
| L3 | 名字命中，但离端面超过端面带 | 仍用该组，`inset = min(1.5, 实测距离)` |
| L5 | 没有任何可用名字命中 | 默认位（§3） |

`stage` 只影响 L2 内部的排序：`ALWAYS_ON_LIGHTS(40) > LIGHTS(30) > EXTERIOR/未知(20) > INTERIOR(10)`，
再叠加"是否在正确的一端（+12）"、"离端面多近（0–20）"、"该车厢内该组有几个放置（0–4）"。

## 3. 默认位公式（L5）

模型里根本没有灯位时（真实数据里全是公交），按车体尺寸估：

```
横向  lateral = ±0.36 × width        // width 取模型 JSON 的 width，缺失时 2.9 格
高度  y       = 0.55                 // 以车节参考点（TrainPose 给出的那个点）为 0
纵向  along   = 端面 + 0.05           // 从该车自己的端面向外 0.05 格
```

- 是**一对**灯（±0.36 宽），渲染时每颗一条锥，单条 alpha 取 `peak / √2`，所以成对不会比单灯更亮。
- 0.55 / 0.05 是"车头灯在车体下沿上方半格、贴着端面外沿"的保守估计；它保证**任何车型都有光柱**，
  也保证位置永远落在车体轮廓内（不会飘在车外）。

## 4. 按车厢逐节算（不是整个模型包围盒）

两个真实陷阱：

1. **一个 bbmodel 可以装整列车**。`sh15_pth.bbmodel` 的 `head_light` 距模型端点 8.87 格、
   `K-Train AEL` 的 `headlights` 距端点 11.5 格——拿整模型 bbox 当"车头面"就会把光柱放进车厢中部。
2. **一个组有多份放置**。`partsInfo` 里每组是 **Set**：`positions: [[0,0],[0,160],[0,-160]]` 会实例化出三份
   （一对左右灯 = 同位置两份；多节车 = 每节一份）。求平均会把左右灯、前后车厢全糊在一起。

做法：

```
车长 carLen = 模型 length（缺失时用 Train.spacing，且必须与模型尺寸相容）
节数 sections = clamp(round(span / carLen), 1, 8)
节长 sectionLen = span / sections

每组：
  1) 按 (along - min) / sectionLen 把该组的所有放置分箱
  2) 取放置最多的一节（灯对=2）；并列时取 |s| 更大的那节（真正的车头/车尾，而不是中段副本）
  3) 在这一节里取 |s| 最大的放置作为锚点（离车中心最远的那个）
  4) 只对与锚点同位置（±1 格）的"镜像双胞胎"求平均 → 左右灯对取中点，单灯保持原位
  5) s = 2*(锚点在本节内的位置)/节长 - 1，faceDist = (1-|s|)*节长/2
```

渲染端（`ProtoBeamCone.emitSlot`）再用 **pose 的车节几何**落位：

```
apex = carStart(0) / carEnd(cars-1)          // 该车自己的端面
     + carForward(车) * (0.05 - inset)       // 沿该车自己的切线
     + right * lateral * (reversed ? -1 : 1) // right = 水平化(forward) × up
     + (0, height, 0)
```

头灯用第 0 节、尾灯用最后一节，各自用**该节自己的切线**——弯道上不再两头共用一条弦。

## 5. 缓存

- `Layout` 按 `DynamicTrainModel` 身份缓存（有界 LRU，96 个），首次 `layoutFor` 才反射解析；之后列车移动只做向量运算。
- 世界切换 / 重载时 `LightController` 调 `VehicleLights.clearCache()`。
- 诊断行按 `baseTrainType` 去重（有界 512），每列车第一次被点亮时打一行。

## 6. 真实数据校验（离线）

`node tools/check-light-binding.js` —— 用 `.bbmodel` 的 cube 中心 + `positions` 复算同一套规则
（`.bbmodel` 单位是 1/16 格，`positions` 也是）。

当前资源包目录（134 个包，其中 54 个 zip；318 个车辆定义）：

| 指标 | 数值 |
|---|---|
| 车辆定义（有 `parts[].stage`） | **318** |
| 能配到 `.bbmodel` 并复算几何的样本 | **41** |
| 样本里按名字命中（L2） | 头 **33/41 = 80.5%**，尾 18/41 |
| 样本里走 L3（名字命中但超出端面带） | **0** |
| 样本里走默认位（L5） | 头 8，尾 23 |
| 多节车 bbmodel | 2 |

样本里**每一个走默认位的车型都是"根本没有 head/tail 命名组"的公交**，没有一个是几何筛掉的。
全库口径（旧统计，318 个定义）：名字命中头 300/318 = 94.3%、尾 289/318 = 90.9%。

### 会走到默认位的车型（全库口径）

头灯 18 个（全是公交，没有 head/tail 命名）：

| 包 | 数量 |
|---|---|
| 上海申沃 SWB6109BEV3（含乐正绫涂装） | 2 |
| 中通 N12（含丁品定制版） | 4 |
| 北京福田 BJ6127C8BHB | 3 |
| 厦门金龙 XMQ6106 / 6125 / 6127 | 6 |
| 青年 JNP6103BEVA | 2 |
| （洛天依专车）厦门金龙 XMQ6125AGBEVL | 1 |

尾灯 29 个（上表 18 个 + 11 个只有头灯组、没有尾灯组的车型，例：厦门金旅 XML6855/6588 系列、
仿重庆单轨、宇通 E8、连云港 bj6109evca3、YRC DL50 boxcar）。

> 另：`YRC DL50` 的 `head_light` 在 0.92 车高处、`K-Train/sh15` 的灯组离模型端点 9–12 格 —— 前者靠 95% 车顶阈值放行，
> 后者靠分节 + 锚点归位到本车厢端面，两者都命中而不是落到默认位。

## 7. 日志

配置里的 `beamCone` 默认 `true`，无新增开关。每个模型第一次真正被点亮时（已过距离闸，所以只描述看得见的车）：

```
[lte] lights: model=mtr_custom_train_xml6855jevy0c5_lty slots=27 head=headlight(L2,name+geo) tail=taillight(L2,name+geo) source=name
[lte] lights: model=mtr_custom_train_bj6127c8bhb slots=19 head=(default)(L5,default) tail=(default)(L5,default) source=default
```

- `slots=` 参与判定的组数；
- `head=` / `tail=` 选中的组名 + 级别（L2 名字+几何 / L3 仅名字 / L5 默认）；
- `source=name` 表示至少一端是名字命中的，`source=default` 表示两端都是默认位（新的公交包会这样）。

光柱自身仍是每 5 秒一行：

```
[lte] cone: calls=… trains=… models=… noModel=… noSlot=… drawn=… quads=… probe=… near=… far=… shaders=… peak=… depthTex=… volume=…
```

启动指纹：`[lte] cone active: halfAngle=13deg segments=14 rings=4 shells=4 probe=false blend=additive volume=…`

## 8. 改动清单

| 文件 | 改动 |
|---|---|
| `common/.../VehicleLights.java` | 原型 → 正式：`origin+offset`、`height`、按放置分箱 + 车节归一、名字黑名单、几何筛选、L2/L3/L5、默认位、按模型缓存、`announce()` 诊断 |
| `common/.../ProtoBeamCone.java` | 去掉 bbox 近似的 `headOffset`，改为 `emitSlot`（按车节 + 该车切线 + 灯对）；`reversed` 与 LightController 一致（`^ swapEnds`）；日志前缀 `[lte-proto]` → `[lte]` |
| `common/.../LightController.java` | 每列车首次点亮时输出 `lights: model=…`；世界切换清布局缓存 |
| `tools/check-light-binding.js` | 新增：离线复算同一套规则，量出上面那张表 |
