package com.example.flipunlock

import com.example.flipunlock.hook.identity.DeviceIdentityHook
import com.example.flipunlock.hook.cutout.CutoutHook
import com.example.flipunlock.hook.cutout.GlobalCutoutHook
import com.example.flipunlock.hook.util.log
import com.example.flipunlock.hook.util.Config
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

internal var module: Main? = null

class Main : XposedModule() {

    private val hooks = listOf(
        DeviceIdentityHook,  // toast 居中 (ROOT: isFlipDevice → false)
        // GlobalCutoutHook,    // [TEST DISABLED] cutout 去除
        // CutoutHook,          // [TEST DISABLED] cutout 去除
    )

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        module = this
        Config.logConfig()
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        log("Main: onSystemServerStarting — loading system hooks")
        CutoutHook.hookFramework(param)  // cutout 去除 (框架侧)
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
