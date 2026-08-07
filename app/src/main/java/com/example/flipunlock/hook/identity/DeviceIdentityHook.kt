package com.example.flipunlock.hook.identity

import com.example.flipunlock.hook.BaseHook

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

object DeviceIdentityHook : BaseHook() {
    override val targetPackages = listOf("*")

    @Volatile private var hooksInstalled = false

    override fun hook(param: PackageReadyParam) {
        if (param.packageName in Config.identityExcludedPackages) return

        if (hooksInstalled) return
        hooksInstalled = true

        log("DeviceIdentityHook: loading for ${param.packageName}")
        safeHook("DeviceIdentityHook") {
            hookRootDeviceType(param)
        }
    }

    private fun hookRootDeviceType(param: PackageReadyParam) {
        runCatching {
            val cls = param.classLoader.loadClass("miui.util.MiuiMultiDisplayTypeInfo")
            runCatching {
                val method = cls.method("isFlipDevice")
                hook(method, replaceResult(false))
                log("DeviceIdentity: blocked MiuiMultiDisplayTypeInfo.isFlipDevice")
            }
            runCatching {
                val method = cls.method("isFoldDevice")
                hook(method, replaceResult(false))
                log("DeviceIdentity: blocked MiuiMultiDisplayTypeInfo.isFoldDevice")
            }
        }.onFailure { log("DeviceIdentity: MiuiMultiDisplayTypeInfo not found", it) }
    }
}
