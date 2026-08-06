package com.example.flipunlock.hook.system_server

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * Expand computeFrames parentFrame right edge to full windowBounds width.
 * Source: DisplayStateHook — hookComputeFrames
 */
object FrameExpand {

    fun hook(param: SystemServerStartingParam) {
        safeHook("FrameExpand") {
            runCatching {
                val wlClass = param.classLoader.loadClass("android.view.WindowLayout")
                val method = wlClass.declaredMethods.filter { it.name == "computeFrames" }
                    .maxByOrNull { it.parameterCount }
                if (method == null) {
                    log("FrameExpand: computeFrames NOT FOUND")
                    return@runCatching
                }
                method.isAccessible = true
                hook(method) { chain ->
                    val windowBounds = chain.args[3] as? android.graphics.Rect
                    val fullRight = windowBounds?.right ?: 0
                    val result = chain.proceed()
                    if (fullRight <= 0) return@hook result
                    val frames = chain.args[chain.args.size - 1]
                    if (frames != null) {
                        val pf = frames.getField("parentFrame") as? android.graphics.Rect
                        val df = frames.getField("displayFrame") as? android.graphics.Rect
                        if (pf != null && pf.right in 1 until fullRight) pf.right = fullRight
                        if (df != null && df.right in 1 until fullRight) df.right = fullRight
                    }
                    result
                }
                log("FrameExpand: computeFrames hooked")
            }.onFailure { log("FrameExpand: failed", it) }
        }
    }
}
