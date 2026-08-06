package com.example.flipunlock.hook.system_server

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * Force ALWAYS cutout mode in system_server WindowLayoutStubImpl.
 * Source: DisplayStateHook — hookLayoutCutoutMode
 */
object CutoutModeAlways {

    fun hook(param: SystemServerStartingParam) {
        safeHook("CutoutModeAlways") {
            runCatching {
                val cls = param.classLoader.loadClass("android.view.WindowLayoutStubImpl")
                val method = cls.getDeclaredMethod("getLayoutInDisplayCutoutMode",
                    android.view.WindowManager.LayoutParams::class.java)
                method.isAccessible = true
                hook(method, replaceResult(3))  // ALWAYS
                log("CutoutModeAlways: getLayoutInDisplayCutoutMode → ALWAYS")
            }.onFailure { log("CutoutModeAlways: failed", it) }
        }
    }
}
