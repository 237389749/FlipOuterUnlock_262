package com.example.flipunlock.hook.util

/**
 * Feature toggles via SystemProperties. All default to true (enabled).
 *
 * List all keys and current values:
 *   getprop | grep persist.flipunlock
 *
 * Set before reboot:
 *   setprop persist.flipunlock.enable false              # master kill switch
 *   setprop persist.flipunlock.display.dual false        # dual display
 *   setprop persist.flipunlock.display.aod false         # outer screen AOD
 *   setprop persist.flipunlock.display.cutout false      # remove cutout
 *   setprop persist.flipunlock.gesture.home false        # bottom gestures
 *   setprop persist.flipunlock.gesture.back false        # back gestures
 *   setprop persist.flipunlock.ui.lockscreen false       # lock screen layout
 *   setprop persist.flipunlock.ui.widget false           # disable widget overlay
 *   setprop persist.flipunlock.ui.controlcenter false    # control center restore
 *   setprop persist.flipunlock.ui.recentsmenu false      # recents long-press menu
 *   setprop persist.flipunlock.ime false                 # input method freedom
 *
 * ═══ Dependency / Coupling ═══
 *
 * display.aod ── depends on ── display.dual
 *   Enforced: displayAod getter includes displayDual check. Disabling dual
 *   automatically disables AOD.
 *
 * gesture.back ── depends on ── gesture.home
 *   Enforced: gestureBack getter includes gestureHome check. Disabling home
 *   automatically disables back. Both target fliphome launcher identity.
 *
 * display.cutout ── INDEPENDENT ──
 * ui.widget ── INDEPENDENT ──
 * ui.controlcenter ── INDEPENDENT ──
 * ui.recentsmenu ── INDEPENDENT ──
 * ui.lockscreen ── INDEPENDENT ──
 * ime ── INDEPENDENT ──
 *
 * ═══ Centralized Exclusion Lists ═══
 *
 * These packages need REAL device identity or cutout info:
 * - com.android.systemui: lock screen panel layout (TinyKeyguardPanelViewController)
 * - com.sohu.inputmethod.sogou.xiaomi: keyboard height (isTinyScreen) + layout (safeInsetRight)
 * - com.miui.fliphome: outer screen launcher init (isFlipDevice/isFlipTinyScreen)
 *
 * All hooks reference these lists — no per-file hardcoded exclusions.
 */
object Config {
    private val keys = listOf(
        "persist.flipunlock.enable",
        "persist.flipunlock.display.dual",
        "persist.flipunlock.display.aod",
        "persist.flipunlock.display.cutout",
        "persist.flipunlock.gesture.home",
        "persist.flipunlock.gesture.back",
        "persist.flipunlock.ui.lockscreen",
        "persist.flipunlock.ui.widget",
        "persist.flipunlock.ui.controlcenter",
        "persist.flipunlock.ui.recentsmenu",
        "persist.flipunlock.ime",
    )

    // ── Centralized exclusion lists ───────────────────────────────────
    // Packages excluded from DeviceIdentityHook (need real flip identity).
    val identityExcludedPackages: Set<String> = setOf(
        "com.android.systemui",                     // lock screen panel layout
        "com.sohu.inputmethod.sogou.xiaomi",         // keyboard height on outer screen
        "com.miui.fliphome",                         // outer screen launcher init
    )

    // Packages excluded from cutout hooks (need real cutout insets).
    val cutoutExcludedPackages: Set<String> = setOf(
        "com.sohu.inputmethod.sogou.xiaomi",         // keyboard reads safeInsetRight for layout mode
    )

    // ── Master switch ─────────────────────────────────────────────────
    val enabled: Boolean get() = raw("persist.flipunlock.enable", true)

    // ── Display ───────────────────────────────────────────────────────
    val displayDual: Boolean get() = enabled && raw("persist.flipunlock.display.dual", true)

    // display.aod depends on display.dual — AOD targets display=0 (outer in state=6).
    // Enforced here so disabling dual automatically disables AOD.
    val displayAod: Boolean get() = enabled && displayDual && raw("persist.flipunlock.display.aod", true)

    val displayCutout: Boolean get() = enabled && raw("persist.flipunlock.display.cutout", true)

    // ── Gesture — back depends on home ────────────────────────────────
    // Both target the same fliphome launcher identity. If home gestures are
    // off, back gestures should also be off to avoid inconsistent state.
    // Enforced here so disabling home automatically disables back.
    val gestureHome: Boolean get() = enabled && raw("persist.flipunlock.gesture.home", true)
    val gestureBack: Boolean get() = enabled && gestureHome && raw("persist.flipunlock.gesture.back", true)

    // ── UI ────────────────────────────────────────────────────────────
    val uiLockScreen: Boolean get() = enabled && raw("persist.flipunlock.ui.lockscreen", true)
    val uiWidget: Boolean get() = enabled && raw("persist.flipunlock.ui.widget", true)
    val uiControlCenter: Boolean get() = enabled && raw("persist.flipunlock.ui.controlcenter", true)
    val uiRecentsMenu: Boolean get() = enabled && raw("persist.flipunlock.ui.recentsmenu", true)

    // ── Other ─────────────────────────────────────────────────────────
    val ime: Boolean get() = enabled && raw("persist.flipunlock.ime", true)

    /** Print all toggle keys and values, plus coupling verification. */
    fun logConfig() {
        val sb = StringBuilder("═══ FlipOuterUnlock Config ═══\n")
        for (key in keys) {
            sb.append("  $key = ${readProp(key)}\n")
        }
        // Verify coupling constraints (belt-and-suspenders — getters already enforce)
        if (displayAod && !displayDual) {
            sb.append("  ⚠ display.aod=ON but display.dual=OFF (should not happen — getter enforces)\n")
        }
        if (gestureBack && !gestureHome) {
            sb.append("  ⚠ gesture.back=ON but gesture.home=OFF (should not happen — getter enforces)\n")
        }
        sb.append("  Identity excluded: ${identityExcludedPackages.joinToString()}\n")
        sb.append("  Cutout excluded:   ${cutoutExcludedPackages.joinToString()}\n")
        sb.append("  (getprop | grep persist.flipunlock)")
        log(sb.toString())
    }

    // ── Internal ──────────────────────────────────────────────────────

    private fun raw(key: String, default: Boolean): Boolean {
        return try {
            Class.forName("android.os.SystemProperties")
                .getDeclaredMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType!!)
                .invoke(null, key, default) as? Boolean ?: default
        } catch (_: Exception) {
            default
        }
    }

    private fun readProp(key: String): String {
        return try {
            Class.forName("android.os.SystemProperties")
                .getDeclaredMethod("get", String::class.java, String::class.java)
                .invoke(null, key, "") as? String ?: ""
        } catch (_: Exception) {
            ""
        }
    }
}
