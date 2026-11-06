# LDL 动态光照 A/B 排查（MC 1.19.4 / LambDynamicLights 2.3.1）

用途：一次实机运行内判定“整个 LDL 停摆”到底是不是我们注册的光源造成的。
不需要改 JSON、不需要重启、不需要退世界。

## 1. A/B 开关

| 操作 | 效果 |
| --- | --- |
| `/externallights ldlreg off` | 注销我们的**全部**光源（LDL 的 `dynamicLightsources` 里只剩 LDL 自己的：玩家手持光源、实体、方块实体）。beam 光带、NTE 轨道烘焙、配置界面全部照常 |
| `/externallights ldlreg on` | 恢复注册（下一 tick 自动重建） |
| `/externallights ldl` | 只打印当前开关状态 |

等价于把 `config/mtr-headlight.json` 里的 `"ldlRegisterSources": false` 写盘后重进世界
（命令会同时写盘，所以用命令就够）。

## 2. 实机步骤（5 分钟）

1. 进存档，站在暗处，**手持火把**，确认现象：火把不照亮地面。
2. 执行 `/externallights ldlreg off`，等 2–3 秒（不要退出世界）。
3. 再看火把：
   - **火把恢复照亮** → 是我们的注册行为把 LDL 搞挂了 → 结论 A，把日志里的
     `ldl2 registry` / `ldlprobe` 两行发回来即可定位到具体哪一条约束被破坏。
   - **火把仍然不亮** → 与我们的光源无关（LDL 自己的光源都没了） → 结论 B，
     问题在 LDL 之下的烘焙/光照贴图链路，继续看第 4 步的 `brightened`。
4. 执行 `/externallights ldlreg on` 恢复，然后看日志（`logs/latest.log`）每 5 秒两行：

```
[mtr-headlight] ldl2 registry: ours=64 ldlTotal=65 ldlOthers=1 adds=64 removes=0 updates=...
ldlprobe: at=-191, 4, 470 ownLum=9 ldlLevel=8.39 ldlSources=65 ldlUpdates=8 bake=0x... in=0xf000f0 brightened=true
```

判定表：

| 观察 | 含义 |
| --- | --- |
| `ldlOthers = 0` 且此时手持火把 | LDL 里根本没有玩家光源 → 玩家光源没被 LDL 注册（不是我们清掉的，我们从不调用 `clearLightSources`/`removeLightSources`） |
| `ldlOthers > 0`，火把却不亮 | 玩家光源在集合里但没生效 → 引擎的更新/烘焙链路问题 |
| `ldlLevel ≈ 14`、`brightened=true`，世界仍然没光 | LDL 的取光与打包都正常 → 问题在**区块重建 / 光照贴图纹理**，不在我们注册的光源上（我们的光源值域已被钳制在 0..15） |
| `brightened=false`（`bake == in`） | LDL 认为该点没有动态光（模式没开 / 该点被判为不透明） |
| `ldl2: LDL dropped our sources (ours=.. engine=..)` | LDL 自己清空过集合（`MinecraftClient.setWorld` 会调用 `clearLightSources()`），我们已经自动重建 |

## 3. 这次代码里被硬化的约束（与上面日志字段一一对应）

- **不在 LDL 的回调里回调 LDL**：`updateAll` 持读锁调用我们，而
  `getDynamicLightLevel` / `getLightSourcesCount` / `containsLightSource` 都会再取同一把读锁；
  `ReentrantReadWriteLock` 在写请求排队时会把这次重入读永久挂起（我们自己的
  `addLightSource`/`removeLightSource` 就在 tick 线程上取写锁）→ 渲染线程死在 `updateAll` 里，
  之后 LDL 一个源都不再更新、任何区块都烘焙不上动态光，**包括玩家火把**。
  现在所有诊断都移到 `Ldl2Backend#tick()`（tick 线程，未持锁）。
- `getLuminance()` 恒返回 0..15（钳制），坐标非有限/越界时该点直接置 0（不写坏值）。
- 每个接口方法（含 getter）都 try/catch，异常返回安全值 —— LDL 的 `maxDynamicLightLevel`
  和 `updateAll` 都没有 try/finally，一次抛出就漏锁或中止整趟更新。
- `getDynamicLightWorld()` 恒返回当前客户端世界（LDL 在 `setWorld` 时会清空光源集合，
  返回旧世界会让 `containsLightSource` 永远为 false）。
- 每 2 秒对账一次 `getLightSourcesCount() < ours` → 自动重注册。
- `isDynamicLightEnabled()` 不再走接口默认实现（默认实现会取读锁）。
