# 旧版（1.18.2 / 1.19.2 / 1.19.4）PIDS 文字乱码：字体层被「按层缓存」串了

状态：已修（`PidsXiamenText.ClippingProvider`）。1.20.1 未受影响、未改动。

## 症状

- 1.20.1：中文正常。
- 1.18.2 / 1.19.2 / 1.19.4：**滚动标语的中文全乱**（错字、串图、位置看着也乱），**英文正常**。
- 同一块屏上「到站行 / 时钟」的中文是好的 —— 这是关键线索：它们走原版 provider，只有标语走裁切。

## 排错的顺序（留档，别重走）

1. `TextRenderer#draw` 的重载四个版本都对（javap 核过描述符：1.18.2 / 1.19.2 是
   `Matrix4f + boolean seeThrough`，1.19.4 / 1.20.1 是 `org.joml.Matrix4f + TextLayerType`）。
   → 不是重载拼错。
2. PIDS 渲染路径里**一次 `RenderSystem` 调用都没有**（`grep RenderSystem` 为空）：
   不调 `setShaderTexture`、不调 `setShader`、不 `depthMask/enableBlend`。
   → 「我们把底图绑给字形用了」这条**不成立**，GL 状态没被污染。
3. 三个版本 PIDS 底图 md5 完全一致（`pids_xiamen_metro.png` `1d945d35…`、
   `pids_xiamen_brt.png` `44db2831…`，与 1.20.1 逐字节相同）。
   → 「贴图好像也有问题」跟底图资源无关。

## 真因：顶点归属哪一层，不由你手里的 consumer 决定

1.18.2 `VertexConsumerProvider.Immediate`（`javap -c` 逐行核过，1.19.2 / 1.19.4 同形）：

```
getBuffer(layer):
    buf = layerBuffers.getOrDefault(layer, fallbackBuffer)     // ← 字体图集层不在 layerBuffers 里
    if (currentLayer != layer):
        draw(currentLayer)                                     // 切层时顺手把上一层画掉
        if (activeConsumers.add(buf)) buf.begin(...)
        currentLayer = layer
    return buf
```

- `BufferBuilderStorage` 只给 glint / lines / water_mask 那些特殊层预建 `BufferBuilder`；
  **字体图集层没有自己的 buffer，全部共用同一个 fallback `BufferBuilder`**。
- 于是「这块顶点属于哪一层」**只由 `currentLayer` 在绘制那一刻决定**，
  而 `currentLayer` 只在 `delegate.getBuffer(layer)` 被调用时才切换。

我们的 `ClippingProvider` 原来是 `Map<RenderLayer, ClippingConsumer>` 每层缓存一个
consumer（consumer 里存着创建时拿到的 delegate）：
第二次用到同一层时**不再调用 `delegate.getBuffer(layer)`** →
`currentLayer` 还停在上一个图集 → 这些字形的顶点被写进上一层的 buffer、用上一层的贴图画出来。

**为什么只有中文坏**：一条纯 ASCII 文字只落在**一个**图集层上，怎么缓存都不会串；
一条中文会跨 **≥2 个**图集层，于是逐字交替 → 中文被按 ASCII 图集采样 → 「英文正常、中文全乱」。
跨层有两种成因，都成立（运行时用下面那行诊断可确认是哪一种）：

- 字形按 `hasColor()` 分组：`BitmapFont.BitmapFontGlyph.hasColor()` 与
  `UnicodeTextureFont.UnicodeTextureGlyph.hasColor()` 都是
  `image.getFormat().getChannelCount() == 4`，源图（`ascii.png` = RGBA、`unicode_page_*.png` = 调色板）
  展开后的通道数不同就会落进 `hasColor=true` / `false` 两张图集；
- 图集页溢出：`GlyphAtlasTexture` 每页只有 256×256（`SLOT_LENGTH = 256`），
  中文用量一大就会开第二页 —— 第二页就是第二个 `RenderLayer`。

**没能站住的两条假设**（都已排除，别重走）：
- 「我们换绑了 `setShaderTexture`」：PIDS 渲染路径里一次 `RenderSystem` 调用都没有；
- 「底图资源不对」：三个版本 PIDS 底图与 1.20.1 md5 逐字节相同。

## 修法

- `ClippingProvider` 不再按层缓存：**每个字形都重新问一次 `delegate.getBuffer(layer)`**
  （原版 `TextRenderer$Drawer#accept` 就是每字一问），consumer 对象复用一个
  （同一时刻只有一个字形在攒 4 个顶点），`bind()` 换 buffer。
- `fixedColor` / `unfixColor` / `emit` 加 `delegate == null` 守卫（防 NPE 掉整块屏）。
- 新增一次性诊断：`[lte] pids text: fontTexId=… ourTexId=… layer=…`
  （`PidsXiamenText.lastLayerName()` ← `RenderLayer.toString()`，含图集 id；
  每 JVM 只打一行）。

## 复现/验证要点

- 编译：`gradle -p v1.18.2 compileJava`（三个旧版均 BUILD SUCCESSFUL）。
- 进游戏看日志那一行：`fontTexId` 里应是 `minecraft:default/<n>` 之类的图集 id，
  `ourTexId` 是 `mtrlte:textures/block/pids_xiamen_*.png`。两者若**同时**出现在一个字形上
  才叫「状态污染」；正常情况下它们是各自独立的层。
