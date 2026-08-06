package com.example.flipunlock.hook.system_server

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * Zero cutout in system_server: calculateDisplayCutoutForRotation → NO_CUTOUT
 * + InsetsState.getDisplayCutoutSafe → full bounds.
 * Source: DisplayStateHook — hookDisplayInfoCutoutZero
 */
object CutoutZero {

    fun hook(param: SystemServerStartingParam) {
        safeHook("CutoutZero") {
            val displayCutoutClass = param.classLoader.loadClass("android.view.DisplayCutout")
            val noCutout = displayCutoutClass.field("NO_CUTOUT").get(null) ?: return@safeHook

            // calculateDisplayCutoutForRotation → NO_CUTOUT
            runCatching {
                val dcClass = param.classLoader.loadClass("com.android.server.wm.DisplayContent")
                val method = dcClass.getDeclaredMethod("calculateDisplayCutoutForRotation",
                    Int::class.javaPrimitiveType!!)
                method.isAccessible = true
                hook(method, replaceResult(noCutout))
                log("CutoutZero: calculateDisplayCutoutForRotation → NO_CUTOUT")
            }.onFailure { log("CutoutZero: calculateDisplayCutoutForRotation failed", it) }

            // InsetsState.getDisplayCutoutSafe → full bounds
            runCatching {
                val insetsStateClass = param.classLoader.loadClass("android.view.InsetsState")
                val method = insetsStateClass.getDeclaredMethod("getDisplayCutoutSafe",
                    android.graphics.Rect::class.java)
                method.isAccessible = true
                hook(method, after { chain, _ ->
                    val outBounds = chain.args[0] as? android.graphics.Rect
                    outBounds?.set(-100000, -100000, 100000, 100000)
                    null
                })
                log("CutoutZero: getDisplayCutoutSafe → full bounds")
            }.onFailure { log("CutoutZero: getDisplayCutoutSafe failed", it) }
        }
    }
}
