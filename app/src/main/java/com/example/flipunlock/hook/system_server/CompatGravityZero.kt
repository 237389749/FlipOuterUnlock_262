package com.example.flipunlock.hook.system_server

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * BoundsCompatUtils.getCompatGravity() → 0 (NO_GRAVITY).
 *
 * In system_server, this method reads the internal DisplayCutout (not
 * affected by our Display.getCutout() API hook). It checks cutout safe
 * insets to choose gravity, which feeds into positionCompatBounds()
 * and causes the left-shift of popups/toasts.
 * Source: SystemServicesHook — hookCompatGravity
 */
object CompatGravityZero {

    fun hook(param: SystemServerStartingParam) {
        safeHook("CompatGravityZero") {
            runCatching {
                val cls = param.classLoader.loadClass(
                    "com.android.server.wm.BoundsCompatUtils")
                val method = cls.declaredMethods.firstOrNull {
                    it.name == "getCompatGravity" && it.parameterCount == 1
                }
                if (method != null) {
                    method.isAccessible = true
                    hook(method, replaceResult(0))
                    log("CompatGravityZero: getCompatGravity → 0")
                }
            }.onFailure { log("CompatGravityZero: failed", it) }
        }
    }
}
