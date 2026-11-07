**修复 1.19.x 上「优化完全没生效」的静默故障，并解决动态光源残留**

### 修复
- **1.19.4 / 1.19.2 / 1.18.2 用 ANTE 时，我们一个 mixin 都没应用**：此前四个版本里，1.19.x 只注册了 NTE 那套 mixin 配置（目标 `cn.zbx1425.mtrsteamloco.*`），而 ANTE 的 Fabric 包名是 `fabric.cn.zbx1425.mtrsteamloco.*`，探测失败后 `require = 0` 让整个过程完全静默 —— 轨道 Baking 光照与「每帧重建上限」同时失效
  - 现在**四个版本都同时注册 ANTE 与 NTE 两套配置**，各自独立探测，哪个宿主在就应用哪个
  - 启动日志新增判据：`rail mixin: ante=applied nte=none boost=3`
- **动态光源残留（1.18.2 / 1.19.2 / 1.19.4）**，两个成因都修了
  - 移除光源时旧位置没有被标脏：`scheduleTrackedChunksRebuild` 只标渲染线程上一次 50ms pass 的集合，移除时可能已过期或为空 → 改为按「最后位置 ∪ 跟踪集」无条件标脏
  - 光源被冻结在原地：`missingKeepTicks/missingDimTicks/missingDetachTicks`（30/60/120 秒）叠加 `near()` 防抖后门几乎恒为真，车驶出同步半径后会全亮度冻结最长 120 秒 → 已收紧
- **刷新线路 / 清除车辆后光源不消失**：整批消失快速通道 —— `missing ≥ max(2, ceil(tracked × 0.5))` 或列表整体清空时立即移除，单列消失仍保留宽限期

### 优化
- 每帧允许宿主重建的 rail chunk 数可配置（`railChunkBuildPerFrame`，默认 3，范围 1–8），带开关 `railChunkBuildBoost`
- `railRelightMaxPerSec` 默认 12 → 16

### 说明
- 纯客户端模组，服务器无需安装；ANTE / NTE / 方速均为可选依赖
