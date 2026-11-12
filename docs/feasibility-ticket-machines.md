# 可行性验证：厦门售票机 / 余额查询机 / 两种闸机

> 只做验证，未写实现代码。所有结论都来自 javap 反编译（MTR 3.6.4.2 / 方速 fangsu 1.3.2 / 天津 tjmetro 1.1.3），不是推测。

## 一、售票机（TVM）

### 结论：可行，双 UI 切换不需要任何 mixin，也不需要改方速或 MTR

**两套界面本质完全不同：**

| | MTR 售票机 | 方速售票机 |
|---|---|---|
| 触发位置 | **服务端**：`onUse` → `PacketTrainDataGuiServer.openTicketMachineScreenS2C(world, player)` | **纯客户端**：`onUse` 里 `if (world.isClient)` → `ClientHooks.openTicketMachineScreen(Text, BlockPos)`，服务端什么都不做 |
| 需要方块类型 | 不需要（GUI 只用玩家） | 不需要（`BlockTicketMachine extends Block`，连方块实体都没有） |

方速那个调用的实现只有三条指令：

```java
public static void openTicketMachineScreen(Text title, BlockPos pos) {
    ClientHooks.OPEN_TICKET_MACHINE_SCREEN.accept(title, pos);
}
```

`OPEN_TICKET_MACHINE_SCREEN` 是 `public static` 的 `Consumer<Text, BlockPos>` 字段，所以**我们可以直接调这个方法**（客户端侧、`FabricLoader.isModLoaded("fangsu")` 守卫 + 反射以避免编译期依赖），也可以直接给它赋自己的 consumer。

### 推荐实现

1. 我们的方块 `mtrlte:tvm_xiamen`（两格高，仿 MTR 的双格结构），自带一个方块实体存**本机偏好 UI**：
   ```java
   boolean useFangsuUi;   // 默认 = 方速已加载
   ```
2. `onUse`：
   - 主手是 MTR 刷子 → `useFangsuUi = !useFangsuUi`（落盘 NBT + 动作栏提示「界面：方速 / 原版」），返回 SUCCESS
   - 否则 → 按 `useFangsuUi` 分支：
     - 方速：客户端 `ClientBridge.openFangsuTicketMachine(pos)`（`ClientHooks.openTicketMachineScreen`，标题用我们自己的 i18n 键）
     - 原版：服务端 `PacketTrainDataGuiServer.openTicketMachineScreenS2C(world, serverPlayer)`
3. 未装方速时 `useFangsuUi` 强制为 false（读方块时兜底），刷子切换提示「未检测到方速，仅原版界面」。

### 风险 / 待确认

- **方速界面是否依赖它自己方块的位置校验**：`openTicketMachineScreen(Text, BlockPos)` 只接到 consumer，具体 Screen 很可能用 pos 查最近车站（和 MTR 一样按距离）。若它内部校验 `world.getBlockState(pos)` 是不是方速的机器，就需要改用「我们的机器旁边放一个隐形方速方块」或直接反射构造 `TicketMachineMainScreen`。**这一条要进游戏验一次**（装方速，右键方速自己的机器看界面是否按位置取站）。
- 方速 GUI 的文案/票价来自它自己的 `ticketSystem`，与 MTR 的 `TicketSystem` 共用同一组 scoreboard objective（见下），所以两套界面出票行为一致。
- 双格方块的放置/破坏逻辑要复用 MTR `BlockDirectionalDoubleBlockBase`（我们屏蔽门已经踩过一遍坑）。

工作量：**中等**（方块 + 双格 + BE + 刷子分支 + 客户端桥 + 模型贴图）。真正的难点是最后那条「方速界面是否认位置」。

## 二、余额查询机

### 结论：可行，而且不需要任何模组提供 API —— 余额在 scoreboard 里

MTR 自己的票务系统就把钱存在记分板：

```java
mtr.data.TicketSystem.BALANCE_OBJECTIVE      = "mtr_balance"
mtr.data.TicketSystem.ENTRY_ZONE_OBJECTIVE   = "mtr_entry_zone"
```

方速的 `com.fangsu.ticketSystem.MtrTicketSystem` **用的是同一组键**（`mtr_balance` / `mtr_entry_zone`，常量池里逐字相同），所以不管玩家用的是 MTR 原版闸机还是方速闸机，读的都是同一个余额。

### 实现

- 服务端读 `TicketSystem.getPlayerScore(world, player, "mtr_balance").getScore()`（这个方法是 public static）
- 通过我们自己的 S2C 包把余额发给点方块的玩家，客户端在自己的界面里显示（**不能客户端直读**：客户端只有被同步的 objective 才有分数，`mtr_balance` 通常不在侧边栏里）
- 界面可以直接复用 MTR 的 `DashboardScreen` 风格，或做一个小的自绘界面：大字余额 + 「最近一次进出站」可选（`mtr_entry_zone` 解码，MTR 有 `encodeZone/decodeZone` 但都是 private，得自己按 4 位打包解）

### 风险

- `TicketSystem.getPlayerScore` 是 `public static`，但它内部对 `world.getScoreboard()` 取 objective，**只在服务端有效**；客户端调用会返回 null。
- 玩家没坐过车时 objective 不存在 → 必须兜底显示 0 / 「无记录」，不能 NPE。
- 若玩家用方速的单程票（`ItemSingleJourneyTicket`）而不是记分板余额，余额这栏对它没意义 —— 可选的第二行显示「本次单程票」信息，但那是方速私有数据（`MetropolisTicketUtil` 走反射检测），**不做**。

工作量：**小**（一个方块 + 一个包 + 一个界面）。

## 三、两种闸机（进站 / 出站）

### 结论：最简单的一项，继承即可

MTR：

```java
mtr.block.BlockTicketBarrier(boolean isEntrance)
```

- 自带 `OPEN` 属性（`TicketSystem.EnumTicketBarrierOpen`）、`onEntityCollision`、`tick`、碰撞/轮廓
- 真正的判定全在 `mtr.data.TicketSystem.passThrough(world, pos, player, isEntrance, isExit, 5 个音效, boolean)`，闸机自己只是触发器和动画

天津的做法就是**混进 `passThrough`**（`ziyue.tjmetro.mixin.TicketSystemMixin`：`tjmetro$passThrough` / `tjmetro$handleTransferExit` / `tjmetro$handleEntrance` / `stationAt`），它甚至还多加了「换乘站/换乘时间」三个 objective。

所以我们的两种闸机：

- `mtrlte:ticket_gate_in` / `ticket_gate_out` 各自 `extends BlockTicketBarrier(true/false)`，只换模型贴图与音效
- **不需要任何 mixin**：`passThrough` 是现成的，天津的 mixin 打在上面，我们的闸机照样吃得到（只要玩家装天津，就自动支持换乘逻辑）
- 方速的闸机是另一套（`com.fangsu.blockEntities.BlockEntityTicketBarrier` + `TicketBarrierHandler.handle(...)`），和 MTR 的闸机互不干扰；我们做 MTR 那套即可，与天津/方速共存

### 风险

- 闸机是**两格高**? 不 —— MTR 的 `BlockTicketBarrier` 是单格 + 门扇动画，我们照它的 `getOutlineShape` 写即可
- 开门判定依赖 `TicketSystem.passThrough` 的返回枚举，音效参数要按 MTR 的传（否则会出现「能过但没声音」）
- 我们要不要自己的「卡」物品？MTR 用 `mtr_balance` 记分板，不需要物品；方速有单程票物品。**建议不做物品**，直接用余额，避免和方速的单程票体系打架

工作量：**小**（两个方块 + 模型贴图，逻辑零成本）。

## 四、建议实现顺序

1. **闸机**（最小风险，先验证我们的方块注册/模型/音效链路）
2. **余额查询机**（服务端读分 + 简单界面，顺便把「我们自己的 S2C 包」基建跑起来）
3. **售票机**（最复杂，且需要实机确认方速界面是否按位置取站）

## 四、补充结论（2026-11-12 追加）

### 闸机音效：用南滨的

`nanbin`（Nanbin Create Mod，**MIT**，Nanbin-Studio / LIEH-SAD）自带两个音效事件：

```
nanbin:crt_ticket        crt_ticket.ogg   pitch 1.0
nanbin:crt_ticket_error  crt_ticket.ogg   pitch 0.7   （同一段音频降调）
```

所以闸机开/关音效直接引用 `SoundEvents` 里的这两个 id 即可（软依赖：`isModLoaded("nanbin")` 时用，否则回落到 MTR 原音效）。不用复制 ogg，也不需要额外授权——MIT。

### 余额查询机：不做屏幕，就用文字

MTR 原版对余额就是一句文字提示，我们照做：右键 → 服务端读 `mtr_balance` → 给该玩家发一条 action bar / 聊天消息「余额：¥ X」。也就**不需要方块实体屏幕渲染**，工作量降到「一个方块 + 一次读分 + 一条消息」。

### 售票机：装了方速默认方速

已确认，`useFangsuUi` 默认值 = `FabricLoader.isModLoaded("fangsu")`，刷子右键可切回原版界面。

### RCC 站台方块：不需要你下载任何旧版本

我把 Modrinth 上 RCC 的所有版本过了一遍（项目 `reality-city-construction`，数据源 `api.modrinth.com/v2/project/.../version`，共 70 个版本），情况是：

- **1.0.3（现在装的这版）已经把站台方块的 class 删了，但资源全留着**：
  ```
  assets/tcwm/blockstates/{platform, platform_indented, platform_type_quartz}.json
  assets/tcwm/models/block/platform{,_psd,_apg}.json
  assets/tcwm/models/block/platform_indented{,_psd,_apg}.json
  assets/tcwm/models/block/platform_type_quartz{,_psd,_apg}.json
  assets/tcwm/textures/block/{platform_cn, marble}.png
  ```
- blockstate 的 when 条件就是 MTR 那套 `door_type` × `facing`（`none` / `psd` / `apg` 三档各一份模型），也就是说**只要有一个继承 MTR `BlockPlatform` 的方块，这些模型立刻能用**——和我们大理石站台是同一套路，`BlockPlatformDoorTypeMixin` 已经在生效
- 1.20.1 只发布过 7 个版本（最早 1.0.2-pre-1），更早的 1.18.2/1.19.x（2023 年的 1.0.0）才可能有 class；但既然资源齐、行为靠 MTR 提供，**没必要去翻旧 jar**

**结论**：不需要下载。做法是把这三个模型/贴图/blockstate 抄进我们命名空间（`mtrlte:platform_cn` / `platform_indented` / `platform_type_quartz`），各写一个 `extends BlockPlatform` 的空子类，单开一个创造页签。

**唯一要注意的是许可**：RCC 是 **LGPL-3.0**（我们本体是 MIT）。移植它的模型贴图属于「使用 LGPL 资源」，需要：保留版权声明、附 RCC 的 LICENSE 副本、在 README 致谢里写明来源。代码/资源本身可以放进 MIT 项目，但那部分仍是 LGPL，这一点我会在仓库里单独开一个 `LICENSES/RCC-LGPL-3.0.txt` 说明。


### 已确定的取舍（2026-11-12）

1. 售票机默认界面：**装了方速就默认方速**，刷子右键切原版。
2. 余额查询机：**不做屏幕**，照 MTR 原版，一句文字（action bar / 聊天）报余额。
3. 闸机音效：**用南滨的** `nanbin:crt_ticket`（失败配音 `crt_ticket_error`），南滨不在时回落 MTR 原音效。

剩下只等模型：三种闸机/售票机/查询机的外观素材齐了我就开工。
