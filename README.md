# mtr-headlight

给 **MTR（Minecraft Transit Railway）3.x** 列车加动态车头/车尾灯光的 Fabric 模组（客户端）。

- **不放置、不修改任何方块**
- **不修改 MTR**：不 mixin、不反射，只读 `ClientData.TRAINS` 与 `Train` 的公开成员
- 光照通过 **LambDynamicLights 4.x 公开 API**（`DynamicLightBehavior` / `LineLightBehavior`）注入
- 目标：Minecraft **1.20.1**、Fabric

> 详细调研、证据与多版本成本见 `..\docs\headlight-mod-feasibility.md`。

## 现状

代码已完成并通过离线类型校验（`tools/typecheck`），但**尚未产出 jar**：
构建机上 `maven.fabricmc.net` 下行被限到 ~432 B/s，Gradle 卡在解析 `fabric-loom` 插件。
这不是代码问题，换到能访问 fabric maven 的网络执行一次构建即可。

## 构建

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.10"
cd D:\MC-MTR-JS\mtr-headlight
.\gradlew build
# 产物: build\libs\mtr-headlight-0.1.0.jar

# 装进实例
Copy-Item build\libs\mtr-headlight-0.1.0.jar `
  "C:\Users\AD\Desktop\PCL\.minecraft\versions\1.20.1-Fabric 0.19.3\mods\"
```

依赖说明：

| 依赖 | 来源 | 打包进 jar？ |
|---|---|---|
| Minecraft 1.20.1 / Yarn 1.20.1+build.10 | maven.fabricmc.net | 否 |
| fabric-loader 0.16.9 | maven.fabricmc.net | 否 |
| fabric-api 0.92.12+1.20.1 | maven.fabricmc.net | 否 |
| `libs/lambdynamiclights-api-4.4.0+1.20.1.jar` | 从已安装的 LDL 主 jar 的 `META-INF/jars/` 抽出 | 否（`modCompileOnly`，运行时由 LDL 提供） |
| `libs/MTR-fabric-1.20.1-3.6.4.2.jar` | 实例 mods 目录 | 否（`modCompileOnly`） |

`build.gradle` 里没有任何 `include(...)`，所以上面的本地 jar **不会**被打进产物；
`fabric.mod.json` 里对它们的依赖是 `depends`（运行时必须已安装），符合预期。

## 运行时依赖

- Minecraft Transit Railway 3.6.x（`mods/MTR-fabric-1.20.1-3.6.4.2.jar`）
- LambDynamicLights 4.4.0+（`mods/[Lambd的动态光源] lambdynamiclights-4.4.0+1.20.1.jar`）
- Fabric API

## 配置

首次启动生成 `config/mtr-headlight.json`：

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 总开关 |
| `onlyTrains` | `true` | 只对 `TransportMode.TRAIN` 生效 |
| `headLuminance` | `15` | 车头亮度 0–15 |
| `headLength` | `8.0` | 车头光束长度（格） |
| `headOffset` | `0.4` | 光束起点与车头端面的间隙 |
| `tailLuminance` | `7` | 车尾亮度（同色更暗，**不是红色**） |
| `tailLength` | `4.0` | 尾灯长度 |
| `tailOffset` | `0.4` | 尾灯起点间隙 |
| `heightOffset` | `1.0` | 光段相对轨面高度 |
| `maxDistance` | `192.0` | 超出距离不注册（带 1.25× 迟滞） |
| `swapEnds` | `false` | 前后灯装反时置 `true` |

## 界面（`/externallights`、键位、Mod Menu）

**全局页**七个控件，全部即时生效（改动写在内存里，最迟离开界面时落盘）：
总开关 · 渲染距离（16–512 格，步长 8）· 车灯亮度（0–15）· 光束长度（0–32 格，步长 0.5）·
轨道光照开关 · 轨道光带开关 · 「按侧线配置…」；底部是「立即重载光源」和「完成」。

- 未进入存档时「按侧线配置…」置灰不可点，鼠标悬停在该行下方显示“请先进入存档”。
- 进存档后点进去是侧线列表：`#编号 名称 · 类型 · 长度 · 节数`，右侧显示 `启用 / 自定义 / 已停用`。
  存档里没有任何侧线时显示空态“请先创建侧线”。
- 点一条侧线进入**详情页**，是同一套七个控件，但写的是该侧线的覆盖值；未改过的项带“（跟随全局）”
  后缀，「恢复为跟随全局」删除该侧线的全部覆盖项。

## 按侧线配置的存储

稀疏覆盖表，只写改过的字段（Gson 跳过 null）：

| 键 | 默认 | 说明 |
|---|---|---|
| `sidingSettings` | `{}` | 键为 `Train.sidingId` 的十进制字符串，值为下列字段的任意子集 |
| `sidingSettings.<id>.enabled` | 跟随全局 | 该侧线总开关（旧 `mutedSidings` 的替代） |
| `…maxDistance` / `…headLuminance` / `…headLength` | 跟随全局 | 同全局键 |
| `…tailLuminance` / `…tailLength` | 跟随全局 | 同全局键 |
| `…railLight` / `…railGlow` | 跟随全局 | 该侧线的轨道光照 / 光带开关 |

生效路径：`HeadlightConfig#effectiveFor(sidingId)` 把全局值与覆盖合并成一个快照，`LightController`（光束/
亮度/距离）、`RailRelightScheduler`（该侧线是否需要轨道光照）、`RailGlow`（光带）都只读这个快照，
因此“全局关闭、单条侧线打开”也能正常渲染。

`mutedSidings`（旧版“静音侧线”列表）已废弃：加载时一次性迁移成 `sidingSettings[id].enabled=false`
并清空，之后不再读写；字段保留只是为了让旧 JSON 仍能解析。

## 为什么尾灯不是红色

LambDynamicLights 4.4.0 的动态光是**标量亮度**（`DynamicLightingEngine#getDynamicLightLevel` 返回 `double`，
`CollectionDynamicLightSource$Entry` 只有 `int luminance`，整个 jar 没有任何 RGB 字段）。
原版光照引擎同样是每方块 0–15 的单值。因此红尾灯在当前技术栈**无法实现**，
本模组用「更暗的白光」表达尾灯。

## 离线校验（无需 Gradle）

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.10"
cd D:\MC-MTR-JS\mtr-headlight
$gson = "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\com.google.code.gson\gson\2.10.1\b3add478d4382b78ea20b1671390a858002feb6c\gson-2.10.1.jar"
& "$env:JAVA_HOME\bin\javac" --release 17 -encoding UTF-8 -d tools/typecheck/out -cp "$gson" `
    (Get-ChildItem -Recurse -Filter *.java src/main/java,tools/typecheck/stubs,tools/typecheck/src | % FullName)
& "$env:JAVA_HOME\bin\java" -cp "tools/typecheck/out;$gson" dev.js.mtrheadlight.PoseCheck
```

`tools/typecheck/stubs` 是按 `javap` 签名手写的最小桩，**只用于类型校验**，
不参与打包，也不能替代 Loom 的 remap 构建。
