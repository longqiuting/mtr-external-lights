# 厦门 PIDS：刷子界面 / 仪表板全局开关 / 本地时间 / 即将进站 / 广告贴边 / 去光晕

范围：`v1.20.1` 的 `dev/js/mtrheadlight/xiamen/pids/`、`ConfigScreen`、`LteServer`、lang、`fabric.mod.json`（未改）。
`compileJava` 通过；未进游戏、未 commit。

## 1. 刷子打开「站台 / 广告」界面

`PidsXiamenBlock#onUse`：手持刷子时不再走 MTR 的
`PacketTrainDataGuiServer.openPIDSConfigScreenS2C`（那是「每行一条自定义文字 + 隐藏到站」的界面，
厦门的固定版式用不上），改由本模组接管：

```
刷子右键（服务端）
  → syncData() 两半            // 先把 platformIds / displayPage 推给客户端
  → S2C mtrlte:pids_xiamen { OPEN, dataPos, partnerPos }
  → 客户端 setScreen(new PidsXiamenConfigScreen(...))
```

界面内容（`PidsXiamenConfigScreen`，`ScreenMapper`）：

| 控件 | 作用 | 数据通路 |
|---|---|---|
| 自动检测附近站台 | 勾上 = `platformIds` 清空（MTR 语义） | `sendPIDSConfigC2S` |
| 选择站台… | 复用 MTR 的 `PIDSConfigScreen.getPlatformFilterButton` + `DashboardListSelectorScreen` | 同上 |
| 显示页码 | 1 起的输入框，关界面时写回 | 同上 |
| 上一张 / 下一张 | 广告序号 ±1，立刻生效 | `mtrlte:pids_xiamen` C2S `{ AD, pos, index }` |

- 关界面（完成 / ESC）统一发一条 MTR 的 PIDS 配置包，`messages` / `hideArrival` 原样带回
  （渲染器不用它们，但别把 MTR 里的数据清掉）。
- 广告序号是本模组自己的 NBT 字段，MTR 的包不认，所以单独一个 C2S；服务端只改「这个坐标上真是我们的
  PIDS」且玩家在 8 格内，然后 `syncData()`。
- 客户端没装 LTE（通道不认）时退回 MTR 自己的 `PIDSConfigScreen`，不会让刷子点了没反应。
- 广告按钮的「第 N / M 张」按渲染器同一算法 `(时间轮播 + 手动序号) % 总数` 算，点一下不会跳格。
- 关界面时**只有真的改过**站台 / 页码才发包：方块实体还没同步到客户端时，无条件发一条空配置
  等于一按刷子就把绑定清成「自动检测」。

## 2. 仪表板全局开关

LTE 仪表板（`ConfigScreen`，第 4 个入口「厦门 PIDS」）→ `PidsXiamenSettingsScreen`，三个全局开关：

| 开关 | 开 | 关 |
|---|---|---|
| 本地时间 | 时钟 / 日期 = 现实时间 | 时钟 / 日期 = 世界时间（第 0 天 = 2024-01-01） |
| 即将进站 | 到站前一分钟内写「即将进站」 | 继续写「X 秒 / X 分钟」 |
| 广告 | 右侧广告区轮播 | 只留空白底图 |

- 存在 `config/lte_xiamen_pids.json`（`PidsXiamenSettings`）：**没有**动 `common/HeadlightConfig`，
  所以 1.18.2 / 1.19.2 / 1.19.4 的配置格式不受影响。
- 点一下立刻写内存 + 落盘，不进主界面的「完成 / 取消」快照（它改的是显示偏好，不是世界数据）。
- 站台 / 页码 / 广告序号仍然是**每块屏**的（方块实体 + 世界存档），位置不变。

## 3. 本地时间

`formatTime` 以前是世界钟点（24000 tick → 24h），日期却是 `LocalDate.now()` —— 一半现实一半世界。
现在两者同一个时间源：`PidsXiamenSettings.localTime()` 为真时用 `LocalTime.now()` / `LocalDate.now()`，
为假时都回落到世界时间（`world.getTimeOfDay()` / `EPOCH + days`）。BRT 的「MM月DD日星期X」同样走 `dateNow`。

## 4. 即将进站

- 语言键 `gui.mtrlte.pids.arriving` 从「即将到站」改成 **「即将进站」**（en：`Arriving`）。
- 阈值 15s → **60s**：最后**一分钟**写「即将进站」而不是「45 秒」；关掉开关时回到「X 秒 / X 分钟」。
- 已过到点时间仍旧写「已到站」；倒计时不会出现负数。

## 5. 广告贴边

广告矩形改成「把底图上那块空白画布四边填满」（`AD_KEEP_ASPECT = false`，直接拉伸）：

| | 旧 | 新 | 依据 |
|---|---|---|---|
| 地铁 | `170,34 – 501,197` | `170,34 – 512,199` | 徽章条最后一行像素是 y=33；标语框白色上边框在 y=199；右侧蓝色一直铺到屏幕边缘（旧值 501 留了 11px 蓝缝） |
| BRT | `178,9 – 504,176` | `179,10 – 504,176` | 卡片外框 178/504、9/176 是 1px 描边，内部可画区是 179..503 / 10..175 |

地铁左边界仍是 170（= 左栏外沿 169 再让 1px，不盖白描边）。

验证：`temp/pids_ad_fit.js` 把这两块矩形涂成半透明红叠在底图上（`temp/out_ad_metro.png` /
`temp/out_ad_brt.png`），四边贴齐、BRT 的 1px 边框仍然可见、不压到下方标语卡片。

## 6. 去光晕

- 删掉 `drawGlow()`、它的调用、6 参数 `drawQuad(..., alpha)` 与 `PidsXiamenLayout.GLOW_*`。
- 屏幕本身是自发光层（`RenderLayer.getEntityTranslucentEmissive`，满亮度、写深度），
  只剩「一块亮屏」，不再向外扩一圈加色光。

## 新增 / 修改文件

- 新：`PidsXiamenSettings.java`、`PidsXiamenNet.java`、`PidsXiamenConfigScreen.java`、
  `PidsXiamenSettingsScreen.java`
- 改：`PidsXiamenBlock.java`（onUse）、`PidsXiamenClient.java`（收 OPEN 包）、`PidsXiamenRenderer.java`
  （时间 / 倒计时 / 广告开关 / 去光晕）、`PidsXiamenLayout.java`（广告矩形 / 去 GLOW）、
  `server/LteServer.java`（注册 C2S）、`ConfigScreen.java`（第 4 个入口）、`lang/zh_cn.json`、`lang/en_us.json`

## 待确认（假设）

「仪表板全局开关」按「LTE 仪表板里的全局显示开关」实现（三个开关）。若本意是别的（例如 MTR 的
铁路仪表板方块里加开关），只需要把 `PidsXiamenSettingsScreen` 的入口换掉，开关本体与持久化不用动。

---

> **后续修订（2026-11-12 晚）**：「厦门 PIDS」入口从主界面第 4 个按钮搬到 **全局配置 → 其他**
> （主界面只剩四个入口）；「立即重载光源」从「其他」搬到「光源」。见
> `docs/report-2026-11-12-pids-dep-leftovers.md`。
