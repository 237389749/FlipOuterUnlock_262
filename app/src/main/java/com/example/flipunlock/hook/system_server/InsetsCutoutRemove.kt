package com.example.flipunlock.hook.system_server

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * Remove display cutout from InsetsState after fillInsetsState runs.
 * This feeds both sync paths (addWindowInner / relayoutWindow) and async
 * paths (reportResized / notifyInsetsControlChanged).
 * Source: AppBoundsHook — hookFillInsetsState
 */
object InsetsCutoutRemove {

    fun hook(param: SystemServerStartingParam) {
        safeHook("InsetsCutoutRemove") {
            val displayCutoutClass = param.classLoader.loadClass("android.view.DisplayCutout")
            val noCutout = displayCutoutClass.field("NO_CUTOUT").get(null)
            val insetsTypeClass = param.classLoader.loadClass("android.view.WindowInsets\$Type")
            val displayCutoutType = insetsTypeClass.method("displayCutout").invoke(null) as? Int ?: 0

            val windowStateClass = param.classLoader.loadClass("com.android.server.wm.WindowState")
            val insetsStateClass = param.classLoader.loadClass("android.view.InsetsState")
            hook(
                windowStateClass.method("fillInsetsState", insetsStateClass, Boolean::class.javaPrimitiveType!!),
                after { chain, _ ->
                    runCatching {
                        val state = chain.args[0]
                        noCutout?.let { state.callMethod("setDisplayCutout", it) }
                        for (i in (state.callMethod("sourceSize") as? Int ?: 0) - 1 downTo 0) {
                            val source = state.callMethod("sourceAt", i) ?: continue
                            if (source.callMethod("getType") as? Int == displayCutoutType) {
                                state.callMethod("removeSourceAt", i)
                            }
                        }
                    }
                    null
                }
            )
        }
    }
}
