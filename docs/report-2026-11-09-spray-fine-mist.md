# 水雾粒子：调细 / 调密 / 缩短寿命（1.20.1）

日期：2026-11-09　范围：`v1.20.1`（`SprayParticle` + `RainSpray`），未提交 git。

## 起因

用户反馈水雾粒子「太粗」：单颗 0.55–1.05 格、活 30–50 tick，看起来是几团贴不住车体的大白雾，
而不是原版雨滴那种细密水点；拖尾也长，100 km/h 时像一条雾带。

## 最终参数

### 单颗粒子 `SprayParticle`

| 参数 | 旧值 | 新值 |
|---|---|---|
| 起始尺寸 `SCALE_MIN–SCALE_MAX` | 0.55–1.05 格 | **0.12–0.28 格**（≈2–4 px） |
| 收缩 `SHRINK` | 0.55（终值 45%） | 不变 |
| 寿命 `MIN_LIFE–MAX_LIFE` | 30–50 tick（1.5–2.5 s） | **10–18 tick（0.5–0.9 s）** |
| 起始透明度 `ALPHA_MIN–ALPHA_MAX` | 0.62–0.86 | **0.45–0.60** |
| 竖直初速 `DRIFT_MIN–DRIFT_MAX` | 0.01–0.03 | **0.01–0.02** |
| 重力 / 水平阻尼 / tint | 0 / 0.90 / (0.82,0.90,1.00) | 不变 |

### 生成侧 `RainSpray`

| 参数 | 旧值 | 新值 |
|---|---|---|
| 每列车每 tick 上限 `MAX_PER_TRAIN_PER_TICK` | 3 | **5** |
| 全局每 tick 上限 `MAX_PER_TICK` | 10 | **16** |
| 令牌桶 `TOKENS_PER_TICK` / `TOKEN_CAP` | 4 / 12（≈80 个/秒） | **10 / 30（≈200 个/秒）** |
| 每 tick 扫描车厢 `MAX_CARS_SCANNED` | 4 | **6**（20 节编组 4 tick 覆盖一遍） |
| 每车抽签概率 `spawnChance` | 0.18 + 0.82·i | **0.25 + 0.75·i** |
| 水平初速扩散 | 无（每事件常量） | **每颗 ±0.05 blocks/tick** |
| 竖直初速 | 0.01–0.03 | **0.01–0.02** |
| 最小档抖动 `MINIMAL_JITTER` | 0.45 | 0.30 |
| 最小档概率乘数 `MINIMAL_CHANCE_SCALE` | 0.45 | 0.55 |

令牌桶仍是总闸：三层上限同时生效，任何一层都不看帧率。

## 诊断

`[lte] spray:` 汇总行保留 `spawned=`，新增 `avgSize=`：

```
[lte] spray: trains=1 spawned=37 skipped=4 avgSize=0.199 raining=true weather=true grad=1.00 particles=ALL sprayType=self culled=none
```

`avgSize` 不是常量取中值，而是 `SprayParticle` 在构造时累加的真实起始尺寸均值（千分位整数累加，
窗口结束时由 `RainSpray` 读走并清零）；走 `minecraft:cloud` 回退时无样本，打 `-`。
期望值约 0.20（0.12–0.28 均分）。窗口 100 tick。

## 预期观感

- 稳态存活粒子：单列车受 5/tick 上限约束 ≈ 5 × 14 ≈ 70 颗；旁边两列车以上时由令牌桶 10/tick 封顶 ≈ 140 颗。
  全部落在车体侧面外 0.10–0.70 格、轨面 0.30–1.05 格，每颗 ≈2–4 像素、半透明 0.45–0.6，
  叠起来是「雾状的细密水点」，不再是几团白块。
- 单颗只活半秒多，拖尾比原来短 60–70%：高速时沿车侧看到的是连续的细小水点在闪，而不是一条拉长的雾带。
- 每颗水平方向 ±0.05 随机扩散，不会排成一条整齐的虚线。
- 总亮度下降（alpha 上限 0.86→0.60），水雾不再压过车灯；数量补回来的是「细」而不是「亮」。
- 最小档（`MINIMAL`）仍是 4 颗一团 + force，观感从「一大团」变成「一小撮」，数量上因概率乘数 0.45→0.55 略增。

## 验证

- `gradle clean build` → BUILD SUCCESSFUL（仅 `RailRelightScheduler` 既有的 unchecked 警告）。
- `javap -constants` 核对 class 内常量：MIN_LIFE=10、MAX_LIFE=18、SCALE 0.12/0.28、ALPHA 0.45/0.6、DRIFT 0.01/0.02。
- 产物 `lte-1.0.0.jar`（501,857 B，sha256 `5769ae6b…915a`）已覆盖到桌面与实例 `mods/`，三份哈希一致。
- 未启动游戏。
