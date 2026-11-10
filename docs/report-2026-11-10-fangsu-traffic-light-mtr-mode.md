# 方速交通灯：右键自动切「MTR信号联动」并完成绑定

日期：2026-11-10 · 只构建 **v1.20.1** · 产物 `lte-1.0.23.jar`（已拷桌面 + 实例，未提交 git）

对象 jar：`mods/[方速MTR扩展] [MTR3]fangsu-mc1.20.1-FABRIC-1.3.2-build20261102090023.jar`
（62016527 字节，与 `temp/fangsu.jar` 同一文件），CFR 0.152 全文 + `javap -p -c`。

---

## 1. 结论：mtr 档**存在但是空实现**（这次把它补上了）

方速 1.3.2 的 `BlockEntityTrafficLight` 有 `dispType ∈ {program, mtr}`，但只有 `program` 会驱动灯色。
上一轮的结论（「不可用」）在字面上是对的 —— 不是我们没找到入口，是**方速那一档没写**。
所以本次实现 = **切模式**（用方速自己的配置 setter）+ **替它写 `lightState`**（我们自己的 mixer）。

### 1.1 字节码证据 A：`whenRendering()` 里 mtr 档什么都不做

```
public void whenRendering();
   0: aload_0 / 1: getfield markedError:Z / 4: ifne 21
   8: getfield modelLoaded:Z / 11: ifeq 21
  15: getfield content:…TrafficLightContent; / 18: ifnonnull 22 / 21: return
  22: ldc  "program"                       ← 只认 program
  25: getfield dispType:Ljava/lang/String;
  28: invokevirtual java/lang/String.equals
  31: ifeq 38                              ← 不是 "program"（即 "mtr"）→ 直接跳到 38，跳过 drive()
  34: aload_0 / 35: invokevirtual drive:()V
  38: … 之后是 subModelDmhs 的绘制循环
 103: getfield lightState:Ljava/util/Map;   ← 只读
 108: invokeinterface java/util/Map.get     ← 只读
 132: ifnull 58 / 133: ifne 139              ← 取不到或 false 就跳过这颗灯珠
```

`whenRendering` 里**没有任何一条 `lightState.put`**。

### 1.2 字节码证据 B：`drive()` 是全 jar 唯一的 `lightState` 写入者

```
private void drive();       // CFR 全文：player.tick(nanoTime/1e9) → getLightStates() → lightState.put → applyDisplayStates()
  19: invokevirtual TrafficLightProgram.getLightStates:()Ljava/util/Map;
  53: getfield lightState:Ljava/util/Map;
  74: invokeinterface java/util/Map.put       ← 唯一写入点
```

另外两处写 `lightState` 的是 `whenLoading()`（每个非 main 的 subModel 置 `false`）和
`applyDisplayStates()`（倒计时数字的段，只由 `drive()` 调）。

### 1.3 顺带确认的方速 bug：`dispType` 不参与 NBT 往返

```
FunctionalObjBlockEntity.method_11007(writeNbt)  → tag.put("extraConfig", 含 dispType)
FunctionalObjBlockEntity.method_11014(readNbt)   → 只把 extraConfig 灌进 extraConfigs map
```

全 jar `grep dispType` 只命中 `BlockEntityTrafficLight` 的字段声明、`getConfigs()` 的两个 lambda、
`whenRendering()` 的判断 —— **没有一行从 `extraConfigs` 恢复字段**。所以重进存档后客户端字段回落
`"program"`。这就是渲染侧必须**每帧**钉字段、而不是只在切换那一次写的原因。

---

## 2. 切换模式的确切方法

### 2.1 方速自己怎么存、怎么改

```
BlockEntityTrafficLight.getConfigs()   // javap
  29: ldc "program" / 31: ldc "mtr" / 34: List.of(Object,Object)   ← values，mtr 下标 = 1
  73: invokedynamic #1:accept:(LBlockEntityTrafficLight;Ljava/util/List;Ljava/util/Map;)Ljava/util/function/Consumer;

private void lambda$getConfigs$1(java.util.List, java.util.Map, java.lang.Integer);   // = EnumConfig 的 setter
   6: List.get(I)            → values.get(idx)
  14: putfield dispType      → 字段
  24: Map.put                → extraConfigs.put("dispType", …)
  31: invokevirtual setConfigChanged:()V   → markDirty() + rebuildProgram()
```

- 落盘：`FunctionalObjBlockEntity.method_11007`（`extraConfig` 复合标签）
- 下发客户端：`readC2S` 里 `world.updateListeners(pos, state, state, 3)`（`class_1937.method_8413`）
- 配置界面：`EnumConfig.createWidget` 点一下 → `value=(value+1)%n` → `notifyValueChanged()`

### 2.2 我们的切法（`signal/FangsuTrafficLightMode#toMtr`，服务端线程）

1. **首选**：`be.getClass().getMethod("getConfigs").invoke(be)` → 找类名为
   `com.fangsu.extraConfig.EnumConfig` 的那一条 → 反射出 `ConfigEntry.setter`
   （`protected final Consumer<T> setter`，javap 已确认）→ `accept(Integer.valueOf(1))`。
   **和玩家在配置界面点一下走的是同一条 lambda**，字段 / `extraConfigs` / `markDirty` 全部由方速自己做。
2. **兜底**：直接 `putfield` 式的反射写 `dispType="mtr"` + `extraConfigs.put("dispType","mtr")` +
   `BlockEntity.markDirty()` +（有就调）`rebuildProgram()`。
3. **回读确认**：两条路结束都重新 `getDeclaredField("dispType").get(be)`，不是 `"mtr"` 就返回 `FAIL`。
4. `world.updateListeners(pos, state, state, 3)` 顺手把 `extraConfig` 推给客户端（配置界面显示正确；
   显示效果不依赖它）。

日志：`[lte] fangsu: mode=<old>->mtr ok` / `… fail`（INFO / WARN）。
返回枚举：`OK` / `ALREADY` / `NOT_FANGSU` / `FAIL`。

---

## 3. 我们的接入点

| 位置 | 作用 |
|---|---|
| `signal/SignalLinkItem#useOnBlock` | `isLamp(state)` = MTR 信号灯 **或** 方速交通灯；方速且非潜行 → 先 `switchFangsuMode`，再走原来的「选中→绑定」流程 |
| `signal/SignalLinkItem#isLamp` | 新增；替换原来 3 处「是不是信号灯」的判定（含 pending 源灯校验） |
| `signal/SignalLinkItem#switchFangsuMode` | 调 `FangsuTrafficLightMode.toMtr`，按结果发 `message.mtrlte.signal_link.fangsu_mode_mtr` / `…_manual` |
| `signal/FangsuTrafficLightMode` | 服务端切模式（纯反射，无编译期 `com.fangsu` 依赖） |
| `mixin/fangsu/FangsuTrafficLightRenderMixin` | `@Inject(method="whenRendering", at=HEAD, require=0, remap=false)` → `FangsuSignalSync.onRender(this)` |
| `signal/FangsuSignalSync` | 客户端每帧：解析绑定 → 算 aspect → 写 `lightState` 红/黄/绿，并把 `dispType` 钉在 `mtr` |
| `mtrlte.fangsu.mixins.json` | `client` 列表加 `FangsuTrafficLightRenderMixin`（config 仍 `required:false` + `FangsuMixinPlugin` class 探针） |
| `signal/FangsuSignalCompat` | 只保留「按 registry id 认方块」，类注释换成新结论 |

### 3.1 为什么注入 `whenRendering` 而不是渲染器

`ModBlockClient` 第 28 行把交通灯的渲染器注册成通用 `BaseBlockEntityRender`：
它每帧只做 `tryBeginRendering() → whenRendering()`（跑在 `fangsu-rendering-async` 线程）→ 绘制
`EyeCandyDrawCalls`。`whenRendering` 是唯一「每帧必走、且在我们写完之后才读 `lightState`」的位置，
等价于 `drive()` 在 program 档里扮演的角色。

### 3.2 aspect 从哪来（不发散）

`FangsuSignalSync#aspect` 是 MTR `RenderSignalBase#getOccupiedAspect` 的逐字副本（3.6.4.2，3 档）：

```
从 node 出发，角度 = lamp.底 facing.asRotation() + 90（与 MTR 完全一致）
for j in 1..2:
  ClientData.RAILS.get(pos) → rails
  rail.facingStart.similarFacing(angle)
  ClientData.SIGNAL_BLOCKS.isOccupied(PathData.getRailProduct(pos, end)) → return j
  ClientData.OCCUPIED_RAILS.get(product) == TRUE                    → return j
  下一层 = rail.facingEnd.getOpposite().angleDegrees, 标记 0（有轨道没占）
一节都走不通 → -1
```

颜色映射照抄 MTR `RenderSignalLight3Aspect`：**1→红、2→黄、0→绿、-1→全灭**；模型没有 `yellow`
灯珠时（`lightState` 里没有该 key）就退化成 2 档：`>=1 → 红`。
不反射方速自己那份 `MinecraftClientUtil#getOccupiedAspect`（起点写死、全 jar 无调用点），
直接用 MTR 的公开 `ClientData`，编译期类型安全（`javap` 已核对生产 jar 的签名：
`RAILS:Map<class_2338,Map<class_2338,Rail>>`、`SIGNAL_BLOCKS:SignalBlocks`、`isOccupied(UUID)`）。

### 3.3 灯 → 灯

方速灯作为「抄的那一方」：`SignalLinks.resolveSource` 拿到链尾源灯 → 用**源灯**的节点与朝向；
源灯自己没绑节点就按 MTR `getNodePos` 的扫描（`{0,1,-1,2,-2,3,-3,4,-4}` × `up(-5..0)`）找他脚边那颗。

---

## 4. 降级（任何一步失败都只提示，不崩）

- 切模式失败 / `NOT_FANGSU` → 黄字 `…fangsu_mode_manual`，**绑定照旧**（绑定表与模式无关，玩家可以自己切）；
- 渲染侧：`require = 0` + config `required:false` + class 探针 + handler 内 `catch (Throwable)` →
  方速改结构只会变成「灯保持方速自己的行为」；
- 反射字段名变了 → `Refs.NONE`，功能静默关闭；
- 未绑定的方速交通灯**一个字段都不碰**，玩家选的「程序控制」照旧。

已知代价：一旦绑定，这盏灯的积木程序被停用（`dispType=mtr` 就是「别跑 program」）。这是「像 MTR
信号灯一样」的前提，不是副作用遗漏。

---

## 5. 构建

```
gradle -p v1.20.1 clean build --no-daemon   → BUILD SUCCESSFUL（14s，8 tasks executed）
产物：v1.20.1/build/libs/lte-1.0.23.jar（sha256 15a040b0…b338dd）
已拷：桌面 lte-1.0.23.jar / lte-1.0.23+1.20.1.jar、实例
      …/PCL/.minecraft/versions/1.20.1-Fabric 0.19.3/mods/lte-1.0.23.jar（四份哈希一致）
```

jar 内已核对：`FangsuTrafficLightRenderMixin.class`、`FangsuSignalSync(.$Refs).class`、
`FangsuTrafficLightMode(.$Result).class`、`mtrlte.fangsu.mixins.json`（含新条目）、
`assets/mtrlte/lang/{zh_cn,en_us}.json`（两条新 key）。
反编译核对：`FangsuSignalSync` 里 `BlockEntity.getWorld/getPos/getCachedState` 已 remap 成
`class_2586.method_10997 / method_11016 / method_11010`，`Direction.NORTH → class_2350.field_11043`；
MTR 调用保持 `mtr/client/ClientData.RAILS`、`mtr/data/RailAngle.similarFacing(F)Z`、
`mtr/path/PathData.getRailProduct(class_2338,class_2338)UUID`、`mtr/data/SignalBlocks.isOccupied(UUID)Z`。

未启动游戏（按要求）。
