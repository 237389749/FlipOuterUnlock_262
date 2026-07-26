package com.example.flipunlock.hook.systemui

import android.content.ComponentName
import android.content.Context
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedInterface.PRIORITY_LOWEST
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * SystemUI-side hooks for the external display.
 *
 * Currently hooks:
 * - DecorWindowManagerImpl.shouldHideDecorWindow() to hide widget overlay
 * - MiuiNotificationMenuRow.createMenuViews() with isTinyScreen -> false scope
 * - MiuiCollapsedStatusBarFragment clock visibility (hide on flip outer screen)
 * - NotificationIconContainer / MiuiStatusIconContainer icon expansion
 *
 * DeviceIdentityHook is excluded from SystemUI process (lock screen crash),
 * so this hook applies fixes unconditionally without device state checks.
 */
object SystemUIHook : BaseHook() {
    override val targetPackages = listOf("com.android.systemui")

    // Default max icons for status bar. 8 ensures the always-on display
    // shows enough notification icons.
    private const val STATUS_BAR_ICON_MAX = 8

    override fun setupHooks(param: PackageReadyParam) {
        log("SystemUIHook: loading for ${param.packageName}")
        hookHideDisplayCutoutOrganizer(param)
        hookDecorWindowManager(param)
        hookStatusBarClock(param)
        hookStatusBarIcons(param)
    }

    // ── HideDisplayCutoutOrganizer: block Shell-level cutout crop ──────
    //
    // This DisplayAreaOrganizer reads Display.getCutout().getSafeInsets()
    // and applies setWindowCrop() on the entire display area Surface,
    // cropping content to displayWidth - safeInsetRight. This is a SEPARATE
    // layer from system_server's WindowLayout — even if window frames are
    // correct, the Shell crops them at the Surface level.
    //
    // Hook getDisplayCutoutInsetsOfNaturalOrientation() → Insets.NONE to
    // prevent the crop. Must hook BEFORE updateBoundsAndOffsets() runs.

    private fun hookHideDisplayCutoutOrganizer(param: PackageReadyParam) {
        runCatching {
            val cls = param.classLoader.loadClass(
                "com.android.wm.shell.hidedisplaycutout.HideDisplayCutoutOrganizer")
            val method = cls.getDeclaredMethod("getDisplayCutoutInsetsOfNaturalOrientation")
            method.isAccessible = true
            hook(method) { chain ->
                val result = chain.proceed()
                // One-shot: log what the original method returned vs our override
                if (result != android.graphics.Insets.NONE) {
                    log("DIAG: HideDisplayCutoutOrganizer original insets=$result → overriding to NONE")
                }
                android.graphics.Insets.NONE
            }
            log("DIAG: HideDisplayCutoutOrganizer HOOKED ✓")
        }.onFailure { log("DIAG: HideDisplayCutoutOrganizer FAILED: ${it.message}") }
    }

    // ── DecorWindowManagerImpl.shouldHideDecorWindow ────────────────────
    // Returns true = hide widget, false = show widget.
    // We force true to always hide from SystemUI side.
    private fun hookDecorWindowManager(param: PackageReadyParam) {
        runCatching {
            val cls = param.classLoader.loadClass(
                "com.android.notification.decor.DecorWindowManagerImpl"
            )
            val method = cls.method(
                "shouldHideDecorWindow", ComponentName::class.java
            )
            hook(method, replaceResult(true))
            log("SystemUI: forced DecorWindowManagerImpl.shouldHideDecorWindow -> true")
        }.onFailure { log("SystemUI: failed hook DecorWindowManagerImpl", it) }
    }

    // ── Notification menu ───────────────────────────────────────────────
    // v2.9: scoped isTinyScreen→false hook REMOVED — redundant with
    // LockScreenHook's permanent hook in the same SystemUI process.
    // The createMenuViews hook was a no-op without the scoped override.

    // ── Status bar clock hiding ──────────────────────────────────────────
    // Always hide the status bar clock on the external display since
    // the always-on/outer screen has its own clock layout.
    private fun hookStatusBarClock(param: PackageReadyParam) {
        runCatching {
            val fragmentClass = param.classLoader.loadClass(
                "com.android.systemui.statusbar.phone.MiuiCollapsedStatusBarFragment"
            )

            // clockHiddenMode -> return 8 (GONE) to hide clock
            hook(fragmentClass.method("clockHiddenMode")) { 8 }

            // updateStatusBarVisibilities -> after proceed, force hideClock
            hook(fragmentClass.method(
                "updateStatusBarVisibilities", Boolean::class.java
            )) { chain ->
                val result = chain.proceed()
                chain.thisObject?.callMethod("hideClock", false)
                result
            }

            // showClock -> if arg is true, hide clock instead of showing it
            hook(fragmentClass.method("showClock", Boolean::class.java)) { chain ->
                if (chain.args[0] == true) {
                    chain.thisObject?.callMethod("hideClock", false)
                } else {
                    chain.proceed()
                }
            }

            log("SystemUI: hooked MiuiCollapsedStatusBarFragment clock")
        }.onFailure { log("SystemUI: failed hook status bar clock", it) }
    }

    // ── Status bar icon expansion ────────────────────────────────────────
    // Expand max notification icons shown on the external display.
    // Note: isFlipTinyScreen→false handled by LockScreenHook globally in SystemUI.
    private fun hookStatusBarIcons(param: PackageReadyParam) {
        runCatching {
            // ── NotificationIconContainer ────────────────────────────────
            val containerClass = param.classLoader.loadClass(
                "com.android.systemui.statusbar.phone.NotificationIconContainer"
            )

            val iconHooker = Hooker { chain ->
                val savedMaxIcons = chain.thisObject?.getField("mMaxIcons") as? Int
                chain.thisObject?.setField("mMaxIcons", STATUS_BAR_ICON_MAX)
                runWithCleanup({
                    savedMaxIcons?.let { chain.thisObject?.setField("mMaxIcons", it) }
                }) {
                    chain.proceed()
                }
            }

            hook(
                containerClass.method("calculateIconXTranslations"),
                PRIORITY_LOWEST,
                iconHooker
            )
            hook(
                containerClass.method("onMeasure", Int::class.java, Int::class.java),
                PRIORITY_LOWEST,
                iconHooker
            )

            // ── MiuiStatusIconContainer ──────────────────────────────────
            // REMOVED: scoped isFlipTinyScreen hook was redundant with LockScreenHook.
            // The onMeasure hook is a no-op without it.

            log("SystemUI: hooked status bar icon expansion")
        }.onFailure { log("SystemUI: failed hook status bar icons", it) }
    }

    // ── [REMOVED] NavigationBar fix ────────────────────────────────────────
    // WAS: force NavigationBar creation so miuihome NavStubView could work.
    // REMOVED v2.9: miuihome NavStubView + LauncherHook no longer used.
    // fliphome handles bottom gestures via InputMonitor, no NavStubView needed.
    // HyperOS 4 rewrites miuihome in Rust+Flutter — hook targets gone entirely.

}
