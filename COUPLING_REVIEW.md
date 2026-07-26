# FlipOuterScreen 耦合度审查报告

> 审查日期: 2026-07-26
> 审查标准: CLAUDE.md §1-4
> 审查范围: FlipOuterUnlock 全部 hook 文件 (19 hooks, 2 utils, Config)

---

## 一、总体架构评价

模块的 **目标单一**（让 Mix Flip 外屏当正常手机用），但实现上呈现 **高扇出 (fan-out)** 结构：一个 `Main.kt` 分发到 19 个 Hook 类，每个类 hook 多个目标方法。这种结构本身不是问题——Xposed 模块的本质就是多点 hook。问题在于 **同一关注点被分散到多个 Hook 文件，产生了隐性耦合和重复**。

---

## 二、核心耦合问题

### 🔴 问题 1: Cutout 消除逻辑分散在 7 个文件中

**关注点**: "消除外屏 cutout 对视窗的影响"

**涉及文件**:
| 文件 | 所做工作 | 运行位置 |
|------|---------|---------|
| `CutoutHook` | Parser.parse() 清零, computeSafeInsets 清零, Display.getCutout()→NO_CUTOUT, WindowInsets.getDisplayCutout()→null | framework + 3 app processes |
| `GlobalCutoutHook` | Display.getCutout(), WindowInsets.getDisplayCutout(), isFlipFolded→false, inMiuiSizeCompatScaleMode→false, getSizeCompatBounds→null, getLayoutInDisplayCutoutMode→ALWAYS | 所有 app 进程 |
| `DisplayStateHook` §6 | calculateDisplayCutoutForRotation→NO_CUTOUT, InsetsState.getDisplayCutoutSafe→full bounds, computeFrames 修复, getLayoutInDisplayCutoutMode→ALWAYS | system_server |
| `AppBoundsHook` | fillInsetsState 中去掉 cutout source, appBounds=bounds (4 条路径) | system_server |
| `SystemServicesHook` | BoundsCompatUtils.getCompatGravity()→0 | system_server |
| `SystemUIHook` | HideDisplayCutoutOrganizer.getDisplayCutoutInsetsOfNaturalOrientation→NONE | SystemUI 进程 |
| `ActivityLifecycleHook` | 每个 Activity 设置 layoutInDisplayCutoutMode=ALWAYS | 所有 app 进程 |

**耦合表现**:
- `GlobalCutoutHook` 和 `CutoutHook` **重复 hook** 了 `Display.getCutout()` 和 `WindowInsets.getDisplayCutout()`。区别只是目标包不同（wildcard vs 特定包）
- `DisplayStateHook.hookLayoutCutoutMode()` 的注释明确说 "The GlobalCutoutHook version only runs in APP PROCESSES — useless for actual window frame computation"，说明两个 hook 做同一件事但各管一半
- 修复历史（Hook_Chain_Map.md §14 §15）显示 cutout 问题经过了 8 轮迭代，每次加一个新 hook 堵一个漏 —— **缺乏统一的 cutout 阻断策略**

**建议**: 将 cutout 阻断收敛到一个统一的入口点。CutoutHook 和 GlobalCutoutHook 可以合并。DisplayStateHook 中的 cutout 逻辑可以抽取为独立方法但保持在同一文件（因为运行在 system_server）。

---

### 🔴 问题 2: DeviceIdentityHook 的排除列表是隐式耦合注册表

```kotlin
// DeviceIdentityHook.kt:33
if (param.packageName in setOf(
    "com.android.systemui",
    "com.sohu.inputmethod.sogou.xiaomi",
    "com.miui.fliphome"
)) return
```

这个排除列表承载了 **4 个跨文件依赖**：

| 被排除包 | 原因 | 补救 Hook | 补救 Hook 位置 |
|----------|------|----------|---------------|
| SystemUI | 需要 isFlipDevice=true 用于锁屏面板布局 | LockScreenHook, SystemUIHook, ControlCenterHook | 3 个独立文件 |
| Sogou IME | 需要 isTinyScreen 用于键盘高度 | SogouInputHook | 1 个文件 |
| fliphome | 需要 isFlipDevice/isFlipTinyScreen 用于外屏启动器 | GestureHook, RecentsMenuHook, WatchOverlayHook | 3 个文件 |

**耦合表现**:
- 如果你添加了新包需要排除，你必须知道在 DeviceIdentityHook 里加排除 + 写对应的补偿 hook
- `GlobalCutoutHook` 也独立维护了一个 Sogou 排除（`hookFlipFoldedCutoutStub` 和 `hookSizeCompatScaleMode` 方法内各有一个 Sogou 检查），与 DeviceIdentityHook 的排除逻辑重复
- `LockScreenHook` 重新 hook 了 `isTinyScreen` / `isFlipTinyScreen` —— 这本该是 DeviceIdentityHook 的职责，只是因为 SystemUI 被排除了

**建议**: 将 "排除+补偿" 模式抽象化。可以在 Config 或一个集中的注册表里声明：
```
包名 → 需要保留的 flip 行为 → 补偿 hook 列表
```

---

### 🟡 问题 3: "Wildcard hook 只跑一次" 的双重实现

`Main.kt:89` 有一个 wildcard 去重逻辑：
```kotlin
if (isWildcard && !param.isFirstPackage && hook !is DeviceIdentityHook) return@forEach
```

`DeviceIdentityHook.kt:36` 有自己独立的去重：
```kotlin
@Volatile private var hooksInstalled = false
// ...
if (hooksInstalled) return
hooksInstalled = true
```

两个机制解决同一个问题（防止 wildcard hook 在每个包加载时重复执行），但方式不同且互相不知情。如果将来添加新的 wildcard hook 需要 per-package 执行（像 DeviceIdentityHook），Main.kt 的例外列表和 hook 自身去重都要改。

**建议**: 统一去重策略。要么全在 Main.kt 控制（缺点：Main 需要知道每个 hook 的语义），要么全在各个 Hook 内部控制（缺点：每个 Hook 都要写 `hooksInstalled`）。

---

### 🟡 问题 4: Launcher 架构变更后残留大量死代码

v2.7.1 从 miuihome-takeover 模式切换到 fliphome-native 模式后：

| 残留代码 | 位置 | 状态 |
|----------|------|------|
| `LauncherHook` (Gates 1-8) | 整个类 + Main.kt import | 注释掉，未删除 |
| `GestureHook.hookSideGesturePersistence` | 完整方法体 (~70行) | 注释掉 |
| `SystemUIHook.hookNavigationBar` | 完整方法体 (~50行) | 注释掉 |
| `DisplayStateHook.hookAppLayerToUnfolded` | 完整方法体 (~15行) | 块注释掉 |
| `SystemUIHook.hookNavigationBar` 内部引用的 `NavigationBarControllerImpl` 字段 | 整个方法 | 门面注释 |
| `GestureHook.ensureFlipLauncherEnabled` | 整个方法 (~25行) | 用途反转（从 disable 改为 enable），注释说明了历史原因 |

**耦合表现**: 
- 新加入的开发者无法判断哪些代码是"active but disabled by config" vs "dead legacy from previous architecture"
- `GestureHook` 的文件注释仍描述旧架构（"Bottom gesture navigation in apps is handled by SystemUI's NavigationBar"）与实际不符
- `ensureFlipLauncherEnabled` 的逻辑是"修复之前 disableFlipLauncher 造成的破坏"——如果没有之前的破坏就不需要这段代码，但目前必须保留因为它修复了历史遗留问题

**建议**: 删除已确认不再需要的代码（git 历史可以恢复），更新文件级注释。

---

### 🟡 问题 5: Config 耦合关系只文档化未强制

Config.kt 有很好的耦合文档（"Dependency / Coupling Notes"），但在运行时只做 warn：

```kotlin
if (gestureHome != gestureBack) {
    sb.append("  ⚠ gesture.home ≠ gesture.back — recommended keep together\n")
}
if (displayAod && !displayDual) {
    sb.append("  ⚠ display.aod=ON but display.dual=OFF — AOD may route to wrong display\n")
}
```

Hook 方法内部各自独立检查 config：
```kotlin
// AodHook.kt:25
if (!Config.displayAod) { log("AodHook: DISABLED"); return }
```
但 `displayAod` 的 getter 只检查 `enabled && raw(...)`，**不检查** `displayDual` 是否为 true。

**建议**: 在 Config getter 中内联依赖检查：
```kotlin
val displayAod: Boolean get() = enabled && displayDual && raw("...display.aod", true)
```
这样 `displayAod` 自动跟随 `displayDual`，不需要开发者记得这个依赖。

---

### 🟢 正面案例: 设计良好的部分

1. **BaseHook 抽象**: 简洁（8 行），职责清晰。每个 Hook 只需声明 targetPackages 和 setupHooks。

2. **HookUtils 的 `hookScope` 模式**: 
   ```kotlin
   val fakeFlipScreen = hookScope(isFlipScreen) { false }
   // 用法:
   fakeFlipScreen.run { chain.proceed() }
   ```
   优雅地解决了"在特定调用栈上下文中临时替换方法返回值"的需求。ControlCenterHook 和 SogouInputHook 都复用了这个模式。

3. **AodHook 的两层架构**: Layer 1 (framework, 立即安装) + Layer 2 (runtime, dream 启动后安装)。分离了"总是可用的 hook"和"需要特定 classloader 的 hook"，通过对象图遍历 (`findObjectByClassName`) 在运行时桥接。

4. **Hook_Chain_Map.md 的文档质量**: 350+ 行的依赖映射，覆盖所有 19 个 hook 的上游/下游关系。Config.kt 中的 coupling notes 也很清晰。

---

## 三、Hook 依赖全景图

```
                    ┌─────────────────────────────┐
                    │  DisplayStateHook (S1)       │  ← 基石: state=6 DUAL
                    │  system_server               │
                    └──────────┬──────────────────┘
                               │ 提供: 外屏=displayId=0
                               │ 影响: 所有 system_server hooks + 所有 app 进程
            ┌──────────────────┼──────────────────┐
            ▼                  ▼                  ▼
    ┌──────────────┐  ┌──────────────┐  ┌──────────────┐
    │ AppBounds    │  │ Letterbox    │  │ CompatConfig │
    │ Hook (S2)    │  │ Hook (S3)    │  │ Hook (S4)    │
    └──────────────┘  └──────────────┘  └──────────────┘
    
    ┌──────────────────────────────────────────────────────┐
    │  DeviceIdentityHook (A1)  +  ScreenTypeHook (A2)     │  ← 基石: 身份伪装
    │  ★ wildcard, 所有 app 进程                           │
    │  排除: SystemUI, Sogou, fliphome                     │
    └──────┬───────────────────────────────────────────────┘
           │ 影响: 所有不排除的 app hooks
           │
    ┌──────┴──────────────────────────────────────────────┐
    │  ┌────────────┐  ┌───────────────┐  ┌─────────────┐ │
    │  │ AodHook    │  │ LockScreen    │  │ ControlCenter│ │
    │  │ (A3)       │  │ Hook (A5)     │  │ Hook (A11)   │ │
    │  │ SystemUI   │  │ SystemUI      │  │ SystemUI     │ │
    │  └────────────┘  └───────────────┘  └─────────────┘ │
    │  ┌────────────┐  ┌───────────────┐                  │
    │  │ GestureHook│  │ WatchOverlay  │                  │
    │  │ (A6)       │  │ Hook (A7)     │                  │
    │  │ fliphome   │  │ fliphome      │                  │
    │  └────────────┘  └───────────────┘                  │
    └─────────────────────────────────────────────────────┘

    ┌──────────────────────────────────────────────────────┐
    │  Cutout 阻断 (分散在 7 个文件)                        │
    │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌─────────┐ │
    │  │CutoutHook│ │GlobalCut │ │DisplaySt │ │AppBounds│ │
    │  │(A5)      │ │Hook (NEW)│ │Hook §5-6 │ │Hook (S2)│ │
    │  └──────────┘ └──────────┘ └──────────┘ └─────────┘ │
    │  ┌──────────┐ ┌──────────┐ ┌──────────┐             │
    │  │SystemUI  │ │SystemSvc │ │Activity  │             │
    │  │Hook (A8) │ │Hook (S7) │ │Lifecycle │             │
    │  └──────────┘ └──────────┘ └──────────┘             │
    └──────────────────────────────────────────────────────┘
```

---

## 四、按严重程度排序的改进建议

### 优先级 1 — 减少重复，降低维护成本

1. **合并 CutoutHook + GlobalCutoutHook** → 单个 `CutoutHook` 统一处理 app 进程和 system_server 的 cutout 阻断。去除重复的 `Display.getCutout()` 和 `WindowInsets.getDisplayCutout()` hook。

2. **集中 Sogou 排除逻辑** → 在 Config 或一个 `ExclusionRegistry` 中声明排除列表，让各 hook 引用而非各自硬编码。

### 优先级 2 — 清理和一致性

3. **删除已弃用的死代码** → `LauncherHook` (整个类), `hookSideGesturePersistence`, `hookNavigationBar`, `hookAppLayerToUnfolded`。更新相关注释。

4. **统一 wildcard 去重机制** → 要么全在 Main.kt 控制，要么全在各 Hook 内部控制。选一种。

5. **Config 依赖内联** → `displayAod` getter 应该隐含 `displayDual` 检查，`gestureHome` 与 `gestureBack` 应该同步。

### 优先级 3 — 文档和可维护性

6. **DeviceIdentityHook 排除列表文档化** → 在文件头部明确列出排除原因和补偿 hook 的映射表。

7. **每个 Hook 添加"前置依赖"注释** → 例如 AodHook 头部标注 "Depends on: DisplayStateHook (S1§4, AOD power) + DeviceIdentityHook (isFlipDevice excluded from SystemUI)"。

---

## 五、指标总结

| 指标 | 值 |
|------|-----|
| Hook 类总数 | 19 (15 app + 9 system_server, 有重叠) |
| 目标包数 | 6 个不同包 (wildcard *, systemui, aod, camera, fliphome, miuihome, sogou) |
| 唯一关注的系统类 | ~40 个 |
| Cutout 阻断涉及文件数 | 7 |
| DeviceIdentity 排除涉及的补偿 Hook | 7 个跨 4 个文件 |
| 已弃用但未删除的代码块 | 5 处 |
| Config 开关间声明的依赖数 | 3 对 (dual→aod, home↔back, dual→lockscreen) |
| Config 开关间强制执行的依赖数 | 0 |
