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
 * Disable fliphome launcher UI and interstitial start pages.
 *
 * With state=6 (DUAL, outer=displayId=0), the inner launcher (com.miui.home)
 * runs on the outer screen. fliphome's FlipLauncher UI should be hidden.
 *
 * Bottom gesture navigation in apps is handled by SystemUI's NavigationBar
 * (fixed in SystemUIHook), not by fliphome's InputMonitor.
 *
 * Back gestures work via fliphome's GestureStubView (edge overlays),
 * which are independent of our hooks.
 */
object GestureHook : BaseHook() {
    override val targetPackages = listOf("com.miui.fliphome")

    private var launcherEnabled = false

    override fun setupHooks(param: PackageReadyParam) {
        if (!Config.gestureBack) { log("GestureFix: DISABLED by persist.flipunlock.gesture.back"); return }
        log("GestureFix: setupHooks")
        hookNoStartPage(param)
        ensureFlipLauncherEnabled(param)
//        hookSideGesturePersistence(param)  // DISABLED: fliphome handles gestures natively when it's the active launcher
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

    // ── 2. [DISABLED] Side gesture persistence ───────────────────────────
    // WAS: protect fliphome gestures from fold-state false signals during
    // miuihome-takeover era. Now fliphome is the active launcher and handles
    // gesture lifecycle natively via BaseGestureImpl enableGestureInput()/
    // clearBackStubWindow() based on actual fold state.
    //
    // BaseGestureImpl.onDisplayFoldChanged(false) triggers:
    //   clearBackStubWindow() → GestureStubView.clearGestureStub()
    //     → hideGestureStub() + wm.removeView(this)  ← WINDOW DESTROYED
    //     → mGestureStubLeft/Right = null
    //   disableGestureInput() → mGestureInputHelper.setEnable(false)
    //
    // Once the stubs are destroyed, showBackStubWindow() is a no-op
    // (null check returns early). They only come back if onDisplayFoldChanged(true)
    // fires again, which may never happen due to our display state hooks.
    //
    // Fix: block clearBackStubWindow + disableGestureInput at the source.
    // Also force mIsFolded=true so all the guards pass.
    private fun hookSideGesturePersistence(param: PackageReadyParam) {
        runCatching {
            val cls = param.classLoader.loadClass(
                "com.miui.fliphome.gesture.BaseGestureImpl")

            // Block clearBackStubWindow() — prevents GestureStubView removal
            runCatching {
                val method = cls.getDeclaredMethod("clearBackStubWindow")
                method.isAccessible = true
                hook(method) {
                    log("GestureFix: BLOCKED clearBackStubWindow")
                    null
                }
            }

            // Block disableGestureInput() — keeps InputMonitor alive
            runCatching {
                val method = cls.getDeclaredMethod("disableGestureInput")
                method.isAccessible = true
                hook(method) {
                    log("GestureFix: BLOCKED disableGestureInput")
                    null
                }
            }

            // Force mIsFolded=true on every relevant callback
            runCatching {
                val foldedField = cls.getDeclaredField("mIsFolded")
                foldedField.isAccessible = true

                // Hook onResumed: force mIsFolded=true before enableGestureInput() check
                val onResumedMethod = cls.getDeclaredMethod("onResumed",
                    android.content.ComponentName::class.java)
                onResumedMethod.isAccessible = true
                hook(onResumedMethod, before { chain ->
                    val obj = chain.thisObject
                    runCatching { foldedField.setBoolean(obj, true) }
                })

                // Hook AbstractSystemEventPresenter.onDisplayFoldChanged:
                // spurious unfold signal → mIsFolded=false → gestures dead.
                // Restore mIsFolded=true AFTER the original runs.
                runCatching {
                    val presenterClass = param.classLoader.loadClass(
                        "com.miui.fliphome.presenter.AbstractSystemEventPresenter")
                    val foldMethod = presenterClass.getDeclaredMethod(
                        "onDisplayFoldChanged", Boolean::class.javaPrimitiveType!!)
                    foldMethod.isAccessible = true
                    hook(foldMethod, after { chain, _ ->
                        val isFolded = chain.args[0] as? Boolean
                        if (isFolded == false) {
                            // Anonymous class BaseGestureImpl$1 → this$0 = outer BaseGestureImpl
                            val this0 = chain.thisObject?.getField("this\$0")
                            if (this0 != null) {
                                runCatching { foldedField.setBoolean(this0, true) }
                                log("GestureFix: restored mIsFolded=true after onDisplayFoldChanged(false)")
                            }
                        }
                        null
                    })
                }

                // Defense-in-depth: also block hideBackStubWindow
                runCatching {
                    val hideMethod = cls.getDeclaredMethod("hideBackStubWindow")
                    hideMethod.isAccessible = true
                    hook(hideMethod) {
                        log("GestureFix: BLOCKED hideBackStubWindow")
                        null
                    }
                }
            }

            log("GestureFix: side gesture persistence hooks installed")
        }.onFailure { log("GestureFix: side gesture persistence failed", it) }
    }

    // ── 3. Ensure FlipLauncher component is ENABLED ────────────────────
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
