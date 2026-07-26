# FlipOuterUnlock 重构方案

> 基于 5 个 agent 对照 refMD 调用链全面分析
> 日期: 2026-07-26

---

## 一、当前状态

```
hook/
├── BaseHook.kt               (抽象基类, 8行)
├── DeviceIdentityHook.kt     (*  wildcard, 194行, 14个hook路径)
├── ScreenTypeHook.kt         (*  wildcard, 40行)
├── GlobalCutoutHook.kt       (*  wildcard, 167行, 有Sogou排除)
├── ActivityLifecycleHook.kt  (*  wildcard, 55行)
├── CutoutHook.kt             (systemui/aod/camera + framework, 194行)
├── AodHook.kt                (systemui/aod, 226行)
├── SystemUIHook.kt           (systemui, 255行)
├── LockScreenHook.kt         (systemui, 198行)
├── ControlCenterHook.kt      (systemui, 208行)
├── GestureHook.kt            (fliphome, 176行, 含76行死代码)
├── WatchOverlayHook.kt       (fliphome, 249行)
├── RecentsMenuHook.kt        (fliphome, 518行, Gate7与菜单无关)
├── SogouInputHook.kt         (sogou, 172行, DexKit)
├── CameraHook.kt             (camera, 145行, 已禁用)
├── LauncherHook.kt           (miuihome, 612行, 已禁用)
├── LauncherDensityHook.kt    (miuihome, 72行, 已禁用)
├── gesture/GestureHook.kt    ← GestureHook实际位置
├── system/
│   ├── DisplayStateHook.kt   (system_server, 468行, 9个关注点)
│   ├── AppBoundsHook.kt      (system_server, 147行)
│   ├── LetterboxHook.kt      (system_server, 42行)
│   ├── CompatConfigHook.kt   (system_server, 60行)
│   ├── InterceptHook.kt      (system_server, 51行)
│   ├── InputMethodHook.kt    (system_server, 58行)
│   ├── SystemServicesHook.kt (system_server, 101行)
│   ├── WhitelistHook.kt      (system_server, 120行)
│   └── SubScreenGestureHook.kt (system_server, 100行)
└── util/
    ├── Config.kt             (141行)
    ├── HookUtils.kt          (80行)
    └── ReflectUtils.kt       (63行)
```

**活跃代码**: ~4,060 行 (22 文件) | **死代码**: ~964 行 (4 文件 + 3 块注释)

---

## 二、Agent 核心发现汇总

### 2.1 SystemUI (4文件 → 1目录, 3个冗余点消除)

| 发现 | 当前 | 改进 |
|------|------|------|
| SystemUIHook的`hookScope(isTinyScreen)` | 在createMenuViews内scoped返回false | **删除** — LockScreenHook已在同进程全局hook |
| SystemUIHook的`hookScope(isFlipTinyScreen)` | 在statusBar测量内scoped返回false | **删除** — LockScreenHook已在同进程全局hook |
| `LockScreenHook.hookInstantFlipTinyScreen` | 方法hook + 字段hook(防御纵深) | **保留** — 字段fix是root fix, 方法hook是防御 |

**按6层组织**:
1. 身份覆盖 (isTinyScreen, isFlipTinyScreen, isInstantFlipTinyScreen)
2. 框架层 (DreamService.setDozeScreenState, HideDisplayCutoutOrganizer)
3. 应用配置 (SystemUIApplication.onConfigurationChanged)
4. UI组件 (锁屏面板, 状态栏时钟/图标, 通知菜单, 装饰窗口)
5. 插件层 (ControlCenter插件style + tile + device center)
6. AOD运行时 (DozeMachine, DozeService, FlipLinkageStyleController)

### 2.2 fliphome (3文件 → 1目录, Gate7独立)

| 发现 | 当前 | 改进 |
|------|------|------|
| `hookSideGesturePersistence` | 76行方法体, 调用点注释掉 | **删除** — 死代码(旧miuihome-takeover架构) |
| GestureHook类注释 | 描述旧架构(miuihome接管) | **更新** — fliphome现在是活动launcher, 走native InputMonitor |
| `RecentsMenuHook.Gate7` | 与菜单功能混在一起, 无条件运行 | **独立文件** — DisplayFilterFix.kt, 不依赖Config.uiRecentsMenu |
| WatchOverlayHook 4层 | Layer 2-4是Layer 1下游冗余 | **保留** — 合法防御纵深(Controller→View→Window→WM) |

### 2.3 system_server (9文件 → 11文件, DisplayStateHook拆分)

| 发现 | 当前 | 改进 |
|------|------|------|
| DisplayStateHook | 468行, 3个无关子系统 | **拆成3个文件**: DisplayState + FullscreenFrame + AodPower |
| cutout 顶层hook | `calculateDisplayCutoutForRotation` | **保留** (距源头最近, 但可能静默失败) |
| cutout 输出层 | `getDisplayCutoutSafe` + `computeFrames` | **保留** — 防御纵深(getDisplayCutoutSafe更干净, computeFrames兜底) |
| getLetterboxDetails | 修复largestNominalAppWidth维度交换 | **移到FullscreenFrame** — 不是cutout问题, 是display维度bug |
| App Launch三重冗余 | CompatConfig + Intercept + Whitelist | **保留** — 三重覆盖是故意的, 但InterceptHook的isInterceptListForProperty **覆盖**了CompatConfigHook的continuity.policy→5 (因为方法级hook先执行) |

### 2.4 Wildcard (5文件, 2个重复hook消除)

| 发现 | 当前 | 改进 |
|------|------|------|
| DeviceIdentityHook 7条路径 | 5条是isFlipDevice()的严格下游 | **保留全部** — 防御纵深对classloader隔离/静默失败有价值, 但需文档标注冗余级别 |
| CutoutHook + GlobalCutoutHook | 重复hook `Display.getCutout()` 和 `WindowInsets.getDisplayCutout()` | **合并到GlobalCutoutHook** — CutoutHook保留定义层(Parser), GlobalCutoutHook接管消费层 |
| Sogou排除散落 | DeviceIdentityHook + GlobalCutoutHook 独立维护 | **集中到Config.kt** — `val cutoutExcludedPackages` |
| `DisplayStateHook.hookLayoutCutoutMode` | 定义了但从未从hook()调用 | **休眠代码, 需激活或删除** |

### 2.5 Sogou/Camera (IME链无重叠, Camera阻塞于硬件)

| 发现 | 当前 | 改进 |
|------|------|------|
| IME三层 | system_server(S6) → Sogou process排除(A1) → Sogou内部(A9) | **无重叠, 互补, 保持独立** |
| CameraHook | 145行完整代码, 阻塞于Mix Flip所有相机报告LENS_FACING_BACK | **保留文件**(正确架构, 硬件局限) |
| Sogou排除位置 | 只有2处: DeviceIdentityHook + GlobalCutoutHook | 不是最初估算的4-5处 |

---

## 三、按包聚目录结构

```
hook/
├── BaseHook.kt                     # 不变

├── global/                         # Wildcard hooks (所有进程)
│   ├── DeviceIdentityHook.kt       # 身份伪装, 标注哪些是ROOT/REDUNDANT/DEFENSIVE
│   ├── ScreenTypeHook.kt           # getScreenType()→0
│   └── GlobalCutoutHook.kt         # 接管CutoutHook的Display.getCutout+WindowInsets
│                                    #   + 原有的isFlipFolded, inMiuiSizeCompatScaleMode

├── system/                          # system_server (不变, 仅拆分DisplayStateHook)
│   ├── DisplayStateHook.kt         # 减至~145行 (SS1, SS1b, SS3, SS4a, SS4b)
│   ├── FullscreenFrameHook.kt      # 新增: 从DisplayStateHook拆出 (SS5, SS6a, SS6b, 
│   │                                #   computeFrames, getLayoutInDisplayCutoutMode)
│   ├── AodPowerHook.kt             # 新增: 从DisplayStateHook拆出 (updateRearDozeSettings,
│   │                                #   DreamController.stopDream)
│   ├── AppBoundsHook.kt            # 不变
│   ├── LetterboxHook.kt            # 不变
│   ├── CompatConfigHook.kt         # 不变
│   ├── InterceptHook.kt            # 不变
│   ├── InputMethodHook.kt          # 不变
│   ├── SystemServicesHook.kt       # 不变
│   ├── WhitelistHook.kt            # 不变
│   └── SubScreenGestureHook.kt     # 不变

├── com.android.systemui/           # SystemUI 目录 (4文件→5文件)
│   ├── IdentityOverrides.kt        # 新增: LockScreenHook的身份覆盖 (isTinyScreen,
│   │                                #   isFlipTinyScreen, isInstantFlipTinyScreen, 
│   │                                #   sInstantAppConfig.screenType字段fix)
│   ├── LockScreenPanel.kt          # LockScreenHook剩余部分 (controller→Dummy, QS tile列数)
│   ├── StatusBar.kt                # SystemUIHook状态栏 + 装饰窗口 (时钟, 图标, 通知菜单,
│   │                                #   HideDisplayCutoutOrganizer, DecorWindow)
│   ├── ControlCenter.kt            # ControlCenterHook (插件拦截 + 样式 + tile + device center)
│   └── AodHook.kt                  # AodHook (Layer 1 framework + Layer 2 runtime)

├── com.miui.fliphome/              # fliphome 目录 (不变文件数, Gate7独立)
│   ├── LauncherControl.kt          # GestureHook的活跃部分 (NoStartPage + ensureEnabled)
│   ├── WidgetOverlay.kt            # WatchOverlayHook (4层不变)
│   ├── RecentsMenu.kt              # RecentsMenuHook (仅菜单功能)
│   └── DisplayFilterFix.kt         # 从RecentsMenuHook独立: Gate7 (removeOtherDisplayTask→no-op)

├── com.sohu.inputmethod.sogou.xiaomi/
│   └── SogouInputHook.kt           # 不变

├── com.android.camera/
│   └── CameraHook.kt               # 不变 (disabled, 保留)

├── util/
│   ├── Config.kt                   # 新增: 集中排除列表 (cutoutExcludedPackages)
│   ├── HookUtils.kt                # 不变
│   └── ReflectUtils.kt             # 不变
```

**删除**:
```
hook/LauncherHook.kt            (612行, 已禁用)
hook/LauncherDensityHook.kt     (72行, 已禁用)
hook/CutoutHook.kt              (194行 — 定义层合并入GlobalCutoutHook, 
                                  消费层已被GlobalCutoutHook覆盖)
hook/gesture/GestureHook.kt     (176行 — 活跃部分→fliphome/LauncherControl,
                                  死代码(hookSideGesturePersistence)删除)
```

---

## 四、消除的具体冗余

### 删除: SystemUIHook 中的 scoped hooks

```kotlin
// SystemUIHook.kt L93-95 — 删除
val fakeTinyScreen = hookScope(
    miuiConfigs.method("isTinyScreen", Context::class.java)
) { false }

// SystemUIHook.kt L149-151 — 删除  
val fakeFlipTinyScreen = hookScope(
    miuiConfigs.method("isFlipTinyScreen", Context::class.java)
) { false }
```
LockScreenHook 已在同一个 SystemUI 进程中全局 hook 这两个方法。scoped hook 永远不会改变结果。

### 合并: CutoutHook 的 Display.getCutout + WindowInsets.getDisplayCutout → GlobalCutoutHook

CutoutHook 保留定义层：Parser.parse, pathAndDisplayCutoutFromSpec, computeSafeInsets (合并到 GlobalCutoutHook 或保留为 framework-spec 独立 hook)

### 分离: DisplayStateHook → 3个文件

| 新文件 | 内容 | 行数 |
|--------|------|------|
| DisplayStateHook | setDeviceStateLocked, DeviceStateToLayoutMap.get, getDisplayInfoForStateLocked, isEnabledLocked, disableExternalDisplayLocked | ~145 |
| FullscreenFrameHook | getLayoutInDisplayCutoutMode, computeFrames, getLetterboxDetails, calculateDisplayCutoutForRotation, getDisplayCutoutSafe | ~230 |
| AodPowerHook | updateRearDozeSettings, DreamController.stopDream | ~60 |

### 集中: Sogou 排除列表

```kotlin
// Config.kt 新增
object Config {
    // ...
    /** Packages that need REAL cutout info (excluded from cutout hooks). */
    val cutoutExcludedPackages: Set<String> get() = setOf(
        "com.sohu.inputmethod.sogou.xiaomi",  // keyboard layout needs safeInsetRight
    )
}
```

---

## 五、变更统计

| 操作 | 数量 | 说明 |
|------|------|------|
| **删除文件** | 4 | LauncherHook, LauncherDensityHook, CutoutHook, GestureHook(gesture/) |
| **新增文件** | 5 | FullscreenFrameHook, AodPowerHook, IdentityOverrides, StatusBar, DisplayFilterFix |
| **重命名/移动** | 8 | 按目录重组 |
| **消除冗余hook** | 4 | SystemUIHook x2 scoped, CutoutHook x2 Display/WM |
| **删除死代码** | ~900行 | LauncherHook(612) + LauncherDensity(72) + GestureHook dead(76) + SystemUIHook dead NavigationBar(50) + DisplayStateHook dead continuity(15) |
| **净减少行数** | ~250行 | 删除死代码900 - 新增结构开销~650 |
| **最终文件数** | 18个文件 (从22活跃→18) | |

---

## 六、Config.kt 耦合强制化

```kotlin
// 当前 (只warn):
if (gestureHome != gestureBack) {
    sb.append("  ⚠ gesture.home ≠ gesture.back\n")
}

// 改为 (内联依赖):
val gestureHome: Boolean get() = enabled && raw("...gesture.home", true)
val gestureBack: Boolean get() = enabled && gestureHome && raw("...gesture.back", true)
// gestureBack 自动跟随 gestureHome; 关闭home就自动关闭back

val displayAod: Boolean get() = enabled && displayDual && raw("...display.aod", true)
// displayAod 自动跟随 displayDual
```

---

## 七、实施顺序建议

1. **Phase 1**: 删除死代码 (4文件 + 3块注释) — 无风险, 纯删除
2. **Phase 2**: Config.kt 集中排除列表 + 耦合强制化 — 低风险, 改getter
3. **Phase 3**: 消除 scoped hook 冗余 (SystemUIHook x2) — 低风险, 已验证冗余
4. **Phase 4**: CutoutHook + GlobalCutoutHook Display.getCutout合并 — 中风险, 需测试cutout表现
5. **Phase 5**: DisplayStateHook 拆分为3个文件 — 低风险, 纯重组
6. **Phase 6**: 按目录重组所有文件 + 更新 Main.kt import — 低风险, 纯重组
7. **Phase 7**: SystemUI/fliphome 按新目录结构合并 — 中风险, 需全功能回归测试
