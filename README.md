# LTE (LTY's transit expansion)

**MTR（Minecraft Transit Railway）3.x 的交通拓展模组** — Fabric，支持 **1.18.2 / 1.19.2 / 1.19.4 / 1.20.1**。

基于 MTR 的列车/轨道/信号数据，补上 MTR 本身没有的灯光、天气、碰撞与编辑能力。

- 仓库：<https://github.com/longqiuting/mtr-external-lights>
- 问题反馈：<https://github.com/longqiuting/mtr-external-lights/issues>
- 协议：MIT（屏蔽门部分移植自 MTR，同样 MIT）

---

## 功能

### 车灯与光效

- **光带 / 光柱**：头灯、尾灯光束，按车型灯位自动匹配（内置 95/114 个 MTR 车型的灯位表），左右灯成对、只给头灯
- **方向取自车身弦**，不是轨道切线 —— 单节车过急弯不会甩方向
- **自动开关**：按天空光照 + 昼夜判定，隧道/夜晚自动亮，接触网不再导致闪烁；车厂停放 10 秒自动熄灯
- **动态光源**：通过 LambDynamicLights 照亮周围方块（可选）
- 光柱默认**关闭**（实验性质），可在面板里打开

### 天气

- 雨天列车卷起的**水雾**：自定义粒子 `mtrlte:spray`，灰白细丝，向后下方跟随尾流；隧道内不生成；20–50 km/h 渐入、50 以上全密度

### 碰撞与伤害

- 列车**外部碰撞**：分段 OBB，弯道不失效，开门时整侧放行
- **推挤 / 撞飞 / 伤害**分档：`<5` 只推、`5–20` 低速伤害、`20–50` 中速、`50–80` 撞飞、`≥80` 飞行档
- 撞飞距离随时速指数增长（`5.5 × exp(k(v−50)/50)`，默认 50 km/h≈5.5 格、80≈10 格、120≈22 格）
- 死亡消息：
  - 低速：`%1$s 因为被罚了5000HKD而被气似了`
  - 高速：`%1$s 随着列车的呼啸声消失了`
- 服务端安装时伤害/撞飞由服务端结算，纯客户端也不会双结算

### 连接器

| 物品 | 右键 | 左键 |
|---|---|---|
| 信号连接器 | 节点→灯 / 灯→节点 / 灯→灯 | 解绑 |
| 投影机连接器 | 投影机 ↔ 车站/站台（支持站外） | 解绑 |
| PIDS 连接器 | PIDS ↔ 站台（MTR / 天津地铁 / JCM） | 解绑 |

- 手持时高亮视距内的轨道节点
- 方块被拆时自动解绑（避免原地重放继承旧绑定）
- 方速信号灯：右键自动切到「同步 MTR 信号」模式并绑定

### 3D 光束编辑器

- `/mtr_lte beam3d` 或「车灯编辑器」物品打开
- 直接加载资源包里的真实列车模型，左键旋转视角、右键平移、滚轮缩放
- 选择 / 移动 / 旋转 / 颜色工具，XYZ 轴约束拖拽，六向吸附，Shift 0.5 格步进
- 支持搜索车型、导入未加载的资源包、1–8 节车厢预览
- 导出 JSON 到桌面，或写入资源包 `assets/lte/beam3D/<车型id>.json`

### 厦门风格物件

- 全高屏蔽门（PSD）与半高屏蔽门（APG），**1 / 2 / 3 / 4 / 6 号线**各一套
- 行为完整继承 MTR：列车到站自动开门、刷子切线路指示方向、剪刀切形态、门灯（1/2/3 橙色、4/6 绿色）、站台方块自动切换模型
- 线路条带按厦门实拍重绘：空心圆环站点、换乘图标（追逐箭头 + 线路牌）、终点三角箭头、45° 站名、颜色跟随线路
- 厦门地铁 logo 方块

---

## 安装

1. 安装 **Fabric Loader** 与 **Fabric API**
2. 安装 **MTR 3.x**（对应你的 MC 版本）
3. 把 `lte-1.0.0+<mc版本>.jar` 放进 `mods/`
4. 可选：**LambDynamicLights**（动态光源）、**Mod Menu**（配置入口）

多人游戏：服务端也装 LTE 时，碰撞、撞飞、伤害由服务端结算；只装客户端也能正常使用。

---

## 常用指令

主指令 `/mtr_lte`（旧别名 `/externallights`），无参数打开控制面板。

完整清单见 [`docs/COMMANDS.md`](docs/COMMANDS.md)。

```text
/mtr_lte beam3d [车型id]        3D 光束编辑器
/mtr_lte headlight auto|always|off
/mtr_lte rain on|off|status|force [on|off]
/mtr_lte trains [status|on|off|reload]
/mtr_lte cone [status|volume on|off|occl on|off|steps N]
/mtr_lte debug coneSource model|glow
/mtr_lte debug cone on|off | coneBoost on|off | spray on|off | killself [low|high]
```

---

## 构建

需要 JDK 21 与 Gradle 8.14（工程自带 wrapper 是 8.8，fabric-loom 1.11.8 要求 ≥8.14）。

```bash
JAVA_HOME="C:/Program Files/Java/jdk-21.0.10" \
  "/d/MC-MTR-JS/temp/gradle/gradle-8.14/bin/gradle" -p v1.20.1 clean build --no-daemon
```

产物：`v1.20.1/build/libs/lte-<version>.jar`。版本号自动为 `1.0.<git 提交数>`。

四个版本各自独立构建：

```bash
for v in v1.18.2 v1.19.2 v1.19.4 v1.20.1; do
  gradle -p $v clean build --no-daemon
done
```

---

## 目录结构

```
common/     跨版本共享逻辑（配置、车灯、几何、雨、碰撞模型…）
v1.18.2/    版本相关：渲染、BE/BER、界面垫片、资源
v1.19.2/
v1.19.4/
v1.20.1/
docs/       设计与排障文档（每个功能都有对应报告）
tools/      构建、发布、校验脚本
```

---

## 已知限制

- 光柱体积光（raymarch）仍为实验特性，默认关闭，1.19.x/1.18.2 退化为加法壳层
- 方速信号灯「同步 MTR 信号」档在 1.3.2 为空实现，由本模组补驱动
- 活塞/爆炸/`/setblock` 移除绑定方块不会触发自动解绑（只会灭灯或空屏，不会崩）
- CurseForge 上传受网络限制，暂只发布 GitHub / Modrinth

## 致谢

- [Minecraft Transit Railway](https://github.com/jonafanho/Minecraft-Transit-Railway)（MIT）—— 屏蔽门实现移植来源
- 天津地铁模组（Tianjin Metro – MTR3 Adapted Edition）：ZiYueCommentary (ziyue)、jiangxingyunjian、\_\_Lingxii\_\_ (lingxii) —— PIDS 的屏幕渲染、广告系统与挂杆，以及屏蔽门的继承方式参考其公开行为实现
- MTR 车站装饰（MSD, MTR Station Decoration Addon）：AIDA64S、Santorsia、Nemo、Zbx1425 —— **墙上扶手与玻璃扶手的模型均基于其修改**（命名空间/几何/碰撞盒）
- 南滨创意（Nanbin Create Mod，MIT）：Nanbin-Studio、LIEH-SAD —— 闸机音效（`nanbin:crt_ticket`）
- 真实城市建设（RCC / Reality City Construction，LGPL-3.0）：CodeCat、yaoxiansen —— 站台方块的模型与贴图
- 厦门地铁实拍与素材：本项目作者
- 贡献者：天童アリス、596wjr、Bai.
