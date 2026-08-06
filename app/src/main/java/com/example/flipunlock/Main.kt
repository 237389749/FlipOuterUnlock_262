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
        // ScreenTypeHook,  // [ROUND4 tested - not sufficient alone]
        DeviceIdentityHook,  // [ROUND5] testing alone
        // GlobalCutoutHook,  // [ROUND2 tested - not the key]
        // AodHook,  // [DISABLED for toast-debug]
        // ControlCenterHook,  // [DISABLED for toast-debug]
        // CutoutHook,  // [ROUND8 DISABLED] testing DeviceIdentity + system_server only
        // SystemUIHook,  // [ROUND8 DISABLED]
        // GestureHook,  // [DISABLED for toast-debug] v2: block fliphome InputMonitor
        // LockScreenHook,  // [DISABLED for toast-debug] fix lock screen
        // DisplayFilterFix,  // [DISABLED for toast-debug] Gate 7: prevent display-ID filtering
        // SogouInputHook,  // [DISABLED for toast-debug]
        // ActivityLifecycleHook,  // [DISABLED for toast-debug]
        // WatchOverlayHook,  // [DISABLED for toast-debug]
    )

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        module = this
        Config.logConfig()
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        log("Main: onSystemServerStarting — loading system hooks")
        // CutoutHook.hookFramework(param)  // [ROUND8 DISABLED]
        // LetterboxHook.hook(param)  // [DISABLED for toast-debug]
        // WhitelistHook.hook(param)  // [DISABLED] not cutout/display
        // SubScreenGestureHook.hook(param)  // [DISABLED for toast-debug]
        // DisplayStateHook.hook(param)  // [ROUND9 DISABLED]
        // AppBoundsHook.hook(param)  // [ROUND9 DISABLED]
        // SystemServicesHook.hook(param)  // [ROUND9 DISABLED]
        // InputMethodHook.hook(param)  // [DISABLED] not cutout/display
        // InterceptHook.hook(param)  // [DISABLED] not cutout/display
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
