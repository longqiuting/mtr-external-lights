# 铁轨发光：旧 lightmap 注入默认关闭（2026-11-14）

玩家反馈「铁轨照亮你似乎忘记去掉了，铁轨还在发光」。光柱删掉、换成光带/灯晕之后，**铁轨本身**
还被旧方案点亮——它不在光柱那条链路上，而是另一套「把列车灯烘焙进轨道顶点」的子系统。

## 旧链路：谁在给铁轨打光

| 类 | 作用 | 改后 |
|---|---|---|
| `mixin/ante/AnteBrightnessMixin`、`mixin/ante/AnteInstancedBrightnessMixin`、`mixin/nte/NteRailChunkBuildMixin` | `@Redirect` ANTE/NTE `MeshBuildingRailChunk#rebuildBuffer` 里的两次 `World#getLightLevel`，把动态光塞进轨道顶点的 lightmap | 保留（不改），但被下面的门阀挡住 |
| `RailLightInject` | 上面三个 mixin 的唯一逻辑落点：block 通道取 `max(world, RailDynamicLight.level)`，shadow 的 sky 通道按 `railLightShaderBoost` 复制 | **加门阀 `railLightOn()`**：`enabled && dynamicLight && railLight(或侧线)` 否则原值返回 |
| `RailDynamicLight` → `RailLightState` | `RailLightState.publish()` 的波束快照（7 double/段），按到线段的线性衰减给 0–15 | 不改；靠门阀 + 清状态生效 |
| `RailRelightScheduler` | 每 tick 轮询玩家与车头，`registerLightUpdate` 让 ANTE 重建轨道 chunk（30+ 配置项都在这条链路上） | 门阀不变（`railLightAnySiding()`）；**新增**：从「开」变「关」的那一 tick `reset()` + `RailRelightDispatcher.clearAll()`，让已经烘焙进顶点的光被重新构建掉 |
| `FangsuTurnoutLight` | 芳苏道岔 OBJ 每帧取一次光照（`RailLightInject.resolveQuiet`） | 它读的就是 `cfg.railLight`，默认关后一起停 |
| `mixin/beamlight/BeamLightMixin` + `BeamBlockLight` | `WorldRenderer#getLightmapCoordinates`：把光束烘进**原版地形顶点**（地面亮斑），**这个保留** | 新增 `state.getBlock() instanceof RailBlock` 提前返回：四种原版铁轨不再被地面亮斑点亮，其它方块照旧 |

结论：铁轨发光只有两条代码路径能造成——(a) 轨道顶点的 lightmap 注入，(b) 原版铁轨方块被
`beamBlockLight` 当普通方块点亮。两条现在都关掉了，(b) 保留给道砟/站台/隧道壁。

## 配置

- `HeadlightConfig#railLight`：`true` → **`false`**（字段注释写明「旧方案·铁轨自己会发光」）。
- `CONFIG_VERSION`：**22 → 23**（同批另一处「灯晕尺寸 0.9 → 1.4」的改动随后把它推到 24；
  本项迁移按 `if (configVersion < 23)` 判断，22 及更老的文件照样命中，两者互不覆盖）。
- `migrate()` 新增 `< 23` 分支：旧文件里的 `railLight=true` 一律改 `false` 并打一行日志；逐侧线
  `sidingSettings[*].railLight == true` 一并清成 `null`（= 跟随全局 = 关）。从 23 起玩家再打开
  会被保留（`configVersion` 已等于 23，这段不会重跑）。
- 开关本身保留，而且**第一次进了配置界面**：光源页「铁轨发光（旧）：开/关」一行
  （`ConfigWriteTarget.RAIL_LIGHT = 37`，全局可写、侧线可覆盖 + ↺ 回到跟随全局）。

## 铁轨不发光的判据（代码路径）

1. **烘焙期**：`RailLightInject#resolve` 前两行——`original = world.getLightLevel(...)`，随后
   `if (!railLightOn()) return original;`（javap 已确认 `invokestatic railLightOn` / `ifeq → ireturn`）。
   默认配置下 `railLightOn()` 是 `enabled && dynamicLight && railLight=false` → `false`，所以 ANTE/NTE
   重建出来的轨道顶点带的就是世界光照值本身。
2. **重光照期**：`RailRelightScheduler#tick` 顶部 `!cfg.railLightAnySiding()` → 直接 return；
   转换那一 tick 还会 `RailLightState.clear()`（`reset()` 里）+ `RailRelightDispatcher.clearAll()`
   （host 的 `clearRail()`），把已经烘焙过光的 chunk 逼迫重建一次。
3. **原版铁轨**：`BeamLightMixin` 在 `state.getBlock() instanceof RailBlock` 时 `return`，不写 block 半字节。
4. **道岔**：`FangsuTurnoutLight#enabled()` 读 `cfg.railLight`。

## 验证

- `gradle -p v1.20.1 compileJava` → BUILD SUCCESSFUL。
- `javap -c HeadlightConfig` 构造函数：`iconst_0; putfield railLight:Z`（默认 false）；
  `CONFIG_VERSION` 常量 23 出现在 `load`/`defaults`/`migrate` 四处。
