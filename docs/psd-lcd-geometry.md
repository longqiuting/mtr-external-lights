# 厦门 PSD-LCD 挂板（`mtrlte:psd_lcd_xiamen`）的面板几何

日期：2026-11-19 · 版本：`v1.20.1`

面板的**高度带 / 厚度 / 前后位置 / 每段宽度**不是估的，是从两处量出来的：

1. MTR 自己的屏蔽门顶板面板带（`docs/mtr-psd-top-research.md` §1.2 已用 `javap` 实测）；
2. 参考模组 `mtr_psd_lcd` 1.2.8（Fabric 1.19.2 / MTR 3.2.2）的模型与 BER（MIT，只学做法）。

## 量到的数

| 量 | 来源 | 值 |
| --- | --- | --- |
| 面板高度带（块内 y） | MTR `RenderPSDTop` 面板矩阵：底边 `bottomPadding = 1.5px`、顶边 `8.5px`（7.5px 是外壳上唇） | **1.5 … 8.5 px**（16 分之几，高 **7px = 0.4375 格**） |
| 同一带的验证 | 参考模组 `models/block/psd_top_lcd14_groove_left_base.json`：插屏凹槽的上下唇在 `y 1.6` 与 `y 15.1`（顶朝下量的 0.056 / 0.874） | 槽口净高 13.1px（那是它自己的线路图面板；见下） |
| BER 里画的屏幕 | `RenderPSDTop#drawGroovedScreen`：`drawTexture(..., y1 = 0.056, y2 = 0.874)`（y 从块顶往下量） | 13.09px 高 = **0.818 格**（`SCALE = 90`，即 1 格 = 90 单位） |
| 厚度 | 参考模组凹槽：`x 4.4 … 6.0` | **1.6px（0.1 格）** |
| 前后位置 | 凹槽前面在 `x = 6` = 箱体朝外的那一面（箱体本体 `x 0 … 6`） | 面板**外面与方块朝外那一面平齐** |
| 每段宽度 | MTR：`(leftBlocks + rightBlocks + 1) − 2 × sidePadding`，`sidePadding = 0.125/16` | **1 格 / 段**（整排两头各让 0.125px，肉眼不可见，代码里忽略） |

## 为什么高度取 7px，不是参考模组的 13.09px

参考模组那块 0.818 格高的是**它自己的竖版线路图**（MTR 顶板样式），和本模组的
「厦门地铁 PIDS 版式」不是一回事：本模组的版式是按实拍（`扫描全能王 2026-11-19 19.07.jpg`，
6.91:1）与用户示意稿排的，全部布局常量都以「屏高 h」为单位。

- 7px / 格 ⇒ 每段 2.29:1；**3 段 = 6.86:1 ≈ 示意稿的 6.91:1**；
- 4 段 = **9.14:1**（MTR 文档里「4 格 → aspect 9.11」正是同一组比例）；
- 若取 0.818 格，4 段只有 4.89:1，比设计稿还窄 —— 左卡片和右侧一组会叠在一起，不可用。

## 落到代码里

| 位置 | 常量 |
| --- | --- |
| `PsdLcdLayout#PANEL_Y0_PX / PANEL_Y1_PX` | 1.5 / 8.5（16 分之格） |
| `PsdLcdLayout#THICKNESS_PX` | 1.6（16 分之格） |
| `PsdLcdLayout#FACE_GAP_PX` | 0.016px（只为了避免和模型面 z-fighting） |
| `PsdLcdLayout#HEIGHT_PX` | `520 × 7/16 = 227.5` px（屏高 h） |
| `PsdLcdLayout#CENTER_Y_BLOCKS` | 5/16 = 0.3125 格（面板中线） |
| `models/block/psd_lcd_xiamen.json` | `from [0, 1.5, 0] to [16, 8.5, 1.6]`（north 朝向那一份） |
| `PsdLcdBlock` 的四个 `OUTLINE_*` | 同上，厚度 1.6px |

## 用法

挂在**顶板下沿那一格**（门头高度），面板就贴在吊顶下沿、和 MTR 顶板箱体的显示带同一高度，
实拍就是这样。`PsdLcdRenderer` 把整张 520px/格 宽的位图缩到
`1/520` 格，画在「朝外那一面再往外 0.016px」的平面上。
