# 资源包光柱 JSON 读不到：现场诊断与修复（1.20.1 / lte-1.0.19）

诊断对象：`resourcepacks/厦门金旅XML6125JEVD0C（连云港BRT）`，症状「编辑器/运行时都看不到光柱位置效果」。

## 1. 这个包的实际结构

```
厦门金旅XML6125JEVD0C（连云港BRT）/
├─ pack.mcmeta                      pack_format=6
├─ pack.png
└─ assets/
   ├─ lte/
   │  └─ beam3D/
   │     └─ train_12_2.json         ← 全包唯一的光柱文件（1314 B，4 束）
   └─ mtr/
      ├─ mtr_custom_resources.json  ← custom_trains 的 key
      ├─ xml6125jevd0c/             ← .bbmodel / .json / 5 张 png
      └─ sounds/xml6125jevd0c/
```

- `custom_trains` 的 key（5 个涂装）：`xml6125jevd0c`、`xml6125jevd0c_cloud`、
  `xml6125jevd0c_normal`、`xml6125jevd0c_repair`、`xml6125jevd0c_notclear`。
- **没有** `assets/<ns>/lte_beams/`。
- 文件名 `train_12_2.json` 与上面任何一个 key 都不相等。
- 包确实**已启用**：`options.txt` 的 `resourcePacks` 里有 `file/厦门金旅XML6125JEVD0C（连云港BRT）`。

## 2. 为什么读不到（三个独立原因，都有日志证据）

### 2.1 文件名用的是 MTR 运行时的车型 id，不是包的 key

MTR 对一辆自定义车同时挂三个名字：

| 来源 | 例子 |
|---|---|
| `mtr_custom_resources.json` 的 key | `xml6125jevd0c_normal` |
| MTR 注册 id | `mtr_custom_train_xml6125jevd0c_normal` |
| 渲染器 / 编辑器看到的 `baseTrainType` | **`train_12_2`**（MTR 自己生成，跟包里的 key 无关） |

`latest.log` 里三条并排的证据：

```
[19:31:17] mtr_custom_train_xml6125jevd0c_normal            ← 注册 id
[19:32:23] [lte] lights: model=train_12_2 slots=19 ...       ← baseTrainType
[19:33:37] [lte] beam editor: train_12_2.json <- ...beam3d/train_12_2.json (4 beams)
```

第三条说明**扫描本身是通的**：只要查的 id 恰好是 `train_12_2`，文件就在包里被 openRoot 读到了。
从注册表那一行（id = `mtr_custom_train_xml6125jevd0c_normal`）打开时，`read()` 只试
`xml6125jevd0c_normal.json` / `mtr_custom_train_xml6125jevd0c_normal.json` → 必然落空，
而且**当时没有任何 miss 日志**，看起来就是「模组不读包」。

### 2.2 运行时压根没有读这份文件的代码

- 运行时的资源包扫描（`VehicleLightPacks`）只认 `assets/<ns>/lte_vehicle_lights.json`。
- `VehicleLightOverrides.Resolved`（把光束换算成渲染端数值的那层）**没有任何渲染端消费者** ——
  全仓库只有 `resolve()` 自己在调它。
- `ProtoBeamCone` 画光柱时直接取 `VehicleLights.layoutFor` 的 head/tail 灯位，一车就两条锥。

所以即使文件读到了，世界里看到的也还是默认那两条 16/8 格的光柱，跟文件里的 4 束位置毫无关系。

### 2.3 附带发现：`Resolved.of` 的 inset 反算把 head/tail 分支写反了

`addSlot` 写的是 `along = (head ? front : -front) * (half - inset)`，反算却写成
`head ? half - along : along + half`，两个分支都对不上 —— 车头灯的 inset 会算成 `2*half`
（灯口落到车尾外面 14 格）。2.2 里没人用这层，所以这个 bug 一直没暴露。

## 3. 改了什么

### 3.1 统一的读取优先级（编辑器与运行时同一份）

1. `<桌面>/<id>.json` —— 编辑器上次导出的工作副本（桌面不可用时即 `config/lte_beams/`）
2. `assets/lte/beam3D/<id>.json` —— 约定的主路径，永远先看
3. `assets/<ns>/beam3D/<id>.json` —— 作者自己的命名空间
4. `assets/<ns>/lte_beams/<id>.json` —— 旧路径（兼容）
5. `config/lte_beams/<id>.json` —— 老版本导出（兼容）

同一层里后加载的包赢（栈顶压栈底）；文件名比较忽略大小写；目录名同时试 `beam3D` / `beam3d`。
新增 `read(id, aliases...)`：同一辆车的 key / 注册 id / `baseTrainType` 都可以当候选文件名
（`candidateIds` / `namesFor`，key 排最前）。

### 3.2 不再静默

- 命中：`[lte] beam pack: <文件> <- <包!路径> beams=N (id=<实际文件名>)`
- 落空：`[lte] beam pack: no file for id=<id> (tried [候选文件名…]; packs enabled=N)`
- 包在磁盘上但没启用：`[lte] beam pack: file/<包名> contains beam3D files but is NOT enabled …`
  （后台线程做，每个会话一次，不卡渲染帧；文件夹包看文件系统，zip 看条目表）

### 3.3 运行时也读这一份

- `VehicleLightOverrides.setPackBeamReader(...)`：客户端初始化时注入 `BeamPackWriter::read`
  （注入而不是直接依赖，因为 `BeamPackWriter` 只在 1.20.1 的源码集里，`VehicleLightOverrides`
  是各版本共用的）。
- `lookupOrLoad`：渲染端第一次问某个车型 id 时才去读包，读到就进 PACK 层（与
  `lte_vehicle_lights.json` 同层，编辑器 `put` 的运行时编辑照旧压过它）。**每个 id 每次资源重载
  只读一次**；已启用的包栈缓存成 `PACK_CACHE`，F3+T / 装卸包时 `invalidatePacks()` 丢弃并关句柄
  —— 不缓存的话第一次问就要打开上百个 zip，落在渲染线程上就是一帧卡死。
- `ProtoBeamCone.emitDeclared`：这个车型**有**声明表就照表画（每束自己的 pos/rot/长度/宽度/
  颜色/alpha，尾端仍受 `coneOnTail` 管），**没有**才退回自动识别的一头一尾。两条路互斥，
  否则「文件里 4 束」会变成 6 束。
- `Resolved.of` 的 inset 反算改成 `head ? half - along*front : half + along*front`。

## 4. 现在打开这份包会看到什么

`train_12_2` 这辆车（`baseTrainType`）第一次进入渲染范围时：

```
[lte] beam pack: train_12_2.json <- file/厦门金旅XML6125JEVD0C（连云港BRT）:assets/lte/beam3d/train_12_2.json beams=4 (id=train_12_2.json)
```

世界里的光柱就是文件里的 4 束：两个车头灯（`pos` ≈ ±1.0 / y≈0.37 / z=-7.056，16 格长、
3 格粗、`#FFF3D6`、alpha 0.16）和两个尾灯（z=+6.068，8 格、`#FF6A5A`）。
编辑器里选中同一辆车（列表里打 `●` 的那一行，id 显示为 `train_12_2`）也会直接带出这 4 束。

## 5. 这个包的命名要不要改

不用改，现在就能生效。想按包 key 一涂装一份的话，规范名是
`assets/lte/beam3D/<key>.json`（例如 `xml6125jevd0c_normal.json`），在编辑器里选中该涂装行再
「导出 JSON」写进包的 `assets/<ns>/beam3D/` 即可；三个 id 之间已经可以互为别名。

## 6. 验收

- `gradle build`（Gradle 8.14 / JDK 21，v1.20.1）：**BUILD SUCCESSFUL**
- jar：`桌面/lte-1.0.19+1.20.1.jar` 与 `实例/mods/lte-1.0.19.jar`，sha256
  `198e94465226e1d3c31680e77010b35f2845e517d7d03ac3e3ea6efbb434dc2c`（两份一致；
  实例里旧的 `lte-1.0.18.jar` 已删除，避免同 mod id 双份）
- 离线检查（`v1.20.1/temp/beamcheck`，无需启动游戏）：
  - `BEAM PACK CHECK OK`
  - 真实包文件解析出 4 束，`color/pos/rot/length/width/alpha` 全部到位
  - 只写 `type` 的稀疏条目仍保留，缺字段走默认值
  - 别名候选含 `train_12_2.json`，且 key 仍排第一
  - `resolved head(inset=0.00…)` / `tail(inset=0.99…)` —— 端面反算正确
