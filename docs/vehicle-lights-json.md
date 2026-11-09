# 车辆灯位数据层 (`lte_vehicle_lights.json`)

状态：数据层完成（加载 / 匹配 / 合并 / 导出 / 热重载 / 诊断）。光束编辑器的 **界面** 尚未接入，
按照下面的 API 调用即可。

## 1. 格式

资源包路径：`assets/<namespace>/lte_vehicle_lights.json`（namespace 任意，不限于 `mtrlte`）。

```json
{
  "version": 1,
  "vehicles": [
    {
      "id": "mypack_train_*",
      "beams": [
        { "type": "head", "color": "#FFF3D6", "pos": [0.85,1.2,10.8],
          "rot": [0,0,0], "length": 16, "width": 3, "alpha": 0.16 }
      ]
    }
  ]
}
```

| 字段 | 含义 |
|---|---|
| `id` | MTR 的车型 id（`baseTrainType`）。结尾 `*` = 前缀通配（`mypack_train_*`） |
| `beams[].type` | `head` / `tail`，也可以是包作者自定义的名字（`marker` 等） |
| `beams[].color` | `#RRGGBB`（也接受 `RRGGBB`、`0xRRGGBB`、`#AARRGGBB`、整数） |
| `beams[].pos` | 车厢局部模型坐标，见下 |
| `beams[].rot` | 角度 `[yaw, pitch, roll]` |
| `beams[].length` / `width` | 光束长度 / 直径（方块） |
| `beams[].alpha` | 0..1 |

**pos 的含义**：车厢局部模型坐标 —— `x` 横向、`y` 向上、`z` 沿车长，车厢中心在原点，
两端面在 `±length/2`；模型的长度轴是 X 时，"along" 那一维就是 `pos[0]`（坐标始终按模型自己的轴写）。
所以 `pos` 是直接从 bbmodel 上读到的数，不需要换算世界坐标。

**所有字段（除 `type`）都可省略**：省略 = 从自动识别继承。只写
`{"type":"head","color":"#FF0000"}` 就是"把头灯染红，位置/长度/透明度都不动"。

## 2. 匹配规则

1. 精确 id 优先于通配；
2. 通配之间比较前缀长度，**更长（更具体）的前缀赢**；
3. 同一精度下 runtime（编辑器 `put`）优先于资源包；
4. 同一层里后加载的赢（资源包堆叠顺序，上面的包覆盖下面的包）。

匹配忽略大小写。

## 3. 优先级链（合并）

```
资源包声明  >  runtime 编辑（put）  >  自动识别 VehicleLights  >  自动识别的默认位
```

* 自动识别（`VehicleLights`）给出每个车型的 head / tail 灯组位置，自己再兜底到"默认一对灯"。
* 资源包按 **字段** 覆盖：颜色、pos、rot、length、width、alpha 各自独立继承。
* 按 **类型** 覆盖几何：文件里给了某类型的 `pos`，该类型的自动几何整体让位（写一个头灯就是
  一个头灯）；只改颜色时，自动识别出的一对灯会被 **保留成两个**，两个都变色。
* 文件没提到的类型（例如只写 head）保住自动的 tail。

## 4. 资源来源

| 侧 | 来源 | 说明 |
|---|---|---|
| 客户端 | 资源包堆叠（`assets/**`）+ `config/lte_lights/**` | 资源包按堆叠顺序，上面的包优先 |
| 服务端（dedicated） | `config/lte_lights/**` + `<world>/datapacks/**` | 服务端 `ResourceManager` 只挂 `data/`，`assets/` 里的文件看不到，所以这两处是服务端的同一份格式 |

单人游戏里服务端不做额外扫描 —— 它和客户端同一个 JVM，客户端那份（含资源包）就是它的视图。

`config/lte_lights/` 里放的就是 `export()` 出来的文件，客户端导出 → 丢到服务端 config 即可。

## 5. 热重载

* 客户端：F3+T / 增删资源包 → `ResourceManagerHelper` 的 reload listener 重扫；`reload()` 清缓存
  （资源包层、自动识别缓存、匹配缓存、`VehicleLights` 的模型布局缓存），**保留 runtime 编辑**。
* 服务端：`/reload`（`END_DATA_PACK_RELOAD`）→ 重扫 config + datapacks。

## 6. 诊断

启动 / 每次重载后打印一行（内容没变则不重复打印）：

```
[lte] lights json: packs=2 vehicles=3 entries=4 failed=0
```

* `packs` 提交过至少一个可用文件的来源数（资源包名或 `config` / `datapacks`）
* `vehicles` 不同 id 数（含 runtime）
* `entries` 灯条目数
* `failed` 完全解析失败的文件数（每个文件只 warn 一次）

单个坏文件不影响其它包、不影响游戏；文件内部的坏字段只跳过该字段（继承自动识别）。

## 7. 给 UI 的 API（`dev.js.mtrheadlight.VehicleLightOverrides`）

```java
List<Vehicle> list();                 // 全部条目快照（pack 层 + runtime 层）
List<String> ids();                   // 全部 id
Vehicle get(String vehicleId);        // 命中该车型的条目（精确/通配），null = 未声明
List<Beam> beams(String vehicleId);   // 命中条目的灯（副本）

void put(String vehicleId, List<Beam> beams);   // 整组替换（runtime，优先级最高）
void put(String vehicleId, Beam beam);          // 按 type 替换/追加一行（编辑器行"应用"）
boolean remove(String vehicleId);               // 丢掉 runtime 覆盖
void clearRuntime();
boolean hasRuntimeEdits();
long version();                       // 每次变更 +1，UI 用来失效自己的缓存

List<Beam> resolve(String vehicleId, VehicleLights.Layout layout);   // 合并后的有效布局（模型坐标）
List<Resolved> resolved(String vehicleId, VehicleLights.Layout layout); // 渲染器可用数值
Path export(Path path) throws IOException;    // 写当前有效布局为资源包格式
String exportJson();
void reload();
String diagnosticLine();
void load(List<VehicleLightJson.Source> sources);   // 由 VehicleLightPacks 调用
```

`Beam` 全部字段 public 且可空（`color`/`pos`/`rot`/`length`/`width`/`alpha`）；null = 继承。
`list()`/`get()` 返回的是副本：改完必须 `put` 回去才会生效。

线程安全：一把 `ReentrantReadWriteLock`；渲染线程/服务端 tick 读、资源重载线程与客户端线程写。

## 8. 导出样例

`export(Path)` 写出的是"当前运行期的有效布局"，具体车型 id（不带通配），并带 `_comment`：

```json
{
  "version": 1,
  "_comment": [
    "LTE vehicle light layout - generated file, editable by hand.",
    "Place it at assets/<your_pack>/lte_vehicle_lights.json in a resource pack (client) or in a",
    "datapack / config/lte_lights on the server.",
    "id supports a trailing '*' wildcard (prefix match); an exact id wins over a wildcard,",
    "and a longer wildcard prefix wins over a shorter one.",
    "Fields you leave out are filled in from LTE's automatic light detection.",
    "pos is car-local model space: [lateral, height, along] in the model's own axes,",
    "so on a model whose length axis is X the 'along' number is pos[0].",
    "rot is degrees [yaw, pitch, roll]; length/width are blocks; alpha is 0..1."
  ],
  "vehicles": [
    {
      "id": "mtr:train_x",
      "beams": [
        { "type": "head", "color": "#FFAA00", "alpha": 0.3 }
      ]
    },
    {
      "id": "mypack_train_7",
      "beams": [
        { "type": "head", "color": "#FF0000", "pos": [-1.0,0.55,9.0], "length": 16.0, "width": 3.0, "alpha": 0.16 },
        { "type": "head", "pos": [1.0,0.55,9.0], "length": 16.0, "width": 3.0, "alpha": 0.16 },
        { "type": "tail", "pos": [-1.0,0.55,-9.0], "length": 8.0, "width": 3.0, "alpha": 0.16 },
        { "type": "tail", "pos": [1.0,0.55,-9.0], "length": 8.0, "width": 3.0, "alpha": 0.16 }
      ]
    }
  ]
}
```

（真实导出是每行一个字段的缩进格式，数值数组内联；上面为了排版压缩过。）
通配条目会被展开成运行时真的见过的具体 id；没匹配到任何车、但确实写了的通配条目会原样保留。

## 9. 文件

| 文件 | 作用 |
|---|---|
| `common/.../VehicleLightJson.java` | 格式解析/写出、坏字段处理、目录扫描（纯 Java + Gson） |
| `common/.../VehicleLightOverrides.java` | 注册表：匹配、合并、导出、热重载、诊断、线程安全、`Resolved` 视图 |
| `v1.20.1/.../VehicleLightPacks.java` | 资源管理器适配：客户端堆叠顺序、服务端 config/datapacks |
| `v1.20.1/.../VehicleLightsJsonClient.java` | 客户端入口：reload listener + 启动兜底扫描 |
| `v1.20.1/.../VehicleLightsJsonServer.java` | 服务端入口：SERVER_STARTED / END_DATA_PACK_RELOAD |
