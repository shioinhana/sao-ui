# betterdisplay 1.21.11 — mcui 多人界面移植

> 架构、双持输入映射、网络协议、UI 结构等设计细节见 [DESIGN.md](DESIGN.md)。

Minecraft **1.21.11** / Forge **61.0.6** / Java 21 的 Better Display 源码工程。
基础是 `src.rar` 里的官方源码，多人联机界面（mcui 那套）在此基础上移植进来。

## 目录

```
betterdisplay-1.21.11\
├─ src\main\java\com\daoyou\betterdisplay\
│  ├─ BetterDisplayMod            入口
│  ├─ BetterDisplayConfig         CLIENT 配置（新增 showRemoteUi）
│  ├─ BetterDisplayClient         双持输入 / HUD / SAO 入口 / 世界渲染帧通道
│  ├─ BetterDisplayCombat         战斗槽和 betterdisplay:combat 网络通道
│  ├─ QuickHotbarInput / QuickHotbarUse  数字键速用
│  ├─ BetterDisplayInventoryInput / BetterDisplayInventory  换排与拾取
│  ├─ BetterDisplayInventoryScreen SAO 菜单与在线玩家界面
│  ├─ BetterDisplayNetworking     联机 UI 网络入口
│  ├─ RemoteUiSync                服务端采集与广播
│  ├─ RemoteUiRenderer            世界内把面板贴到其他玩家头侧
│  ├─ RemoteUiStateStore          客户端快照缓存（5 秒过期）
│  ├─ RemoteUiStatePacket         S→C 快照包
│  ├─ SaoMenuUpdatePacket         C→S 菜单页上报
│  ├─ UiStateSnapshot             快照（UUID + SaoMenuState）
│  ├─ SaoMenuState / Profile / Item   数据模型
│  ├─ SaoMenuCodec                线缆编解码
│  ├─ SaoMenuModel                菜单树 / 行布局
│  └─ SaoMenuRenderer             面板绘制（376x142）
├─ src\main\resources\            资源（含 profilebg.png 与 27 个菜单图标）
├─ src-original-backup\           src.rar 原始源码备份（未改动）
├─ tools\                         构建脚本
├─ build\                         中间产物
└─ dist\betterdisplay-1.21.11-1.2.3-mcui.jar   可加载产物
```

## 移植自 1.19.4 mcui 的东西

| 1.19.4 | 1.21.11 对应 | 说明 |
| --- | --- | --- |
| `SaoMenuState` | `SaoMenuState` | 原 6 字段 + 拔刀/双手，让远程战斗条一起显示 |
| `SaoMenuProfile` | `SaoMenuProfile` | name/health/maxHealth/level/armor/expPct/attack/speed |
| `SaoMenuItem` | `SaoMenuItem` | id + count |
| `UiStateSnapshotKt.writeSaoMenu/readSaoMenu` | `SaoMenuCodec` | 字段顺序、上限、clamp 全部一致 |
| `SaoMenuUpdate` | `SaoMenuUpdatePacket` | C→S |
| `RemoteUiStateStore` | `RemoteUiStateStore` | UUID → 快照，5 秒失效，null 即移除 |
| `SaoMenuModel.rows/showsProfile` | `SaoMenuModel` | 根行 25x20 / 96x18，子行 x=142 宽 89 |
| `SaoMenuRenderer` | `SaoMenuRenderer` | WIDTH 376 / HEIGHT 142 / PROFILE_LEFT -92 / 环 12 格 |
| `RemoteUiRenderer` | `RemoteUiRenderer` | 距离 32、anchor 偏移 0.78/0.45/0.25、侧视抬升 70 |
| `UiStatePublisher` + `SaoMenuUpdate.forPlayer` | `BetterDisplayClient` / `RemoteUiSync` | 发送菜单变化，服务端校验并同步给 32 格内玩家 |

## 关键常量（与 1.19.4 逐项对齐）

```
面板          376 x 142
档案框        x = -92, 宽 83, 高 128, 内边距 7
快捷栏环      中心 (-51, 58), 半径 (31, 43), 12 格, 每格 30°
面板缩放      0.016 方块/像素
侧视过渡      0.4，纯侧视抬升 70 像素
广播/显示半径 32 格（1024 的平方）
心跳          客户端每 10 tick 更新打开的菜单，服务端定期刷新附近玩家的快照
```

## 构建

本机 PowerShell 会破坏长参数，所以编译走 Python：

```powershell
Set-ExecutionPolicy -Scope Process Bypass -Force
$py = "C:\Users\Administrator\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe"

& .\tools\classpath.ps1                       # 生成 build\classpath.txt
& $py .\tools\deploy.py                      # 编译 + 打包 + 自动装入 1.21.11-Forge
& $py .\tools\check_logs.py                  # 扫描实例日志，过滤离线登录噪声
& $py .\tools\make_at_jar.py                 # 生成 AT 后的 client jar
& $py .\tools\build.py                       # 编译到 build\classes
& $py .\tools\package.py                     # 打包到 dist\
```

验证：

```powershell
& $py .\tools\check_metadata.py              # mods.toml / pack.mcmeta 合法性
& $py .\tools\codec_roundtrip_test.py        # 编解码往返 17 项
& $py .\tools\packet_roundtrip_test.py       # 包 / 缓存 11 项
& $py .\tools\repro_from_json.py             # 按 HMCL 版本 json 真机启动一次
```

## 三个必须知道的坑

**1. `mods.toml` 不能留 Gradle 占位符（真机启动必炸）**

`src.rar` 里的 `META-INF/mods.toml` 是 MDK 模板，里面是 `${mod_id}` / `${mod_version}`。
这些由 ForgeGradle 在构建期替换；用 javac 直接打包会原样留下，FML 启动时直接抛：

```
ParsingException: Invalid bare key: '${mod_id}'
```

表现为**黑屏两秒后进程退出、无 crash-report、latest.log 只写到 Mixin 那行**。
现在已全部写成字面量，`tools\check_metadata.py` 会拦住这种回归。

**2. 编译期缺 AT / 缺 Forge 织入接口**

Forge 运行时做访问转换，但 `client-1.21.11-official.jar` 里这些成员还是 `protected`／
私有，而且 `IForgeItemStack` 等接口是**运行时织入**的。
`tools\at_patch.py` 会：

- 把 `tools\at_widen.json` 里列出的成员改成 public
  （`ClientInput.moveVector`、`AbstractContainerScreen.renderSlotHighlight*`）；
- 把 Forge universal / client / eventbus 里的 `IForge*` 接口**内联进 jar**；
- 给 `Entity` / `Player` / `ItemStack` / `Level` 等类**补上 `implements`**，
  这样 `getPersistentData()`、`getSweepHitBox()`、`KeyMapping#getKey()` 才能解析。

产物 `build\libs\client-1.21.11-at.jar` **只用于编译**，不会进游戏。
再撞到类似问题，往 `at_widen.json` 加一条重跑即可。

**3. 协议两端必须同版本**

`SaoMenuCodec` 里 1.19.4 的 count 字段是定长循环，没有长度前缀；
1.21.11 新增的 inventory 列表写成了带长度的形式。改了字段顺序就要两边一起换。
`betterdisplay:combat` 使用协议版本 `5`。客户端和服务端都需要更新。消息 10 上报菜单路径、焦点、滚动位置、当前物品页和打开菜单时的朝向，关闭菜单时清除快照。消息 13 用于切换快捷栏整排。


## 快捷键与拾取

| 操作 | 作用 |
|---|---|
| Tab | 打开 SAO。「社交 → 在线玩家」可查看玩家名单、延迟和服务器页眉页脚 |
| E | 打开原版背包 |
| 滚轮 | 逐格切换快捷栏 |
| Shift + 滚轮 | 在快捷栏和背包三排之间循环切换，保留当前选中的列 |
| 按住 1–9 | 食物和可饮药水按原版使用时长结算，提前松手即可取消 |
| 按下 1–9 | 方块朝准星安放，投掷类物品释放一次。按住可重复 |

数字键速用只在双手都有物品时启用，使用时保留当前主手选中槽。任一手为空时，数字键按原版切换快捷栏。捡到物品时优先补充快捷栏中的同类堆叠和空槽。

SAO 沿用原版「玩家列表」键位，可在控制设置中修改，默认是 Tab。

双手都持有物品时，左键和右键分别用副手与主手攻击或挖掘。按住 Shift 后，两键分别执行对应手的使用和交互。任一手为空时，鼠标沿用原版行为。

双持时，Shift＋点击优先使用对应手里当前可吃喝的物品。其余情况会先尝试打开箱子、工作台等方块界面，或与准星指向的实体交互。目标未处理时，继续尝试使用对应手的物品。吃喝、拉弓等动作可以按住鼠标，松手按原版规则结算或中止。

实现详见 [DESIGN.md](DESIGN.md) 第 4.7–4.9 节。

## 游戏内测试

把 `dist\betterdisplay-1.21.11-1.2.3-mcui.jar` 放进
`C:\Users\Administrator\Desktop\.minecraft\versions\1.21.11-Forge\mods`，用 `1.21.11-Forge` 启动。

已实测：装 jar 后启动 → 主菜单 → 进入存档并连续渲染约 5 分钟，无异常、无渲染报错。
在「设置 → 选项 → 界面」里可关闭「共享菜单」（`shareUiState`，默认开启）。配置项 `showRemoteUi` 控制自己是否看见其他玩家的面板。
第一人称打开 SAO 时，菜单图标列在视线中央。移动鼠标可转动视角并查看展开的菜单，鼠标回到中央时回到打开时的朝向，退出 SAO 后从当前朝向继续游玩。
拔刀后，V 点击攻击准星指向的实体，按住 V 用副手挖掘准星指向的方块。X 使用副手物品。

远程面板在同世界、相距 32 格内且视线无遮挡时显示。玩家关闭 SAO 或关闭共享后，面板消失。共享范围是 BetterDisplay 自带菜单的页面和物品列表，弹窗和外部扩展页面留在本地。

## 已知版本迁移提示

加载旧版 `config\betterdisplay-client.toml` 时，Forge 会清理旧的 `replaceInventory` 配置键，
这是 Forge 正常的配置补全，日志里的 `is not correct. Correcting` 属预期行为。
