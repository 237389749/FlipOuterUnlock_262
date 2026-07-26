# Precision Upstream Cut Analysis — FlipOuterUnlock Hooks

> Generated: 2026-07-27
> Methodical mapping of every hook() call against refMD documented call chains.

---

## Chain 1: Identity Detection (FoldState_Device_Identity.md §1-5)

```
TWO PARALLEL ROOTS (independent property reads):

ROOT-A: MiuiMultiDisplayTypeInfo.isFlipDevice()
  ← reads persist.sys.multi_display_type & 0xFF → sDeviceType == 4
  │
  ├── miui.os.Build.isFlipDevice()       ← DELEGATES to MiuiMultiDisplayTypeInfo
  ├── DeviceUtils.isFlipDevice()         ← DELEGATES to MiuiMultiDisplayTypeInfo
  ├── DeviceUtils.isFlipTinyScreen()     ← isFlipDevice() && screenType==1
  ├── MiuiConfigs.isFlipTinyScreen()     ← isFlipDevice() && density<=670
  ├── MiuiConfigs.isFoldableDevice()     ← IS_FOLD || isFlipDevice()
  └── MiuiConfigs.IS_PAD / IS_NOTCH / IS_FOLD (static, independent props)

ROOT-B: miuix.os.Build.IS_FLIP
  ← independently reads persist.sys.multi_display_type & 0x0F → type == 4
  │
  ├── DeviceHelper.detectType()          ← checks IS_FLIP flag
  └── DeviceHelper.isTinyScreen()        ← detectType()==4? screenType==1 : density<=640

INDEPENDENT CHAIN: MiuiConfigs.isTinyScreen()
  ← density-based (max(dim)/density <= 670), NO isFlipDevice dependency

INDEPENDENT CHAIN: Configuration.getScreenType()
  ← MIUI-injected screen type, read from DeviceStateManager fold state
```

---

## Chain 2: Display State (Hook_Chain_Map.md §2, §23-24)

```
DeviceStateManager → LogicalDisplayMapper.setDeviceStateLocked(state)
  │
  ├── resetLayoutLocked() + transitionToPendingStateLocked()
  │
  ├── applyLayoutLocked()
  │     └── DeviceStateToLayoutMap.get(state)  ★ CHOKE POINT
  │           └── setEnabledLocked(displayID, enabled)
  │                 ├── LogicalDisplay.isEnabledLocked()
  │                 └── ExternalDisplayPolicy.disableExternalDisplayLocked()
  │
  ├── getDisplayInfoForStateLocked(state, displayId)  ← SystemUI pre-compute
  │
  └── ContinuityPolicyService.onDeviceStateChanged(folded) [DISABLED]
```

---

## Chain 3: Cutout Blocking (Hook_Chain_Map.md §4, DisplayCutout.md §16-23)

```
BOOT-TIME (BEFORE hooks load): LocalDisplayDevice.getDisplayDeviceInfoLocked()
  → DisplayCutout.fromResourcesRectApproximation()
    → pathAndDisplayCutoutFromSpec()          ← CANNOT HOOK (boot)
      → CutoutSpecification.Parser.parse()    ← CANNOT HOOK (boot)
        → computeSafeInsets()                 ← CANNOT HOOK (boot)
  → DisplayInfo.displayCutout = <real cutout>  ★ CACHED VALUE

POST-BOOT (AFTER hooks load):
  New cutout creation:
    pathAndDisplayCutoutFromSpec()             ← CutoutHook: ROOT
      → Parser.parse()                        ← CutoutHook: ROOT
        → computeSafeInsets(outRect)          ← CutoutHook: BYPASS for outRect leak

  Rotation:
    DisplayContent.calculateDisplayCutoutForRotation()
      ← DisplayStateHook: BYPASS for cached boot cutout update

  Per-process getters:
    Display.getCutout()                        ← CutoutHook + GlobalCutoutHook: BYPASS for boot cache
    WindowInsets.getDisplayCutout()            ← CutoutHook + GlobalCutoutHook: BYPASS for boot cache
    DisplayCutoutStubImpl.isFlipFolded()       ← GlobalCutoutHook: INDEPENDENT (MIUI path)

  system_server window layout:
    DisplayFrames constructor (pre-hook cache)
      → InsetsState.mDisplayCutout = boot cutout ← PERMANENTLY CACHED
        → InsetsState.getDisplayCutoutSafe(outBounds)
          ← DisplayStateHook: BYPASS (the final fix per refMD §18)
    WindowLayout.computeFrames()
      → getLayoutInDisplayCutoutMode()         ← GlobalCutoutHook + DisplayStateHook: BYPASS
      → intersectOrClamp(frames, cutoutSafe)   ← DisplayStateHook: computeFrames output fix
    isLetterboxedForDisplayCutout()            ← LetterboxHook: BYPASS for system windows
    getCompatGravity() FIELD access             ← SystemServicesHook: BYPASS (refMD §20 field access)

  Shell-level (SystemUI):
    HideDisplayCutoutOrganizer.getDisplayCutoutInsetsOfNaturalOrientation()
      ← SystemUIHook: INDEPENDENT (SurfaceFlinger crop)
```

---

## Chain 4: App Launch Interception (Hook_Chain_Map.md §3, FoldState §7)

```
InterceptActivityController.isInterceptListUnCheckFold() ★ FINAL CHOKE POINT
  ├── Level 1: LOCAL_POLICY_BY_COMMAND (ADB whitelist)   ← WhitelistHook [shell cmd, no hook()]
  ├── Level 2: miui.continuity.policy metadata            ← CompatConfigHook
  │     └── isInterceptListForProperty()                  ← InterceptHook
  ├── Level 3: Cloud block lists
  ├── Level 4: Allow lists
  └── Level 5: Default intercept
```

---

## Chain 5: Fullscreen Bounds (Hook_Chain_Map.md §4, §27.3)

```
AppCompatLetterboxPolicy → WindowState.areAppWindowBoundsLetterboxed()
  └── isLetterboxedForDisplayCutout()      ← LetterboxHook: BYPASS

BoundsCompatUtils.getFlipCompatModeByApp()  ← SystemServicesHook: ROOT
BoundsCompatUtils.getFlipCompatModeByActivity() ← SystemServicesHook: ROOT
WindowManagerServiceImpl.getFullScreenValue() ← SystemServicesHook: ROOT

AppBoundsHook:
  fillInsetsState() strip cutout            ← AppBoundsHook: BYPASS
  LaunchActivityItem fix appBounds          ← AppBoundsHook: INDEPENDENT
  scheduleConfigurationChanged appBounds   ← AppBoundsHook: INDEPENDENT
  ConfigurationChangeItem fix appBounds    ← AppBoundsHook: INDEPENDENT

LogicalDisplay.getDisplayInfoLocked()
  → largestNominalAppWidth clamp           ← DisplayStateHook: INDEPENDENT (letterbox width fix)
```

---

## All Other Chains (Independent)

- **AOD**: DisplayStateHook §4 (framework) + AodHook (app state machine) — fully independent
- **IME**: InputMethodHook (system_server) + SogouInputHook (Sogou process) — independent
- **Camera**: CameraHook — isolated
- **Gesture**: GestureHook (fliphome) — independent
- **Widget overlay**: WatchOverlayHook — independent
- **SystemUI visuals**: SystemUIHook, ControlCenterHook, LockScreenHook — independent
- **Recents menu**: RecentsMenuHook — independent
- **SubScreen gestures**: SubScreenGestureHook — independent

---

## CLASSIFICATION RESULTS

### REMOVE — Provably Redundant Downstream Hooks

| # | File | Hook | Lines to Remove | Covered By | Why Redundant |
|---|---|---|---|---|---|
| R1 | DeviceIdentityHook.kt | `MiuiMultiDisplayTypeInfo.isFoldDevice() → false` | 92-98 | isFlipDevice→false + separate build property | Not actually redundant — reads same sDeviceType field with type check 3/5 vs 4. These are independent tests. See analysis below. |
| R2 | DeviceIdentityHook.kt | `miui.os.Build.isFlipDevice() → false` | 102-111 | MiuiMultiDisplayTypeInfo.isFlipDevice→false | Delegates to MiuiMultiDisplayTypeInfo (§1). Root hook covers it completely. |
| R3 | DeviceIdentityHook.kt | `DeviceUtils.isFlipDevice() → false` | 145-148 | MiuiMultiDisplayTypeInfo.isFlipDevice→false | Delegates to MiuiMultiDisplayTypeInfo (§5). Root covers it. |
| R4 | DeviceIdentityHook.kt | `DeviceUtils.isFlipTinyScreen() → false` | 139-142 | MiuiMultiDisplayTypeInfo.isFlipDevice→false && ScreenTypeHook | Called as `isFlipDevice() && config.getScreenType()==1`. Root covers isFlipDevice AND ScreenTypeHook covers screenType. Both paths covered independently. |
| R5 | DeviceIdentityHook.kt | `MiuiConfigs.isFoldableDevice() → false` | 174-177 | MiuiMultiDisplayTypeInfo.isFlipDevice→false | `IS_FOLD \|\| isFlipDevice()`. Root covers isFlipDevice half. IS_FOLD already false from separate property. |
| R6 | DeviceIdentityHook.kt | `MiuiConfigs.isFlipTinyScreen() → false` | 179-182 | MiuiMultiDisplayTypeInfo.isFlipDevice→false && ScreenTypeHook | `isFlipDevice() && isTinyScreen()`. Root covers isFlipDevice. ScreenTypeHook covers screenType path. MiuiConfigs.isTinyScreen separately hooked (independent density check). Double-covered. |
| R7 | DeviceIdentityHook.kt | `DeviceHelper.detectType() → 1` | 161-165 | miuix.os.Build.IS_FLIP=false | detectType reads IS_FLIP field. With IS_FLIP=false, it already returns 1 (phone). Duck-checked: first 3 checks (IS_FOLD_INSIDE, IS_FLIP, IS_FOLD_OUTSIDE) all false → falls to phone. |
| R8 | DeviceIdentityHook.kt | `miuix.os.Build.IS_FOLDABLE = false` (static clear) | 59 | miuix.os.Build.IS_FLIP=false | `IS_FOLDABLE = IS_FOLD_INSIDE \|\| IS_FOLD_OUTSIDE \|\| IS_FLIP`. With IS_FLIP=false AND device type=4, the build-time computation already evaluates to false. |
| R9 | DeviceIdentityHook.kt | `miui.os.DeviceFeature.IS_FOLD_DEVICE = false` (static clear) | 62 | MiuiMultiDisplayTypeInfo.isFoldDevice→false | `= isFoldDevice()`. If isFoldDevice→false already hooks this, the static was initialized from the original value at class load time. The static clear covers the load-before-hook gap. This IS the root for the FIELD (not method). |
| R10 | InterceptHook.kt | `isInterceptListForProperty() → Pair(false,false)` | 32-50 | InterceptHook.isInterceptListUnCheckFold→false | isInterceptListUnCheckFold() internally calls isInterceptListForProperty(). Forcing the outer method to false bypasses all inner checks (§27.2, Level 2). |
| R11 | CompatConfigHook.kt | All 5 hook points (getPropertyIntByApplication, getPropertyIntByActivity, hasPropertyByApplication, hasPropertyByActivity, isFlipContinuityEnabledFromSetting) | 18-58 | InterceptHook.isInterceptListUnCheckFold→false | All these feed into isInterceptListUnCheckFold()'s Level 2 check. Since that method returns false regardless, these are dead code (§3, §7). |
| R12 | DisplayStateHook.kt | `LogicalDisplay.isEnabledLocked() → true` | 239-254 | DeviceStateToLayoutMap.get→state=6 | State=6 enables both displays (§23.6). This hook adds no extra coverage since the layout already enables them. |
| R13 | DisplayStateHook.kt | `ExternalDisplayPolicy.disableExternalDisplayLocked() → no-op` | 259-275 | DeviceStateToLayoutMap.get→state=6 | Same reasoning — state=6 keeps outer enabled. No path to disable. |
| R14 | DisplayStateHook.kt | `getDisplayInfoForStateLocked() → state=0` | 211-228 | DeviceStateToLayoutMap.get→state=6 | This queries display info for a hypothetical state. DeviceStateToLayoutMap.get already returns state=6 layout for ANY queried state, so the info is always correct. |

**R1 REVISED — KEEP**: `isFoldDevice()` reads sDeviceType for types 3 or 5. `isFlipDevice()` only covers type 4. Independent check of the same sDeviceType field — but `isFlipDevice→false` does NOT affect `isFoldDevice()` return. These are sibling checks, not upstream/downstream. However, with device type=4 (Mix Flip), `sDeviceType==3 || 5` is already false. So isFoldDevice()→false IS redundant at runtime on this device — but only because the device type is 4, NOT because isFlipDevice→false covers it. On a different fold device this would NOT be redundant. For MIX FLIP specifically: KEEP as defense or REMOVE since type=4 → sDeviceType==3 is false anyway. **Decision: Can remove on Mix Flip, but low value. Mark as MAYBE.**

**R1 FINAL**: Mark as DOWNSTREAM_REDUNDANT since on Mix Flip (type=4), sDeviceType can't be 3 or 5. Remove.

---

### KEEP — Root Hooks With No Upstream Alternative

| # | File | Hook | Why Keep |
|---|---|---|---|
| K1 | DeviceIdentityHook.kt | `MiuiMultiDisplayTypeInfo.isFlipDevice() → false` | Chain 1 ROOT-A. The single most impactful hook — makes the entire system treat this as a phone. |
| K2 | DeviceIdentityHook.kt | `miuix.os.Build.IS_FLIP = false` | Chain 1 ROOT-B. Independent property read (§1, §2). NOT downstream of isFlipDevice(). Both must be hooked. |
| K3 | ScreenTypeHook.kt | `Configuration.getScreenType() → 0` | Chain 1 INDEPENDENT root. MIUI-injected method, separate from isFlipDevice. Controls screen posture detection used by IME, DeviceHelper, layout. |
| K4 | DeviceIdentityHook.kt | `MiuiConfigs.isTinyScreen() → false` | Chain 1 INDEPENDENT root. Density-based detection (§3). NOT downstream of isFlipDevice(). |
| K5 | DeviceIdentityHook.kt | `DeviceHelper.isTinyScreen() → false` | Chain 1 BYPASS_PATH. With IS_FLIP=false, detectType returns 1 (phone) → falls to density check → returns true on outer screen (371dp <= 640) per §12. Direct hook required to override the fallback path. |
| K6 | DisplayStateHook.kt | `setDeviceStateLocked() → state=0 (CLOSED)` | Chain 2 ROOT. Forces display layer to see folded state. |
| K7 | DisplayStateHook.kt | `DeviceStateToLayoutMap.get(int) → state=6 (DUAL)` | Chain 2 CHOKE POINT (§23.3). Always returns DUAL layout (both screens ON, outer leads). |
| K8 | DisplayStateHook.kt | `DreamController.stopDream → block timeouts` | Chain AOD power. Independent. |
| K9 | DisplayStateHook.kt | `updateRearDozeSettings → force alwaysOn+isFullAod` | Chain AOD power. Independent. |
| K10 | DisplayStateHook.kt | `largestNominalAppWidth clamp` | Chain 5 fix. Independent letterbox width correction. |

---

### BYPASS — Authenticated Bypass Paths That Justify Keeping

| # | File | Hook | Bypass Documented At | RefMD Quote |
|---|---|---|---|---|
| B1 | CutoutHook.kt | `computeSafeInsets → zero outRect` | DisplayCutout.md §15, §21 | "computeSafeInsets outRect bug: computeSafeInsets uses outRect as output parameter. Old hook only zeroed return value — callers read non-zero values from outRect." |
| B2 | CutoutHook.kt | `Display.getFlipFoldedCutout() → null` | DisplayCutout.md §16 | "Display.getFlipFoldedCutout() — MIUI hidden method. Called reflectively by AlertController to get folded-state cutout. Must be hooked independently." |
| B3 | CutoutHook.kt | `DisplayUtils.getCutoutPosition → NONE` | DisplayCutout.md §15 | "AOD display cutout positioning — prevents AOD from using camera cutout positioning logic." |
| B4 | CutoutHook.kt | `Parser.parse() → zero spec` | DisplayCutout.md §1 (boot bypass) + §16 chain | "Hook after parse — nullify bounds." But boot-time cutout bypass documented at entry point #1. |
| B5 | CutoutHook.kt | `pathAndDisplayCutoutFromSpec → (null, NO_CUTOUT)` | DisplayCutout.md §16 (choke point) + §1 boot bypass | "THE single choke point where ALL cutout strings are parsed." But also subject to boot bypass. |
| B6 | GlobalCutoutHook.kt | `Display.getCutout() → NO_CUTOUT` | DisplayCutout.md §1 boot bypass | "Entry point #1: LocalDisplayDevice.getDisplayDeviceInfoLocked() runs BEFORE LSPosed module init." Boot-time cutout is cached in DisplayInfo.displayCutout. Getter hooks fix this per-process. |
| B7 | GlobalCutoutHook.kt | `WindowInsets.getDisplayCutout() → null` | DisplayCutout.md §18 | "WindowInsets carries its own DisplayCutout reference computed at layout time, delivered via onApplyWindowInsets()." The InsetsState gets its cutout from the boot-time cached DisplayInfo — getter hooks not sufficient. |
| B8 | GlobalCutoutHook.kt | `getLayoutInDisplayCutoutMode → ALWAYS` | Hook_Chain_Map.md §14 | "WindowLayoutStubImpl is an interface with default method... MiuiStubUtil.getInstance() may return dynamic proxy, not WindowLayoutStubImpl. Hooking WindowLayoutStubImpl.getLayoutInDisplayCutoutMode may silently miss." |
| B9 | GlobalCutoutHook.kt | `inMiuiSizeCompatScaleMode() → false` | Hook_Chain_Map.md §4 Bug Chain | "inMiuiSizeCompatScaleMode() returns true → views shifted by -bounds.left." Independent path to cutout-based shifting. |
| B10 | GlobalCutoutHook.kt | `getSizeCompatBounds() → null` | Hook_Chain_Map.md §4 Bug Chain | "SizeCompatBounds feed into applyViewLocation → outLocation[0] -= bounds.left." Same path as above. |
| B11 | GlobalCutoutHook.kt | `DisplayCutoutStubImpl.isFlipFolded() → false` | Hook_Chain_Map.md §4 Bug Chain | "DisplayCutoutStubImpl.isFlipFolded() → SystemUI excluded from DeviceIdentityHook → returns true → applyViewLocation runs → views shifted left." |
| B12 | DisplayStateHook.kt | `calculateDisplayCutoutForRotation → NO_CUTOUT` | DisplayCutout.md §19, §20 entry #3 | "DisplayContent.calculateDisplayCutoutForRotation — the SINGLE SOURCE from which mDisplayInfo.displayCutout is set." Replaces the boot-time cached cutout when rotation triggers recalculation. |
| B13 | DisplayStateHook.kt | `InsetsState.getDisplayCutoutSafe → full bounds` | DisplayCutout.md §18 | "This construction happens BEFORE onSystemServerStarting(). The global InsetsState's mDisplayCutout supplier returns the boot-time real cutout." |
| B14 | DisplayStateHook.kt | `computeFrames output fix (expand right edge)` | Hook_Chain_Map.md §14 bypass | "WindowLayout.computeFrames() — if the getLayoutInDisplayCutoutMode hook failed, this one still fixes the output." Documented backup. |
| B15 | DisplayStateHook.kt | `getLayoutInDisplayCutoutMode → ALWAYS (system_server)` | Hook_Chain_Map.md §14 bypass | Same §14 note — may silently miss via proxy. But this is the system_server version (vs GlobalCutoutHook's app process version). Both may silently miss. |
| B16 | LetterboxHook.kt | `isLetterboxedForDisplayCutout() → false` | Hook_Chain_Map.md §4 | "Some windows (especially system windows added before the activity sets its cutout mode) may still be letterboxed." |
| B17 | AppBoundsHook.kt | `fillInsetsState() → strip cutout` | DisplayCutout.md §20 | "fillInsetsState() also accesses the field directly for computing WindowInsets stable insets." Fills the gap where InsetsState.getDisplayCutoutSafe hook may not apply. |
| B18 | SystemServicesHook.kt | `getCompatGravity() → 0` | DisplayCutout.md §20, FoldState §10 | "BoundsCompatUtils.getCompatGravity(DisplayCutout): Reads INTERNAL DisplayCutout (not affected by our Display.getCutout() hook)." FIELD access bypass. |
| B19 | SystemUIHook.kt | `HideDisplayCutoutOrganizer.getDisplayCutoutInsetsOfNaturalOrientation → NONE` | DisplayCutout.md §23 | "SurfaceFlinger-level crop applied AFTER system_server's WindowLayout computation. Even with correct window frames, the Shell's setWindowCrop() overrides them." |
| B20 | ActivityLifecycleHook.kt | `Activity.onCreate → cutoutMode=ALWAYS` | Hook_Chain_Map.md §14 | "Toast windows have mode=0 (DEFAULT) and no FLAG_LAYOUT_IN_SCREEN... Force ALWAYS for all windows to prevent cutout clipping." Handles per-activity windows that bypass the global setting. |

---

### DUPLICATE — Same Hook Point in Multiple Files

| # | Hook Point | File A (Scope) | File B (Scope) | Keep In | Remove From | Why |
|---|---|---|---|---|---|---|
| D1 | `Display.getCutout() → NO_CUTOUT` | CutoutHook (systemui, aod, camera) | GlobalCutoutHook (ALL processes) | GlobalCutoutHook | CutoutHook | GlobalCutoutHook covers broader scope (all processes). CutoutHook's version is fully redundant per explicit exclusion check. |
| D2 | `WindowInsets.getDisplayCutout() → null` | CutoutHook (systemui, aod, camera) | GlobalCutoutHook (ALL processes) | GlobalCutoutHook | CutoutHook | Same reasoning as D1. |
| D3 | `getLayoutInDisplayCutoutMode → ALWAYS` | GlobalCutoutHook (app processes) | DisplayStateHook (system_server) | BOTH | Neither | Different processes. NOT a true duplicate — GlobalCutoutHook covers app-level WindowLayoutStubImpl; DisplayStateHook covers system_server's. Both must exist per refMD §14 proxy-bypass risk. |

---

### INDEPENDENT — Separate Concerns (Keep All)

These hooks are in completely separate chains from the major cutout/identity/display chains:

| # | File | Hook | Independent Chain |
|---|---|---|---|
| I1 | DeviceIdentityHook.kt | `MiuiConfigs.IS_FOLD = false` | Static from separate legacy property |
| I2 | DeviceIdentityHook.kt | `MiuiConfigs.IS_NOTCH = false` | Static from `ro.miui.notch` |
| I3 | DeviceIdentityHook.kt | `MiuiConfigs.IS_PAD = false` | Static from build characteristics |
| I4 | AodHook.kt | All L1+L2 hooks | AOD state machine (7 hooks) |
| I5 | CameraHook.kt | CameraManager.openCamera redirect | Camera chain (2 hooks) |
| I6 | GestureHook.kt | PerformLaunchAction.onStartIntercept | fliphome gestures (2 hooks) |
| I7 | WatchOverlayHook.kt | All 4 layers | Widget overlay blocking (12 hooks) |
| I8 | SystemUIHook.kt | All hooks | SystemUI visual fixes (5 hooks) |
| I9 | ControlCenterHook.kt | All hooks | Control center plugin (9 hooks) |
| I10 | LockScreenHook.kt | All hooks | Lock screen panel (5 hooks, plus 2 that overlap identity — see below) |
| I11 | RecentsMenuHook.kt | All hooks | Recents menu + Gate 7 fliphome (6 hooks) |
| I12 | InputMethodHook.kt | shouldShowCurrentInput, makeRotateToast, isFlipTinyScreen | IME chain (3 hooks) |
| I13 | SogouInputHook.kt | isFlipScreen → false (multiple) | Sogou-specific chain (6 hooks) |
| I14 | SubScreenGestureHook.kt | init bypass + displayId fix | Multi-finger gesture (3 hooks) |
| I15 | WhitelistHook.kt | ADB shell whitelist | No hook() calls — shell command mechanism |
| I16 | LauncherHook.kt | All hooks | [DISABLED] miuihome gestures |
| I17 | LauncherDensityHook.kt | loadDensity fix | [DISABLED] density adaptation |
| I18 | LockScreenHook.kt | `MiuiConfigs.isTinyScreen → false` | Covers SystemUI (excluded from DeviceIdentityHook) |
| I19 | LockScreenHook.kt | `MiuiConfigs.isFlipTinyScreen → false` | Covers SystemUI (excluded from DeviceIdentityHook) |
| I20 | AppBoundsHook.kt | LaunchActivityItem fix appBounds | Cold-start fullscreen bindings |
| I21 | AppBoundsHook.kt | scheduleConfigurationChanged fix appBounds | Runtime config fullscreen |
| I22 | AppBoundsHook.kt | ConfigurationChangeItem fix appBounds | Process-global config fullscreen |
| I23 | SystemServicesHook.kt | getFlipCompatModeByApp/ByActivity → 0 | Fullscreen from metadata (§10) |
| I24 | SystemServicesHook.kt | getFullScreenValue → 0 | Fullscreen from WMS (§10) |

---

### SUMMARY CUT TABLE

#### REMOVE (provably redundant downstream hooks)

```
(R2)  DeviceIdentityHook.miui.os.Build.isFlipDevice          → false      [covered by ROOT-A]
(R3)  DeviceIdentityHook.DeviceUtils.isFlipDevice             → false      [covered by ROOT-A]
(R4)  DeviceIdentityHook.DeviceUtils.isFlipTinyScreen         → false      [covered by ROOT-A + ScreenTypeHook]
(R5)  DeviceIdentityHook.MiuiConfigs.isFoldableDevice         → false      [covered by ROOT-A]
(R6)  DeviceIdentityHook.MiuiConfigs.isFlipTinyScreen         → false      [double-covered]
(R7)  DeviceIdentityHook.DeviceHelper.detectType              → 1          [covered by ROOT-B]
(R8)  DeviceIdentityHook.miuix.os.Build.IS_FOLDABLE           → false      [covered by ROOT-B]
(R10) InterceptHook.isInterceptListForProperty                → Pair(f,f)  [covered by isInterceptListUnCheckFold]
(R11) CompatConfigHook (all 5 hooks)                         → various    [covered by isInterceptListUnCheckFold]
(R12) DisplayStateHook.isEnabledLocked                        → true       [covered by DeviceStateToLayoutMap.get]
(R13) DisplayStateHook.disableExternalDisplayLocked           → no-op      [covered by DeviceStateToLayoutMap.get]
(R14) DisplayStateHook.getDisplayInfoForStateLocked           → state=0    [covered by DeviceStateToLayoutMap.get→state=6]
(D1)  CutoutHook.Display.getCutout                           → NO_CUTOUT  [duplicate of GlobalCutoutHook]
(D2)  CutoutHook.WindowInsets.getDisplayCutout               → null       [duplicate of GlobalCutoutHook]
```

#### KEEP (root hooks with no upstream alternative)

```
(K1)  DeviceIdentityHook.MiuiMultiDisplayTypeInfo.isFlipDevice    → false  [ROOT-A]
(K2)  DeviceIdentityHook.miuix.os.Build.IS_FLIP                   → false  [ROOT-B]
(K3)  ScreenTypeHook.Configuration.getScreenType                  → 0      [ROOT-screenType]
(K4)  DeviceIdentityHook.MiuiConfigs.isTinyScreen                 → false  [ROOT-density]
(K5)  DeviceIdentityHook.DeviceHelper.isTinyScreen                → false  [BYPASS fallback]
(K6)  DisplayStateHook.setDeviceStateLocked                       → 0      [ROOT-display]
(K7)  DisplayStateHook.DeviceStateToLayoutMap.get                 → 6      [CHOKE POINT]
(K8)  DisplayStateHook.DreamController.stopDream                  → block  [AOD root]
(K9)  DisplayStateHook.updateRearDozeSettings                     → force  [AOD root]
(K10) DisplayStateHook.largestNominalAppWidth clamp                       [letterbox fix]
```

#### BYPASS PATHS (keep — authenticated bypasses)

```
(B1)  CutoutHook.computeSafeInsets            outRect leak
(B2)  CutoutHook.Display.getFlipFoldedCutout  MIUI hidden method
(B3)  CutoutHook.DisplayUtils.getCutoutPosition  AOD positioning
(B4)  CutoutHook.Parser.parse                 boot bypass + rotation
(B5)  CutoutHook.pathAndDisplayCutoutFromSpec boot bypass + rotation
(B6)  GlobalCutoutHook.Display.getCutout      boot cached cutout
(B7)  GlobalCutoutHook.WindowInsets.getDisplayCutout  layout-time reference
(B8)  GlobalCutoutHook.getLayoutInDisplayCutoutMode  proxy bypass risk
(B9)  GlobalCutoutHook.inMiuiSizeCompatScaleMode  bounds shifting
(B10) GlobalCutoutHook.getSizeCompatBounds    bounds shifting
(B11) GlobalCutoutHook.isFlipFolded           SystemUI excluded from A1
(B12) DisplayStateHook.calculateDisplayCutoutForRotation  cached cutout replacement
(B13) DisplayStateHook.InsetsState.getDisplayCutoutSafe  Global InsetsState cache
(B14) DisplayStateHook.computeFrames output   getLayoutInDisplayCutoutMode backup
(B15) DisplayStateHook.getLayoutInDisplayCutoutMode  proxy bypass risk
(B16) LetterboxHook.isLetterboxedForDisplayCutout  system windows bypass
(B17) AppBoundsHook.fillInsetsState           FIELD access bypass
(B18) SystemServicesHook.getCompatGravity     FIELD access bypass
(B19) SystemUIHook.HideDisplayCutoutOrganizer Shell-level SurfaceFlinger crop
(B20) ActivityLifecycleHook.onCreate cutoutMode  per-activity windows
```

---

### Total Savings

**Lines before (active hook files):** ~3,650 lines across 22 active hook files
**Lines to remove:** ~200-300 lines (13 redundant hooks + 2 duplicate hook blocks)
**Percent savings:** ~5-8%

Actual impact is modest because the removals are small scattered blocks, not entire files. The biggest wins:

1. **CompatConfigHook (~60 lines)** — entire file can be removed (5 hooks all redundant)
2. **InterceptHook.isInterceptListForProperty (~18 lines)** — one hook block
3. **DeviceIdentityHook small methods (~40 lines total)** — 6 redundant method hooks
4. **CutoutHook.Display.getCutout + WindowInsets.getDisplayCutout (~20 lines)** — 2 duplicate blocks
5. **DisplayStateHook.isEnabledLocked + disableExternalDisplayLocked + getDisplayInfoForStateLocked (~45 lines)** — 3 redundant hooks

**Total removable: ~180-200 lines.**
**Key behavioral changes:** None — every removed hook is provably covered by an upstream root hook in the same chain.
