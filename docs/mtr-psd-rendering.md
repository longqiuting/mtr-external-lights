# MTR 屏蔽门（PSD/APG）渲染原理 —— 照做指南

> 对象：MTR-fabric-1.20.1-**3.6.4.2** 的 `mtr.render.RenderPSDAPGDoor`。
> 证据来源：`D:\MC-MTR-JS\temp\mtrjar`（jar 解包）+ `javap -p -c`，以及 MTR 自己的资源包内容。
> 本文是本模组 `xiamen/RenderPsdDoor.java`（1 格高门扇 BER）的实现依据，也是下一次「门扇画不出来」
> 时的第一手排查表。

---

## 0. 一句话结论（最容易踩的坑）

**门扇贴图的命名空间是 `mtr:`，不是模组自己的命名空间。**
`RenderPSDAPGDoor` 拼的是
`new Identifier(String.format("mtr:textures/block/psd_door_%s_%s_%s.png", …))`，
所以**玩家的屏蔽门材质包会接管它**（它们改的都是 `assets/mtr/textures/block/psd_door_*`）。

一旦把这张图「拷进模组自己做一份 `mtrlte:`」，就出现了两套同时存在的门扇贴图：

| 谁 | 读哪个 id | 结果 |
| --- | --- | --- |
| MTR 自带门（左边） | `mtr:…` | 命中**当前生效资源包**那一张（白门板 + 橙腰线 + 警示贴） |
| 我们的门（右边） | `mtrlte:…` | 命中**当初从某个包拷出来的快照**，可能完全是另一张图 |

这就是「同一位置左边正常、右边纯白」的根因：**不是贴图没提交，也不是 UV 算错，而是两边读的不是同一张图**。
详细证据与本次修复见 §10、§11。

---

## 1. 一列屏蔽门长什么样（结构）

一列（`BlockPSDAPGDoorBase`）是 **3 格高**，门一次放 **2 列**（左右两扇对开）：

```
y+2   psd_top        吊板（BlockPSDTop，MTR 的 RenderPSDTop 画站名/线路图/箭头）
y+1   psd_door HALF=UPPER   上段门扇
y+0   psd_door HALF=LOWER   下段门扇
```

相邻列：玻璃 `BlockPSDAPGGlassBase`（`SIDE_EXTENDED = left/middle/right/single`）、
端部收口 `BlockPSDAPGGlassEndBase`（`TOUCHING_LEFT/RIGHT`）。
**玻璃 / 端部 / 吊板是方块模型（JSON）**，只有**门扇**是 BlockEntityRenderer 现画 ——
所以「玻璃正常、只有门扇白」这个现象本身就已经把范围钉在 `RenderPSDAPGDoor` 上。

门扇方块本身没有模型：`assets/mtr/blockstates/psd_door.json` 是
`{"variants":{"":{"model":"minecraft:block/air"}}}`（`psd_door_2.json` 同理）。
**若哪天门扇变成实心立方体 / 洋红黑格，先查 blockstate，不要查 BER。**

---

## 2. 渲染入口与调度（MTR 不是「直接画」）

```java
// 1) BER 入口：只把「画什么」排进队列，不碰 GL
public void render(T entity, float tickDelta, MatrixStack matrices,
                   VertexConsumerProvider vertexConsumers, int light, int overlay)

// 2) 排队
RenderTrains.scheduleRender(Identifier texture, boolean translucent,
                            QueuedRenderLayer layer,
                            BiConsumer<MatrixStack, VertexConsumer> draw);

// 3) MTR 自己的渲染 pass（世界渲染事件里）里按 4 个层依次 flush
RenderTrains.render(...) → RENDERS[translucent ? 1 : 0].get(layer.ordinal()).forEach(...)
```

`QueuedRenderLayer` 只有 4 个值，落到 `MoreRenderLayers` 后是：

| QueuedRenderLayer | MoreRenderLayers | 实际 RenderLayer（1.20.1 yarn / 中介名） |
| --- | --- | --- |
| `LIGHT` | `getLight(tex, false)` | `RenderLayer.getBeaconBeam(tex, false)`（`method_23592`，全亮、无 lightmap） |
| `LIGHT_TRANSLUCENT` | `getLight(tex, true)` | `getBeaconBeam(tex, true)` |
| `INTERIOR` | `getInterior(tex)` | **`RenderLayer.getEntityCutout(tex)`**（`method_23576`） |
| `EXTERIOR` | `getExterior(tex)` | **`RenderLayer.getEntityCutout(tex)`**（同上，只是另开了缓存） |

要点：

* **门扇本体 = `EXTERIOR` = `getEntityCutout(tex)`**：**贴图跟着 RenderLayer 走**，顶点里只写 UV；
  全程没有 `RenderSystem.setShaderTexture`。
* `INTERIOR` 与 `EXTERIOR` 在 3.6.4.2 里是**同一个层**（同一个 `method_23576`），只是 `MoreRenderLayers`
  分了两个缓存 Map；两者的差别在别处的光照注入上，不是 RenderLayer 类型。
* `cull` 是**开**的（`getEntityCutout` 而不是 `getEntityCutoutNoCull`）。所以顶点绕序必须对，
  否则门扇整片消失（不是变白）。
* 门灯「亮」是 `getBeaconBeam`（自发光层），「灭」是 `getEntityCutout`。

---

## 3. 变换：三段，缺一不可

```java
// lambda$render$0 —— 基础变换
matrices.translate(pos.getX() + 0.5, pos.getY(),        pos.getZ() + 0.5);
UtilitiesClient.rotateYDegrees(matrices, -facing.asRotation());
UtilitiesClient.rotateXDegrees(matrices, 180.0F);          // ← 关键：y、z 都取反
```

`rotateX(180°)` 把 (x, y, z) 映射成 (x, −y, −z)。MTR 的模型全部按 **y ∈ [−16, 0]、z ∈ [−8, 6]** 写
（`ModelSingleCube` 内部是 `addBox(x-8, y-16, z-8, w, h, d)`），翻转后才是「y 向上、+z 朝轨道」。

```java
// lambda$render$4 —— 整扇沿墙平移（本地 x 轴）
matrices.translate(open * (side ? -1 : +1), 0, 0);
```

---

## 4. 图集文件名拼接规则

```java
// 主扇（普通门，type 0/1）
String.format("mtr:textures/block/psd_door_%s_%s_%s.png",
        half ? "top" : "bottom",          // ① 上/下段
        side ? "right" : "left",          // ② 右扇/左扇（side = SIDE == RIGHT）
        type == 1 ? "2" : "1");           // ③ 门型号（constructor 的 int type）

// 端部门（END=true）：主扇 / 副扇各一张
String.format("mtr:textures/block/psd_door_end_%s_%s_1_%s.png", …);   // 主扇
String.format("mtr:textures/block/psd_door_end_%s_%s_2_%s.png", …);   // 副扇（只滑 open/2）

// 其它
"mtr:textures/block/light_on.png" / "light_off.png"        // 门灯
"mtr:textures/block/sign/door_not_in_use.png"              // 「暂停使用」牌
"mtr:textures/block/apg_door_%s_%s.png"                    // type 2（APG）
"mtr:textures/block/apg_door_light_%s.png"
"mtr:textures/block/lift_door_%s_%s_1.png"                 // type 3/4（升降门）
```

**参数顺序记牢**：`top/bottom` → `left/right` → `型号`。
（`mtr:textures/block/` 下有 `_1` 和 `_2` 两套；`_2` 只会被 `type == 1` 的渲染器取到。）

> ⚠️ 资源包里的老格式 `psd_door_top_1.png` / `psd_door_top_back_1.png`（整图、256×256）
> 是 **MTR 3.x 之前那一代**门扇用的，**不要**拿来当 box UV 图集用 —— 它按 0..1 铺满一个面
> 会有大片留白，看起来就是「一块白板 / 没贴图」。

---

## 5. Box UV 表（本项目实际使用的数字）

MTR 的每个部件都是一个 `ModelSingleCube(texW, texH, x, y, z, w, h, d)`：

```text
texW = 2(d + w)      texH = d + h          // 图集画布（单位，1 单位 = 图集像素 / 缩放）
```

盒子六面在画布上的位置（MC `ModelPart.Cuboid` 标准布局）：

```text
      u: 0 ─── d ─── d+w ─── d+w+d ─── 2d+2w
          │+x 面│  +z 面  │  -x 面  │  -z 面  │
v: 0 ── d ─┬─────────────────────────────────
  上/下檐 │  +y（上）在 [d, d+w]×[0,d]，-y（下）在 [d+w+d, 2d+2w]×[0,d]
  侧面    │  +z/-z/+x/-x 都是 [d, d+h] 整高
```

### 5.1 普通门扇（`MODEL_PSD`）

`new ModelSingleCube(36, 18, 0, 0, 0, 16, 16, 2)` → **d=2, w=16, h=16，画布 36×18 单位**。
16× 的图集就是 **576×288**（资源包里 `psd_door_{top,bottom}_{left,right}_1.png` 即此尺寸）。

| 面 | 语义 | u 范围（单位） | u/36 | v/18 | 576×288 里的像素框 |
| --- | --- | --- | --- | --- | --- |
| **−z（盒子自己的 −z）** | **朝轨道的外表面** | `[d+w+d, 2d+2w] = [20, 36]` | 0.5556–1.0000 | 0.1111–1.0000 | x 320–576, y 32–288 |
| +z | 朝站台内的内表面 | `[d, d+w] = [2, 18]` | 0.0556–0.5000 | 0.1111–1.0000 | x 32–288, y 32–288 |
| +x | 门扇窄边（合缝侧） | `[0, d] = [0, 2]` | 0.0000–0.0556 | 0.1111–1.0000 | x 0–32 |
| −x | 门扇窄边（另一侧） | `[d+w, d+w+d] = [18, 20]` | 0.5000–0.5556 | 0.1111–1.0000 | x 288–320 |
| +y | 上檐 | `[d, d+w] = [2, 18]` | 0.0556–0.5000 | 0.0000–0.1111 | x 32–288, y 0–32 |
| −y | 下檐 | `[d+w+d, 2d+2w] = [20, 36]` | 0.5556–1.0000 | 0.0000–0.1111 | x 320–576, y 0–32 |

> **哪一半朝轨道**：因为 `rotateX(180)`，**图集右半（u ≥ 0.5556）才是朝轨道那一面**。
> 两侧图集**不是镜像对称的**（实测同一张 576×288 的左右两半，镜像后 meanAbsDiff ≈ **51/255**），
> 所以正/背面配错会画成另一侧的设计。
> 我们的 `PsdQuads.UvSet.box()` 已经在「翻转后」的坐标里写几何，按
> `+z ← 盒子 −z 面`、`+y ← 盒子 −y 面` 重新配对，等价于 MTR。

### 5.2 端部门扇（`MODEL_PSD_END_*`）

`new ModelSingleCube(20, 18, u, 0, z, 8, 16, 2)` → **d=2, w=8, h=16，画布 20×18 单位**。
16× 图集是 **320×288**（`psd_door_end_*_1_1.png`），4× 是 **80×72**（`_end_bottom_*`）。

| 部件 | u 偏移 | z 偏移 | 备注 |
| --- | --- | --- | --- |
| `MODEL_PSD_END_LEFT_1` | 0 | 0 | 主扇，x ∈ [−8, 0]（`side=false`） |
| `MODEL_PSD_END_RIGHT_1` | 8 | 0 | 主扇，x ∈ [0, 8]（`side=true`） |
| `MODEL_PSD_END_LEFT_2` | 8 | 2 | 副扇，x ∈ [0, 8]，**z 靠里 2px（翻转后 z 4..6）** |
| `MODEL_PSD_END_RIGHT_2` | 0 | 2 | 副扇，x ∈ [−8, 0]，同上 |

面内 UV 与 §5.1 同一套公式，只是 `w=8`：

| 面 | u 单位 | u/20 | 320×288 像素 |
| --- | --- | --- | --- |
| −z（朝轨道） | `[12, 20]` | 0.60–1.00 | x 192–320 |
| +z | `[2, 10]` | 0.10–0.50 | x 32–160 |
| +x | `[0, 2]` | 0.00–0.10 | x 0–32 |
| −x | `[10, 12]` | 0.50–0.60 | x 160–192 |

---

## 6. 门扇平移（开合动画）

```java
float open = Math.min(entity.getOpen(MTRClient.getLastFrameDuration()), type >= 3 ? 0.75f : 1.0F);
float dir  = side ? -1.0F : 1.0F;      // RIGHT 扇往 −x，LEFT 扇往 +x，两扇对开
```

| 部件 | 平移量 | 用的是哪份 matrix |
| --- | --- | --- |
| 门灯 | 0（跟着门框） | **`stored.copy()`（未平移）** |
| 端部**副扇** | `open / 2 * dir` | `stored.copy()`（未平移）+ 自己的 `open/2` |
| 主扇 / 整扇 | `open * dir` | `stored`（已平移） |
| 「暂停使用」牌 | 跟着主扇走（端部再额外 ±0.25） | `stored`（已平移） |

* `open` 来自方块实体：`TileEntityPSDAPGDoorBase#getOpen(float delta)`，内部把 `openClient`
  每帧朝 `open - 0.1` 靠（`change = delta * 0.95f`），返回 `openClient / 32`。
  **开合进度只有这一个真源**，列车调 `setOpen(int)`（0..32）驱动它。
* 整扇平移量是 **1 格**（`open` 归一化到 1.0）。
* `type >= 3`（升降门）只按 0.75 格算。

---

## 7. 门灯（`MODEL_PSD_LIGHT_*`）

* **只在 `HALF == UPPER` 时画**（`if (half)`），下层不画。
* 模型：`ModelSingleCube(16, 16, x, y, 5, 1, 1, 1)`
  * LEFT：`x = 0, y = -1` → 翻转后 **x ∈ [−8, −7], y ∈ [16, 17], z ∈ [2, 3]**
  * RIGHT：`x = 15, y = -1` → 翻转后 **x ∈ [7, 8], y ∈ [16, 17], z ∈ [2, 3]**
  （y = 16..17 正好是上段门块顶上那 1px，藏进吊板模型 0..1 的那条缝里。）
* 亮灭判据 `isDoorLightOn(getOpenValue())`：

```text
openValue <= 0   → 灭
openValue >= 32  → 常亮
其它             → System.currentTimeMillis() / 500 % 2 == 0   （500ms 闪一次）
```

* 贴图 `light_on.png` / `light_off.png`；**亮 = `QueuedRenderLayer.LIGHT`（`getBeaconBeam`，全亮），
  灭 = `EXTERIOR`（跟随方块光照）**。灯的 UV 是整张图（0..1）。
* `EnumDoorLight`（`BlockPSDTop`）是**死代码**，别去读它。

---

## 8. 「暂停使用」牌（`MODEL_PSD_DOOR_LOCKED`）

* 条件：`half && !unlocked`（上段 + 方块状态 `UNLOCKED == false`）。
* 模型：`ModelSingleCube(6, 6, 5, 6, 1, 6, 6, 0)`
  → `addBox(-3, -10, -7, 6, 6, 0)` → 翻转后 **x ∈ [−3, 3], y ∈ [4, 10], z = 7**（0 厚薄片，
  正好落在 6..8 的门板内部 —— 靠门板中间的透明区域透出来）。
* 端部门额外再 `translate(side ? +0.25 : −0.25, 0, 0)`。
* 贴图 `mtr:textures/block/sign/door_not_in_use.png`，`EXTERIOR` 层。

---

## 9. type 分支一览（同一份渲染器兜了 5 种门）

| type | 门型 | 主贴图 | 备注 |
| --- | --- | --- | --- |
| 0 | PSD 门（`psd_door`） | `psd_door_{top,bottom}_{left,right}_1.png` | 最常见 |
| 1 | PSD 门 第二代（`psd_door_2`） | `…_2.png` | 同一个类，末位换成 2 |
| 2 | APG 门 | `apg_door_{top,bottom}_{left,right}.png` | 上段 `MODEL_APG_TOP`，下段 `ModelAPGDoorBottom`；门灯在 `apg_door_light_on/off.png`，且要先看邻居是不是 `BlockAPGGlass`/`BlockAPGGlassEnd` |
| 3 / 4 | 升降门 | `lift_door_{top,bottom}_{left,right}_1.png` | 平移量 0.75；type 4 还要过 `ITripleBlock.ODD`，ODD 格不画，其余 `translate(±0.5)` |

---

## 10. 本模组的实现对照（`xiamen/RenderPsdDoor.java`）

| 项 | MTR `RenderPSDAPGDoor` | 我们（修复前 → 修复后） |
| --- | --- | --- |
| 贴图 id | `mtr:textures/block/psd_door_*` | `mtrlte:textures/block/…`（模组内快照）→ **改成 `mtr:`（与 MTR 完全一致）** |
| RenderLayer | `getEntityCutout(tex)`（cull 开） | `getEntityCutoutNoCull(tex)` → 保留 NoCull（见下） |
| 提交方式 | `scheduleRender(tex, false, EXTERIOR, …)` 延后画 | BER 里直接 `vertexConsumers.getBuffer(layer)` |
| 变换 | translate(+0.5) → rotateY(−facing) → **rotateX(180)** | translate(+0.5) → rotateY(−facing)，**几何直接写在翻转后的空间** |
| Box UV | `ModelPart.Cuboid` 的 `addBox` | `PsdQuads.UvSet.box(w,h,d)`（§5 那张表） |
| 正/背面 | 朝轨道 = 图集**右半** | 修复前用了左半（镜像）→ **按 §5 重新配对** |
| 平移 | `open * (side?-1:+1)`；副扇 `open/2` | 相同 |
| 门灯 | 上段、1×1×1、LIGHT 层 | 相同位置；亮用 `MAX_LIGHT_COORDINATE` 代替 `getBeaconBeam`（观感一致，少一套顶点格式） |
| 光照 / 颜色 | `light`/`overlay` 原样透传，color=(1,1,1,1) | `color(255,255,255,255)`、`light` 透传（等价） |
| 视锥 | `shouldRenderOffScreen = true` | `rendersOutsideBoundingBox = true` |

### 为什么保留 `getEntityCutoutNoCull`（而 MTR 用 cull）

MTR 的盒子来自 `ModelPart`（绕序由 MC 生成，必然正确），所以敢开 cull。
我们手写的 `PsdQuads.quadX()` 侧面绕序与法线相反（`z × y = −x`），开 cull 会丢两条 2px 窄边。
`NoCull` 只是把背面也画一遍，正反面本来就都要画，代价可忽略。
**若以后改成 `getEntityCutout`，必须先把 `quadX()` 的两个顶点顺序换过来。**

---

## 11. 「门扇纯白、一点贴图都没有」的完整解释（本次修复）

按顺序排除：

1. **不是 RenderLayer 没带上贴图** —— 我们一直是
   `vertexConsumers.getBuffer(RenderLayer.getEntityCutoutNoCull(texId))`，
   顶点只写 UV，全模组没有一处对门扇调用 `RenderSystem.setShaderTexture`
   （唯一的 `setShaderTexture` 在 `ConeVolumeRenderer`，用的是深度图，与门无关）。
   javap 我们的 jar 可以核对：`class_1921.method_23578` = `getEntityCutoutNoCull`。
2. **不是贴图不存在** —— `assets/mtr/textures/block/psd_door_top_left_1.png` 等 15 个 id
   在 MTR jar 里全部存在；模组里那份副本也在（576×288）。
   （现在首次渲染会打一行自检：`[lte] psd door textures resolved: 15/15 present, …`，
   缺哪个会单独 WARN `psd door texture MISSING: <id>`。）
3. **不是 UV 算错** —— 把 `UvSet.box(16,16,2)` 的六个矩形叠回图集用肉眼核过：
   正面框正好框住「白门板 + 顶部橙警示条 + 底部橙纹样」。
4. **真正的原因：门扇读的是另一张图，而那张图的「门体」是 alpha=0 的。**
   * 我们用的是 `mtrlte:` 命名空间下的**模组内快照**（从资源包 `厦门地铁一号线车辆` 拷的），
     `md5 780a07f5…`；MTR 门读的是 `mtr:`，被**当前生效的资源包**接管
     （例如 `南北组…屏蔽门材质包`，`md5 ace8808c…`），**两张图不是同一张**。
   * 实测那份 576×288 的图：**不透明像素只占 54.9%**，门板主体整片 **alpha = 0**，
     只有顶部橙警示条、底部橙纹样是实心（`psd_door_bottom_*` 更极端：46.3%，
     门体全透，只剩底部一条灰色踢脚线）。
   * `entityCutout` 会把 **alpha < 0.1 的像素整片丢弃** → 门板主体**一个像素都不画**，
     透过门框看到的就是背后那面白墙 —— 观感就是「整个门扇纯白、一点贴图都没有」。
   * 同一列的**玻璃 / 端部 / 吊板**为什么正常：它们走**方块模型**（JSON，自己的 `#front`/`#back`
     贴图是真正的玻璃图，有实体像素），吊板走 MTR 的 `RenderPSDTop`，都不经过我们的门扇 BER。

**修复**：把门扇全部 15 个贴图 id 改回 `mtr:`（`RenderPsdDoor.tex()`），
于是我们的门扇和旁边的 MTR 门**按定义读同一张图**：装什么屏蔽门材质包两边一起变，
不装包时一起回落到 MTR 原版图集。顺带把 §5 的正/背面配对与 MTR 对齐。

> **不要再把 `psd_door*` 图集拷进 `mtrlte:` 复用。** 那一定会和资源包栈再次分叉，
> 而且拿到的可能是「玻璃扇」那种整片透明的版本。
> 模组里现存的那批 `mtrlte:…psd_door_*` 文件现在已经**没有任何代码引用**（保留只为对照/回溯）。

---

## 12. 下次排查清单（照这个顺序，别乱试）

1. **方块本身**：`assets/<ns>/blockstates/<door>.json` 必须是 `minecraft:block/air`
   （无条件 `multipart` 或空 `variants`）。变成实心方块 → 查这里，不是 BER。
2. **BER 有没有注册**：`BlockEntityRendererRegistry.register(<你的 BE type>, …)`；
   方块实体必须 `extends BlockPSDAPGDoorBase.TileEntityPSDAPGDoorBase`（列车 `setOpen` 认这个类型）。
3. **日志自检**：`grep "psd door textures" logs/latest.log` —— 看到 `15/15 present` 就排除「图不存在」；
   有 `MISSING` 就先解决 id/命名空间。
4. **命名空间**：贴图 id 是否和 MTR 一致（`mtr:`）？自己另开命名空间就必然和资源包分叉。
5. **看图的 alpha**：`颜色看起来对但像没贴图` → 十有八九是**图集本身门体全透明**。
   用任意工具统计「不透明像素占比」，占比 < 60% 且门体透光就是这种图。
6. **正/背面反了**（贴图内容镜像）→ 对照 §5，`rotateX(180)` 之后「朝轨道 = 图集右半」。
7. **整片消失**（不是白）→ cull 打开但绕序错，切 `NoCull` 或修绕序。
8. **门不动** → `TileEntityPSDAPGDoorBase#getOpen(delta)` 是唯一来源，
   要有人（列车 / `setOpen`）在改 `open` 字段；`MTRClient.getLastFrameDuration()` 决定插值速度。

---

## 13. 证据索引（javap）

`D:\MC-MTR-JS\temp\mtrjar`，`javap -p -c`：

| 事实 | 位置 |
| --- | --- |
| 贴图 id 用 `mtr:` + `psd_door_%s_%s_%s.png` | `RenderPSDAPGDoor.render` 偏移 637(`ldc #307`) 起 |
| 端部 `psd_door_end_%s_%s_{1,2}_%s.png` | 偏移 279(`#233`)、549(`#300`) |
| `EXTERIOR` 层 + `scheduleRender` | 偏移 702–717、343–362 |
| 门灯 `light_%s.png` + LIGHT/EXTERIOR | 偏移 200–274 |
| 锁定牌 `sign/door_not_in_use.png` | 偏移 725–762 |
| 变换 translate/rotateY/rotateX(180) | `lambda$render$0`（偏移 821–849） |
| 整扇平移 `open * dir` | `lambda$render$4` |
| 副扇 `open / 2 * dir` | `lambda$render$2` |
| 六个模型尺寸（36×18 / 20×18 / 16×16 灯 / 6×6 牌） | `static {}` 偏移 851–1005 |
| `isDoorLightOn` | 方法体偏移 486–511 |
| `EXTERIOR → method_23576 = getEntityCutout` | `MoreRenderLayers.lambda$getExterior$3` |
| `LIGHT → method_23592 = getBeaconBeam` | `MoreRenderLayers.lambda$getLight$0` |
| 中介名 ↔ yarn 名 | `~/.gradle/caches/fabric-loom/1.20.1/net.fabricmc.yarn.1_20_1.1.20.1+build.10-v2/mappings.tiny` |

---

## 14. 本次改动清单

| 文件 | 改动 |
| --- | --- |
| `xiamen/RenderPsdDoor.java` | `tex()` 命名空间 `mtrlte:` → **`mtr:`**；补 `verifyTextures()` 一次性自检日志；类注释重写 |
| `xiamen/PsdQuads.java` | `UvSet.box()` 按 `rotateX(180)` 重新配对六面（朝轨道 = 图集右半、上檐取盒子 −y 面） |
| 产物 | `v1.20.1/build/libs/lte-1.0.21.jar`（1,006,749 B，`md5 cb63fef3…`）→ 桌面 `lte-1.0.21+1.20.1.jar` + 实例 `mods/lte-1.0.21.jar`（三份 md5 一致） |

> 按要求**未启动游戏**验证。上面关于「门会显示橙腰线/警示贴」的结论依据是：
> 修复后门扇与旁边的 MTR 门读**同一个 id 的同一张图**、同一套 box UV、同一个 RenderLayer 家族，
> 因此不可能再与 MTR 门长得不一样。

---

## 15. 第二轮：用户实测「门扇依旧透明」→ 逐字对账 + 改为直接继承 MTR 渲染器（lte-1.0.22）

### 15.1 结论先行

1. **贴图 id 根本没有差异，这条路是死的。** §11 猜「我们读 `mtrlte:` 快照、MTR 读 `mtr:`」在 1.0.21 里已经修掉；
   实测日志（`logs/latest.log` 20:59:10）证明：15 个 id 全部 `present`，请求的就是
   `mtr:textures/block/psd_door_top_left_1.png`，层是 `entity_cutout_no_cull`。
   所以「门扇透明」**不是 id 拼错、不是图缺失**。
2. **渲染器本身换掉了**：`RenderPsdDoor` 现在 `extends mtr.render.RenderPSDAPGDoor`，只把
   `context.getRenderDispatcher()` 和 `type = 0` 交给父类。几何 / 盒子 UV / `rotateX(180)` /
   正反面配对 / 平移 / 门灯 / 渲染层 / `scheduleRender` 批处理，全部是 MTR 自己的代码，
   **与旁边的 MTR 门逐字节相同**，不再存在「自己抄一份」的偏差空间。
3. **同时修掉一个真 bug**：`XiamenClient` 以前把「挂渲染层 + 注册两个 BER」全塞进**一个** `try`，
   前面任何一个 `putBlock` 抛异常，后面的门扇 BER 注册就被一起吞掉 —— 症状正是「门扇整片透明」。
   现在每步独立 `try/catch`，且门扇注册排在最前 + 成功时打一行日志。

### 15.2 两边请求的完整 id 对照表（逐字）

MTR 侧原文（CFR 反编译 `mtr.render.RenderPSDAPGDoor#render`，`type` 为构造器第 2 参数）：

```java
// 门灯（只在 half 时）
new Identifier(String.format("mtr:textures/block/light_%s.png", doorLightOn ? "on" : "off"))
// 端部门扇 副扇
new Identifier(String.format("mtr:textures/block/psd_door_end_%s_%s_2_%s.png",
        half ? "top" : "bottom", side ? "right" : "left", this.type == 1 ? "2" : "1"))
// 端部门扇 主扇
new Identifier(String.format("mtr:textures/block/psd_door_end_%s_%s_1_%s.png",
        half ? "top" : "bottom", side ? "right" : "left", this.type == 1 ? "2" : "1"))
// 普通门扇
new Identifier(String.format("mtr:textures/block/psd_door_%s_%s_%s.png",
        half ? "top" : "bottom", side ? "right" : "left", this.type == 1 ? "2" : "1"))
// 「暂停使用」牌
new Identifier("mtr:textures/block/sign/door_not_in_use.png")
```

| 部件 | MTR 拼出的 id（type=0，逐字） | 1.0.21 我们请求的 id | 差异 |
| --- | --- | --- | --- |
| 上段·左扇 | `mtr:textures/block/psd_door_top_left_1.png` | `mtr:textures/block/psd_door_top_left_1.png` | **无** |
| 上段·右扇 | `mtr:textures/block/psd_door_top_right_1.png` | 同 | 无 |
| 下段·左扇 | `mtr:textures/block/psd_door_bottom_left_1.png` | 同 | 无 |
| 下段·右扇 | `mtr:textures/block/psd_door_bottom_right_1.png` | 同 | 无 |
| 端部·上·左 主扇 | `mtr:textures/block/psd_door_end_top_left_1_1.png` | 同 | 无 |
| 端部·上·右 主扇 | `mtr:textures/block/psd_door_end_top_right_1_1.png` | 同 | 无 |
| 端部·上·左 副扇 | `mtr:textures/block/psd_door_end_top_left_2_1.png` | 同 | 无 |
| 端部·上·右 副扇 | `mtr:textures/block/psd_door_end_top_right_2_1.png` | 同 | 无 |
| 端部·下·左/右 主扇 | `mtr:textures/block/psd_door_end_bottom_{left,right}_1_1.png` | 同 | 无 |
| 端部·下·左/右 副扇 | `mtr:textures/block/psd_door_end_bottom_{left,right}_2_1.png` | 同 | 无 |
| 门灯 | `mtr:textures/block/light_{on,off}.png` | 同 | 无 |
| 锁定牌 | `mtr:textures/block/sign/door_not_in_use.png` | 同 | 无 |

**逐字对账的结论：`_left`、`_1`、`_back`、`textures/block/` 前缀一处不差。**
（`new Identifier("mtr", "textures/block/x.png")` 与 `new Identifier("mtr:textures/block/x.png")`
解析结果完全相同：namespace=`mtr`、path=`textures/block/x.png`。）
所以 1.0.22 不再靠猜 id，而是让 MTR 自己 `String.format`（见 15.3），id 按构造必然一致。

### 15.3 改动（选「继承 MTR 渲染器」这条路）

MTR 渲染器的签名（`javap`）：

```
public class mtr.render.RenderPSDAPGDoor<T extends BlockPSDAPGDoorBase.TileEntityPSDAPGDoorBase>
        extends mtr.mappings.BlockEntityRendererMapper<T>
        implements IGui, IBlock
public RenderPSDAPGDoor(net.minecraft.client.render.block.entity.BlockEntityRenderDispatcher dispatcher, int type)
public void render(T entity, float tickDelta, MatrixStack, VertexConsumerProvider, int light, int overlay)
public boolean shouldRenderOffScreen(T blockEntity)   // = true
```

我们只需（`RenderPsdDoor` 就这一行是「接线」）：

```java
public RenderPsdDoor(BlockEntityRendererFactory.Context context) {
    super(context.getRenderDispatcher(), 0);   // type 0 = PSD（psd_door_*_1.png）
}
```

注册不用改（`XiamenClient`）：

```java
BlockEntityRendererRegistry.register(XiamenObjects.psdDoorEntityType(), RenderPsdDoor::new);
```

之所以能这么干：我们的 `TileEntityXiamenPSDDoor extends BlockPSDAPGDoorBase.TileEntityPSDAPGDoorBase`、
`Door extends BlockPSDAPGDoorBase`（列车开门 `instanceof` 的硬要求），泛型 `T` 正好满足。
**贴图 id 会自动跟随方块/资源包**：id 完全由 MTR 在渲染时按方块状态拼 `mtr:` 前缀，
哪个资源包接管 `assets/mtr/textures/block/psd_door_*_1.png`，我们的门就和 MTR 的门一起变；
不装包时一起回落到 MTR 内置图集。

`RenderPsdDoor.render()` 现在只多做两件事然后 `super.render(...)`：第一帧打印 15 个 id 的
**命中来源**，之后每个初次请求的 id 打一行 —— `[lte] psd door tex: <id> <- <资源包名|MISSING>`。

### 15.4 像素统计：哪张才是「门板」

统计脚本 `temp/psdstats.js`（自写 PNG 解码 + box UV 正面区域采样）。「门板正面区」= 图集里
`u∈[d,d+w]、v∈[d,d+h]` 那一块（MTR `ModelSingleCube` 真正采样的矩形）。

| 贴图（生效包 = 南北组…屏蔽门材质包；厦门包未被启用） | 尺寸 | 整张不透明占比 | alpha=0 占比 | 正面区不透明占比 | 内容 |
| --- | --- | --- | --- | --- | --- |
| `psd_door_top_left_1.png`（南北组，生效） | 576×288 | **54.7%** | 45.1% | 50.5% | 顶部橙警示横幅 + 中部**玻璃（整片 alpha=0）** + 底部橙腰线 + 白色开启箭头 |
| `psd_door_top_right_1.png`（南北组，生效） | 576×288 | 54.7% | 45.1% | 50.5% | 同上（右扇） |
| `psd_door_bottom_left_1.png`（南北组，生效） | 512×256 | **65.3%** | 32.8% | 64.1% | 灰白门板 + 下部实心（**门板主体是这张**） |
| `psd_door_bottom_right_1.png`（南北组，生效） | 512×256 | 65.3% | 32.8% | 63.8% | 同上（右扇） |
| `psd_door_top_left_1.png`（厦门包，**未启用**） | 576×288 | 54.7% | 45.1% | 50.5% | 与南北组同构，腰线是橙色 |
| `psd_door_bottom_left_1.png`（厦门包，**未启用**） | 144×72 | 46.3% | 53.7% | 39.8% | 更透：门体基本全透，只剩白箭头 + 灰色踢脚 |
| `psd_door_top_left_1.png`（MTR jar 内置） | 72×36 | **31.3%** | 68.7% | 20.9% | 只有一圈黑框，MTR 原版 PSD 就是玻璃 |
| `psd_door_bottom_left_1.png`（MTR jar 内置） | 72×36 | 39.7% | 60.3% | 31.4% | 同上（黑框 + 玻璃） |
| `psd_top.png` / `psd_top_edge.png` | 64×64 | **100%** | 0% | 100% | 纯白（吊板，不是门扇） |
| `apg_door_bottom.png`（厦门包） | 256×256 | 77.3% | 22.7% | 70.3% | 橙横幅 + 玻璃（APG 也不是白门板） |

**要点**：门扇「上半段」在所有包里都是**玻璃 + 橙色横幅**，中部整片 alpha=0 —— 这是资源包的设计，
MTR 的门同样如此；真正的不透明门板是「下半段」那张（65% 不透明）。透过玻璃看到的那面白墙，
就是观感里的「白门板」。`psd_door_end_*` / `psd_glass_*` 同族数据（不透明 27%–65%）见
`temp/psdstats.js` 的完整输出。

### 15.5 `door_tile_entity_migration/` 是什么

- **是 MTR 自己的历史命名空间，不是作者备份。** MTR jar 里有
  `assets/mtr/models/block/door_tile_entity_migration/*.json`（36 个）+
  `assets/mtr/textures/block/door_tile_entity_migration/*.png`（旧格式
  `psd_door_top_1.png` / `_back_1` / `apg_door_*.png`）。这些 model 的 `parent` 是
  `door_tile_entity_migration/psd_door_left_base`，贴图写 `#particle = mtr:block/door_tile_entity_migration/psd_door_top_1`。
- **没有任何代码按这个目录名回退**：`grep -rl "door_tile_entity_migration"` 在 MTR 的 **`.class` 里 0 命中**，
  在 `blockstates/` 与其它 model 里也 0 命中 —— 唯一出现的地方就是这批 model **自己**。
  `blockstates/psd_door.json` 现在是 `{"variants":{"":{"model":"minecraft:block/air"}}}`（BER 全权绘制）。
- 它是 MTR 3.2「方块模型时代」的旧贴图/旧模型，随 3.6 的 BER 化一并留在 jar 里（资源包作者把整份也拷了）。
  **播放动画用的不是它**；门扇动画（开合 + 门灯闪烁）完全在 `RenderPSDAPGDoor` 里由 `open` 算出来。

### 15.6 本次改动清单（1.0.22）

| 文件 | 改动 |
| --- | --- |
| `xiamen/RenderPsdDoor.java` | **整类重写**：`extends mtr.render.RenderPSDAPGDoor<TileEntityXiamenPSDDoor>`，构造器只有 `super(context.getRenderDispatcher(), 0)`；新增 id 表日志（15 个 id + 命中资源包）与逐帧真实请求日志；删掉自写几何 / `PsdQuads` 用法 |
| `xiamen/XiamenClient.java` | 每步独立 `try/catch`；门扇 BER 注册提到最前并打日志；补 `psdDoor` 的 `cutout` 层（照 MTR `MTRClient` 给 `PSD_DOOR_1` 挂 cutout） |
| `xiamen/PsdQuads.java` | 无改动，但门扇已不再使用（保留作对照） |
| 产物 | `v1.20.1/build/libs/lte-1.0.22.jar`（1,016,366 B，`md5 9ad7ee192693c12b08ee5f6db777e6e0`，`BUILD SUCCESSFUL in 18s`）→ 桌面 `lte-1.0.22.jar` + `lte-1.0.22+1.20.1.jar` + 实例 `mods/lte-1.0.22.jar`（三份 md5 一致，旧的 1.0.21 已从 mods 删除） |
| 工具 | `temp/psdstats.js`（像素统计）、`temp/scan.js`（批量扫描）、`temp/compose.js`（叠棋盘格导出，用来看 alpha） |


---

## 16. 半高 APG（`xiamen_apg_glass` / `xiamen_apg_end`）

半高门是另一条路：玻璃上的线路图来自 **另一个 BER** `mtr.render.RenderAPGGlass`
（同一套 `RenderRouteBase` / `RouteMapGenerator` 数据，几何 `z=4/16, top=8/16, bottom=4/16, side=8/16`），
端部紫黑块是 blockstate 文件名与方块 id 不匹配。详见 `docs/mtr-apg-glass-report.md`。
