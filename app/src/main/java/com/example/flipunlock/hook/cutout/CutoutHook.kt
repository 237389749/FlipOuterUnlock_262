package com.example.flipunlock.hook.cutout

import com.example.flipunlock.hook.BaseHook

import android.graphics.Insets
import android.graphics.Path
import android.graphics.Rect
import android.view.Display
import android.view.DisplayCutout
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedInterface.Hooker
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
            hookPathAndDisplayCutoutFromSpec(param.classLoader)
            // DISABLED: hookDisplayFlipFoldedCutout — 排除法测试
        }
    }

    override fun setupHooks(param: PackageReadyParam) {
        if (!Config.displayCutout) return
        log("CutoutHook: loading for ${param.packageName}")
        hookCutoutParser(param.classLoader)
        hookPathAndDisplayCutoutFromSpec(param.classLoader)
        // DISABLED: hookDisplayFlipFoldedCutout — 排除法测试
    }

    private fun hookCutoutParser(classLoader: ClassLoader) {
        runCatching {
            val parserClass = classLoader.loadClass("android.view.CutoutSpecification\$Parser")
            val parseMethod = parserClass.method("parse", String::class.java)
            // Zero out ALL cutout specs unconditionally.
            // Previously only filtered specific strings ("M 604,664", "@bind_right_cutout")
            // which missed other cutout sources (config_mainBuiltInDisplayCutout, etc.)
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

        // Hook computeSafeInsets — zero both return value AND the out-Rect.
        // computeSafeInsets takes (int rotation, Rect outRect): fills outRect
        // with safe insets (left, top, right, bottom) and returns edge count.
        // The old hook only zeroed return value — callers read the non-zero
        // values from outRect, causing hints/toasts to shift left as if a
        // right-side cutout were still present.
        runCatching {
            val parserClass = classLoader.loadClass("android.view.CutoutSpecification\$Parser")
            val method = parserClass.getDeclaredMethod("computeSafeInsets",
                Int::class.javaPrimitiveType!!,
                android.graphics.Rect::class.java)
            method.isAccessible = true
            hook(method) { chain ->
                val outRect = chain.args[1] as? android.graphics.Rect
                outRect?.setEmpty()  // zero all four edges
                0  // no edges have safe insets
            }
        }.onFailure { log("CutoutFix: failed hook computeSafeInsets", it) }
    }

    // Hook DisplayCutout.pathAndDisplayCutoutFromSpec — THE single choke point
    // where ALL cutout strings (resource-loaded or direct-spec) are parsed.
    // Return (null, NO_CUTOUT) unconditionally to block ALL cutouts.
    private fun hookPathAndDisplayCutoutFromSpec(classLoader: ClassLoader) {
        runCatching {
            val dcClass = classLoader.loadClass("android.view.DisplayCutout")
            val method = dcClass.declaredMethods.firstOrNull {
                it.name == "pathAndDisplayCutoutFromSpec" && it.parameterCount == 9
            } ?: return@runCatching
            method.isAccessible = true

            val noCutout = dcClass.getDeclaredField("NO_CUTOUT").also { it.isAccessible = true }.get(null)!!
            val pairClass = classLoader.loadClass("android.util.Pair")
            val pairCtor = pairClass.getConstructor(Any::class.java, Any::class.java)

            hook(method) {
                pairCtor.newInstance(null, noCutout) // Pair(null, NO_CUTOUT) — always
            }
        }.onFailure { log("CutoutFix: failed hook pathAndDisplayCutoutFromSpec", it) }
    }

    // MIUI hidden method: Display.getFlipFoldedCutout()
    // Called reflectively by AlertController (miuix.jar) to get the
    // folded-state cutout. Separate from getCutout() — must be hooked
    // independently to prevent MIUI dialogs from seeing the real cutout.
    private fun hookDisplayFlipFoldedCutout() {
        runCatching {
            val method = Display::class.java.method("getFlipFoldedCutout")
            hook(method, replaceResult(null))
        }.onFailure { /* method may not exist on non-MIUI or older versions */ }
    }

}
