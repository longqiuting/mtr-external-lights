# 第三方来源与许可声明（NOTICE）

本项目本体以 **MIT** 发布（见仓库根 `LICENSE`）。下列文件/实现来自第三方，按各自许可使用。

本文档由脚本核对生成：把本仓库 `assets/` 下每个 `.png` / `.ogg` 与整合包里 78 个模组的资源逐一做 **MD5 比对**（排除我们自己的 jar），再用 MTR 作为「MIT 上游」基准排除间接拷贝。

---

## 1. Minecraft Transit Railway（MTR）

- 用途：屏蔽门（PSD/APG）实现**移植**自上游源码；若干模型与贴图直接沿用
- 沿用文件（MD5 与 MTR 一致，共 19 个，示例）：
  ```
  models/block/psd_top_left_base.json, psd_top_right_base.json,
                 psd_top_left_persistent_base.json, psd_top_right_persistent_base.json
  textures/block/black.png, light_on.png, light_off.png, apg_door_light_on.png,
                 apg_door_light_off.png, apg_side_triangle.png,
                 psd_red_top.png, psd_red_bottom.png, psd_side_light.png,
                 psd_top.png, psd_top_edge.png, transparent.png …
  ```
- 许可：**MIT**（上游 jonafanho/Minecraft-Transit-Railway）
  - 说明：整合包里 `MTR-fabric-1.20.1-3.6.4.2.jar` 的 `fabric.mod.json` 写着
    `"license": "All Rights Reserved"`，但该 ARR 仅覆盖 **Yomi_307 修改的那部分代码**，
    本项目**未使用**这些修改，只用了上游 MIT 的屏蔽门实现与资源文件，因此按 MIT 处理。
- 版权：Jonathan Ho 及 MTR 贡献者

## 2. 南滨创意（Nanbin Create Mod）

- 许可：**MIT**（`fabric.mod.json` 明确）
- 作者：Nanbin-Studio；贡献者 LIEH-SAD
- 用途：闸机音效。`assets/mtrlte/sounds/crt_ticket.ogg` 是从该模组
  `assets/nanbin/sounds/crt_ticket.ogg` **逐字节复制**（MD5 `e09178365792e6873eaa23a752b82510`），
  并在 `assets/mtrlte/sounds.json` 注册为 `mtrlte:crt_ticket`（pitch 1.0）与
  `mtrlte:crt_ticket_error`（同一音频 pitch 0.7）。
- 许可证全文：`LICENSES/NANBIN-MIT.txt`

## 3. 真实城市建设（RCC / Reality City Construction）

- 许可：**LGPL-3.0**（`fabric.mod.json` 明确）
- 作者：CodeCat、yaoxiansen
- 用途：
  - 大理石站台方块的模型与贴图（`assets/mtrlte/models/block/marble_platform*.json`、
    `textures/block/floor_hangzhou*.png` 等，源自 RCC `tcwm:block/platform*` 系列）
  - 后续「厦门售票机」阶段计划移植 RCC 的三种站台方块
    （`platform` / `platform_indented` / `platform_type_quartz`）
- 这些文件属于 LGPL-3.0 衍生作品（我们做了命名空间与路径修改），其余代码仍为 MIT。
- 许可证全文：`LICENSES/RCC-LGPL-3.0.txt`

## 4. MTR 车站装饰（MSD, MTR Station Decoration Addon）

- 许可：**MIT**
- 作者：AIDA64S、Santorsia、Nemo（4.0.5 版本另见 Zbx1425）
- 用途：
  - **墙上扶手**（11 个 `railing_wall_*` / `railing_stair_*_on_wall.json`）与
    **玻璃扶手**（11 个 `railing_glass_*` / `railing_stair_glass*`）**均基于 MSD 的模型修改而来**
    （修改点：命名空间 `msd:` → `mtrlte:`、按我们方块体系的几何对齐、碰撞/框选盒重算）
  - `assets/mtrlte/textures/block/railing.png` 与其同名文件 MD5 一致；
    `railing_stair_glass.png` 亦源自该模组同系列贴图
  - 模型组织方式（一模型一方块）参考其 `BlockRailingStair`
- 许可证全文：`LICENSES/MSD-MIT.txt`

## 5. 天津地铁（Tianjin Metro – MTR3 Adapted Edition）

- 许可：**GPL-3.0-only**
- 作者：ZiYueCommentary (ziyue)、jiangxingyunjian、\_\_Lingxii\_\_ (lingxii)
- 用途：**仅参考其公开行为与字节码**（PIDS 屏幕渲染链、广告系统、挂杆、屏蔽门继承方式），
  **未复制其二进制资源**——MD5 核对显示：我们与天津重合的贴图
  （`light_on/off`、`psd_red_*`、`psd_side_light`、`apg_side_triangle` 等）**全部同时存在于 MTR**，
  因此来源判定为 MTR（MIT），不是天津。
- 结论：当前仓库**没有** GPL-3.0 内容。若将来直接复制天津任何资源或源码，整个项目须转为 GPL-3.0。

## 6. 方速 MTR 扩展（Fangsu）

- 未复制任何资源（MD5 比对无「仅方速存在」的命中）
- 仅调用其**公开静态方法** `com.fangsu.client.ClientHooks.openTicketMachineScreen(Text, BlockPos)`
  （售票机双界面切换），通过反射 + `isModLoaded("fangsu")` 守卫，编译期不依赖

## 7. LambDynamicLights（LDL）

- 仅使用其公开 API（`DynamicLightBehavior` / `LineLightBehavior`），未复制资源

## 8. Metropolis / 其他

- 截至 2026-11-12 **未使用**。若后续接入，须在此处补充条目与许可。

---

## 复核方法（可重跑）

```bash
node temp/audit-provenance.js     # 全量：我们每个资源 vs 78 个模组，按命中数分组
node temp/audit-nonmtr.js         # 关键：排除 MTR 之后仍然命中的文件（= 非 MIT 上游来源）
```

2026-11-12 的结果：`非 MTR 来源命中 = 1 个`（`railing.png`，来自 MSD/MIT）。
