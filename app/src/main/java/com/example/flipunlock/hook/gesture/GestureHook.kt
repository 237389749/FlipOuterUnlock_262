package com.example.flipunlock.hook.gesture

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.UserHandle
import android.view.View
import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * fliphome launcher — block interstitial start pages, ensure FlipLauncher enabled.
 *
 * fliphome (com.miui.fliphome) is the active launcher on the outer screen.
 * Gestures are handled by fliphome's native InputMonitor("swipe-up") —
 * miuihome NavStubView hacks are no longer used.
 *
 * Back gestures work via fliphome's GestureStubView (edge overlays),
 * which are independent of our hooks.
 *
 * Previous miuihome-takeover architecture (LauncherHook, hookSideGesturePersistence,
 * hookNavigationBar) removed in v2.9 — HyperOS 4 rewrites miuihome in Rust+Flutter.
 */
object GestureHook : BaseHook() {
    override val targetPackages = listOf("com.miui.fliphome")

    private var launcherEnabled = false

    override fun setupHooks(param: PackageReadyParam) {
        log("GestureFix: setupHooks")
        hookNoStartPage(param)
        ensureFlipLauncherEnabled(param)
        blockRegisterInputConsumer(param)  // prevent SecurityException crash
    }

    // ── 1. No start page (ported from MixFlipMod) ────────────────────────
    private fun hookNoStartPage(param: PackageReadyParam) {
        runCatching {
            val cls = param.classLoader.loadClass("com.miui.fliphome.utils.PerformLaunchAction")
            val method = cls.method("onStartIntercept",
                UserHandle::class.java, Intent::class.java, Bundle::class.java, View::class.java)
            hook(method, replaceResult(false))
            log("GestureFix: disabled start page intercept")
        }.onFailure { log("GestureFix: PerformLaunchAction not found", it) }
    }

    // ── 3. Block registerInputConsumer to prevent SecurityException crash ──
    // fliphome calls IWindowManager.createInputConsumer which requires INPUT_CONSUMER
    // permission that fliphome doesn't have → SecurityException → crash → black screen.
    // Fix: hook GestureInputHelper.registerInputConsumer() to return immediately.
    private fun blockRegisterInputConsumer(param: PackageReadyParam) {
        runCatching {
            val cls = param.classLoader.loadClass(
                "com.miui.fliphome.gesture.GestureInputHelper")
            val method = cls.getDeclaredMethod("registerInputConsumer")
            method.isAccessible = true
            hook(method) {
                log("GestureFix: BLOCKED registerInputConsumer")
                null
            }
            log("GestureFix: blockRegisterInputConsumer installed")
        }.onFailure { log("GestureFix: blockRegisterInputConsumer failed", it) }
    }

    // ── 4. Ensure FlipLauncher component is ENABLED ────────────────────
    // Previous versions DISABLED FlipLauncher (miuihome-takeover era).
    // DONT_KILL_APP means the disabled state persisted across reboots.
    // Now fliphome is the active launcher — must ENABLE it or fliphome
    // crashes with NPE in GestureModeApp.<init> (resolveActivity returns null).
    private fun ensureFlipLauncherEnabled(param: PackageReadyParam) {
        if (launcherEnabled) return
        runCatching {
            val flipAppClass = param.classLoader.loadClass("com.miui.fliphome.FlipApplication")
            hook(flipAppClass.method("onCreate"), after { chain, result ->
                if (launcherEnabled) return@after result
                runCatching {
                    val app = chain.thisObject
                    val ctx = app.callMethod("getApplicationContext") as? Context ?: return@runCatching
                    val component = ComponentName("com.miui.fliphome", "com.miui.fliphome.FlipLauncher")
                    val pm = ctx.packageManager
                    val current = pm.getComponentEnabledSetting(component)
                    if (current != PackageManager.COMPONENT_ENABLED_STATE_ENABLED &&
                        current != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
                        pm.setComponentEnabledSetting(component,
                            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                            PackageManager.DONT_KILL_APP)
                        log("GestureFix: ENABLED FlipLauncher (was $current)")
                    }
                    launcherEnabled = true
                }.onFailure { log("GestureFix: enable FlipLauncher err", it) }
                result
            })
            log("GestureFix: hooked FlipApplication.onCreate")
        }.onFailure { log("GestureFix: FlipApplication failed", it) }
    }
}
