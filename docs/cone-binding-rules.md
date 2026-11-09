# 光柱灯位匹配规则（左右成对 / 只给头灯 / 车身姿态）

日期：2026-11-09　代码：`common/.../VehicleLights.java`、`common/.../TrainPose.java`、
`common/.../ProtoBeamCone.java`、`common/.../HeadlightConfig.java`

本文记录自动匹配的**优先级**、**左右配对**怎么判定、**方向**从哪来。三件事对应实机反馈的三个问题：
只绑定了一侧、尾灯也被画了光柱、单节车过急弯时光柱方向偏转。

---

## 0. 结论

| 问题 | 根因 | 修法 |
|---|---|---|
| 左右两侧都有灯，只绑定一侧 | ①同端有两个组（`headlight_left` / `headlight_right`）时 `pick()` 只取分数最高的**一个组**；②一个组有两个 placement 时把左右**取平均**，均值 ≈ 0，只剩中间一条 | `Slot.lateral` 改为**每盏灯一项**；同面、同 stage、镜像的组一起吸收 |
| 尾灯也被画光柱 | 头/尾两端都解析、都无条件发射 | 新增 `coneOnTail`（默认 `false`），只给头灯 |
| 急弯方向偏 | 方向取轨道切线（`Rail#getPosition` 在 mid ±0.5 格采样） | 改取 `TrainPose.carBodyForward()`：车身两端面之间的弦；切线只作退化兜底 |

验证（`tools/check-light-binding.js`，41 个真实车型，逐 cube 建模 = 运行期一个 cube 一个 `PartInfo`）：

```
              改前（每次命名匹配只发 1 条）   改后
head lamps    x1=41                        x1=3   x2=37  x3+=1
tail lamps    x1=41                        x1=2   x2=39  x3+=0
head 镜像成对  —                            38/41 = 92.7%
```

改后仍为 x1 的 3 个车型是几何上真的只有一盏（`head_light` 组只有 1 个 placement／单中心灯），
不是配对失败；唯一的 x4 是连云港那台公交，几何上确实有两对（内/外）前灯。

规则本身还有一组**合成车型**做定点验证（`tools/make-pair-packs.js` + `tools/check-light-binding.js`，
每个 case 对应本文一条规则）：

```
A-two-groups  headlight_left + headlight_right（同 stage、同端面）  -> 2 条
B-one-group   一个 headlights 组，左右各一个 cube                    -> 2 条
C-single      单盏居中灯                                            -> 1 条
D-geometry    词表不认识的组名（qianzhao），LIGHTS 镜像对            -> 2 条（级别 4）
```

---

## 1. 匹配优先级

`VehicleLights.pick(head)` 按顺序尝试，命中即返回：

| 级别 | 名称 | 判据 | 说明 |
|---|---|---|---|
| 2 | `name+geo` | 组名命中头/尾词表，且该组落在**端面**带内（`faceDist <= max(1.2, 0.35 * sectionLen)`） | 主路径，真实包 80% 走这条 |
| 3 | `name` | 组名命中，但在端面带外（内凹灯、斜面车头） | 仍然绑定，`inset` 被 `MAX_INSET=1.5` 截断 |
| 4 | `geo` | 无名字命中：stage ∈ {`LIGHTS`, `ALWAYS_ON_LIGHTS`}、在正确端面、**成镜像对**、灯高 0.3–3.4 格 | 词表不认识的命名习惯靠这条救回；单盏无名灯**不接受**（退回 5） |
| 5 | `default` | 按车宽估计：`±0.36 * width`、高 0.55 格、出端面 0.05 格 | 无灯几何的车型 |

词表之外还有一票否决：

- **黑名单**（`REJECT_WORDS`）：`door` / `roof` / `window` / `interior` / `always_on` / `lcd` / `tv` /
  `plate` / `marker` / `translucent` … 以及 `_off` / `_closed` 后缀。命名命中也照样拒绝
  （`roof_head_light`、`taillight` 在 `INTERIOR`、`doorlight_on` 都是真实反例）。
- **车顶条**：组高超过模型高度 95% → 视为车顶灯带，不是车灯。
- `stage` **不筛**命名组：`headlights` 有 225 个车型只出现在 `ALWAYS_ON_LIGHTS`，按 `LIGHTS` 筛会漏掉
  74%；`stage` 只参与打分（`ALWAYS_ON_LIGHTS` > `LIGHTS` > 其它）和级别 4 的门槛。

### 车体分节

一个 `.bbmodel` 可以装整列车（`sh15_pth.bbmodel` 里有 4 节）。`sections = round(span / length)`，
每个组先归到它**物理所在**的那一节（placement 最多的节胜出，平票取更靠端面的），
再在该节内归一化，永远不拿整车 bbox 当一节车。

---

## 2. 左右配对规则

### 2.1 组内：一盏灯一项，绝不平均

每组的 placement（+ 每个 cube）先按**沿车轴**取端面附近的一条带（`|along - anchor| <= 1.0`，`anchor`
= 该节内离车厢中心最远的那个 cube），再把这些 cube 的**横向位置聚类**：

- 聚类阈值 `LAMP_CLUSTER_TOL = 0.35` 格：一盏灯由多个 cube 组成（中心差几厘米）→ 合成 1 项；
  左右两盏相距 ≥ 1 格 → 2 项。
- 结果就是 `Group.lamps`（模型坐标）和 `Slot.lateral`（相对中轴）。

**均值是错的**：±0.9 两盏平均成 0，光柱就跑到车头正中——这正是"只点亮了一侧"的另一种表现。

### 2.2 组间：同 stage、同端面、镜像

`faceLamps(anchor)` 先放入 anchor 组自己的灯，再吸收其它组，条件全部满足才吸收：

1. `stageRank` **相同**（`ALWAYS_ON_LIGHTS` / `LIGHTS` / 其它 各自一档）；
2. 与 anchor **同端**（`signum(s)` 相同）；
3. **同端面**：`|faceDist - anchor.faceDist| <= 0.6` 格；
4. 不在黑名单、不是车顶条；
5. 且满足其一：组名命中该端词表（`headlight_left` + `headlight_right` 这种），
   或者它的某盏灯与已收下的某盏灯**关于中轴镜像**（`|(a-c) + (b-c)| <= 0.35`）。

于是两种建模方式都能配对：

- 一个组 + 两个 placement（`headlights: positions=[[-14,0],[14,0]]`）→ 组内聚类出 ±；
- 两个组 + 各一个 placement（`headlight_left` / `headlight_right`）→ 组间镜像吸收。

### 2.3 同一侧多盏灯

不去重成"每侧一盏"：同一侧的两盏（例如近光 + 远光）各自留在列表里，只有：

- `LAMP_DUPLICATE_TOL = 0.35` 格内视为同一盏（两个组指同一颗灯，例如 `headlight` 与 `headlight_on`
  同时覆盖一个灯罩——否则会出现两条几乎重合的锥，叠亮成一个斑）；
- 灯位带（`LAMP_TRACK_SLACK = 1.3`）：多于两盏时，最接近关于中轴对称的那一对决定灯距，
  比它再往外 `1.3×`+0.1 格的点不算灯（多半是后视镜/示宽灯被同组带进来），直接丢弃；
- 总数上限 `MAX_LAMPS = 4`（超了取最外两盏 + 次外两盏，避免整条灯带变成扇形）。

### 2.4 中轴怎么定（这一条曾经直接导致"只剩一侧"）

`Layout.latCentre` **不是** bbox 中点，而是**各组横向均值的中间值（median）**：

- bbox 中点会被后视镜、开着的门扇、甚至同一文件里的第二节车带偏；实测有车型偏 3 格以上。
  中轴偏 3 格时，±0.9 的两盏灯都落到同一侧，再被 0.15 格去重合并成一条——看起来就是"只绑定一侧"。
- 逐 cube 的中位数会反向偏移（一大组车身板件压倒一切）。
- 一组一票的中位数就是车身本身。

横向钳制也改成 `max(0.45 * width, min(halfWidth + 0.2, 2.5))`：真实公交头灯在 ±1.05，
而 JSON 的 `width` 常写 2，旧的 `0.45 * width = 0.9` 会把两侧都钳到同一个值再被去重。

---

## 3. 方向：车身姿态，不是轨道切线

`TrainPose.carBodyForward(car)` = **该节车两个端面之间的弦**（`boundaries[car] - boundaries[car+1]`，
单位化，含坡度）。

依据是 MTR 自己怎么转车体（`mtr.data.Train#calculateCar` 字节码）：

```
centre = average(positions[car], positions[car + 1])
yaw    = MathHelper.atan2(dz, dx)      // positions[car+1] - positions[car]
pitch  = asin(dy / chordLength)
```

即 **MTR 画车体用的就是这两个端面的弦**。轨道切线只是弦的二阶近似：

- 急弯（半径小、车长固定）时弦与切线差半个圆心角；
- 跨轨道接头时 `Rail#getPosition` 的方向会突变，±0.5 格采样窗口正好骑在接头上，
  切线会指向两条轨道之间的中间方向——单节车最明显。

所以光柱轴线取弦，和车身**共线**；只有弦退化（两端面重合，`chord == 0`）时才回退
`carForward(car)`（切线），再退化才 `gate(6)` 不画。

**没有改动**的部分：地面光带（`BeamGlow`）、动态光、`RailRelightScheduler` 仍用
`forward()` / `backward()` / `carForward()`（切线）。它们不是"从灯口射出的锥"，不受这条影响。

---

## 4. 只给头灯（`coneOnTail`）

`HeadlightConfig.coneOnTail`，**默认 `false`**，配置版本 18 写入文件。

- 关：`ProtoBeamCone` 只发头端光柱；**尾灯位照旧解析**（`Layout.tail` 仍在，动态尾灯与地面光带要用），
  改开关不会让尾部动态光/光带消失。
- 开：`/mtr_lte cone tail on`（写盘、下一帧生效），或手改 `config/mtrlte.json` 的
  `"coneOnTail": true`。`/mtr_lte cone` 的状态行里带 `tail=on|off`。

理由：头灯是常亮的（夜间/隧道/雨天），尾灯只在刹车/驻车时亮；自动匹配在两端都能找到灯位，
不设开关就是每列车背后常拖一条红锥。

---

## 5. 诊断

每列车每车型一行（`VehicleLights.announce`）：

```
[lte] lights: model=<id> slots=2 head=headlights(L2,name+geo,x2) tail=taillights(L2,name+geo,x2) source=name
```

- `x2` = 该端会发 2 条光束（左右成对）；`x1` = 单灯；无 `xN` = 1 条。
- `L2/L3/L4/L5` 对应第 1 节的优先级；`source=default` 表示两端都靠尺寸估计。
- 5 秒诊断行的 `beams=` 现在是"头端灯数"（默认 `coneOnTail=false` 时不含尾端）。

## 6. 常量

| 常量 | 值 | 含义 |
|---|---|---|
| `LAMP_ALONG_TOL` | 1.0 | 同一端面带的沿车轴半宽 |
| `LAMP_CLUSTER_TOL` | 0.35 | 同一盏灯的横向聚类阈值 |
| `FACE_MATCH_BLOCKS` | 0.6 | 两个组算"同一端面"的距离差 |
| `MIRROR_TOL` | 0.35 | 镜像判定（相对中轴的偏移之和） |
| `LAMP_DUPLICATE_TOL` | 0.35 | 两个组指同一盏灯的去重阈值 |
| `LAMP_TRACK_SLACK` | 1.3 | 灯位带：超过最对称一对的 `1.3×` + 0.1 格的点不算灯 |
| `MAX_LAMPS` | 4 | 每端光柱上限 |
| `LATERAL_CLAMP_FRACTION` / `MAX_LATERAL_BLOCKS` | 0.45 / 2.5 | 横向钳制 |
| `END_FRACTION` / `END_MIN_BLOCKS` | 0.35 / 1.2 | 端面带 |
| `MAX_INSET` | 1.5 | 灯最多内凹多少格起射 |
| `TANGENT_EPS` | 0.5 | 切线采样半窗（只在弦退化时用到） |
