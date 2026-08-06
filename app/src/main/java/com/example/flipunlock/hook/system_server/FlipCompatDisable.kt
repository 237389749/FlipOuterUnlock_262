package com.example.flipunlock.hook.system_server

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * Disable flip compat mode and fullscreen overrides in system_server.
 * Forces getFlipCompatModeByApp → 0, getFlipCompatModeByActivity → 0,
 * and getFullScreenValue → 0.
 * Source: SystemServicesHook — hookBoundsCompatUtilsByApp
 *         + hookBoundsCompatUtilsByActivity + hookWindowManagerGetFullScreenValue
 */
object FlipCompatDisable {

    fun hook(param: SystemServerStartingParam) {
        safeHook("FlipCompatDisable") {
            hookFlipCompatByApp(param)
            hookFlipCompatByActivity(param)
            hookFullScreenValue(param)
        }
    }

    private fun hookFlipCompatByApp(param: SystemServerStartingParam) {
        runCatching {
            val boundsCompatUtils = param.classLoader.loadClass(
                "com.android.server.wm.BoundsCompatUtils"
            )
            val atmsClass = param.classLoader.loadClass(
                "android.app.ActivityTaskManagerService"
            )
            val method = boundsCompatUtils.method(
                "getFlipCompatModeByApp", atmsClass, String::class.java
            )
            hook(method, replaceResult(0))
            log("FlipCompatDisable: getFlipCompatModeByApp → 0")
        }.onFailure { log("FlipCompatDisable: getFlipCompatModeByApp failed", it) }
    }

    private fun hookFlipCompatByActivity(param: SystemServerStartingParam) {
        runCatching {
            val boundsCompatUtils = param.classLoader.loadClass(
                "com.android.server.wm.BoundsCompatUtils"
            )
            val activityRecordClass = param.classLoader.loadClass(
                "com.android.server.wm.ActivityRecord"
            )
            val method = boundsCompatUtils.method(
                "getFlipCompatModeByActivity", activityRecordClass
            )
            hook(method, replaceResult(0))
            log("FlipCompatDisable: getFlipCompatModeByActivity → 0")
        }.onFailure { log("FlipCompatDisable: getFlipCompatModeByActivity failed", it) }
    }

    private fun hookFullScreenValue(param: SystemServerStartingParam) {
        runCatching {
            val wmsImpl = param.classLoader.loadClass(
                "com.android.server.wm.WindowManagerServiceImpl"
            )
            val packageItemInfoClass = param.classLoader.loadClass(
                "android.content.pm.PackageItemInfo"
            )
            val method = wmsImpl.method(
                "getFullScreenValue", packageItemInfoClass
            )
            hook(method, replaceResult(0))
            log("FlipCompatDisable: getFullScreenValue → 0")
        }.onFailure { log("FlipCompatDisable: getFullScreenValue failed", it) }
    }
}
