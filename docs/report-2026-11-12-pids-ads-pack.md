# 厦门 PIDS：四个 bug + 广告逻辑 + 资源包适配（只 1.20.1）

范围：`v1.20.1` 的 `xiamen/pids/` 与 `assets/mtrlte/`（广告表 / 标语表 / 广告图 / lang）。
`gradle -p v1.20.1 compileJava` → **EXIT 0**（只剩改动前就有的 4 条 deprecation 警告）。未进游戏、未 commit。

| # | 用户报的问题 | 根因 | 改法 |
|---|---|---|---|
| 1 | 导入资源包页面「完成」失效、ESC 也退不出来 | 1.20.1 的 `PackScreen.close()` **只** `organizer.apply()`，自己不切屏；我们传的 applier 只刷新了资源包 | applier 里 `setScreen(本界面)`（原版也是这么接的） |
| 2 | PIDS 配置界面按钮与文字重叠 | 标签按「固定行高反推」画：广告那行标签落在上一行页码输入框上 | 每行 = 标签 12px + 控件 20px + 8px，y 由 `init()` 记录，标签一律画在控件上方 |
| 3 | 广告还是太突出（浮在屏外） | `TEXT_LAYER_Z(-7px)` 与 `AD_LAYER_Z(-1.5px)` **叠加** = 广告实际在 −8.5px ≈ 3.3cm、文字 −7px ≈ 2.7cm | 文字 −0.7px、广告总偏移 −0.35px（≈1.3mm，贴面） |
| 4 | 滚动标语全消失 | 顶点级裁切在**世界坐标**里比屏幕 px 的边界（`183..496`）→ 所有字形都被判成「在窗口外」整块丢掉 | 裁切改在屏幕平面做：TextRenderer 收本地矩阵，`ClippingConsumer` 落笔时再乘回世界矩阵 |

另外两项是这次一起改的：**广告逻辑重构**、**资源包适配**。

## 1. 资源包界面退不出去

`javap -c` 看到 1.20.1 的 `PackScreen.close()` 只有：

```
organizer.apply();        // 落盘 + 回调构造时传进来的 applier
closeDirectoryWatcher();
return;                   // ← 没有 setScreen
```

原版 `OptionsScreen` 的 applier 里就是 `options.refreshResourcePacks(manager); client.setScreen(this);`。
我们漏了后半句，于是 `close()` 执行完仍停在 PackScreen 上 —— 按钮和 ESC 都「没反应」。
现在 applier 里刷新完资源包就 `setScreen(self)`；`setScreen` 会重新跑 `init()`，
分类表按新包重读，等于「装完包原地回来」。

## 2. 配置界面重叠

旧 `render()` 用 `y = 62; y += PITCH …` 反推每一行的位置，而「广告：已选 N 个分类」那行画在
`rowY - 12` —— 正好落在上一行页码输入框（`112..132`）里，长一点的中文标签直接压到输入框上。
另外「附近没有车站」的红字画在**按钮的 y 上**（`y` 没加行高），也是叠字。

现在 `init()` 把每个控件的 y 记下来（`platformY / filterY / pageY / adsY`），`render()` 只按这些值画：

```
y = 66   勾选框（标签在 54）
y = 96   选择站台…（没有车站时 112 处再加一行红字，后面整体下移 16）
y = 126  显示页码（标签 x 左边、输入框 x+96，同一行，垂直居中）
y = 156  选择广告…（「已选 N 个分类 / 共 M 张」写进按钮文字，不再单独占一行）
```

## 3. 广告 / 文字的「凸出」

`renderFace()` 在画完底图后先 `translate(0,0,TEXT_LAYER_Z)`，广告又在这个栈里再
`translate(0,0,AD_LAYER_Z)` —— 两个常量是**相加**的。上一版 `-7 + (-1.5) = -8.5px`，
换算到世界是 `8.5 / 261.1 ≈ 0.033 格 ≈ 3.3cm`，斜看就是一块浮在屏外的板。

现在：

| | 旧 | 新 |
|---|---|---|
| 文字 | `-7.0px`（2.7cm） | `-0.7px`（2.7mm） |
| 广告（总） | `-8.5px`（3.3cm） | `-0.35px`（1.3mm） |

**为什么这么小也不会被底图盖住**：底图还是 `MoreRenderLayers.getLight`（beacon 层），
广告改成**写深度**的 `RenderLayer.getEntityCutoutNoCull`（顶点仍给 `FULL_LIGHT`，自发光观感不变）。
广告在 −0.35px 写深度后，底图（0）不论先画后画都过不了 `LEQUAL`；文字层（不写深度）在 −0.7px
再压一层。深度决定胜负，不再靠「把广告推出去」抢。

## 4. 滚动标语消失

`PidsXiamenText.ClippingConsumer` 拿到的顶点是 `TextRenderer` **乘过传入矩阵**之后的坐标。
`blit()` 传的是渲染器那张栈（含 `translate/rotY/rotZ/rotX/scale(1/261.1)`），所以 x 是世界坐标
（≈ `0.5 ± 1`），而裁切边界是底图像素 `183..496` → `x1 <= left` 恒成立，每一个字形都被丢掉。
文字（时钟 / 到站）不裁切，所以只有标语整条消失。

改法：裁切时给 `TextRenderer` 一张**只含 `translate(cursor, top)` + `scale(字号)`** 的新栈，
顶点因此就是屏幕 px，可以正常和 `innerLeft/innerRight` 比；`ClippingConsumer` 在 `emit()` 里
再用捕获的世界矩阵把顶点乘回去（`VertexConsumer.vertex(Matrix4f, …)`，1.20.1 的默认重载）。
顶点级裁边（半个字被切开）的逻辑一行没动。

## 5. 广告逻辑重构

* **默认分类 `mtrlte` 只有一张图** `advertisements/xiamen_scenery.png`（用户给的厦门风景，
  4096×3072 → 2:1 中心裁切 → 512×256 PNG，259KB），`text = ticker.mtrlte.ad_welcome`
  =「欢迎乘坐厦门地铁」。**一张 = 轮播下标恒为 0 = 不轮播**。
* **`xiamen_ads` 分类**装原来的三张风景图；玩家在「选择广告」里勾上它，分类里就有 4 张
  （默认那张 + 三张）开始轮播；只勾它 = 三张轮播。这就是「启用厦门广告以后才轮播」。
* **标语跟广告走**：天津的语义是「屏下那条滚动文字 = 当前广告的 `text`」。渲染器现在把这一帧
  真正画出去的那张广告的 `text` 传给 `PidsXiamenTicker.draw(..., messages)`，并且**按广告的
  轮播周期重新起滚**（换图时不会半句话被顶掉）。广告关掉 / 这张没有 text 时回落到
  `pids_xiamen_ticker.json`（现在只留「欢迎乘坐厦门地铁」一条）。
* 轮播间隔仍是 `AD_ADVANCE_TICKS = 200`（10 秒），刷子界面的序号偏移不变。

## 6. 资源包适配（南北组 / 天津广告包）

`PidsXiamenAds` 现在读**两张**表：自己的 `assets/mtrlte/pids_xiamen_ads.json`，
以及天津地铁的 `assets/tjmetro/pids_tianjin_ads.json`（`TIANJIN_CONFIG_ID`）。

```
南北组 …材质包/assets/tjmetro/pids_tianjin_ads.json   ← 9 张 nbz*.png + advertisement.nanbeizu.banner1..9
```

所以那个包**不用改一个字节**：装上去，刷子 → 选择广告 里就多出「南北组广告（9 张）」，
勾上就播，标语跟着每张广告的文案滚。图片仍按它自己的 `tjmetro:advertisements/*.png` 采样
（`getEntityCutoutNoCull` 只吃 Identifier，跨命名空间没问题）。
同名分类（key 相同）合并时按**图片 + 文案**去重，所以资源包以后要是再补一份 `mtrlte` 表也不会出现两遍。

> 注：如果以后想让资源包自带一套「我们的」广告，只要在包里写
> `assets/mtrlte/pids_xiamen_ads.json`（结构同天津）即可，两份表会自动合并。

## 改动文件

```
v1.20.1/src/main/java/dev/js/mtrheadlight/xiamen/pids/PidsXiamenLayout.java       TEXT_LAYER_Z -0.7 / AD_LAYER_Z 0.35
v1.20.1/src/main/java/dev/js/mtrheadlight/xiamen/pids/PidsXiamenRenderer.java     广告层改写深度；广告返回 Ad；标语用广告 text；注释
v1.20.1/src/main/java/dev/js/mtrheadlight/xiamen/pids/PidsXiamenText.java         裁切改到屏幕平面（本地矩阵 + emit 乘回世界）
v1.20.1/src/main/java/dev/js/mtrheadlight/xiamen/pids/PidsXiamenTicker.java       draw(..., messages) 重载
v1.20.1/src/main/java/dev/js/mtrheadlight/xiamen/pids/PidsXiamenAds.java          读天津表 + 同 key 去重；兜底改单张海报
v1.20.1/src/main/java/dev/js/mtrheadlight/xiamen/pids/PidsXiamenAdsScreen.java    PackScreen applier 里 setScreen(self)
v1.20.1/src/main/java/dev/js/mtrheadlight/xiamen/pids/PidsXiamenConfigScreen.java 行高重排、标签按控件位置、广告计数进按钮
v1.20.1/src/main/java/dev/js/mtrheadlight/xiamen/pids/PidsXiamenSettings.java     注释
v1.20.1/src/main/resources/assets/mtrlte/pids_xiamen_ads.json                    默认单张 + xiamen_ads 三张
v1.20.1/src/main/resources/assets/mtrlte/pids_xiamen_ticker.json                 只留「欢迎乘坐厦门地铁」
v1.20.1/src/main/resources/assets/mtrlte/advertisements/xiamen_scenery.png       新增（512×256，259KB）
v1.20.1/src/main/resources/assets/mtrlte/lang/{zh_cn,en_us}.json                 新增 ticker.mtrlte.ad_welcome / xiamen_ads 分类 / 按钮文案
temp/make_ad_photo.ps1                                                            生成上面那张图（不参与构建）
```

## 进游戏要看的四件事

1. 选择广告 → 导入资源包 → 点「完成」/按 ESC **能回到选择广告界面**（分类表刷新）；
2. 刷子界面：每行标签都在控件上方，没有叠字；广告按钮上写着「已选 N 个分类 / 共 M 张」；
3. 屏上默认是那张厦门风景图 + 标语「欢迎乘坐厦门地铁」，**不轮播**；勾上「厦门广告（三张风景）」
   才开始转，标语跟着每张广告的文案换；
4. 斜看屏面：广告 / 文字都贴在屏幕上，没有 3cm 的浮层；标语条有字在滚（边缘是半个字被切开）。
