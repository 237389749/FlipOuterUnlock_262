package com.example.flipunlock.hook.system_server

import android.content.ComponentName
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * Inject continuity properties so the flip activity-interception always
 * allows launch — belt-and-suspenders for AppWhitelist (allowstart).
 *
 * Logic chain (refMD: FoldState_Device_Identity.md §38.6, verified on
 * b5c1e89 real-device decompile):
 *
 *   InterceptActivityController.isInterceptListForProperty()
 *     → mPolicy (ContinuityPolicyService, extends ApplicationCompatPolicy)
 *     → ApplicationCompatPolicy.getPropertyIntByActivity/hasPropertyByActivity
 *       → DELEGATES to ApplicationCompatManager (same process, the bottom of
 *         the chain — this is what we hook)
 *     → ApplicationCompatManager.getPropertyIntByActivity() =
 *         PackageManager.getProperty("miui.continuity.policy", component)
 *     → value 5 = allow, 4 = intercept
 *
 *   hasPropertyByActivity("miui.continuity.policy")==true &&
 *   getPropertyIntByActivity(...)==5  → Pair(true,false) → NOT intercepted
 *
 * So: hooking ApplicationCompatManager's 4 property accessors to return
 * "has property = true / value = 5" forces the allow branch.
 *
 * Redundancy note: AppWhitelist's allowstart hits step 1 of
 * isInterceptListUnCheckFold (short-circuits before property check), so this
 * hook only matters as a fallback for packages the allowlist missed.
 * isFlipContinuityEnabledFromSetting→true keeps the continuity policy engine
 * active for packages that query it.
 *
 * Process: system_server
 */
object CompatConfigHook {

    fun hook(param: SystemServerStartingParam) {
        log("CompatConfigHook: setting up")
        safeHook("CompatConfig") { hookCompatConfig(param) }
        safeHook("FlipContinuity") { hookFlipContinuity(param) }
        log("CompatConfigHook: done")
    }

    private fun hookCompatConfig(param: SystemServerStartingParam) {
        val mgr = param.classLoader.loadClass("com.android.server.wm.ApplicationCompatManager")
        val props = setOf(
            "miui.continuity.policy",
            "android.window.PROPERTY_COMPAT_ALLOW_SMALL_COVER_SCREEN"
        )

        val propertyIntHook = Hooker { chain ->
            when (chain.args[0]) {
                "miui.continuity.policy" -> 5
                "android.window.PROPERTY_COMPAT_ALLOW_SMALL_COVER_SCREEN" -> 1
                else -> chain.proceed()
            }
        }
        hook(
            mgr.method("getPropertyIntByApplication", String::class.java, String::class.java),
            propertyIntHook
        )
        hook(
            mgr.method("getPropertyIntByActivity", String::class.java, ComponentName::class.java),
            propertyIntHook
        )

        val hasPropertyHook = Hooker { chain ->
            if (chain.args[0] in props) true else chain.proceed()
        }
        hook(
            mgr.method("hasPropertyByApplication", String::class.java, String::class.java),
            hasPropertyHook
        )
        hook(
            mgr.method("hasPropertyByActivity", String::class.java, ComponentName::class.java),
            hasPropertyHook
        )
    }

    private fun hookFlipContinuity(param: SystemServerStartingParam) {
        val c = param.classLoader.loadClass("com.android.server.wm.InterceptActivityController")
        hook(
            c.method("isFlipContinuityEnabledFromSetting", String::class.java, Int::class.java, String::class.java),
            replaceResult(true)
        )
    }
}
