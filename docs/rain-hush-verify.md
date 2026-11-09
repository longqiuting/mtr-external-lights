# 验证报告：车厢内不画雨 / 车窗照亮三条路

一次性原型 + 实机验证，**不进 git、不进 release**。截图与完整日志在 `docs/rain-hush/`。

---

## A. 玩家在车内时全局不画雨

### A1. 1.20.1 的雨渲染入口（javap 实测）

| 东西 | yarn 名 | intermediary |
|---|---|---|
| 雨的绘制 | `net.minecraft.client.render.WorldRenderer#renderWeather(LightmapTextureManager, float, double, double, double)` | `class_761.method_22714(class_765;FDDD)V` |
| 地面水花（粒子） | `WorldRenderer#tickRainSplashing(Camera)` | `class_761.method_22713(class_4184)V` |

`javap -p -c` 里的关键事实：

* `renderWeather` 是 **private**，由 `WorldRenderer#render` 在**两处**调用（Fabulous 画质走 `RenderPhase.WEATHER_TARGET` 的 framebuffer，普通画质直接画）。所以注释只能挂在这个方法本身，挂调用点要挂两处。
* 方法体第一件事就是 `world.getRainGradient(delta)`，`<= 0` 直接 `return`；然后是 `Tessellator` + `BufferBuilder`，逐列查高度图、把雨/雪贴图写进**同一个批次**。**没有雨滴实体、没有粒子对象**——不存在「逐个剔除」的余地，只能整段不画。
* 雨和**雪**在同一个方法里靠 `Biome#getPrecipitation` 分流（SNOW 用雪贴图）。HEAD cancel 因此会连雪一起关掉。

refmap 已核（`unzip -p lte-1.0.0.jar lte.refmap.json`）：

```json
"renderWeather(Lnet/minecraft/client/render/LightmapTextureManager;FDDD)V"
  -> "Lnet/minecraft/class_761;method_22714(Lnet/minecraft/class_765;FDDD)V",
"tickRainSplashing(Lnet/minecraft/client/render/Camera;)V"
  -> "Lnet/minecraft/class_761;method_22713(Lnet/minecraft/class_4184;)V"
```

### A2. 「在车内」的判定

用与 `CollisionSystem` 同一套车厢几何（`TrainPose` 每节车两端点 + `trainCollisionHeight`），但判的是**包含**：玩家整个 AABB 必须被车厢盒包住。

* 竖直：脚底 ≥ 轨面 + 0.10，头顶 ≤ 轨面 + 高度 − 0.10 —— 站车顶的人头顶必然超顶，天然排除；
* 横向：|lat| + AABB 半投影 ≤ halfW − 0.05；
* 纵向：两端各留 0.3 格，避开车厢连接处的贯通道。

误差（写死在代码注释里，报告照抄）：

| 位置 | 现象 | 量 |
|---|---|---|
| 车门 | 站在门框平面上会被判「不在车内」，雨照下 | 模糊带 ≈ 0.35 格（门洞边缘往里一步） |
| 车厢连接处 | 两节车之间的贯通道正中间判「不在车内」 | 0.6–1.0 格宽的缝 |
| 车体模型 vs 碰撞盒 | 裙板/空调罩不在盒内，人身高 1.8 离两头都远 | 实际 0 |

### A3. 连带影响（逐个入口核对）

| 东西 | 受不受影响 | 为什么 |
|---|---|---|
| 雨丝 / 雪 | **一起关**（雪是被连带的） | 同一个 `renderWeather` |
| 地面水花 | 受 `rainHushSplash` 单独控制，默认也关 | 另一个入口 `tickRainSplashing` |
| 雨声 | **看是哪个开关**：`renderWeather` 的 cancel 不影响雨声；但雨声和地面水花同在 `tickRainSplashing` 里（`SoundEvents.WEATHER_RAIN` / `WEATHER_RAIN_ABOVE`，字段 `rainSoundCounter` 就在它里面），所以 `rainHushSplash=true`（默认）会**连雨声一起停** —— 关门的车厢里安静，通常是想要的；想留雨声设 `rainHushSplash=false` | |
| 天空压暗 / 雨雾 | 不受影响 | 不在 `renderWeather` 里，按 `getRainGradient` 走；截图 2 里天空仍是阴的、地面无雨 |
| 屏幕湿滑 / 镜头水滴 | 不受影响 | **原版没有**这个东西，是光影包的后处理；我们没碰雨量 |
| 水下效果 | 不受影响 | 另一条管线 |

### A4. 开关

`config/mtrlte.json`：

```json
"rainHush": true,
"rainHushSplash": true,
"rainHushDiagnostics": true
```

命令：`/mtr_lte rain`（状态）、`/mtr_lte rain on|off`、`/mtr_lte rain force on|off`（调试：无视几何强制认作在车内，不写盘）。

诊断行（5 s 一条，INFO）：

```
[lte] rain: frames=18 suppressed=4 splash=60 inside=true force=false switch=true
[lte] rain: inside=true train=mtr_custom_train_qst_tongchong_yzl9_lcd car=1 along=9.40/25.00 lat=0.01 feet=5.00 floor=4.00 roof=7.80
```

`frames` = `renderWeather` 注入点被调用次数（**恒为 0 = mixin 没落地或这条路径没走**），`suppressed` = 真正 cancel 掉的帧数。

### A5. 实机证据（1.20.1 实例，存档 = 「测试摄影专用」的副本 `selftest`）

运行方式：隔离 gameDir（`%TEMP%\mcboot-rain\game`，mods 里放本次构建的 jar）+ `-Dlte.raintest=true` + `--quickPlaySingleplayer selftest`。

| 截图 | 状态 | 结果 |
|---|---|---|
| `1_rain_outside.png` | 露天，`/weather rain`，force=off | 雨丝满屏 |
| `2_rain_forced_inside.png` | **同一机位**，force=on | 雨丝完全消失；天空仍阴、雨量仍 1.0 |
| `3_rain_back.png` | force=off | （截图取上一帧，本帧仍是抑制帧，见下） |
| `4_in_car.png` | 传送进**真实车厢**（`qst_tongchong` 车 1），几何判定 inside=true | 车内无雨丝 |
| `5_beside_car.png` | 同一列车旁 8 格，inside=false | 雨丝回来（这一对车内外只差位置） |
| `6_switch_off_in_car.png` | 车内 + `rainHush=false` | 开关关闭帧（日志证明） |

日志关键段（`run-rain3.log`）：

```
22:21:22 rain on, outside: gradient=1.0 inside=false ... frames=10 suppressed=0
22:21:23 forced inside: ... frames=11 suppressed=1
22:21:25 in car: inside=true ... along=9.40/25.00 lat=0.01 feet=5.00 floor=4.00 roof=7.80
22:21:29 beside car: inside=false ... frames=16 suppressed=4     <- 出车后不再抑制
22:21:30 switch off in car: inside=true ... frames=18 suppressed=4 <- 开关关掉后 frames 照涨、suppressed 不动
```

三个结论：① 注入点确实在跑（frames 在涨）；② 抑制只在 inside=true 时发生（suppressed 只在那时涨）；③ 关掉开关后同样的位置不再抑制（frames 15→18，suppressed 停在 4）。

**注意**：这台机器帧率只有 2–15 fps，`saveScreenshot` 取的是**上一帧**，所以「改状态 → 截图」要等 ≥15 tick，短了会拍到旧画面（截图 3 就是这样）。真要做成可复现的验收脚本，等待值给到 40 tick。

---

## B. 车窗照亮（评估，不做完整实现）

### B1. 能不能从资源包可靠拿到「车窗带」

分两种资源包列车，实测统计（实例 `resourcepacks/` 全量）：

**① MTR 原生 JSON 列车（`assets/mtr/custom_directory/*.json`）：38 个文件 / 5 个包**

* 37/38（97%）声明了名字含 window/glass 的 part —— **但 JSON 里没有任何几何**：
  字段只有 `name, stage, mirror, skip_rendering_if_too_far, door_offset, render_condition,
  positions, whitelisted_cars, blacklisted_cars`，`positions` 是贴图图集里的 UV 裁切（如 `[[0,-128],[0,-64],…]`）。
* stage 直方图（按 part 计）：INTERIOR 1796、EXTERIOR 1596、ALWAYS_ON_LIGHTS 276、LIGHTS 306、**INTERIOR_TRANSLUCENT 156**。
* **`INTERIOR_TRANSLUCENT` 里只有 4 个名字**：`side_panel_translucent`、`side_panel_sp1900_translucent`、`number`、`routes` —— **不是车窗**。车窗在 `stage=INTERIOR`（`window`、`window_2`…）与 `EXTERIOR`（`window_exterior_*`）。
  → 「用 partsInfo 里 INTERIOR_TRANSLUCENT 的盒子当车窗」这个假设**不成立**。

**② ANTE/NTE 的 bbmodel 列车：77 个 `.bbmodel`**

* 42/77（55%）有名字含 window 的**组**；
* 但组名不可信：`qst.bbmodel` 里名为 `window` 的组，内容是 `floor / lower_wall / upper_wall`（**整个车内壳**），y 从 −1 到 33（模型 −14.5…69.2）——按名字取会把整节车厢内壁都点亮；
* 只有 17/77（22%）有名字含 glass/window 的**元素**，其中 15 个是「薄板」形状；
* 这 15 个的玻璃带，按「地板→车顶」归一化后是 **0.09–0.77**（中位中心 0.40），按整个包围盒是 0.11–0.41 … 0.30–0.60 —— **带子很宽**（一个盒子罩住整条侧窗），不是窄条。

**③ 运行时 `partsInfo` 也不普遍**

`VehicleLights.modelOf()` 在实机里对测试世界的 4 列车**全部返回 null**：

```
mtr_custom_train_amtr_line_4_sh_2d_xm  renderer=ScriptedTrainRenderer  dynamicModel=NO
mtr_custom_train_s_train_pth_3         renderer=JonModelTrainRenderer  dynamicModel=NO
minecart                               renderer=JonModelTrainRenderer  dynamicModel=NO
mtr_custom_train_xmlcd_test_5d         renderer=JonModelTrainRenderer  dynamicModel=NO
```

即 `DynamicTrainModel.partsInfo` 只对「动态模型」那一种列车可用，**普通资源包列车（JonModel 渲染、模型不是 DynamicTrainModel）读不到**。车灯那套能靠 partsInfo，窗带不能直接照搬。

**结论**：
* 宁可靠 **bbmodel/元素的 from-to**（15/77 有），也拿不到时就只能按「车体高度比例」估；
* 现有「车体高度 55%–85%」这个估值**偏高**——实测玻璃带上沿落在 0.65–0.77，55–85% 会有一截落在车顶/空调上；
* 更合理的兜底是 **0.35–0.70**，并且**允许逐车型覆盖**（配置里按 trainId 存 band）。

### B2. 三条路的成本与观感风险

| 路 | 做法 | 成本 | 观感风险 | 判 |
|---|---|---|---|---|
| 1. 自绘发光带 | 沿车厢上部画半透明加色四边形（`Tessellator` + `RenderSystem.setShader(GameRenderer::getPositionColorProgram)`，与现有 `BeamGlow` 同一套提交方式），高度取 band | **中低**：一个渲染类 ~200–300 行 + 配置；复用 `BeamGlow` 的相机相对坐标/状态恢复写法 | 贴片感（skill 里记过「完全塑料片」的教训）：平面带在弯道/俯视时会被看出是片；**但**车窗是平的，贴在窗面上比贴在轨道上可信得多。用 `depthMask(false)` + 加色混合 + 低 alpha（0.10–0.18）能压住塑料感 | **推荐** |
| 2. 烘焙进模型亮度 | mixin 车体渲染，抬高高部顶点 brightness | 中：`AnteBrightnessMixin` / `AnteInstancedBrightnessMixin` 已在，加一条「按顶点 y 抬亮」改动小（~80 行） | 三个坑：① 车顶/车头同样被抬亮（顶点 y 判据分不出窗和车顶）；② brightness 是**光照图乘子**，上限 1.0 → 只能做到「不发光的白墙」，做不出「淡淡发光」；③ Iris 下 ANTE 自建 VAO 不绑 UV2 时整条属性读不到（skill 已记：全暗而非惨白），**开光影即失效** | 备选，不推荐做主线 |
| 3. 屏幕空间 | 后处理 | 高：framebuffer + 深度重建 + 与 Iris 合成顺序打架 | 与光影冲突、分辨率/缩放敏感 | **不做** |

### B3. 推荐

**走 1（自绘发光带）**，数据分三级：

1. 有 bbmodel 元素几何 → 直接用玻璃元素的 y 范围（覆盖 15/77）；
2. 没有 → 用「车体高 0.35–0.70」的带，允许 `config` 按 trainId 覆盖；
3. 亮度/宽度/alpha 进配置（`windowGlow`, `windowGlowAlpha`, `windowGlowMinY/MaxY`, `windowGlowColor`）。

### B4. 需要改的文件（如果做）

| 文件 | 改动 |
|---|---|
| `common/.../WindowGlow.java`（新） | 画带：取车厢位姿、按 band 生成两侧四边形、加色混合、相机相对坐标 |
| `common/.../HeadlightConfig.java` | 4–5 个字段 + 钳制 |
| `v1.20.1/.../RenderHooks.java` | 注册 `WorldRenderEvents.AFTER_TRANSLUCENT` 第二个绘制（与 `BeamGlow.render(context, true)` 同批） |
| `common/.../VehicleLights.java` | 复用其 `Layout` 读组/元素高度（可选，取决于要不要 bbmodel 数据） |
| `v1.20.1/.../ParameterScreen.java` | 开关与滑条（可选） |

---

## 本次改动（原型，**未提交**）

新增：
* `v1.20.1/src/main/java/dev/js/mtrheadlight/RainHush.java` — 判定 + 计数 + 强制开关
* `v1.20.1/src/main/java/dev/js/mtrheadlight/mixin/rain/WorldRendererRainMixin.java` — 两个 HEAD cancel
* `v1.20.1/src/main/resources/mtrlte.rain.mixins.json`
* `v1.20.1/src/main/java/dev/js/mtrheadlight/RainTest.java` — 临时的实机验收脚本（**release 前删**，与 `SelfTest` 同性质）
* `docs/rain-hush/` — 6 张截图 + 完整日志

修改：
* `common/.../HeadlightConfig.java` — `rainHush` / `rainHushSplash` / `rainHushDiagnostics`
* `v1.20.1/.../Commands.java` — `/mtr_lte rain [on|off|force|status]`
* `v1.20.1/src/main/resources/fabric.mod.json` — 注册 rain mixin 配置 + `RainTest` entrypoint

## 复测方式

1. 构建：`bash tools/build-all.sh v1.20.1`
2. 把 jar 放进隔离实例的 `mods/`，启动加 `-Dlte.raintest=true --quickPlaySingleplayer <测试存档>`
3. 看日志 `[lte] raintest:` 与 `[lte] rain:`；截图落在 `<gameDir>/screenshots/`
4. **同一时间只允许一个客户端**（16 GB 机器，两个并发会 `mmap failed for G1 virtual space` / 0xC0000005，那不是 bug）
