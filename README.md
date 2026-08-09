# FlipOuterUnlock / MIX Flip 外屏解锁模块

> **`main`** — 稳定分支（纯 hook 无界面） | **`master`** — UI 分支（带 Compose 设置界面）
>
> Make the MIX Flip outer screen behave like a normal phone display.
> 让 MIX Flip 外屏像普通手机屏幕一样工作。

**一句话**：LSPosed 模块，去除外屏挖孔、全屏显示、解除应用限制、控制中心样式恢复、最近任务长按菜单、自由切换输入法、解除强制 Sogou 锁定、修复输入法工具栏、伪装设备身份。

**One-liner**: LSPosed module — removes outer screen cutout, forces fullscreen, unlocks apps, restores control center style, adds recents long-press menu, frees IME choice (no forced Sogou), fixes Sogou toolbar, spoofs device identity.

[English](#english) | [中文](#chinese)

---

<a name="english"></a>
## English

LSPosed module for Xiaomi MIX Flip / MIX Flip 2 — unlock the outer display.

### Features

**Display & Fullscreen**
- Remove outer screen display cutout — clears camera hole-punch via `Display.getCutout()` zero-cutout injection
- Prevent cutout letterboxing — hooks `WindowState.isLetterboxedForDisplayCutout()` in system_server
- Force fullscreen for all apps — sets `layoutInDisplayCutoutMode=ALWAYS` on every Activity
- Fix app bounds on cold start and configuration changes

**Device Identity**
- Spoof device identity — hooks 7 detection paths: `MiuiMultiDisplayTypeInfo.isFlipDevice()`, `miui.os.Build`, `miuix.os.Build.IS_FLIP`, `DeviceUtils`, `DeviceHelper`, `MiuiConfigs`
- Excludes SystemUI (lock screen panel), Sogou IME (keyboard height), fliphome (launcher init) — centralized in `Config.identityExcludedPackages`
- Spoof screen type — hooks MIUI's `Configuration.getScreenType()` to return 0 (EXPAND)

**App Management**
- Whitelist all apps for continuity — uses `ContinuityPolicyService` dump injection
- Compat config injection — `ApplicationCompatManager` → `miui.continuity.policy=5`
- Remove app launch restrictions — `InterceptActivityController.isInterceptListUnCheckFold()` → false

**IME & Input**
- Enable IME in landscape — hooks `shouldShowCurrentInput()` → true
- Suppress rotation toast
- Unlock IME choice — hooks `InputMethodManagerServiceImpl.isFlipTinyScreen()` → false, preventing forced Sogou switch on outer screen
- Sogou toolbar & clipboard fix — restores full keyboard layout on outer screen (uses DexKit)
- **Known issue**: Scan preview orientation varies by app. Some apps require holding the phone with the camera side down BEFORE opening the scan; others use their own camera logic and work regardless.

**SystemUI**
- Widget overlay disabled — 4-layer defense in fliphome process
- Control center style restored — hooks plugin classloader to replace COMPACT with VERTICAL layout, enables QS tile long-press, fixes device center card layout
- Notification menu fix — `isTinyScreen` handled globally by LockScreenHook
- Status bar clock hidden on outer screen
- Status bar icon expansion — shows up to 8 notification icons
- Recents task long-press menu — lock/unlock + app info popup on fliphome recents view
- System gestures (back) — fliphome native InputMonitor handles swipe-up gestures
- Always-On Display enabled on outer screen when folded (v2.3: screen state fix + FlipLinkageStyleController)
- Sub-screen double-tap + 3-finger swipe gestures (displayId fix for state=6)
- Feature toggles — SystemProperties-based, reboot-required, no UI

**v2.9 Changes**
- Removed miuihome hooks (LauncherHook, LauncherDensityHook, hookNavigationBar): HyperOS 4 rewrites miuihome in Rust+Flutter — hook targets gone. fliphome is now the active launcher on outer screen via its native InputMonitor.
- Removed side gesture persistence hacks: no longer needed with fliphome-native gesture lifecycle.
- Config coupling enforced: `gestureBack` getter includes `gestureHome`; `displayAod` includes `displayDual`. No more log-only warnings — disabling the prerequisite automatically disables the dependent feature.
- Exclusion lists centralized in Config.kt: `identityExcludedPackages`, `cutoutExcludedPackages` — no per-file hardcoded exclusions.

### Feature Toggles

All features can be disabled via `setprop`. Changes take effect after reboot. No UI — by design.

```bash
# List current settings
getprop | grep persist.flipunlock

# Disable a feature (example)
setprop persist.flipunlock.ui.widget false
reboot

# Re-enable
setprop persist.flipunlock.ui.widget true
reboot
```

| Property | Default | Controls |
|----------|---------|----------|
| `persist.flipunlock.enable` | true | **Master kill switch** — false disables everything |
| `persist.flipunlock.display.dual` | true | Dual display (DisplayStateHook: force state=6, display enable) |
| `persist.flipunlock.display.aod` | true | Outer screen AOD (depends on display.dual — getter enforces) |
| `persist.flipunlock.display.cutout` | true | Remove cutout + letterbox + appBounds + frame fixes |
| `persist.flipunlock.gesture.home` | true | Home gesture via fliphome InputMonitor |
| `persist.flipunlock.gesture.back` | true | Back gestures via GestureStubView (depends on gesture.home) |
| `persist.flipunlock.ui.lockscreen` | true | Lock screen large layout (LockScreenHook) |
| `persist.flipunlock.ui.widget` | true | Disable widget overlay (WatchOverlayHook) |
| `persist.flipunlock.ui.controlcenter` | true | Control center style restore (ControlCenterHook) |
| `persist.flipunlock.ui.recentsmenu` | true | Recents long-press menu (RecentsMenuHook) |
| `persist.flipunlock.ime` | true | Input method freedom (InputMethodHook + Sogou) |

### Hook Architecture

Codebase organized by **functional directory** — each directory maps to a setprop toggle:

```
hook/
├── BaseHook.kt                  # Abstract base class (8 lines)

├── identity/                    # No toggle — always on
│   ├── DeviceIdentityHook.kt    # 7 detection paths → false (wildcard, excludes SystemUI/Sogou/fliphome)
│   └── ScreenTypeHook.kt        # getScreenType() → 0 (wildcard)

├── display/                     # persist.flipunlock.display.dual
│   └── DisplayStateHook.kt      # state=6, display enable, display info overrides

├── cutout/                      # persist.flipunlock.display.cutout
│   ├── CutoutHook.kt            # Parser + pathAndDisplayCutoutFromSpec + framework hooks
│   ├── GlobalCutoutHook.kt      # Display.getCutout + WindowInsets + size-compat (wildcard)
│   ├── AppBoundsHook.kt         # fillInsetsState + 3 config paths (system_server)
│   ├── LetterboxHook.kt         # isLetterboxedForDisplayCutout → false (system_server)
│   └── ActivityLifecycleHook.kt # layoutInDisplayCutoutMode=ALWAYS per Activity (wildcard)

├── aod/                         # persist.flipunlock.display.aod
│   └── AodHook.kt               # L1 (framework DreamService) + L2 (DozeMachine runtime)

├── gesture/                     # persist.flipunlock.gesture.home + .back
│   └── GestureHook.kt           # fliphome launcher: no start page + ensure FlipLauncher enabled

├── lockscreen/                  # persist.flipunlock.ui.lockscreen
│   └── LockScreenHook.kt        # isTinyScreen/isFlipTinyScreen → false + panel controller → Dummy

├── systemui/                    # Mixed: status bar always-on, control center toggled
│   ├── SystemUIHook.kt          # Status bar clock/icons, HideDisplayCutoutOrganizer, decor window
│   └── ControlCenterHook.kt     # Plugin style COMPACT→VERTICAL + tile long-press + device center

├── widget/                      # persist.flipunlock.ui.widget
│   └── WatchOverlayHook.kt      # 4-layer defense: controller → view → window → WM.addView

├── recents/                     # persist.flipunlock.ui.recentsmenu
│   └── RecentsMenuHook.kt       # Long-press popup menu + Gate 7 (display-ID task filter fix)

├── ime/                         # persist.flipunlock.ime
│   ├── InputMethodHook.kt       # system_server: shouldShowCurrentInput + isFlipTinyScreen
│   └── SogouInputHook.kt        # Sogou process: toolbar + clipboard (DexKit)

├── applaunch/                   # No toggle — always on
│   ├── CompatConfigHook.kt      # continuity.policy → 5 (system_server)
│   ├── InterceptHook.kt         # isInterceptListUnCheckFold → false (system_server)
│   ├── WhitelistHook.kt         # dump command whitelist (system_server)
│   ├── SubScreenGestureHook.kt  # displayId redirect 1→0 for state=6 (system_server)
│   └── SystemServicesHook.kt    # BoundsCompatUtils + WindowManager fullscreen (system_server)

├── camera/                      # Disabled — blocked by hardware (all cameras report LENS_FACING_BACK)
│   └── CameraHook.kt            # Dynamic LENS_FACING enumeration (correct architecture, H/W limit)

└── util/
    ├── Config.kt                # Feature toggles + centralized exclusion lists + coupling enforcement
    ├── HookUtils.kt             # hook(), safeHook(), hookScope(), DexKit bridge
    └── ReflectUtils.kt          # Reflection shortcuts
```

**Activation order:**

```
1. System Server (onSystemServerStarting):
   CutoutHook.hookFramework()  →  DisplayStateHook  →  LetterboxHook
   WhitelistHook  →  SubScreenGestureHook  →  CompatConfigHook
   AppBoundsHook  →  SystemServicesHook  →  InputMethodHook  →  InterceptHook

2. First Package (isFirstPackage=true):
   DeviceIdentityHook (*)  →  ScreenTypeHook (*)  →  GlobalCutoutHook (*)
   ActivityLifecycleHook (*)  →  CutoutHook (framework)

3. Target Packages:
   AodHook (systemui, aod)  →  SystemUIHook (systemui)  →  LockScreenHook (systemui)
   ControlCenterHook (systemui)  →  GestureHook (fliphome)  →  WatchOverlayHook (fliphome)
   RecentsMenuHook (fliphome)  →  SogouInputHook (sogou)
```

### Requirements

- LSPosed (libxposed API 101+)
- Xiaomi MIX Flip / MIX Flip 2
- HyperOS / MIUI

### Build

```bash
./gradlew :app:assembleDebug
```

### Release (signed)

Generate a keystore:
```bash
keytool -genkey -v -keystore flip.jks -keyalg RSA -keysize 2048 -validity 10000 -alias flip
```

Create `local.properties` (git-ignored):
```properties
androidStoreFile=flip.jks
androidStorePassword=<your-password>
androidKeyAlias=flip
androidKeyPassword=<your-password>
```

```bash
./gradlew :app:assembleRelease
```

For CI, add GitHub Secrets: `KEYSTORE` (base64), `KEYSTORE_PASSWORD`, `ALIAS`, `KEY_PASSWORD`.

### Credits

- [MixFlipMod](https://github.com/parallelcc/MixFlipMod) by Parallelc — reference for LSPosed architecture, SogouHook, DexKit usage, SystemUI hooks, and hook utilities
- Reverse engineering references in `refMD/cleaned/` (decompiled MIUI framework, services, fliphome APKs)
- `COUPLING_REVIEW.md` — full coupling/cohesion audit (2026-07-26)
- `REFACTOR_PLAN.md` — consolidation plan with per-agent call-chain analysis

## Current Status (2026-08-10) — for AI/developers onboarding

**定位**：**flip1（ruyi）专用基版**，活跃开发。flip2 问题走 `FlipOuterUnlock2`（基线 90833c4）。

### 当前活跃 hooks（master 最新）
- **DeviceIdentityHook**（wildcard）：
  - 属性层 `SystemProperties.getInt("persist.sys.multi_display_type")→1`（虚拟改属性，免 root）——flip1 上游大杀器
  - `isFlipDevice→false`（双保险）
  - 排除表：**systemui 恢复排除**（§38.4 控制中心/手势副作用）；fliphome/sogou 实验态
- **CutoutAlwaysHook**（app 端四件套）：Parser.parse 清零 + Display.getCutout→空 + getBoundingRect→空 + getLayoutInDisplayCutoutMode→3
- **system_server**：DeviceIdentityHook.hookSystemServer（flip1 corepatch 断路时装不上，无害）、Flip2CutoutLetterboxHook（仅 flip2 激活）、RotationFixHook（方向，依赖 system_server 注入）
- 其余 hooks（AodHook/AppWhitelist/CompatConfigHook 等）按需 DISABLED 待命

### 已验证（flip1）
- isFlipDevice→false + 属性层：cutout 无 / toast 居中 / 控制中心正常（systemui 排除后）
- systemui 排除（§38：TinyKeyguardPanel NPE 崩溃环教训）

### 关键结论
- flip1 上游 = `persist.sys.multi_display_type`（改 1 = 一改百效）
- LSPosed 2.0.1 KSU 环境 system_server 注入异常（corepatch 变体 §41.2）
- 详细分析见 `refMD/cleaned/FoldState_Device_Identity.md`（§1/§28/§38/§41）

### 开发指引
- 修改后：push → CI 编译 → 装机重启 → `adb shell su -c 'logcat -d | grep FlipOuterUnlock'` 验证
- 新实验走单独分支；分析先查 refMD，再看 FlipRes

---

### TODO

- **miuihome gestures** (v2.9: hooks removed) — miuihome and fliphome use completely different gesture architectures. Hook-based attempts (Gates 1–8, LauncherHook) achieved partial results: side/back gestures work via edge overlay injection, bottom swipe-to-home works (returns to desktop), but bottom swipe-to-recents shows empty task list, and system haptic feedback on bottom gestures never fires. The recents animation pipeline (Shell/WindowTransition) is fundamentally broken on the flip outer screen — callbacks never arrive, timeouts produce no action. fliphome's native InputMonitor("swipe-up") was chosen as the primary gesture path instead. Full call-chain analysis preserved in refMD §6 and §13.
- **CameraHook** — Front camera redirect on outer screen (not working — HAL reports all cameras as LENS_FACING_BACK, fallback "1"→"0" not yet verified)
- **FaceUnlock** — Face unlock on outer screen (confirmed infeasible — see below)
- **Toast/hint left-shift** — `Toast.makeText()` hints appear shifted left on outer screen (custom in-app views are centered correctly). Root cause: TYPE_TOAST windows are clipped by the cutout safe area during WindowLayout.computeFrames() in system_server. Multiple hook points tried (InsetsState.getDisplayCutoutSafe, getLayoutInDisplayCutoutMode, computeFrames output fix) without success — the frames may be narrowed upstream of computeFrames, or MIUI has an additional clipping layer not yet identified. Present since initial release.

### Known Issues (Unfolded State)

As of v2.8, `DisplayStateHook` and `CameraHook` include a fold-state guard (`isOuterScreen()`: display height < 2000px). When the device is unfolded with the inner screen intact, these hooks are automatically disabled — the native display topology and front camera work normally. No manual configuration needed.

### License

AGPL-3.0

---

<a name="chinese"></a>
## 中文

### 功能

**显示与全屏**
- 移除挖孔：`Display.getCutout()` 零值注入 + `CutoutSpecification.Parser` 字段清零
- 防 letterboxing：`WindowState.isLetterboxedForDisplayCutout()` → false
- 全屏模式：所有 Activity 设置 `layoutInDisplayCutoutMode=ALWAYS`
- 修复冷启动与配置变更时 appBounds

**设备身份**
- 伪装设备类型：hook 7 条检测路径（`MiuiMultiDisplayTypeInfo.isFlipDevice()`、`miui.os.Build`、`miuix.os.Build.IS_FLIP`、`DeviceUtils`、`DeviceHelper`、`MiuiConfigs`）
- 排除列表集中在 `Config.identityExcludedPackages`：SystemUI（锁屏面板）、Sogou（键盘高度）、fliphome（启动器初始化）
- 伪装屏幕类型：`Configuration.getScreenType()` → 0

**应用管理**
- 所有应用白名单注入
- 兼容配置注入：`miui.continuity.policy=5`
- 移除应用启动拦截

**输入法**
- 横屏键盘启用 + 禁旋转提示
- 解除输入法锁定 — hook `InputMethodManagerServiceImpl.isFlipTinyScreen()` → false，阻止外屏强制切 Sogou
- Sogou 工具栏+剪贴板修复（DexKit）
- **已知问题**：扫一扫预览方向因应用而异。部分应用需在**点击扫一扫前**以靠近摄像头一侧为底才能正常显示；部分应用走自带逻辑无需调整

**SystemUI**
- Widget 覆盖层 4 层禁用
- 控制中心样式恢复 — hook 插件 ClassLoader，COMPACT 布局 → VERTICAL，恢复 QS tile 长按，修复设备中心卡片
- 通知菜单修复 — `isTinyScreen` 由 LockScreenHook 全局处理
- 外屏状态栏时钟隐藏
- 通知图标扩展到 8 个
- 最近任务长按菜单 — 外屏最近任务长按弹出锁定/解锁 + 应用信息
- 系统手势（返回） — fliphome 原生 InputMonitor 处理滑动
- 折叠状态下外屏 AOD 启用（v2.3：屏幕状态修复 + FlipLinkageStyleController）
- 外屏双击休眠 + 三指截屏手势（displayId 修复适配 state=6）
- 功能开关 — 基于 SystemProperties，重启生效，无界面

**v2.9 变更**
- 移除 miuihome hooks（LauncherHook, LauncherDensityHook, hookNavigationBar）：HyperOS 4 用 Rust+Flutter 重写 miuihome — hook 目标消失。fliphome 现在是外屏活动启动器，使用原生 InputMonitor
- 移除侧边手势持久化 hack：fliphome 原生手势生命周期已不需要
- Config 耦合强制化：`gestureBack` getter 包含 `gestureHome`；`displayAod` 包含 `displayDual`。不再只是 log warn — 关闭前置开关自动关闭依赖功能
- 排除列表集中在 Config.kt：`identityExcludedPackages`、`cutoutExcludedPackages` — 不再每个文件硬编码

### 功能开关

所有功能可通过 `setprop` 单独关闭。修改后重启生效。无 UI — 有意为之。

```bash
# 查看当前设置
getprop | grep persist.flipunlock

# 关闭某个功能（示例）
setprop persist.flipunlock.ui.widget false
reboot

# 重新开启
setprop persist.flipunlock.ui.widget true
reboot
```

| 属性 | 默认 | 控制 |
|------|------|------|
| `persist.flipunlock.enable` | true | **总开关** — false 则全部关闭 |
| `persist.flipunlock.display.dual` | true | 双屏显示（DisplayStateHook：强制 state=6，display enable） |
| `persist.flipunlock.display.aod` | true | 外屏 AOD（依赖 display.dual — getter 强制检查） |
| `persist.flipunlock.display.cutout` | true | 去除挖孔 + letterbox + appBounds + 帧修正 |
| `persist.flipunlock.gesture.home` | true | Home 手势（fliphome InputMonitor） |
| `persist.flipunlock.gesture.back` | true | 返回手势（GestureStubView，依赖 gesture.home） |
| `persist.flipunlock.ui.lockscreen` | true | 锁屏大屏样式（LockScreenHook） |
| `persist.flipunlock.ui.widget` | true | 禁用外屏小部件（WatchOverlayHook） |
| `persist.flipunlock.ui.controlcenter` | true | 控制中心样式恢复（ControlCenterHook） |
| `persist.flipunlock.ui.recentsmenu` | true | 最近任务长按菜单（RecentsMenuHook） |
| `persist.flipunlock.ime` | true | 输入法自由切换（InputMethodHook + Sogou） |

### Hook 架构

代码按**功能目录**组织——每个目录对应一个 setprop 开关：

```
hook/
├── identity/    无开关 — 始终运行 (DeviceIdentityHook, ScreenTypeHook)
├── display/     display.dual (DisplayStateHook)
├── cutout/      display.cutout (CutoutHook, GlobalCutoutHook, AppBoundsHook, LetterboxHook, ActivityLifecycleHook)
├── aod/         display.aod (AodHook)
├── gesture/     gesture.home + gesture.back (GestureHook)
├── lockscreen/  ui.lockscreen (LockScreenHook)
├── systemui/    状态栏始终运行 + ui.controlcenter (SystemUIHook, ControlCenterHook)
├── widget/      ui.widget (WatchOverlayHook)
├── recents/     ui.recentsmenu (RecentsMenuHook)
├── ime/         ime (InputMethodHook, SogouInputHook)
├── applaunch/   无开关 — 始终运行 (CompatConfigHook, InterceptHook, WhitelistHook, SubScreenGestureHook, SystemServicesHook)
├── camera/      已禁用 — 硬件限制 (CameraHook)
└── util/        Config, HookUtils, ReflectUtils
```

### 要求

- LSPosed（libxposed API 101+）
- Xiaomi MIX Flip / MIX Flip 2
- HyperOS / MIUI

### 构建与签名

```bash
# 生成密钥
keytool -genkey -v -keystore flip.jks -keyalg RSA -keysize 2048 -validity 10000 -alias flip

# local.properties
androidStoreFile=flip.jks
androidStorePassword=<密码>
androidKeyAlias=flip
androidKeyPassword=<密码>

# 签名构建
./gradlew :app:assembleRelease
```

CI: GitHub Secrets → `KEYSTORE`(base64), `KEYSTORE_PASSWORD`, `ALIAS`, `KEY_PASSWORD`

### 致谢

- [MixFlipMod](https://github.com/parallelcc/MixFlipMod) by Parallelc — LSPosed 架构、SogouHook、DexKit、SystemUI hook 及工具类参考
- `refMD/cleaned/` — MIUI 框架及 fliphome 反编译参考文档
- `COUPLING_REVIEW.md` — 完整耦合/内聚审查 (2026-07-26)
- `REFACTOR_PLAN.md` — 重构方案与 agent 调用链分析

### 未完成

- **miuihome 手势**（v2.9：hooks 已移除）—— miuihome 与 fliphome 使用完全不同的手势架构。基于 hook 的尝试（Gates 1–8，LauncherHook）取得了部分效果：左右返回手势可通过边缘覆盖层注入实现，底部上滑回桌面可用，但底部上滑进入最近任务显示空白列表，且系统震动反馈在底部手势上从未触发。最近任务动画管线（Shell/WindowTransition）在 flip 外屏上根本性损坏——回调永不触发，超时只重置状态不执行操作。最终选择 fliphome 原生 InputMonitor("swipe-up") 作为主手势方案。完整调用链分析保留在 refMD §6 和 §13。
- **CameraHook** — 外屏前置摄像头重定向（不生效 — HAL 上报所有摄像头均为 LENS_FACING_BACK，回退方案 "1"→"0" 未验证）
- **FaceUnlock** — 外屏人脸解锁（已确认不可行 — 详见下）
- **Toast/提示左移** — `Toast.makeText()` 类提示在外屏偏左（自定义 View 类提示正常居中）。根因：TYPE_TOAST 窗口在 system_server 的 WindowLayout.computeFrames() 中被 cutout 安全区削窄。已尝试 InsetsState.getDisplayCutoutSafe、getLayoutInDisplayCutoutMode、computeFrames 输出修正等多层 hook，均未生效——帧可能在 computeFrames 上游即已被削窄，或 MIUI 存在额外的裁剪层。从初始版本即存在。

### 已知问题（展开状态下）

v2.8 起 `DisplayStateHook` 和 `CameraHook` 加入了折叠态守卫（`isOuterScreen()`: 屏幕高度 < 2000px）。展开时自动放行——原生显示拓扑和前置摄像头正常工作。无需手动配置。

### License

AGPL-3.0
