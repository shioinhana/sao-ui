# Better Display 设计文档

> 版本：1.2.3 · 目标平台：Minecraft 1.21.11 / Forge 61.x（javafml:61）
> 文档对应构建：`dist/betterdisplay-1.21.11-1.2.3-mcui.jar`
> 最后更新：2026-11-15

---

## 1. 项目定位

Better Display 是一个**客户端表现 + 服务端权威**的 Forge 模组，解决三件事：

1. **双持战斗**：双手各持武器/工具时，左右键分别驱动对应手，且各手独立冷却
2. **SAO 风格界面**：通过 Tab 打开多层菜单、圆环快捷栏和人物资料卡
3. **联机面板共享**：把玩家当前菜单状态同步给附近玩家，在对方视角中渲染为悬浮面板

设计底线：**任一手为空时，攻击、挖掘和普通右键交互沿用原版鼠标行为**。

---

## 2. 运行环境与构建方式

本项目**不使用 Gradle/ForgeGradle**，而是直接对官方 client jar 编译：

| 环节 | 工具 | 说明 |
|---|---|---|
| 访问转换 | `tools/at_patch.py` + `tools/at_widen.json` | 把 `client-<ver>-official.jar` 中被 Forge 运行时放宽的成员改成 `public`，并内联 `IForge*` 接口 |
| 编译 | `tools/build.py` | `javac --release 21`，classpath = AT jar + Forge/SLF4J/JOML 等 |
| 打包 | `tools/package.py` | classes + resources 打成 mod jar，剔除 `.old/.rar/.zip` |
| 部署 | `tools/deploy.py` | 编译 → 打包 → **原子替换**到实例 `mods/` |
| 等待安装 | `tools/install_when_free.py` | 轮询尝试 `os.replace`，游戏退出瞬间自动装上 |
| 日志检查 | `tools/check_logs.py` | 扫描 `latest.log`，过滤离线登录噪声，列出崩溃报告 |

**为什么必须原子替换**：Minecraft 运行期间持有 jar（SecureJarHandler）。直接覆盖会写入半截文件，
运行时读坏 → `EOFException: Unexpected end of ZLIB input stream` / `NoClassDefFoundError` 崩溃。
`os.replace` 在目标被占用时直接失败（不破坏内容），因此可安全反复尝试。

---

## 3. 模块总览

```
客户端 (BetterDisplayClient)        服务端 (BetterDisplayCombat)
├─ 鼠标/键盘分派                      ├─ 按手动作执行（挖掘/攻击/使用/交互）
├─ 战斗 HUD 渲染                      ├─ 独立攻击冷却
├─ 菜单朝向控制                       ├─ 快捷栏自动使用（计时结算）
├─ 远程面板渲染 (RemoteUiRenderer)    ├─ 双持状态维护 + 存档
└─ 界面替换/共享上报                  └─ 联机广播 (RemoteUiSync)
```

| 文件 | 职责 |
|---|---|
| `BetterDisplayClient.java` | 双持输入、HUD、SAO 开关和视角控制 |
| `BetterDisplayCombat.java` | 双持战斗状态、按手执行和网络包分发 |
| `QuickHotbarInput.java` / `QuickHotbarUse.java` | 数字键拦截、服务端速用和计时结算 |
| `BetterDisplayInventoryInput.java` / `BetterDisplayInventory.java` | 整排切换、地面拾取优先级和背包插入 |
| `BetterDisplayInventoryScreen.java` | SAO 本地界面、资料卡、弹窗和在线玩家列表 |
| `SaoMenuModel.java` / `SaoMenuRenderer.java` | 菜单树、物品页和远程面板绘制 |
| `SaoMenuCodec.java` / `SaoMenuState/Item/Profile.java` | 菜单状态与编解码 |
| `RemoteUiSync.java` / `RemoteUiRenderer.java` | 服务端广播和客户端世界空间渲染 |
| `UiStateSnapshot*.java` / `RemoteUiStateStore.java` | 快照、数据包和客户端缓存 |
| `BetterDisplayConfig.java` | Forge 客户端配置 |

---

## 4. 双持系统（核心）

### 4.1 状态定义

双持是**纯状态推导**，不由任何按键切换：

```java
// 双手都非空，且各自都是“可战斗物品”（武器或工具）
isDualWieldPair(main, off) = !main.isEmpty() && !off.isEmpty()
    && (isWeaponOrTool(main) || isSword(main))
    && (isWeaponOrTool(off) || isSword(off))
```

服务端每 tick 由 `refreshDualWield()` 校正，结果写入 `DRAWN_KEY` 并同步客户端。
客户端另有本地判定 `hasBothHands()`：**只要某只手里有东西就视为“占用”，不等待网络往返**。

> 历史教训：早期版本用按键“拔刀”，把双手物品搬进 NBT 虚拟存档再清空原槽位，
> 一旦某步失败物品就消失。**虚拟手机制已彻底删除**，双手物品使用原版槽位。

### 4.2 输入映射

事件源：`InputEvent.MouseButton.Pre`（每次物理按下/抬起必触发），用
`CancellableEventBus` 的 Predicate 形式取消。

```
                    ┌────────── 双手都有物品？ ──────────┐
                    │ 否 → return false（100% 原版）      │
                    └────────────────┬───────────────────┘
                                     │ 是
                    ┌────────────────┴────────────────┐
              左键  │                                 │  右键
                    ▼                                 ▼
              左手（OFF_HAND）                   右手（MAIN_HAND）
                    │                                 │
        ┌───────────┴───────────┐         ┌───────────┴───────────┐
    无 Shift               Shift        无 Shift               Shift
        │                     │              │                    │
   左键功能               右键功能        左键功能             右键功能
```

| 操作 | 手 | 功能 | 行为 |
|---|---|---|---|
| 左键 | 左手 | 左键功能 | 攻击实体 / 挖掘方块 / 挥手 |
| 右键 | 右手 | 左键功能 | 攻击实体 / 挖掘方块 / 挥手 |
| Shift + 左键 | 左手 | 右键功能 | 安放 / 锄地 / 交互 / 吃喝 |
| Shift + 右键 | 右手 | 右键功能 | 安放 / 锄地 / 交互 / 吃喝 |

**关键实现约束**：

- 抬键事件 `return false` 交回原版。若取消，`KeyMapping.setDown(false)` 不执行，
  原版会一直认为鼠标键按着，持续自动攻击主手，把分派搅乱。
- 实体判定用 `Minecraft.crosshairPickEntity` 兜底，避免 `hitResult` 滞后导致“对着敌人打不到”。

### 4.3 服务端按手执行

原版所有攻击/挖掘判定只读**主手**，因此服务端在执行前把目标手物品临时换到主手：

```java
withHandAsMain(player, hand, () -> { ...原版动作... })
// 内部：保存双手 → 目标手→主手槽、副手清空 → 执行 → 还原 + 还原属性修饰符
```

| 动作 | 实现 |
|---|---|
| 挖掘 | `HandMining` 状态机：每 tick 换手后用 `getDestroyProgress` 推进 |
| 攻击 | `player.attack(target)`（附魔/暴击/横扫全走原版） |
| 使用 | `gameMode.useItem(player, level, stack, hand)` |
| 交互 | `player.interactOn(entity, MAIN_HAND)` |
| 安放/锄地 | `gameMode.useItemOn(..., BlockHitResult)` |

### 4.4 挖掘进度与裂纹

- 进度由**服务端**推进（`HandMining.progress`），完成后 `gameMode.destroyBlock`
- 裂纹：原版 `ServerLevel.destroyBlockProgress` 会**跳过破坏者本人**（假定其客户端有本地预测），
  而本模组是服务端驱动，因此额外给玩家自己补发 `ClientboundBlockDestructionPacket`
- 不校验 `hasCorrectToolForDrops`：原版允许任何物品挖任何方块（只是速度/掉落不同），
  早期加了这道校验导致雪层等方块挖不动

### 4.5 双手独立攻击冷却

原版只有一个共享的 `attackStrengthTicker`，双手共用会互相打架（一手出刀把另一手判成未蓄满）。

服务端自管两份计时：

```java
Map<UUID, long[]> HAND_ATTACK_TICKS;   // [0]=左, [1]=右
Map<UUID, int[]>  HAND_ATTACK_DELAYS;  // 由该手武器攻速算：20 / attackSpeed
```

客户端 HUD 用同一套本地计时（`HandCooldownState`）绘制冷却遮罩。

### 4.6 双持的受击无敌帧处理

原版每次受伤给目标约 10 tick 无敌，期间伤害 ≤ 上次直接丢弃。两把剑冷却都短于该窗口，
会出现“第一刀命中后另一把剑砍不掉血”。

```java
// 仅当“上一次伤害正是本玩家造成”时清零，避免蹭别人的无敌帧
if (target.invulnerableTime > 0 && lastDamageSource.getEntity() == player) {
    target.invulnerableTime = 0;
}
```

### 4.7 数字键速用

`QuickHotbarInput` 在客户端 `ClientTickEvent.Pre` 处理双持快捷栏按键。双手都持有物品时，可速用物品会触发对应操作，主手的选中槽保持原位。任一手为空时，数字键按原版切槽。普通滚轮逐格切换。

| 槽位物品 | 数字键行为 |
|---|---|
| 食物和可饮药水 | 按住达到原版使用时长后消耗一件，松手可提前取消 |
| 方块 | 朝准星指向的表面尝试安放，按住重复 |
| 喷溅药水、滞留药水、附魔瓶、烟花 | 按键尝试投掷或释放，按住重复 |

客户端立即发送首次请求，按住时每 4 tick 再发一次。服务端 `QuickHotbarUse` 按原槽位和物品跟踪吃喝计时。保活只更新最后请求时间，超过 12 tick 未收到请求则取消。结算调用原版物品效果，药水瓶等剩余物按原版规则处理。即时安放和投掷走原版物品交互，借用副手后立即恢复。

### 4.8 背包整排切换与拾取

在游戏中按住任一 Shift 并滚动鼠标，可让快捷栏与背包三排一起循环移动。向上滚从背包最靠近快捷栏的一排开始，向下滚反向循环。服务端更新槽位，选中的列保持一致。

服务端保留原版地面拾取流程，再将本次进入背包或副手的数量优先移入快捷栏。先补可堆叠的快捷栏槽，再填空槽。快捷栏没有空间时，剩余数量按原版流程处理。

### 4.9 双持入口

- `BetterDisplayClient.onMouseButtonPre` 对双手都持有物品的鼠标输入分派按手动作。
- `BetterDisplayCombat` 通过 `withHandAsMain`、`attackWithHand`、`tickHandMining` 和 `handUse` 执行动作。
- `BetterDisplayClient.clearDualStateIfSingleHanded` 清理双持挖掘和使用状态。

---

## 5. 网络协议

通道：`betterdisplay:combat`，协议版本 **5**，`acceptedVersions(exact(5))`。
**客户端与服务端必须同版本**（菜单编解码字段顺序也在其中）。

| id | 包 | 方向 | 用途 |
|---:|---|---|---|
| 0 | `EquipHandPacket` | C→S | 从菜单槽装备到某只手 |
| 1 | `ToggleDrawPacket` | C→S | 历史遗留：仅触发一次状态校正 |
| 2 | `SyncStatePacket` | S→C | 战斗槽状态 + 攻击力 |
| 3 | `UnequipHandPacket` | C→S | 卸下某只手 |
| 4 | `StashHotbarPacket` | C→S | 收纳快捷栏到背包 |
| 5 | `MoveHotbarToBackpackPacket` | C→S | 单槽移入背包 |
| 6/7 | `Equip/UnequipToolSkillPacket` | C→S | 工具技能栏 |
| 8 | `OffhandAttackPacket` | C→S | 左键攻击实体 |
| 9 | `RemoteUiStatePacket` | S→C | 远程玩家 UI 快照 |
| 10 | `SaoMenuUpdatePacket` | C→S | 上报本地菜单状态 |
| 12 | `HandActionPacket` | C→S | **统一按手动作包** |
| 13 | `HotbarRowPacket` | C→S | 滚轮方向，按整排循环快捷栏和背包 |

`HandActionPacket` 结构：

```
VarInt hand(LEFT/RIGHT)  VarInt action  bool hasPos [BlockPos, Direction]
VarInt entityId / slotIndex（按 action 复用）
```

`HandActionType`：

```
MINE_START, MINE_STOP, USE, ATTACK, QUICK_USE,
CONSUME_START, CONSUME_STOP, INTERACT, USE_ITEM, USE_ITEM_STOP
```

---

## 6. UI 系统

### 6.1 战斗 HUD（双持时替换原版快捷栏）

```
[左手格]   [ 原版九格物品栏 ]   [右手格]
   29px          182px              22px
```

- 中间绘制**真实背包内容**（`player.getInventory().getItem(0..8)`）
- 主手所在槽叠加选中框 + 灰色遮罩，表示“已被右手占用”
- 左右手格各自绘制攻击冷却：未就绪部分从底部盖半透明黑
- 双持时原版快捷栏用 scissor 裁掉，避免重复绘制

### 6.2 自定义背包界面（`BetterDisplayInventoryScreen`）

- 多层菜单树：根 → 分支 → 叶子/物品过滤页（`SaoMenuModel`）
- 装备分类：**装备 → 防具 / 武器 / 工具**；点击分类列出**整个背包**中匹配的物品
- 左侧资料卡只显示人物模型与数值；**装备信息只在右侧选项里**
- 物品行标题带装备状态：`已装备 · 头部` / `主手` / `副手`
- Tab 打开 SAO，E 打开原版背包。
- 「社交 → 在线玩家」显示原版玩家名单、延迟、计分板与服务器页眉页脚。

### 6.3 远程面板

- 广播半径 **32 格**，服务端每 **20 tick** 心跳，客户端 100 tick 无更新即移除
- 面板固定在目标玩家“打开菜单时的视线方向”前方 2 格，遇遮挡自动缩短
- 渲染时朝向观看者，边缘视角会补一点倾斜以保证可读
- 面板内容：菜单列（圆环快捷栏、物品列表）+ 资料卡（数值）

---

## 7. 存档与状态

玩家持久化数据（Forge `PlayerPersisted`）下 `betterdisplay:combat_slots`：

| 键 | 含义 |
|---|---|
| `drawn` | 是否双持（状态标记，非“拔刀”） |
| `left` / `right` | 历史虚拟手残留（仅用于登录时救援） |
| `original_main/off/selected` | 历史残留，登录时若发现则**退回背包并清键** |
| `tool_skill_N` | 工具技能栏物品 |

`recoverStrandedStacks()` 在登录时兜底：把历史版本可能残留在 NBT 里的物品退回背包，
这是针对早期虚拟手 bug 造成物品丢失的补偿逻辑。

---

## 8. 配置项

| 键 | 默认 | 说明 |
|---|---|---|
| `closeDistanceBlocks` | `8` | 界面自动关闭距离 |
| `showRemoteUi` | `true` | 是否显示其他玩家的面板 |
| `shareUiState` | `true` | 是否把本地菜单状态共享给附近玩家 |

---

## 9. 日志与诊断

模组在 `latest.log` 输出带前缀的结构化日志，便于定位（不刷聊天栏）：

```
[attack] ok hand=LEFT target=123 hp=18.5
[attack] rejected hand=RIGHT reason=cooldown
[attack] cleared own invulnerability for dual wield, target=...
[use] start hand=LEFT item=...
[use] released (cancelled)
```

`tools/check_logs.py` 可检查实例日志。双持攻击被服务端拒绝时，`[attack]` 记录会列出原因。

---

## 10. 关键设计决策与踩坑记录

1. **删除虚拟手存档**：按键拔刀 + NBT 搬运导致物品丢失，改为纯状态推导，物品永不离开原槽位
2. **入口从 `InteractionKeyMappingTriggered` 换成 `InputEvent.MouseButton.Pre`**：
   前者只在特定路径派发，左键分派不可靠
3. **抬键不可取消**：否则 `KeyMapping` 卡在按下状态，原版持续攻击
4. **裂纹需自己补发**：原版跳过破坏者本人
5. **不用 `hasCorrectToolForDrops` 拦截挖掘**：原版允许任何物品挖任何方块
6. **双手独立冷却**：不能读共享 `attackStrengthTicker`
7. **双持需处理受击无敌帧**：否则第二把武器打不出伤害
8. 吃喝按槽位计时，并由原版物品完成逻辑结算。
9. 客户端先于原版快捷栏处理消费速用按键，避免临时切换主手。
10. **部署使用原子替换**：运行时覆盖 jar 会造成 `ZLIB EOF` 崩溃，看起来像代码 bug

---

## 11. 已知问题 / 未验证项

- 快捷栏吃喝保留使用音效和粒子，第一人称持物动作仍待实现。
- 喷溅药水/附魔瓶/烟花的投掷依赖原版 `useItem` 的瞬时路径，尚未实测
- 工具技能栏（`tool_skill_N`）功能保留但当前 UI 未完整暴露

---

## 12. 后续建议

1. 为双持输入分派补最小化单元测试（模拟事件 → 断言包内容）
2. 继续拆分 `BetterDisplayCombat` 的双持、挖掘与网络分发职责
3. 协议版本升级时同步更新 `SaoMenuCodec` 注释与双端校验
4. 引入 `docs/` 目录保存界面线框与状态机图
