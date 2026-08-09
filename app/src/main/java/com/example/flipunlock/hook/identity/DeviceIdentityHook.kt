package com.example.flipunlock.hook.identity

import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Hook MiuiMultiDisplayTypeInfo.isFlipDevice() → false.
 *
 * ROOT: isFlipDevice() ← persist.sys.multi_display_type == 4
 *   This is the single source of truth. All other identity checks
 *   (miui.os.Build, DeviceUtils, MiuiConfigs, etc.) delegate to it
 *   or read the same system property.
 *
 * Validated by FlipOuterUnlock_262 elimination testing (2026-08-07):
 *   - isFlipDevice→false ALONE solves toast centering
 *   - isFoldDevice hook NOT needed (commented out in 262)
 *   - Additional hooks (miuix.os.Build fields, DeviceHelper, etc.)
 *     are untested and may cause side effects.
 *
 * Wildcard hook: fires on firstPackage only.
 * Exclusions (restored from validated 262 config, 2026-08-08):
 *   - com.android.systemui  : TinyKeyguardPanelViewController NPE-crashes
 *     KeyguardService when isFlipDevice→false (HyperOS3 firmware b5c1e89).
 *   - com.miui.fliphome     : outer launcher init needs real identity.
 *   - sogou IME             : keyboard height on outer screen.
 * Each exclusion is togglable via persist.flipunlock.identity.exclude.*
 *
 * 2026-08-10: systemui 排除恢复（用户决策）——SystemUI 内身份伪造副作用过大
 * （§38.4：控制中心 4 项/手势），放弃 SystemUI 内伪造；fliphome/sogou 暂留实验。
 */
object DeviceIdentityHook : BaseHook() {
    override val targetPackages = listOf("*")

    // Install once per classloader (per process). A plain one-shot flag would
    // permanently skip hooking if the first wildcard fire is an excluded pkg.
    private val installedLoaders = mutableSetOf<ClassLoader>()

    override fun hook(param: PackageReadyParam) {
        val pkg = param.packageName
        // DISABLED 说明：排除表恢复 systemui（2026-08-10 用户决策）——
        // SystemUI 内身份伪造副作用过大（控制中心 4 项/手势问题，§38.4），
        // 且 SystemUiKeyguardFix 已随排除恢复失去前提。miuihome 手势问题无法
        // 通过 SystemUI 内定向 hook 解决 → 放弃 SystemUI 内身份伪造。
        // fliphome/sogou 暂留实验（不排除），验证后决定。
        val excluded = when (pkg) {
            "com.android.systemui" -> Config.identityExcludeSystemUi
            // "com.miui.fliphome" -> Config.identityExcludeFliphome        // [实验] 暂不排除
            // "com.sohu.inputmethod.sogou.xiaomi" -> Config.identityExcludeSogou  // [实验] 暂不排除
            else -> false
        }
        if (excluded) {
            log("DeviceIdentityHook: $pkg excluded (keeps real flip identity)")
            return
        }
        // Master kill switch checked AFTER set-add: a process skipped while
        // disabled must still be hookable when the switch turns back on.
        if (!installedLoaders.add(param.classLoader)) return
        if (!Config.enabled) {
            log("DeviceIdentityHook: master switch off, skipped for $pkg")
            return
        }

        super.hook(param)
    }

    override fun setupHooks(param: PackageReadyParam) {
        log("DeviceIdentityHook: loading for ${param.packageName}")
        safeHook("DeviceIdentityHook") {
            val cls = param.classLoader.loadClass("miui.util.MiuiMultiDisplayTypeInfo")
            runCatching {
                val method = cls.method("isFlipDevice")
                hook(method, replaceResult(false))
                log("DeviceIdentity: blocked MiuiMultiDisplayTypeInfo.isFlipDevice")
            }
            // isFoldDevice — NOT hooked (validated by 262 elimination: not needed)
            // runCatching {
            //     val method = cls.method("isFoldDevice")
            //     hook(method, replaceResult(false))
            //     log("DeviceIdentity: blocked MiuiMultiDisplayTypeInfo.isFoldDevice")
            // }
        }
    }
}
