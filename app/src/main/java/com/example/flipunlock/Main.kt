package com.example.flipunlock

import com.example.flipunlock.hook.identity.DeviceIdentityHook
import com.example.flipunlock.hook.cutout.CutoutHook
import com.example.flipunlock.hook.system_server.AppRestriction
import com.example.flipunlock.hook.system_server.AppWhitelist
import com.example.flipunlock.hook.system_server.DisplayTopologyHook
import com.example.flipunlock.hook.system_server.LauncherRouteHook
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
        DeviceIdentityHook,  // toast 居中 + 外屏全屏 (ROOT: isFlipDevice → false)
        // CutoutHook,       // [DISABLED] 排除法已验证不需要
    )

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        module = this
        Config.logConfig()
        DeviceGuard.logInfo()
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        log("Main: onSystemServerStarting — 262 base (system hooks staged)")
        // ── FU2 system_server hook 移植区（排除法逐个引入）──
        // LauncherRouteHook.hook(param)      // [OFF] updateHomeIntent displayID==5 bypass（旧拓扑判据，新拓扑下需重审）
        // AppRestriction.hook(param)         // [OFF] 外屏启动限制单门闸 → false
        // AppWhitelist.hook(param)           // [OFF] allowstart 白名单全量注册（内存态）
        // DisplayTopologyHook.hook(param)    // [OFF] 钉死 state=0，外屏恒为主屏
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
