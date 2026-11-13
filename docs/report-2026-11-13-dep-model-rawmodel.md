# 挂墙 / 挂顶发车倒数器：模型改走 rawmodel BER（2026-11-13，只 1.20.1）

范围：`v1.20.1` 的 `xiamen/dep/`、`rawmodels/dep_on_{wall,roof}.json`、
`models/block/dep_on_{wall,roof}.json`、`tools/gen-dep-rawmodels.js`、`tools/verify-dep-orient.js`。
**未进游戏、未 commit**；`gradle -p v1.20.1 compileJava` 通过（0 error，只剩改动前就有的 4 条警告）。

## 用户反馈的两条

| # | 反馈 | 结论 |
|---|---|---|
| 1 | 碰撞箱、倒计时数字都对，**模型朝向差 90°** | 真的差 90°，在**挂顶版**（`dep_on_roof`）：素材的 135° 是「数学正角」，代码这边（`yawedAabb` / `SCREEN_NORMAL`）是「俯视顺时针」，两套相差 90°。修法：画模型时补 `MODEL_YAW_FIX_DEGREES = +90°`（绕模型 pivot `(8, 7.15)`），**碰撞箱与读数一行没改**。挂墙版本来就没偏（四个朝向的发光板法线 = `facing`，差 0°）。 |
| 2 | **挂杆 / 支架模型缺失** | 「部分缺失」是旧转换链造成的：原版只吃 `±45 / ±22.5 / 0` 的单轴元素旋转，`dep_on_roof.json` 的挂杆十节是 `154 / -87.5 / -73.5 / {"x":180,"y":-11.75,"z":180}` 这类角度，`tools/gen-dep-on-roof-model.js` 只能吸附，十节里只有两节恰好合法，其余偏差 0.08~0.27/16 格。现在整份模型读素材原样。 |

## 改法

- `models/block/dep_on_wall.json` / `dep_on_roof.json` → `{"elements": []}` 空占位
  （和闸机 `mtrlte:block/empty` 同一套做法），原版不再参与这两块牌子的渲染；
- 新增 `rawmodels/dep_on_wall.json`（6 元素）/ `rawmodels/dep_on_roof.json`（16 元素），
  由 `tools/gen-dep-rawmodels.js` 从 `素材/挂墙发车倒数器/` **原样**拷入（只改贴图名；
  脚本自带「elements 一字未改」断言）；旧的 `tools/gen-dep-on-roof-model.js`（有损转换）删除；
- `DepOnWallRenderer` / `DepOnRoofRenderer` 各加一个 `drawModel`：
  `RawModelJson.get(...)` → `RawModelRenderer.cutoutLayer` → 一次矩阵 →
  `RawModelRenderer.render`。旋转只有「blockstate 的 y」（挂墙）和「+90° 补偿 + blockstate 的 y」（挂顶）；
- 牌身**永远画**（空屏时也在），数字层逻辑原封不动（挂顶版只是把「没有读数就 return」挪到画完牌身之后）。

## 验证（离线，全部可重跑）

```text
node tools/verify-rawmodels.js     # 532 项通过：绕序朝外 / uv 角落 / 刚体旋转 / uv 0..1
node tools/verify-dep-orient.js    # 全部通过（见下表）
```

| 检查 | 结果 |
|---|---|
| 挂墙：模型包围盒 vs `DepOnWallBlock#rotatedBox` 四份碰撞箱 | north/east/south/west 全等（1/16 格） |
| 挂墙：发光板法线 vs `facing` | 四个朝向差 0°，且 y 分量为 0（不会再「立起来」） |
| 挂墙：新几何 vs 旧原版模型 | **6/6 元素逐顶点一致**（≤0.001 格）→ 视觉零变化 |
| 挂顶：牌面法线 vs `SCREEN_NORMAL` | 差 0.0° |
| 挂顶：屏幕正中 vs 发光板中心 | 差 0.06/16 格 |
| 挂顶：牌框包围盒 vs `DepOnRoofBlock#frameBoxes` | 完全相同 |
| 挂顶：挂杆 | 顶到 y=16、高 6/16、十节都在、9 个非 22.5° 角度保留 |
| 挂顶：新几何 vs 旧原版模型 | 牌框 6/6 一致；挂杆 8 节有 0.08~0.27/16 格偏差（正是要修掉的那点） |

离线预览图（不随仓库发布）：`temp/dep_wall_final_{front,iso}.png`、
`temp/dep_roof_final_{iso,side}.png`；旧/新几何逐元素对比脚本 `temp/compare-dep-models.js`。

## 进游戏要看的

1. 挂顶版：牌子斜 45° 朝**东南**（`facing=north` 时），数字正好在发光板上、没有被板挡住；
2. 挂顶版：挂杆从牌顶一直连到天花板，是一根拧起来的杆，不是断开的几截；
3. 挂墙版：外观和上一版应完全一样（几何逐顶点一致），只是底层换成了 BER；
4. 四个朝向都放一块，看一眼模型和碰撞箱是否同一面。
