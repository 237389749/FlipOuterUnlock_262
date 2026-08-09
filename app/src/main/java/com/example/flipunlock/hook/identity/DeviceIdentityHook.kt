package com.example.flipunlock.hook.identity

import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

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
 *
 * 2026-08-10 属性层升级（§38.9）：在 isFlipDevice→false 基础上，hook
 *   SystemProperties.getInt("persist.sys.multi_display_type") → 1（虚拟改属性，
 *   免 root，按进程生效）。覆盖所有运行时读属性的代码。
 *   注意：静态常量（miuix.os.Build.IS_FLIP / DeviceFeature.IS_FOLD_DEVICE /
 *   DefaultTransitionImpl.IS_FLIP_DEVICE）在 zygote 类加载时已固化，hook 覆盖不了
 *   ——需 resetprop（root, post-fs-data）才能在 fork 前生效（TODO）。
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
            hookSystemProperties(param.classLoader)
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

    /**
     * 属性层在 system_server 的注册（§34.7，按机型区分）：
     * - flip2（bixi，zygisk_lsposed）：system_server 注入正常 → 属性 hook 生效 →
     *   system_server 的 isFlipDevice→false → 服务端判定总闸（isFoldScreenDevice /
     *   isDialogContinuityEnabled / 桌面路由）关闭——flip1 因注入断路（§41.2）做不到的
     *   "服务端身份伪造"（§41.1）在 flip2 上可实现。
     * - flip1（ruyi，corepatch 断路）：注入不到 system_server，hook 装不上（无害）。
     */
    fun hookSystemServer(param: SystemServerStartingParam) {
        log("DeviceIdentityHook(system_server): gen=${DeviceGuard.gen}")
        safeHook("DeviceIdentityHook-system_server") {
            hookSystemProperties(param.classLoader)
            runCatching {
                val cls = param.classLoader.loadClass("miui.util.MiuiMultiDisplayTypeInfo")
                hook(cls.method("isFlipDevice"), replaceResult(false))
                log("DeviceIdentity(system_server): blocked MiuiMultiDisplayTypeInfo.isFlipDevice")
            }.onFailure { log("DeviceIdentity(system_server): isFlipDevice hook failed", it) }
        }
    }

    /**
     * 属性层（§38.9）：hook persist.sys.multi_display_type 读取 → 1。
     * 覆盖所有运行时读属性的代码（isFlipDevice/isFoldDevice/DeviceUtils/IS_FLIP 常量
     * 初始化等）。hook android.os.SystemProperties（AOSP 最终实现）+ miuix 包装
     * （MixFlipMod 同款），双路径保险。
     *
     * 限制：静态常量在 zygote 类加载时固化，本 hook 对已加载类无效；
     * 全效果需 resetprop（root）在 fork 前改属性。
     */
    private fun hookSystemProperties(classLoader: ClassLoader) {
        runCatching {
            val sp = classLoader.loadClass("android.os.SystemProperties")
            hook(sp.method("getInt", String::class.java, Int::class.java)) { chain ->
                if (chain.args[0] == "persist.sys.multi_display_type") 1 else chain.proceed()
            }
            log("DeviceIdentity: hooked android SystemProperties.getInt (multi_display_type→1)")
        }.onFailure { log("DeviceIdentity: android SystemProperties hook failed", it) }
        runCatching {
            val sp = classLoader.loadClass("miuix.core.util.SystemProperties")
            hook(sp.method("getInt", String::class.java, Int::class.java)) { chain ->
                if (chain.args[0] == "persist.sys.multi_display_type") 1 else chain.proceed()
            }
            log("DeviceIdentity: hooked miuix SystemProperties.getInt")
        }.onFailure { log("DeviceIdentity: miuix SystemProperties hook failed", it) }
    }
}
