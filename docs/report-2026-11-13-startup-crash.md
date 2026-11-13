# 2026-11-13 · 1.20.1 启动即闪退（1.0.43）

## 症状

窗口刚出来就关掉，没有 crash-report，`logs/latest.log` 末尾：

```
[Render thread/ERROR]: Unhandled game exception
java.lang.IllegalStateException: Some intrusive holders were not registered: [Reference{null=Block{minecraft:air}}]
    at net.minecraft.class_2370.method_40276(SimpleRegistry.java:364)   // Registry#freeze
    at net.minecraft.class_7923.method_47491(Registries.java:239)
    at net.minecraft.class_7923.method_47476(Registries.java:223)
    at net.minecraft.class_310.handler$bfe001$fabric-registry-sync-v0$onStart(MinecraftClient.java:9539)
```

崩溃发生在 **registry 冻结**（客户端 init 末尾、资源重载之后），与资源包/模型无关。

## 定位过程（每一步都是实跑，不是推理）

用一个不经启动器的裸启动脚本（`temp/launch-mc.js`，读版本 json 拼 classpath）复现，从进程启动到抛异常约 13 秒，比手点启动器快得多。

| # | 改动 | 结果 |
|---|---|---|
| 1 | 1.0.40（上一版） | 正常 |
| 2 | 1.0.43 原样 | 崩 |
| 3 | 全部新内容关掉（闸机 / 机器 / 挂顶 DEP / logo / RCC 站台） | 不崩 |
| 4 | 只关闸机 + 机器 | 不崩 |
| 5 | 只关闸机 | **崩** → 嫌疑锁定在售票机 / 余额机 |
| 6 | 只留售票机（关余额机） | 崩 |
| 7 | 只留余额机（关售票机） | 崩 |
| 8 | 同样两个 id、同样 settings，但注册成普通 `Block` | 不崩 |
| 9 | 注册成 `XiamenMachineBlock` 的匿名子类（不设默认状态） | 不崩 |
| 10 | 真实子类 + 去掉构造器里的 `setMachineDefaultState(...)` | **不崩** |
| 11 | 把 **方速（fangsu）** 从 mods 移走，其它不动 | 不崩 |
| 12 | 保留方速、把余额机里的 `mtr.data.TicketSystem.BALANCE_OBJECTIVE` 换成字面量 `"mtr_balance"` | **仍然崩**（排除 MTR 类提前加载） |
| 13 | 保留方速、去掉 `FangsuTicketUi.loaded()` 调用 | **仍然崩**（排除我们的方速探测） |

结论：崩溃需要 **「方速在 + 我们的方块在构造器里调用 `setDefaultState(...)`」** 这一组合。

## 修复

`Block` 的默认状态不再在构造器里改：

- 删掉 `XiamenMachineBlock#setMachineDefaultState(...)` 及其在两个子类构造器里的调用；
- 售票机的 `ui` 属性默认值保持枚举第一项，改成 **放置时写入**（`getPlacementState` 里 `.with(UI, defaultUi())`）；
- `appendProperties` 照旧注册 `HALF` / `UI`，属性默认值由 `Property` 自己提供（`facing=north`、`half=lower`），放置路径本来就会把 `FACING` / `HALF` 写全，所以行为不变。

顺带修掉同一批的两处模型错误：

- `balance_machine_xiamen_top/bottom/item.json` 里 `-12.5°` → `-22.5°`（原版只接受 `-45/-22.5/0/22.5/45`）；
- `balance_machine_xiamen_item.json` 的 `to.y = 32.5` 超界 → 整模型下移 0.5（`0..32.5` → `-0.5..32`）。

## 验证

干净启动到标题画面（`timeout 90` 强杀，非崩溃）：

```
intrusive holders : 0
Failed to load model mtrlte… : 0
Unhandled : 0
[lte] ticket machines: mtrlte:ticket_machine_xiamen (default ui=FANGSU) / mtrlte:balance_machine_xiamen
```

## 教训

- **方块的默认状态不要在构造器里改**。`setDefaultState(...)` 会在方块还没注册进 registry 时就把状态建出来，和某些模组（这里是方速）一起装时会让 registry 冻结阶段发现「空键的 holder」。放置时写属性没有任何代价，永远更安全。
- 这类崩溃不会生成 crash-report，只有 `latest.log` 里那三行 `SimpleRegistry.freeze` 栈——先看日志，别去翻资源包。
