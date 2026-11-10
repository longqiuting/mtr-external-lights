# 信号连接器：反向顺序 + 灯→灯同步 + 去掉「绑完灯自己转一下」

范围：`mtr-headlight` / `v1.20.1` / MTR-fabric **3.6.4.2**。只改了 `signal/*`、`RenderSignalBaseMixin`、
`LteServer`（进服同步）、lang。**没有启动游戏**。

---

## 1. 三种两次右键

| 第一次 | 第二次 | 落盘 | 渲染时 |
|---|---|---|---|
| 轨道节点 | 信号灯 S | `links[S] = node` | S 的 `getNodePos` 返回 `node` |
| 信号灯 A | 轨道节点 | `lampLinks` 清掉，`links[A] = node` | 同上（A 是「后点的那个」） |
| 信号灯 A | 信号灯 B | `lampLinks[B] = A`，`links[B]` 被清 | B 的节点 = A 的节点；B 的角度 = A 的角度 |

对同一盏灯两张表互斥（`SignalLinkStore#put` 清 lamp 源、`#putLamp` 清节点绑定）：
一盏灯只有一个「显示什么」的答案，留两条就变成「谁先谁后谁赢」的隐式规则。

物品 NBT 存第一步：`lte_node` / `lte_lamp` / `lte_dim`（三种都只有一个在生效）。客户端只读
（`SignalLinkItem#selectedNode` / `#selectedLamp`，给 `SignalNodeHighlight` 用）。

## 2. 新增的包字段

| 位置 | 字段 | 含义 |
|---|---|---|
| `SignalLinkStore` | `lampLinks: Map<BlockPos, BlockPos>` | 灯 pos → **源**灯 pos |
| `SignalLinkStore` (NBT) | `lamp_links[] {lamp, source}` | 落盘，与 `links[] {signal, node}` 并列 |
| `SignalLinks` (客户端) | `LAMPS: Map<维度, Map<灯, 源灯>>` | 服务端表的镜像 |
| `SignalLinkNet` | kind `3` LAMP_SYNC / `4` LAMP_PUT / `5` LAMP_REMOVE | 新包类型，不改老的 0/1/2 |

老客户端收到未知 kind 直接 `default -> {}` 忽略，节点表照常同步；老服务端不发 3/4/5，
新客户端把 LAMP 表留空 → 行为退化成「没有灯→灯」，不会因为多读几个字节把整包读崩。

## 3. 循环保护（两层）

1. **服务端建链时拒绝**：`SignalLinkStore#putLamp` → `reaches(source, lamp)` 顺着
   `lampLinks` 从 source 往上走，走到 lamp 就是环 → 返回 `false`，物品回
   `message.mtrlte.signal_link.lamp_cycle`。自指（`lamp == source`）同样拒绝。
2. **客户端解析时兜底**：`SignalLinks#resolveSource` 用访问集 + `MAX_HOPS = 64`，
   检测到环 / 链太长 → 返回 `null` → 这盏灯退回 MTR 原行为（不炸栈、不互相引用）。
   旧存档被手改、包丢了半条都落在这条路径上。

渲染侧不需要重入保护：`@ModifyVariable` 只换形参，不重入。

## 4. 朝向：绑定过程不再写方块

- 删掉了 `alignFacing(...)`（老版本在这里 `world.setBlockState(signal, state.with(HORIZONTAL_FACING, dir), 3)`
  —— 「绑完灯自己转一下」就是它）。
- 现在朝向只做**只读判定**（`facingMismatch`）：灯朝向和节点上的轨道一段都对不上时，聊天栏提示
  「可能不亮，请手动转动灯的方向」，不改世界。
- 证据（源码）：`grep -rn "setBlockState" signal/ mixin/mtr/RenderSignalBaseMixin.java` →
  只命中两行**注释**，无调用。
- 证据（产物字节码）：反编译 `SignalLinkItem.class` / `RenderSignalBaseMixin.class`，
  无 `setBlockState` / `method_7150` / `method_30092` / `updateNeighbors` 指令；世界交互只剩
  `method_8320`（`getBlockState`，读）。

## 5. 渲染链（灯→灯）

```
getNodePos(view, B, facingB)            @Inject HEAD
  source = SignalLinks.resolveSource(view, B)        // 顺着 lamp 链走到终点 A
  nodeA  = SignalLinks.nodeFor(view, A)              // A 绑了节点
        ?: lte$scanNode(view, A, facingA)            // 否则照 MTR 的扫描（javap 逐字照搬）
  capture = { facingB, facingA, nodeA }
  return nodeA;                                      // 不返回 → render 第 89 行就 return 了

getOccupiedAspect(nodeA, angle)         @ModifyVariable index=2 (argsOnly)
  angle' = A 的角度 + 相对角(0° / 180°)              // 正面→正面、背面→背面
  → MTR 自己的 similarFacing / SIGNAL_BLOCKS / OCCUPIED_RAILS 一字不改
```

用 `@ModifyVariable` 换形参而不是 `@Shadow` 私有方法 + 递归：不复制 `ClientData` 遍历、
不用反射，`required:false` + `defaultRequire:0` 时最差只是退化成原行为。

## 6. 构建 / 产物

```
gradle clean build --offline   → BUILD SUCCESSFUL     (v1.20.1, JDK 21)
gradle build --offline         → BUILD SUCCESSFUL
jar: v1.20.1/build/libs/lte-1.0.0.jar
     sha256 97b900ce…eaacdc（jar 内嵌时间戳，每次构建都不同）
     已拷：桌面 lte-1.0.0+1.20.1.jar、实例 D:/MC-MTR-JS/temp/mcrun/mods/
```

产物里已核验：`RenderSignalBaseMixin` 的两个 handler 描述符被 remap 成
`class_1922 / class_2338 / class_2350`；两个注入点分别是
`@Inject(method="getNodePos", HEAD)` 与
`@ModifyVariable(method="getOccupiedAspect", argsOnly=true, index=2, HEAD)` 注解齐全；
`SignalLinkStore` 有 `lampLinks` 字段 / `putLamp` / `lampSourceAt`；
`signal/` 整个包的字节码里没有任何 `setBlockState`（`method_7150` / `method_30092`）调用。

已知未验证项：mixin 在**运行期**是否真的应用（不启动游戏无法确认；失败时按配置静默退回
MTR 原行为）。游戏内建议按 `docs/signal-link-feasibility.md` §7 走一遍，额外加两条：
灯→灯 与 灯→节点 两种顺序，以及「绑定后灯不再自己旋转」。
