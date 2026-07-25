package com.example.flipunlock.hook

import android.view.Display
import android.view.DisplayCutout
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Global cutout zeroing — runs in ALL processes.
 *
 * Unlike CutoutHook (which targets only SystemUI/AOD/Camera), this ensures
 * EVERY app on the outer screen sees no display cutout. This fixes app-level
 * toast/snackbar/dialog positioning that was still shifted by cutout insets
 * delivered through WindowInsets even though Display.getCutout() was zeroed
 * in specific processes only.
 */
object GlobalCutoutHook : BaseHook() {
    override val targetPackages = listOf("*")

    override fun hook(param: PackageReadyParam) {
        if (!Config.displayCutout) return
        val pkg = param.packageName
        // Sogou IME needs real cutout info for keyboard sizing on outer screen.
        // Skip ALL hooks — DeviceIdentityHook already excludes Sogou for the
        // same reason. Keyboard width regression traced to b668164 (Build #220).
        if (pkg == "com.sohu.inputmethod.sogou.xiaomi") return
        safeHook("GlobalCutout") {
            hookDisplayGetCutout(pkg)
            hookWindowInsetsGetCutout(pkg)
            hookFlipFoldedCutoutStub(param)
            hookSizeCompatScaleMode(param)
            hookDisplayMetricsDiag(param)
        }
    }

    /**
     * DisplayCutoutStubImpl.isFlipFolded() → false.
     *
     * Skip Sogou IME — keyboard needs real fold state for layout.
     */
    private fun hookFlipFoldedCutoutStub(param: PackageReadyParam) {
        if (param.packageName == "com.sohu.inputmethod.sogou.xiaomi") return
        runCatching {
            val cls = param.classLoader.loadClass("android.view.DisplayCutoutStubImpl")
            val method = cls.getDeclaredMethod("isFlipFolded")
            method.isAccessible = true
            hook(method, replaceResult(false))
            log("GlobalCutout: DisplayCutoutStubImpl.isFlipFolded → false")
        }.onFailure { log("GlobalCutout: isFlipFolded failed", it) }
    }

    /**
     * ActivityThreadStub.inMiuiSizeCompatScaleMode() → false.
     *
     * SystemServicesHook forces getFlipCompatMode→0 (fullscreen) at the
     * system_server level. But the app process still runs MIUI size-compat
     * logic via inMiuiSizeCompatScaleMode(). When true:
     *   - applyViewLocation() shifts views by -bounds.left
     *   - processMotionEvent() adjusts touch coordinates
     *   - updateSizeCompatBounds() modifies layout bounds
     *
     * By forcing false, we prevent the flip-specific view shifting that
     * causes popups/toasts to appear off-center (shifted left).
     *
     * Skip Sogou IME — keyboard needs real size-compat scaling to fill
     * the outer screen width correctly.
     */
    private fun hookSizeCompatScaleMode(param: PackageReadyParam) {
        if (param.packageName == "com.sohu.inputmethod.sogou.xiaomi") return
        runCatching {
            val cls = param.classLoader.loadClass("android.app.ActivityThreadImpl")
            val method = cls.getDeclaredMethod("inMiuiSizeCompatScaleMode")
            method.isAccessible = true
            hook(method, replaceResult(false))
            log("GlobalCutout: inMiuiSizeCompatScaleMode → false")
        }.onFailure { log("GlobalCutout: inMiuiSizeCompatScaleMode failed", it) }

        // Also neutralize getSizeCompatBounds → null to prevent any bounds-based shifting
        runCatching {
            val cls = param.classLoader.loadClass("android.app.ActivityThreadImpl")
            val method = cls.getDeclaredMethod("getSizeCompatBounds")
            method.isAccessible = true
            hook(method, replaceResult(null))
            log("GlobalCutout: getSizeCompatBounds → null")
        }.onFailure { log("GlobalCutout: getSizeCompatBounds failed", it) }
    }

    /**
     * Diagnostic: log DisplayMetrics and cutout from WindowManager for SystemUI.
     */
    private fun hookDisplayMetricsDiag(param: PackageReadyParam) {
        if (param.packageName != "com.android.systemui") return
        runCatching {
            val wm = android.view.WindowManager::class.java
            val method = wm.getDeclaredMethod("getCurrentWindowMetrics")
            method.isAccessible = true
            var done = false
            hook(method) { chain ->
                val result = chain.proceed()
                if (!done) {
                    done = true
                    val bounds = result?.javaClass?.getDeclaredMethod("getBounds")?.invoke(result) as? android.graphics.Rect
                    log("GlobalCutout DIAG: WindowMetrics bounds=$bounds")
                    val dm = android.content.res.Resources.getSystem().displayMetrics
                    log("GlobalCutout DIAG: system DisplayMetrics=${dm.widthPixels}x${dm.heightPixels} density=${dm.density}")
                }
                result
            }
        }.onFailure { log("GlobalCutout DIAG failed", it) }
    }

    private fun hookDisplayGetCutout(pkg: String) {
        runCatching {
            val method = Display::class.java.method("getCutout")
            val zero = noCutout() ?: return
            hook(method) { zero }
        }.onFailure { log("GlobalCutout: Display.getCutout failed", it) }
    }

    private fun hookWindowInsetsGetCutout(pkg: String) {
        runCatching {
            val method = android.view.WindowInsets::class.java.getDeclaredMethod("getDisplayCutout")
            method.isAccessible = true
            hook(method, replaceResult(null))
        }.onFailure { log("GlobalCutout: WindowInsets.getDisplayCutout failed", it) }
    }

    // Cache NO_CUTOUT from static field — more reliable than constructor reflection
    @Volatile private var noCutoutCache: DisplayCutout? = null

    private fun noCutout(): DisplayCutout? {
        noCutoutCache?.let { return it }
        runCatching {
            val dcClass = DisplayCutout::class.java
            val field = dcClass.getDeclaredField("NO_CUTOUT")
            field.isAccessible = true
            val nc = field.get(null) as? DisplayCutout
            if (nc != null) noCutoutCache = nc
            return nc
        }.onFailure { log("GlobalCutout: NO_CUTOUT field access failed", it) }
        return null
    }
}
