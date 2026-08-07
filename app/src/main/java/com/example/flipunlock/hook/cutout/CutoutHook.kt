package com.example.flipunlock.hook.cutout

import com.example.flipunlock.hook.BaseHook

import android.graphics.Insets
import android.graphics.Path
import android.graphics.Rect
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

object CutoutHook : BaseHook() {
    override val targetPackages = listOf(
        "com.android.systemui",
        "com.miui.aod",
        "com.android.camera",
    )

    fun hookFramework(param: SystemServerStartingParam) {
        if (!Config.displayCutout) { log("CutoutHook: DISABLED by persist.flipunlock.display.cutout"); return }
        log("CutoutHook-framework: setting up in system_server")
        safeHook("CutoutHook-framework") {
            hookCutoutParser(param.classLoader)
        }
    }

    override fun setupHooks(param: PackageReadyParam) {
        if (!Config.displayCutout) return
        log("CutoutHook: loading for ${param.packageName}")
        hookCutoutParser(param.classLoader)
    }

    private fun hookCutoutParser(classLoader: ClassLoader) {
        runCatching {
            val parserClass = classLoader.loadClass("android.view.CutoutSpecification\$Parser")
            val parseMethod = parserClass.method("parse", String::class.java)
            hook(parseMethod, after { chain, result ->
                val spec = result ?: return@after result
                spec.setField("mLeftBound", Rect(0, 0, 0, 0))
                spec.setField("mTopBound", Rect(0, 0, 0, 0))
                spec.setField("mRightBound", Rect(0, 0, 0, 0))
                spec.setField("mBottomBound", Rect(0, 0, 0, 0))
                spec.setField("mInsets", Insets.of(0, 0, 0, 0))
                spec.setField("mPath", Path())
                result
            })
        }.onFailure { log("CutoutFix: failed hook parser", it) }
    }
}
