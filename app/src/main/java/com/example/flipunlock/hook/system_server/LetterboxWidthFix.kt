package com.example.flipunlock.hook.system_server

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * Clamp letterboxFullBounds width to current display width on outer screen.
 * Source: DisplayStateHook — hookLargestAppWidth
 */
object LetterboxWidthFix {

    private fun isOuterScreen(): Boolean {
        val dm = android.content.res.Resources.getSystem().displayMetrics
        return Math.max(dm.widthPixels, dm.heightPixels) < 2000
    }

    fun hook(param: SystemServerStartingParam) {
        safeHook("LetterboxWidthFix") {
            runCatching {
                val cls = param.classLoader.loadClass("com.android.server.wm.AppCompatLetterboxPolicy")
                val displayContentClass = param.classLoader.loadClass("com.android.server.wm.DisplayContent")
                val method = cls.getDeclaredMethod("getLetterboxDetails")
                method.isAccessible = true
                hook(method) { chain ->
                    val result = chain.proceed()
                    if (result != null && isOuterScreen()) {
                        val activityRecord = cls.getDeclaredField("mActivityRecord")
                            .apply { isAccessible = true }.get(chain.thisObject)
                        val dc = activityRecord.javaClass.getMethod("getDisplayContent").invoke(activityRecord)
                        val displayInfo = displayContentClass.getMethod("getDisplayInfo").invoke(dc)
                        val curW = displayInfo.javaClass.getDeclaredField("logicalWidth")
                            .apply { isAccessible = true }.getInt(displayInfo)
                        val outerBounds = result.javaClass.getDeclaredField("letterboxFullBounds")
                            .apply { isAccessible = true }.get(result) as? android.graphics.Rect
                        if (outerBounds != null && outerBounds.width() > curW) {
                            outerBounds.right = outerBounds.left + curW
                        }
                    }
                    result
                }
                log("LetterboxWidthFix: letterboxFullBounds correction installed")
            }.onFailure { log("LetterboxWidthFix: failed", it) }
        }
    }
}
