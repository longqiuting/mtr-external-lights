# 厦门 PIDS 滚动标语格式

> 状态：**接口已定，文案待你填**。改这个文件不需要重新构建模组，资源重载（F3+T 或重启）即可生效。

## 文件位置

```
assets/mtrlte/pids_xiamen_ticker.json
```

放在模组 jar 内（`v1.20.1/src/main/resources/assets/mtrlte/pids_xiamen_ticker.json`），或者放进任意资源包的同路径覆盖它。

之所以放在资源和广告表（`assets/mtrlte/pids_xiamen_ads.json`）同一层，是为了跟天津地铁的广告定义保持一致：天津的标语就是广告条目自带的 `text`，没有单独的配置文件；我们这里把它独立出来，方便你一次写多条。

## 结构

```json
{
  "_comment": "滚动标语：messages 按顺序循环，每条滚完再出下一条",
  "speed": 40,
  "gap": 48,
  "messages": [
    { "text": "ticker.mtrlte.welcome" },
    { "text": "ticker.mtrlte.mind_gap", "color": "#FFD400" },
    { "text": "欢迎乘坐厦门地铁", "color": "#FFFFFF", "speed": 32 }
  ]
}
```

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `speed` | 数字 | `40` | 滚动速度，单位 **屏幕像素/秒**（512×235 的屏幕坐标系）。数字越大越快 |
| `gap` | 数字 | `48` | 两条标语之间的空隙（像素）。`0` = 首尾相接，不推荐 |
| `messages` | 数组 | 空 | 标语列表，按顺序循环播放，空数组 = 不显示标语条 |

每条 `messages[]`：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `text` | 字符串 | 是 | **优先当语言键**解析（`assets/mtrlte/lang/zh_cn.json` 里查 `text`），**查不到就把这个字符串原样显示**。所以你可以直接写中文，也可以写键名 |
| `color` | 字符串 | 否 | `#RRGGBB`。默认白色 `#FFFFFF` |
| `speed` | 数字 | 否 | 覆盖这一条的滚动速度 |

## 语言键写法（推荐）

在 `assets/mtrlte/lang/zh_cn.json` 与 `en_us.json` 里加：

```json
"ticker.mtrlte.welcome":   "欢迎乘坐厦门地铁，请勿倚靠车门",
"ticker.mtrlte.mind_gap":  "请站在黄线以内候车，先下后上",
"ticker.mtrlte.next_stop": "下一站：%s"
```

英文（`en_us.json`）给同样的键，值填英文；英文缺失时中文会兜底显示。

## 渲染行为

- 标语条在屏幕**最下一条横带**（地铁版是与广告区等宽的下框，BRT 版是底部深色条）。
- 文字从左往右排、整体**右→左滚动**，一条滚出左边、下一条从右边进，`gap` 控制间隔。
- **广告开着时这份 JSON 不生效**：滚的那条是「当前广告自带的 `text`」（天津的语义）。
  广告**换图的时机就是这条标语滚完的时刻**（学天津的 `ScrollingText.scrollText → nextSlide`）：
  标语最后半个字出左边缘的那一刻，下一张广告进来、新标语从右边重新起滚，所以每张广告停多久
  取决于它自己那句标语有多长。默认那张厦门风景图的 `text` 是 `ticker.mtrlte.ad_welcome`
  =「欢迎乘坐厦门地铁」；勾了南北组 / 三张风景，标语就跟着每张广告的文案换。
  只有关掉广告（或这张广告没有 text）才回落到这里 `messages` 那几条。
- 到站信息（站名/分钟）不受标语影响，两者各画各的。
- 标语和广告一样受「到站数据」驱动：屏幕没有任何站台数据时只显示标语，不显示到站行。

## 还没定的

- 两条标语之间是否要插入时间/日期（参考图右上角已经有日期时间，暂不重复）
- 是否需要按线路/站台显示不同标语（现在是一套全局）
- 是否需要「临时插播」（例如末班车提示优先于普通标语）

这三条你定了我再加字段，不会破坏现有格式。

## 实现（1.20.1 `PidsXiamenTicker`）

- **几何**：标语条矩形、字号都来自 `PidsXiamenLayout`（地铁 `178,199–501,227`，BRT `178,185–504,225`，
  字高 = 条高 × 0.52 / 0.38），与广告区**右边界对齐**。
- **滚动**：一条一条来 —— 当前这条从条右边缘出发、匀速滑到完全出左边缘，再等 `gap` 对应的时间
  （`gap ÷ 该条速度` 秒）才出下一条；`speed` 是像素/秒（512×235 的底图像素坐标系），
  `messages[].speed` 覆盖这一条。所以长短不一的句子不会互相挤。
- **不缩字、按边缘裁**：一条标语永远用「条高 × sizeRatio」这个字号（与天津的 `ScrollingText` 一致），
  长句保持原字号滚过去，只有跨过标语条左右边缘的字被裁掉 —— 不再「长句整体等比缩小」，
  那样字会越缩越小、屏上读不清。
- **裁切**：不用 `glScissor`，改成**顶点级裁切**：给 `TextRenderer` 套一层
  `VertexConsumerProvider`（`PidsXiamenText.ClippingProvider`），每个字形四边形在进缓冲区之前
  把 x 夹到条内、**u 按夹取比例线性插值** —— 于是字是**在边框那一像素上被切开**的（露出半个字），
  不是整字跳进跳出，也不会整字溢到条框外面。整个过程不碰任何 GL 状态、也不 flush 共享 buffer。
- **临时插播**（文档里“还没定的”第三条，先留接口，不动 JSON）：
  ```java
  PidsXiamenTicker.override(List.of(
      new PidsXiamenTicker.Message("末班车即将发出", 0xFFFFD400, 36.0F)));
  PidsXiamenTicker.clearOverride();   // 回到资源包那套
  ```
- 资源重载（F3+T）后 `invalidate()` 由 `PidsXiamenClient` 的资源监听器调用，改 JSON 不用重启。
