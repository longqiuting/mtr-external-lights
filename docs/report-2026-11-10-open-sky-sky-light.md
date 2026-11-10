# 白天露天车灯时开时关 —— 天空光照判定 + 0.5 s 去抖，并把光柱接进同一个开关

日期：2026-11-10　版本：v1.20.1（`lte-1.0.0+1.20.1.jar`，746,296 字节，md5 `f1feb3ca40d69cc2773a8c9399cd40ae`）
构建：`bash tools/build-all.sh v1.20.1` → **BUILD SUCCESSFUL in 14s**。**未提交 git。未启动游戏。**

## 1. 根因

`RailWeather.openSky` 原实现是 `WorldView#isSkyVisibleAllowingSea(pos)`，它拿
`MOTION_BLOCKING` 高度图与探针 Y 比较 —— 回答的是「这一列方块里有没有<b>任何碰撞体</b>挡在
头顶」。接触网支架 / 吊柱 / 雨棚边梁都是全方块碰撞体：列车从下面开过的那几帧，这一列就被判成
「看不见天空」→ `lightsNeeded` 返回 true → 白天露天每过一根柱子车灯开一次关一次。

判定本身没写错，是**问错了问题**：车灯关心「这个位置有多亮」，不是「这一列有没有方块」。

## 2. 新判定公式（`common/.../RailWeather.java`）

```java
// 探针：车厢坐标抬高 PROBE_LIFT = 1.0 格（跳过轨面/轨枕那一格，那里恒为 0）
int level = world.getLightLevel(LightType.SKY, new BlockPos(x, floor(y + 1.0), z));  // 0..15, -1 = 读不到

boolean openSkyNow = level_nose >= 14 && level_mid >= 14;   // 车头 + 车中两点都要够亮
```

- 阈值 `SKY_LIGHT_MIN = 14`：露天 = 15；正上方 1 格宽的柱子 = 14（横向借邻列的光）→ 仍算露天；
  隧道 / 长棚 / 连续实体顶盖 = 13 以下 → 开灯。
- `-1`（未加载区块 / API 异常）按「露天」处理；`getLightLevel` 抛异常时退回旧的高度图判断。
- `getLightLevel(LightType, BlockPos)` 在 1.18.2 / 1.19.2 / 1.19.4 / 1.20.1 的 `World` 上同名
  同签名，`common` 里四版都能编译（本次只构建 1.20.1）。

### 去抖

```java
DEBOUNCE_TICKS = 10        // 10 tick = DEBOUNCE_MS = 500 ms
RailWeather.openSky(world, pose, trainId)   // 唯一给车灯用的重载
```

- 每列车（键 = `TrainClient#id`）一份状态：`value` = 已提交答案，`candidate` = 正在计时的原始采样。
- 原始采样要**连续 500 ms** 保持另一个值才翻转；中途回到原值或再次跳变则计时归零。
- 首次见到这列车直接采用原始值（不等待）；表上限 256，换世界时 `RailWeather.reset()` 清空
  （`LightController.tick` 的 world-change 分支）。
- 0.5 s 比 6 节车 60 km/h 通过一根柱子（≈0.36 s）长 → 柱子不再被看见；真进隧道最多晚半秒开灯。

## 3. 光柱同步（`common/.../ProtoBeamCone.java:459-470`）

原来的可见性闸只写在 **GLOW 源**分支里，而 `coneSource` 默认是 **MODEL**：

```java
// 旧：只在 glowSource == true 时才查
if (!DepartureState.departed(train) || !HeadlightSwitch.isLit(world, pose, cfg)) { gate(9); continue; }
```

于是白天露天自动关灯后：光带灭了、LDL 光源摘了、轨道烘焙释放了，**光柱还挂在车头**。

现在这道闸**提到两条源之外**（分支前），MODEL 与 GLOW 共用，并且带上同一个 `trainId`：

```java
// ProtoBeamCone.java:465
if (!DepartureState.departed(train)
        || !HeadlightSwitch.isLit(world, pose, cfg, train.id)) { gate(9); continue; }
```

四个消费方现在都是 `HeadlightSwitch.isLit(world, pose, cfg, train.id)`，同一个函数 + 同一个去抖键：

| 消费方 | 位置 |
| --- | --- |
| 光带 `BeamGlow` | `BeamGlow.java:290` |
| 光柱 `ProtoBeamCone` | `ProtoBeamCone.java:465-467`（新增，覆盖 MODEL 源） |
| 动态光源 `LightController` | `LightController.java:223` |
| 轨道烘焙 `RailRelightScheduler` | `RailRelightScheduler.java:395` |

per-siding 配置本来就同源：光柱用 `cfg.effectiveFor(train.sidingId)`（`eff.beamCone` / `beamDistance` /
`beamHeadLength` / 颜色），光带用同一个 `eff`。

## 4. 诊断行（`HeadlightSwitch.diagnose`）

```
[lte] auto light: mode=AUTO openSky=false night=false sky=15 skyMid=12 bright=1.00 lit=on
```

- `sky=` 车头探针的天空光照（0–15，`-1` = 读不到），`skyMid=` 车中探针；
- `openSky=` 是**去抖后**的结论，`lit=` 是最终答案（同一个 `isLit` 调用）；
- 旧的 `sky=0.20` 亮度改名 `bright=`，避免和光照等级混淆。
- 判读：白天露天健康态是 `sky=15 skyMid=15 lit=off`；`sky=15 openSky=false` = 车中在棚下或去抖
  仍在保持隧道结论；行驶中偶尔掉到 13 是接触网柱（去抖应当不让它翻转，若翻了，这一行就是证据）。

## 5. 产物

| 位置 | 字节 | md5 |
| --- | --- | --- |
| `v1.20.1/build/libs/lte-1.0.0.jar` | 746,296 | `f1feb3ca40d69cc2773a8c9399cd40ae` |
| 桌面 `lte-1.0.0+1.20.1.jar` | 746,296 | 同上 |
| 实例 `PCL/.minecraft/versions/1.20.1-Fabric 0.19.3/mods/` | 746,296 | 同上 |

已用 `javap` 核对 jar 内字节码：`RailWeather` 含 `skyLevel*` / `openSky(*,*,long)` / `reset()`，
并 `invokevirtual class_638.method_8314(class_1944, class_2338)`（= `World#getLightLevel(LightType, BlockPos)`）；
`ProtoBeamCone` 中有一处 `HeadlightSwitch.isLit`。

## 6. 改动文件

- `common/.../RailWeather.java`（重写露天判定 + 去抖）
- `common/.../HeadlightSwitch.java`（`isLit`/`diagnose` 增加 `trainId`；诊断行 `sky=`/`skyMid=`/`bright=`/`lit=`）
- `common/.../ProtoBeamCone.java`（光柱闸提到分支外，MODEL 源也受 `isLit` 约束）
- `common/.../BeamGlow.java`、`LightController.java`、`RailRelightScheduler.java`（传 `train.id`）
