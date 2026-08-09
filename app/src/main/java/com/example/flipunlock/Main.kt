package com.example.flipunlock

import com.example.flipunlock.hook.identity.DeviceIdentityHook
import com.example.flipunlock.hook.identity.CutoutAlwaysHook
import com.example.flipunlock.hook.aod.AodHook
import com.example.flipunlock.hook.cutout.CutoutHook
import com.example.flipunlock.hook.system_server.AppRestriction
import com.example.flipunlock.hook.system_server.AppWhitelist
import com.example.flipunlock.hook.system_server.CompatConfigHook
import com.example.flipunlock.hook.system_server.DisplayTopologyHook
import com.example.flipunlock.hook.system_server.Flip2CutoutLetterboxHook
import com.example.flipunlock.hook.system_server.LauncherRouteHook
import com.example.flipunlock.hook.systemui.SystemUiKeyguardFix
import com.example.flipunlock.hook.util.Config
import com.example.flipunlock.hook.util.DeviceGuard
import com.example.flipunlock.hook.util.log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

internal var module: Main? = null

/**
 * 262 基底重建版（2026-08-09）：
 *   262 是排除法验证过的最小可用配置（isFlipDevice→false 单独解决 toast 居中 +
 *   外屏全屏），以其为基底，逐步移植 FlipOuterUnlock2 的能力。
 *
 * 与 FlipOuterUnlock2 的唯一分发差异（重点怀疑对象）：
 *   262 的 wildcard hook 对 DeviceIdentityHook 豁免 firstPackage 限制——
 *   每个作用域包的每次 packageReady 都会回调（内部 installedLoaders 去重）。
 *   FU2 收紧为仅 firstPackage，此处保留 262 行为。
 *
 * system_server hook 采用排除法逐个引入：首版全部注释，先验证纯 262 基线
 * （全屏 + toast 居中），确认后再逐条打开。
 */
class Main : XposedModule() {

    private val hooks = listOf(
        DeviceIdentityHook,  // 属性层(SystemProperties.getInt→1) + isFlipDevice→false（双保险）
        CutoutAlwaysHook,   // app 端 cutout 全屏：WindowLayoutStubImpl.getLayoutInDisplayCutoutMode→3
                            // （§34.6 候选3，无需 system_server；flip2 cutout letterbox 客户端根治）
        // AodHook,          // [DISABLED 2026-08-10 属性层验证] 外屏 AOD
        // SystemUiKeyguardFix, // [DISABLED] systemui 已恢复排除（身份真实），失去前提
        // CutoutHook,       // [DISABLED] 排除法已验证不需要
    )

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        module = this
        Config.logConfig()
        DeviceGuard.logInfo()
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        log("Main: onSystemServerStarting — gen=${DeviceGuard.gen}")
        // 属性层 system_server 注册（§34.7）：flip2 zygisk 注入正常 → 服务端身份伪造生效；
        // flip1 corepatch 断路 → hook 装不上（无害）。
        DeviceIdentityHook.hookSystemServer(param)
        // flip2 专属：DISPLAY_CUTOUT letterbox 服务端开关（§34.6 候选1，仅 FLIP2 激活）
        Flip2CutoutLetterboxHook.hook(param)
        // [DISABLED 2026-08-10 属性层验证] system_server hooks 全部待命：
        // AodHook.hookFramework(param)   // AOD 保活
        // AppWhitelist.hook(param)       // allowstart 白名单
        // CompatConfigHook.hook(param)   // continuity 属性注入
        // AppRestriction.hook(param)
        // LauncherRouteHook.hook(param)
        // DisplayTopologyHook.hook(param)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        log("Main: onPackageReady pkg=${param.packageName} first=${param.isFirstPackage}")
        hooks.forEach { hook ->
            val isWildcard = hook.targetPackages.contains("*")
            val isTargeted = hook.targetPackages.contains(param.packageName)

            if (!isWildcard && !isTargeted) return@forEach

            // 262 基线行为：DeviceIdentityHook 豁免 firstPackage 限制
            if (isWildcard && !param.isFirstPackage && hook !is DeviceIdentityHook) return@forEach

            log("Main: loading ${hook.javaClass.simpleName} for ${param.packageName}")
            hook.hook(param)
        }
    }
}
