package com.example.flipunlock

import com.example.flipunlock.hook.identity.DeviceIdentityHook
import com.example.flipunlock.hook.identity.ScreenTypeHook
import com.example.flipunlock.hook.display.DisplayStateHook
import com.example.flipunlock.hook.cutout.CutoutHook
import com.example.flipunlock.hook.cutout.GlobalCutoutHook
import com.example.flipunlock.hook.cutout.AppBoundsHook
import com.example.flipunlock.hook.cutout.LetterboxHook
import com.example.flipunlock.hook.cutout.ActivityLifecycleHook
import com.example.flipunlock.hook.aod.AodHook
import com.example.flipunlock.hook.gesture.GestureHook
import com.example.flipunlock.hook.lockscreen.LockScreenHook
import com.example.flipunlock.hook.systemui.SystemUIHook
import com.example.flipunlock.hook.systemui.ControlCenterHook
import com.example.flipunlock.hook.widget.WatchOverlayHook
import com.example.flipunlock.hook.recents.DisplayFilterFix
import com.example.flipunlock.hook.ime.SogouInputHook
import com.example.flipunlock.hook.ime.InputMethodHook
import com.example.flipunlock.hook.applaunch.InterceptHook
import com.example.flipunlock.hook.applaunch.WhitelistHook
import com.example.flipunlock.hook.applaunch.SubScreenGestureHook
import com.example.flipunlock.hook.applaunch.SystemServicesHook
import com.example.flipunlock.hook.util.log
import com.example.flipunlock.hook.util.Config
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

internal var module: Main? = null

class Main : XposedModule() {

    private val hooks = listOf(
        ScreenTypeHook,  // Configuration.getScreenType → 0
        DeviceIdentityHook,  // IS_FLIP / isFlipDevice / isFoldDevice → false
        GlobalCutoutHook,  // Display.getCutout + WindowInsets.getDisplayCutout → zero (all apps)
        AodHook,  // v2.3: screen state fix + FlipLinkageStyleController
        ControlCenterHook,  // v2.7: restore normal control center style on outer screen
        CutoutHook,
        SystemUIHook,
        GestureHook,  // v2: block fliphome InputMonitor → system gestures
        LockScreenHook,  // fix lock screen: swipe, shortcuts, wallpaper on outer screen
        DisplayFilterFix,  // Gate 7: prevent display-ID task filtering in fliphome recents
        SogouInputHook,
        ActivityLifecycleHook,
        WatchOverlayHook,
    )

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        module = this
        Config.logConfig()
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        log("Main: onSystemServerStarting — loading system hooks")
        CutoutHook.hookFramework(param)
        LetterboxHook.hook(param)
        WhitelistHook.hook(param)
        SubScreenGestureHook.hook(param)
        DisplayStateHook.hook(param)
        AppBoundsHook.hook(param)
        SystemServicesHook.hook(param)
        InputMethodHook.hook(param)
        InterceptHook.hook(param)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        log("Main: onPackageReady pkg=${param.packageName} first=${param.isFirstPackage}")
        hooks.forEach { hook ->
            val isWildcard = hook.targetPackages.contains("*")
            val isTargeted = hook.targetPackages.contains(param.packageName)

            if (!isWildcard && !isTargeted) return@forEach

            // "*" hooks use the first package's classloader (framework classes).
            // Skip for subsequent packages to avoid duplicate hooking.
            // Exception: DeviceIdentityHook needs per-package exclusion for
            // SystemUI, Sogou, and fliphome (need real isFlipDevice).
            if (isWildcard && !param.isFirstPackage && hook !is DeviceIdentityHook) return@forEach

            log("Main: loading ${hook.javaClass.simpleName} for ${param.packageName}")
            hook.hook(param)
        }
    }
}
