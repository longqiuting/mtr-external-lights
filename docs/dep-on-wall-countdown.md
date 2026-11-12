# 挂墙发车倒数器（`mtrlte:dep_on_wall`，仅 1.20.1）

钉在**站台头端**的墙上，是一块**发车倒数器**（不是 PIDS）：平时屏幕空着，列车到站
**之后**才跳出「0:09」这样一路数到发车时刻。数据全部在客户端从 MTR 的
`ClientData` 现算，**服务端一次都不碰**，方块实体不落任何 NBT。

## 行为（数值逐行照抄 JCM `jsblock:departure_timer` 的算法）

```text
1. RailwayData.getClosePlatformId(ClientData.PLATFORMS, ClientData.DATA_CACHE, pos, 5, 3, 3)
      → 离这块牌子最近的站台（0 = 附近没有站台，屏幕上不写字）
2. ClientData.SCHEDULES_FOR_PLATFORM.get(platformId)          // null → 这一帧没数据
3. new ArrayList<>(...) → Collections.sort（按 arrivalMillis 升序，同刻再按 routeId）
4. 取「第一条还没走完」的班次 = 这块牌子锁定 / 要锁的那一条（见下一节）
      secRaw = (arrivalMillis - now) / 1000                   // ≤ 0；先减再除，向零截断
      secRaw > 0  → 车还没到站，整块空屏（直接 return，不是显示 0:00）
      dwellHalf = platform == null ? 0 : platform.getDwellTime() / 2     // 整数除法
      remain = |dwellHalf - |sec||
```

数字一律是上面那个 `remain`，一个符号都没改。上一版那条「遍历 `ClientData.TRAINS` 读列车
真实停站进度」的主路径**已经整段删掉**（`getElapsedDwellTicks`、用 `railProgress` 反推停站
索引那一套全没了），屏幕上没有第二套计时来源。

`arrivalMillis` 是**到站时刻**。MTR 实际停站 `dwellTime / 2` 秒（`Train#getTotalDwellTicks()`
= `dwellTime * 10` tick，20 tick = 1 秒），所以 JCM 拿 dwell/2 当基准，数到 0 正好是发车点。

两层 `abs` 的效果：到站那一秒 `remain = dwellHalf`，之后递减到 0；过了 0 又递增回来 ——
递增段正好就是「已经晚了几秒」，状态 ③ 直接复用这个数，不另开计时。

只有**颜色 / 阶段**是本模组的扩展（数值仍是 JCM 的 `remain`）：

| 阶段 | 条件 | 显示 |
| --- | --- | --- |
| ② 倒数 | `abs(sec) < dwellHalf` | 绿色 `#00E676`，`%02d:%02d` 递减 |
| ③ 晚点 | `abs(sec) >= dwellHalf` | 红色 `#FF0000`，正计 |
| ④ 清屏 | `remain > 5`（等价于 已过秒 > `dwellHalf + 5`） | 空屏，放锁等下一班 |

JCM 自己会一路数回 `dwellHalf`、永不变红也不熄；这里只留红色那 5 秒，数字数到 `0:05`
就黑屏。顺带把 JCM 的一个坑挡掉了：它只认排序后的第 1 条、过期了照样拿它算，
客户端数据还没刷掉时屏幕上会挂一个越来越大的数字。

## 锁一条班次（2026-11-12 修「2 突然变 5」）

**实机现象**：绿字数到 `0:02`，突然跳回 `0:05` 接着数，下一推再跳一次，一整个停站没有一次
完整的倒数。

**原因**：MTR 的 `SCHEDULES_FOR_PLATFORM` 是服务端**周期性整表推送**的
（`updateSchedule` → `clearAndAddAll`）。每次推送都可能重算同一条班次的 `arrivalMillis`
（列车晚点 / 停站重估）、删掉刚发车的条目、或整个换掉集合。JCM 每帧现取 `list.get(0)`，
于是倒数途中「第 1 条」会换人 / 换时刻：到站时刻被推后 3 秒 → `|已过秒|` 变小 3 →
`remain` 从 2 弹回 5。屏幕上是跳变，本质是**换了基准**。

**修法**：把「用哪一条」从每帧的 `list.get(0)` 变成**锁在方块上的一个 `arrivalMillis`**
（`DepOnWallLock`，存在 `DepOnWallBlockEntity.depLock`，纯客户端、不进 NBT）：

- 锁定键 = `arrivalMillis + routeId`（和 `ScheduleEntry#compareTo` 的排序键一致）；
- `dwellHalf` 一起冻进锁里 —— 站台数据被重推也不换基准；
- **一旦开始倒数就不换人**，服务端怎么推都只数这一条，直到它自己走完（绿 `dwellHalf` 秒 +
  红 5 秒）自动放锁；
- 屏幕还空着（车没到站）时没有可见读数，这时才允许跟着新的第一条改锁 —— 早到的车 /
  新插入的班次照旧能在到站前抢位，且不产生任何数字跳变；
- 选班时跳过**已经过了显示窗口**的条目，所以「刚放锁那条」不会被重新选回来
  （否则会放锁→重锁→再放锁，每帧一次）。

**单调保护**（同一个 `DepOnWallLock`）：绿段只减不增、红段只增不减、开过车不再回到空屏。
`System.currentTimeMillis()` 同一 tick 内多次读、或系统对时 / 回档倒退时，读数是「停住」
而不是往回跳；正常走时公式本来就单调，钳制一个数都不改。

## 取证：`/mtr_lte dep debug on|off`

默认关，只在内存里（退出游戏失效）。打开后**每块牌子每秒一行**：

```text
[lte] dep: pos=(12,64,-30) platform=47 entries=3 firstArrival=1733891234000 now=1733891235123 \
           sec=-12 dwell=20 dwellHalf=10 remain=0 phase=red lockArrival=1733891234000
```

- `firstArrival` = 这一帧排序后**第 1 条**（服务端刚推来的表）；`lockArrival` = 牌子**实际锁定**
  的那条。两者不一致 = 表已经换人而屏幕仍在数老的那条（锁在起作用）；
- `sec` = 锁定班次的 `(arrivalMillis - now) / 1000`；`phase` = `green / red / blank`；
- 再看 `dwellHalf` 和 `now`：前者变了 = 站台数据重推，后者倒退 = 时钟跳了。
  三种可能一次分清，不用猜。

## 几何：文字为什么在这个位置

模型（`assets/mtrlte/models/block/dep_on_wall.json`，素材原样）里那块发光板是**元素 2**：

```text
from [2, 4, 12.3]  to [14, 12, 12.3]     零厚度单面，north 面 uv = [3, 0, 6, 2]
```

也就是屏幕平面 = `x 2..14, y 4..12, z = 12.3`（1/16 格），正面朝 **north**；
盒子本身占 `z 12..16`，所以背面贴墙、正面朝外。

渲染器把这块面当成 **96 × 64 像素**的屏幕（= 贴图里 uv `3..6 × 0..2` 那 96×64 个点），
局部坐标「GUI 风格」：`+X = 屏幕向右、+Y = 屏幕向下、+Z = 扎进屏幕`，原点 = **屏幕正中**
（模型空间的 `(8, 8, 11.96)/16`，x ∈ [-48, +48]、y ∈ [-32, +32] px）。

```text
translate(0.5, 0.5, 0.5)                         方块中心
rotateY(-blockstate 的 y 角)                      与模型同一套旋转（north 0 / east 90 / …）
translate(0, 0, 11.96/16 - 0.5)                  屏幕正中（只有 z 要挪到边框前面那一层）
multiply(right | down | into 三个基向量)           正交、行列式 +1 = 纯旋转，不含镜像
scale(1/128)                                     96 px → 0.75 格，64 px → 0.5 格
```

数字层再单独 push 一层：`translate(0, 0, TEXT_LAYER_Z)` → `[scale(±1, ±1, 1)]`（方向开关）→ 画数字。
所以**翻转永远只作用于数字，底图一个像素都不动**。

> **读数方向（第 4 次返工，2026-11-12）**：前三版都靠「离线复刻矩阵 → 改一个符号（`rotateZ(180)`
> 加不加、`scale(-1,1,1)` 加不加）→ 重编发版」，三次实机都还是反的。这一版不再对角度做加减：
> 渲染器直接把三个基向量算出来 —— `out = 模型空间里的屏幕法线 (0,0,-1)`、`right = up × out`、
> `down = -up`、`into = -out`，拼成的 3×3 正交且**行列式 = +1**，也就是纯旋转、不可能把字形镜成
> 「每个字都反着写」。局部 +X 在世界里的去向由离线核对脚本（`temp/DepOrientCheck.java`，未入仓）
> 逐朝向算过，屏幕四角也逐朝向下和上一版 `ANCHOR_X = 14/16` + `rotateZ(180)` 的写法对过：
>
> | facing | +X 世界方向 | 玩家站位 | +X 是玩家的 |
> | --- | --- | --- | --- |
> | north | 西 (-X) | 北侧朝南 | 右手 ✔ |
> | east  | 北 (-Z) | 东侧朝西 | 右手 ✔ |
> | south | 东 (+X) | 南侧朝北 | 右手 ✔ |
> | west  | 南 (+Z) | 西侧朝东 | 右手 ✔ |
>
> 屏幕四角与上一版（`ANCHOR_X = 14/16` + `rotateZ(180)`）逐朝向下完全重合 —— 换写法没有挪动屏幕。
>
> **实机仍然不对时不要改矩阵**：用游戏内客户端指令现场翻，四档绕屏幕正中缩放，改完下一帧生效：
>
> ```text
> /mtr_lte dep           切下一档：normal → mirror → flip → rotate → normal
> /mtr_lte dep status     看当前档位
> /mtr_lte dep mirror     点名切（normal / mirror / flip / rotate）
> /mtr_lte dep debug on   取证日志（每块牌子每秒一行 [lte] dep: …，见上）
> ```
>
> 当前档位落在 `config/mtrlte-dep-orient.json`（`{"version":2,"mode":"mirror"}`）。配置文件带
> `version`：改了 `DepOnWallOrient.DEFAULT` 就把 `CONFIG_VERSION` +1，老配置一律作废 ——
> 否则玩家上一版存下来的结论会一直盖住新默认（「改了默认、实机却没变」）。
> **2026-11-12 实机**：Y-180 基（`normal`）方向对了但左右镜像，所以默认改成 `mirror`。

`1/128` 不是拍脑袋：`96 px / 0.75 格 = 128`，反过来 64 px 正好 = 0.5 格 = 模型里的 8/16。
全部数字集中在 `DepOnWallLayout`，改排版只改那一个文件。

## 排版

屏幕上只有**一行**七段数码管（`mm:ss`），永远居中：整串水平居中、竖直居中，
字高取 `min(可用高, 可用宽 / 整串宽)` —— 位数变了自动缩，不溢出屏幕。

| 项 | 值 |
| --- | --- |
| 字高 | 四位 `00:00` 约 35 px（宽度先到顶）；字高上限 54 px |
| 左右留白 | `INSET_X = 4 px`（可用宽 88 px） |
| 颜色 | 倒数中亮绿 `#00E676`；过点后红色 `#FF0000` |
| 字宽 / 段厚 / 字距 / 冒号宽 | 0.50 / 0.13 / 0.08 / 0.18（都以字高为单位） |

底图是**自发光**的（`RenderLayer.getEntityTranslucentEmissive` + 同一块 uv，整块压在边框正面
之前 0.04/16 格，见下），所以夜里也是一块亮屏 —— 和厦门 PIDS 一个做法。没车的时候只画这块底图
（一块暗红点阵的 LED 板），一个字都没有。

> **几何修订（2026-11-12）**：底图 + 数字从 `z = 12.28/16`（缩在 0.3/16 深的口袋里，
> 在边柱正面 z = 12 之<b>后</b>）改到 `z = 11.96/16`（边柱正面之<b>前</b> 0.04/16）。
> 原来的位置会和模型自己的发光板（z = 12.3）z-fighting、斜看时被边柱削掉 —— 就是
> 「DEP 文字被模型挡住」。数字层再往前推 1.5 px（`TEXT_LAYER_Z`），保证底图后画也盖不住它。

## 文件

| 路径 | 作用 |
| --- | --- |
| `xiamen/dep/DepOnWallBlock.java` | 方块：朝向、贴墙校验、四向碰撞盒 |
| `xiamen/dep/DepOnWallBlockEntity.java` | 方块实体（只存客户端那份 `depLock`，无 NBT） |
| `xiamen/dep/DepOnWallLock.java` | 锁定的一条班次 + 单调状态机（绿只减、红只增、走完放锁） |
| `xiamen/dep/DepOnWallRenderer.java` | BER：取站台 / 选班 → 底图 + 一行数码管 |
| `xiamen/dep/DepOnWallDebug.java` | `/mtr_lte dep debug` 的取证日志（每块牌子 1 行/秒） |
| `xiamen/dep/DepOnWallLayout.java` | 全部几何 / 排版 / 颜色常量 |
| `xiamen/dep/DepOnWallDigits.java` | 七段数码管字形（按字高现算四边形，不碰 GL 状态） |
| `xiamen/dep/DepOnWallOrient.java` | 读数方向四档 + `/mtr_lte dep` 的落盘状态 |
| `xiamen/dep/DepOnWallObjects.java` | 方块 / 物品 / 方块实体类型 / 创造页签 |
| `xiamen/dep/DepOnWallClient.java` | BER 注册 + cutout 渲染层 |
| `blockstates/` `models/` `textures/block/dep_on_wall.png` `loot_tables/` | 资源 |

注册挂在 `LteServer`（`XiamenObjects` 之后）与 `fabric.mod.json` 的 `client` 入口（`PidsXiamenClient` 之后）。

## 放置规则

- 点墙的水平侧面放置：`facing = 点的那一面`，牌子贴在那面墙上；
- 背后不是完整实心面 → 放不下（`getPlacementState` 返回 `null`，`canPlaceAt` 再兜一道）；
- 背后那格被拆 → 自己变空气（走邻居更新，和原版火把一样）。
