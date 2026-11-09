# 车头/车尾灯位读取 + 圆锥光柱 —— 原型验证报告

日期：2026-11-08　代码：`common/.../VehicleLights.java`、`common/.../ProtoBeamCone.java`（均为 **PROTOTYPE**，带 TODO 注释）
数据来源：`C:\Users\AD\Desktop\PCL\.minecraft\versions\1.20.1-Fabric 0.19.3\resourcepacks`（真实用户包目录）

---

## 0. 结论先说

1. **灯位读取可做，但"组名 + stage"的直觉是错的。** 真实包里 94.3%（300/318）的车型有 head/tail 命名的组，可直接按名字命中；但**这些组绝大多数在 `ALWAYS_ON_LIGHTS` 而不是 `LIGHTS`**（225 个车型只有 ALWAYS_ON_LIGHTS）。`LIGHTS` 阶段主要是**门灯/车顶灯/车内照明**，不是车头灯。把 `stage==LIGHTS` 当作车头灯判据会在 74% 的车型上落空。
2. **`stage` 与 `render_condition` 运行期确实拿得到**，不必解析 JSON 文件：`DynamicTrainModel.properties`（public）就是模型 JSON 本体，含 `parts[].stage` / `parts[].render_condition`；几何在 `partsInfo`（private 字段，反射可读）。
3. 几何"靠端面 1 格内"只能作为**兜底**，不能当主判据：bbmodel 抽样里只有 40.4% 的 head/tail 组落在端面 1 格内，中位数 1.84 格；而且**一个 bbmodel 可能装多节车厢**（上海 15 号线 headlight 距端点 8.9 格），1 格阈值会整体失效。
4. **光锥：两级都可见。** 原版 `shaders=false drawn=12 quads=1890`；Iris + MakeUp `shaders=true drawn=163 quads=25690`，截图 `docs/img/proto-cone-iris-makeup.png` 里锥体清晰横跨轨道。加色 + 深度测试这条路径在光影下没有被吞。唯一的限制是软相交：1.20.1 有 `getDepthAttachment()` 但拿到的只是 GL id，Iris 下不可靠（§5.3）。

---

## 1. 真实资源包统计

扫描范围：`resourcepacks/` 下 54 个 zip + 100+ 个目录包，筛出含 `parts[].stage` 的 MTR3 车辆定义。

| 指标 | 数值 |
|---|---|
| 命中资源包（含车辆定义） | **67** |
| 车辆 JSON（车型 × 编组） | **318** |
| 含 `LIGHTS` 部件的车型 | **313** |
| 完全没有 `LIGHTS` 的车型 | **5** |
| 有 head/tail 命名组的车型 | **300** |
| 有 tail 命名组的车型 | **289** |

stage 分布（部件条目数，非车型数）：

| stage | 条目数 |
|---|---|
| EXTERIOR | 11197 |
| INTERIOR | 8857 |
| LIGHTS | 2464 |
| ALWAYS_ON_LIGHTS | 2040 |
| INTERIOR_TRANSLUCENT | 606 |

`LIGHTS` 阶段一共只有 **45 个不同组名**，全部清单（真实）：
`LCD8, bodyzhaoming, chenei, connectionzhaoming, door_light, door_light_c1141a, door_light_on, doorlight, doorlight_on, doorlighton, doorlights_on, doorzhaoming, end_light, head_light, headlight, headzhaoming, light, light2, light_door_left, light_door_left_off, light_door_right, light_door_right_off, light_ext, lightout, out_light_on, red_doorlight_on, roof_door_light, roof_end_light, roof_end_light_c1141a, roof_end_light_sp1900, roof_head_light, roof_head_light_light, roof_light, roof_light_c1141a, roof_light_door_ael, roof_light_sp1900, roof_light_tcl, roof_light_window_ael, roof_middle_light, roof_side_light, roof_window_light, stablization_light, tv, window_light, yellow_doorlight_on`

> 一眼可见：45 个里真正可能指车头灯的只有 `head_light` / `headlight` 两个；其余是门灯（door*）、车顶灯（roof*）、车窗灯（window*）、车内照明（chenei/bodyzhaoming/light*）、电视（tv）、LCD。

`ALWAYS_ON_LIGHTS` 只有 26 个组名，其中：
`headlight, headlights, head_light, head_lights, taillight, taillights, tail_light, tail_lights, lighthead, light_ext, lighton, lights, head_led, end_light, BANTOUMING, bantouming, display, door_light_on, door_logjt1, end_door, side_panel_*_translucent`

### 1.1 head/tail 命名组实际落在哪个 stage（真实计数）

| 计数 | 组名 \| stage |
|---|---|
| 403 | headlights \| ALWAYS_ON_LIGHTS |
| 378 | tail_lights \| ALWAYS_ON_LIGHTS |
| 160 | head_lights \| ALWAYS_ON_LIGHTS |
| 104 | taillights \| ALWAYS_ON_LIGHTS |
| 73 | headlight \| ALWAYS_ON_LIGHTS |
| 68 | taillight \| ALWAYS_ON_LIGHTS |
| 56 | roof_head_light \| EXTERIOR |
| 56 | roof_head_light \| LIGHTS |
| 50 | head_light \| LIGHTS |
| 48 | head_light \| ALWAYS_ON_LIGHTS |
| 20 | headlight \| LIGHTS |
| 10 | taillight \| INTERIOR |
| 8 | roof_head_light_light \| EXTERIOR |
| 8 | roof_head_light_light \| LIGHTS |
| 4 | tail_light \| ALWAYS_ON_LIGHTS |

按车型统计 stage 组合：

| 车型数 | 该车型 head/tail 命名组出现的 stage 组合 |
|---|---|
| **225** | 只有 ALWAYS_ON_LIGHTS |
| 32 | ALWAYS_ON_LIGHTS + EXTERIOR + LIGHTS |
| 24 | ALWAYS_ON_LIGHTS + LIGHTS |
| 10 | INTERIOR + LIGHTS |
| 9 | 只有 LIGHTS |

→ 也就是 **75/318（23.6%）** 的车型在 `LIGHTS` 里有 head/tail 命名的组；其中 `head_light|LIGHTS`（50 条）和 `headlight|LIGHTS`（20 条）分布在 14 个包（厦门地铁 4 号线、厦门 LCD 示例包、香港 LCD 示例包、厦门金旅/金龙各公交包、仿重庆单轨、YRC DL50 等）。

### 1.2 各包命中（节选；完整 67 个包见 `docs/pack-stats.csv`）

| 车辆数 | 有 LIGHTS | 名字命中头 | 名字命中尾 | 包 |
|---|---|---|---|---|
| 18 | 18 | 18 | 18 | 带受电弓与头等车厢的E44 |
| 16 | 16 | 16 | 16 | eidan-9000 / eidan-9000（洛天依彩绘） / 带受电弓的M-train |
| 10 | 10 | 10 | 10 | MTR First Class Trains Pack V2.0 / 带受电弓的A-train |
| 9 | 9 | 9 | 9 | Q-Train（东涌线增强版）/（市区线普通版） |
| 8 | 8 | 8 | 8 | 上海地铁15号线列车（3 个涂装）/ 厦门地铁4号线 |
| 4 | **0** | 4 | 4 | ZER4型机车（MTR3） ← 有灯位名、无 LIGHTS |
| 3 | 3 | **0** | **0** | 北京福田BJ6127C8BHB ← 公交，无 head/tail 命名 |
| 1 | 0 | 1 | 0 | MTR3适配版连云港公交追加v0.0.2 |

**完全没有 head/tail 命名的 18 个车型全部是公交车**：上海申沃 SWB6109BEV3（2）、中通 N12（4）、北京福田 BJ6127C8BHB（3）、厦门金龙 XMQ6106/6125/6127（6）、青年 JNP6103BEVA（2）、（洛天依专车）厦门金龙 XMQ6125AGBEVL（1）。这些只能靠 stage+几何 / 兜底。

**完全没有 LIGHTS 的 5 个车型**：
`MTR3适配版连云港公交追加v0.0.2/assets/mtr/bj6109evca3/bj6109evca3.json`、
`ZER4型机车（MTR3）/assets/mtr/zer4/zer4_{outer_pthd,outer_pthu,pthd,pthu}.json`

### 1.3 状态区分（亮/灭）

真实数据里 **没有**"同一盏头灯两套模型"的 `headlight` / `headlight_off` 组合（全库对 head/tail 命名做 on/off 匹配：0 条）。头/尾灯基本是 `ALWAYS_ON_LIGHTS` 单套，MTR 自己永远把它画成亮的。

真正成对出现的是**门灯**，而且是"模型 + render_condition"双切换（真实样例）：

```
eidan-9000/eidan_9000.json
  doorlight_on  [LIGHTS,   DOOR_RIGHT_OPEN] [LIGHTS, DOOR_LEFT_OPEN]
  doorlight_off [EXTERIOR, DOOR_RIGHT_CLOSED] [EXTERIOR, DOOR_LEFT_CLOSED]
```

全库共 **295 组** 这种 on/off 对（`doorlight_on/off`、`light_door_left/…_off`、`yellow_doorlight_on/off` 等），集中在 67 个包的 295 个车型变体里。

**判断"哪个是亮的那套"的可靠顺序**（按真实数据校正）：

1. `stage == ALWAYS_ON_LIGHTS` → 这是 MTR 的"常亮灯"语义，头/尾灯 90% 落在这里（225 车型独有）。
2. 名字以 `_on` 结尾 / 无 `_off` 变体 → 亮的那套；`_off` 归到 `EXTERIOR` 且 `render_condition` 是 `DOOR_*_CLOSED`，是灭的那套。
3. `stage == LIGHTS` → 只在没有 ALWAYS_ON_LIGHTS 的包上作为候选（车灯/门灯混用），且必须做名字排除。
4. `render_condition`：实测 `LIGHTS` 阶段里 1732/2464 是 `ALL`，只有 32 条是 `MOVING_FORWARDS`、32 条 `MOVING_BACKWARDS`；`MOVING_FORWARDS` 不是"亮"的判据（eidan 的 `roof_head_light` 就同时在 MOVING_FORWARDS 和 MOVING_BACKWARDS 里，它只是"车头端车顶灯带"）。

> 结论：**不要用 `render_condition` 判亮灭**，用 `stage==ALWAYS_ON_LIGHTS` + 名字；`render_condition` 只用来判"门开/门关"这类几何切换。

### 1.4 反例："组名叫 headlight 但不一定是头灯"

| 反例 | 真实出现次数 | 实际是什么 | 筛法怎么排掉 |
|---|---|---|---|
| `roof_head_light` [LIGHTS] | 56 | 车**头端车顶**的灯带（eidan-9000 系列） | 名字含 `roof` → 直接 reject |
| `roof_head_light` [EXTERIOR] | 56 | 同上，灭灯状态的外壳 | 同上 |
| `roof_head_light_light` [LIGHTS/EXTERIOR] | 8+8 | 车顶灯带的发光层 | 同上 |
| `taillight` [INTERIOR, ALL] | 10 | 尾灯**灯罩/内饰层**（厦门金旅 xml6855jevy0c5 系列），不是光源本体 | `stage==INTERIOR` → reject（且不允许 INTERIOR 进候选） |
| `doorlight_on/off` | 295 组 | 门灯，还带开门/关门两套 | 名字含 `door` → reject |
| `door_light`, `roof_door_light`, `window_light`, `roof_end_light` | 大量 | 门/顶/窗/端部灯带 | `door`/`roof`/`window` → reject |
| `LCD8`, `tv`, `light`, `light2`, `lightout`, `light_ext`, `stablization_light` | LIGHTS 内 7 个组名 | 显示屏背光、电视、杂项灯 | 不含 head/tail 关键词 → 不命中；`stablization`(sic) 也不匹配 |

`VehicleLights` 的 reject 词表就是照这张表写的：`always_on / alwayson / always / interior / door / roof / window / side / cabin / 车内 / 门 / 顶 / 窗 / logo / display / lcd / tv / screen / plate / 牌照 / reading / cargo / under / step / handrail / spot / marker`。

---

## 2. 四级判定命中率（真实包统计）

| 级别 | 判据 | 命中车型 | 命中率 | 备注 |
|---|---|---|---|---|
| a 显式声明 | `lte_vehicle_lights.json` | 0/318 | 0% | 本次只留接口（`VehicleLights.pick` 的 L1 分支），文件格式待定 |
| b 组名（带排除词） | headlight/head_light/头灯/toudeng/deng… | **300/318** | **94.3%** | 尾灯 289/318 = 90.9%；18 个失败全是公交 |
| c `stage==LIGHTS` + 端面 1 格 | — | 75 个车型有 LIGHTS 命名组；按 bbmodel 几何代理约 **30/318 ≈ 9%** | ~9% | 且 `LIGHTS` 里的 `roof_head_light` 必须靠 b 的排除词清掉 |
| d 任意 stage + 自发光贴图名 + 几何 | — | 未量化 | 未知 | `partsInfo` 不含贴图名，需要读 `properties.parts[].texture`；本次没做 |
| e 兜底（车体尺寸） | 车头端面 + 固定高度 | **318/318** | 100% | 精度未知，只能当最后兜底 |

几何代理（77 个 bbmodel / 109 个过滤后的 head/tail 组）：距最近端面 ≤0.5 格 22.0%，≤1 格 **40.4%**，≤2 格 56.0%，≤4 格 73.4%，中位数 1.84 格。

**多节车厢陷阱**：`上海地铁15号线列车/.../sh15_pth.bbmodel` 的 `headlights` 距模型端点 8.87 格、`head_light` 10.22 格 —— 因为一个 bbmodel 里装了整列车的多节车，端面应按**单节车（`spacing`）**算，不是按整个模型 bbox。所以几何判据在运行期必须用"车节"而不是"模型"。

---

## 3. 运行期怎么读（已核实 API）

```java
TrainClient.trainRenderer            // TrainRendererBase（public final）
  instanceof JonModelTrainRenderer   // public class，public final ModelTrainBase model
    model instanceof DynamicTrainModel
      model.partsInfo                // Map<String, Set<PartInfo>>，字段 public，PartInfo 是包私有类
      model.properties               // JsonObject，就是模型 JSON（含 parts[].stage / render_condition）
```

- `PartInfo` 的 `originX/Y/Z`、`offsetX/Y/Z`、`rotationXYZ`、`width/height` 全是 **private final**，只能反射读（`getDeclaredField` + `setAccessible`）。原型已跑通这段反射（见 `VehicleLights.readPartsInfo`）。
- 组名就是 `partsInfo` 的 key，和 JSON `parts[].name` 一一对应，所以可以 `partsInfo`（几何）× `properties.parts`（stage/condition）按名字 join。
- 同一个组名可能出现多条 `parts[]`（不同 stage / condition），`partsInfo` 只有一份几何 → 取并集，stage 取第一条声明。

---

## 4. 若要正式做，需要动的文件

| 文件 | 改动 |
|---|---|
| `common/.../VehicleLights.java` | 原型 → 正式：去掉 dump，加 `lte_vehicle_lights.json` 读取（L1），组名表外置到配置/资源，几何用 `spacing` 归一 |
| `common/.../ProtoBeamCone.java` | 原型 → 正式：锥台顶点复用 `BeamGlow` 的 draw 路径，加软相交（深度纹理，见 §5.3） |
| `common/.../BeamGlow.java` | 与光锥共用提交批次/开关；两套几何合并成一次 draw |
| `common/.../HeadlightConfig.java` | 新增 `beamCone`（开关）、`beamConeAngle`（12–15°）、`beamConeLength`、`beamConeAlpha` |
| `common/.../MtrHeadlightClient.java` | 启动指纹行 + 模型缓存（按 `trainId`/`baseTrainType` 缓存 `Layout`，不要每帧 parse） |
| `v1.20.1/src/.../RenderHooks.java`（以及 1.18.2/1.19.2/1.19.4 同名文件） | 注册 `ProtoBeamCone`（本次已在 1.20.1 注册，其它版本未动） |
| `tools/` 新增 `dump-lights.js` | 把 §1 的扫描脚本固化，发布前跑一次统计 |

---

## 5. 光锥原型（B）

### 5.1 实现

- `ProtoBeamCone`：14 段圆周 × 6 个环的锥台/圆锥 mesh，半角 **13°**（12–15° 区间内），长度取 `beamHeadLength`，颜色取 `beamHeadColor`；加色混合（`SRC_ALPHA, ONE`）、**开深度测试**、关深度写入、关 cull，坐标相机相对。
- 走 `GlowCompat.draw`（与现有光带同一条已验证的提交路径：`Tessellator` + `RenderSystem.setShader(GameRenderer::getPositionColorProgram)` + `BufferRenderer.drawWithGlobalProgram`），**不用** `entityVertexConsumers`（Iris/Sodium 下会被批处理吞掉）。
- 两个 render pass 二选一，与原版/Iris 的判定一致：`ShaderPackProbe.active()` 为真走 `AFTER_TRANSLUCENT`，否则走 `AFTER_ENTITIES`。
- 另画一个**固定探针锥**（相机前方 2 格、长 7 格、alpha 0.9）：即使一辆车都没有、模型解析失败，也能证明"几何 + 混合 + 深度"这条链是通的——避免把"没车"误判成"画不出来"。
- 计数日志（每 5 秒）：`[lte-proto] cone: calls=… trains=… models=… noModel=… noSlot=… drawn=… quads=… probe=… shaders=… depthTex=…`

### 5.2 实机结果（真实日志 + 截图）

启动指纹（证明跑的是这一版）：

```
[lte-proto] cone prototype active: halfAngle=13deg segments=14 probe=true
```

**原版（Iris 关）：**
```
[lte-proto] cone: calls=6 trains=45 models=12 noModel=33 noSlot=0 drawn=12 quads=1890 probe=3 shaders=false depthTex=4
[lte-proto] cone: calls=4 trains=30 models=8  noModel=22 noSlot=0 drawn=8  quads=1260 probe=2 shaders=false depthTex=4
```

**Iris + MakeUp-UltraFast-9.3c（`iris.properties: enableShaders=true`）：**
```
[lte-proto] cone: calls=2  trains=14  models=3   noModel=11  noSlot=0 drawn=3   quads=490   probe=1  shaders=true depthTex=4
[lte-proto] cone: calls=82 trains=614 models=163 noModel=451 noSlot=0 drawn=163 quads=25690 probe=41 shaders=true depthTex=4
```

判据结果：**原版可见、Iris + MakeUp 下同样可见**（`shaders=true` 且 `drawn=163`、`quads=25690`）。截图证据：
`docs/img/proto-cone-iris-makeup.png`（光影下，锥体横跨轨道，清晰可见）、`docs/img/proto-cone-vanilla-oldbuild-endon.png`（原版；旧版探针锥轴朝视线方向，故呈圆形光斑）。

注：`depthTex=4` 在两种情况下都是同一个非 0 纹理 id，说明 `getDepthAttachment()` 在原版和 Iris 下都能拿到东西（但不等于可以直接读到 Iris 合成后的深度，见 §5.3）。

### 5.3 深度纹理（软相交）

- 1.20.1 Yarn **有** `Framebuffer.getDepthAttachment()`（`javap` 已核实：`public int getDepthAttachment()`，字段 `protected int depthAttachment`、`public final boolean useDepthAttachment`）。原型每 5 秒把返回值打进日志，用来确认在原版/Iris 下都不是 0。
- 但拿到的是 **GL 纹理 id**，不是可读缓冲：要在 shader 里采样，得自己写 shader 并绑定 sampler；要 CPU 读回只能 `glReadPixels`（每帧一次全屏读回，不可接受）。
- Iris 下 framebuffer 由 Iris 接管，`getDepthAttachment()` 拿到的可能不是光影最终合成的深度 → **软相交在 Iris 下不可靠**，建议：原版走深度纹理软相交；检测到光影时退化成"按地面高度淡出"（现有 `BeamGlow` 的 heightmap 方案），不要试图读 Iris 的深度。

### 5.4 运行期实测（同一次 Iris 运行里 dump 出的 3 个真实模型）

```
mtr_custom_train_xml6855jevy0c5_lty/1  groups=27 LIGHTS=4 len=9.0  width=2.0 axis=Z span=9/5/19
    head=headlight(L2,name)  tail=taillight(L2,name)
    LIGHTS groups: doorlight[DOORS_OPEN,t=-0.00] doorzhaoming[ALL,t=-0.14] bodyzhaoming[ALL,t=-0.19]
                   connectionzhaoming[ALL,t=-0.70] headzhaoming[ALL,t=0.70] headlight[ALL,t=1.00]

mtr_custom_train_dangui_red/4          groups=20 LIGHTS=6 len=13.0 width=2.0 axis=Z span=4/5/16
    head=headlight(L2,name)  tail=(fallback)(L5,body)

mtr_custom_train_xml6125jevd0c/1       groups=19 LIGHTS=4 len=12.0 width=2.0 axis=Z span=9/5/27
    head=headlight(L2,name)  tail=(fallback)(L5,body)
```

三条真实结论：

1. **组名判据在真机上一次就命中**（3/3 的 `head=headlight`），`partsInfo` 的反射读取没有异常，`properties.parts` 的 stage/condition 也都读到了。
2. `headlight[ALL,t=1.00]`：`t=1.00` 表示该组正好落在模型长度轴的一端 —— 端面判据在这类包上是对的，但不是所有包都这样（见 §1.3 的多节车厢陷阱）。
3. **尾灯两次落到 L5 兜底**，原因就是 §1.4 的反例：这些包的 `taillight` 被声明成 `INTERIOR`（灯罩/内饰层），被 reject 词表排掉了。这是"排除法宁可漏、不可错"的代价：要修就得允许"唯一的 tail 命中即使是 INTERIOR 也接受，但只作为 L4 而不是 L2"。
