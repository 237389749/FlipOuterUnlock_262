package com.example.flipunlock.hook.system_server

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * Force display layer to CLOSED state and DUAL layout on outer screen.
 * Source: DisplayStateHook — hookDisplayToClosed + hookDisplayLayoutGet
 */
object DisplayStateForceClosed {

    private fun isOuterScreen(): Boolean {
        val dm = android.content.res.Resources.getSystem().displayMetrics
        return Math.max(dm.widthPixels, dm.heightPixels) < 2000
    }

    fun hook(param: SystemServerStartingParam) {
        safeHook("DisplayStateForceClosed") {
            hookDisplayToClosed(param)
            hookDisplayLayoutGet(param)
        }
    }

    private fun hookDisplayToClosed(param: SystemServerStartingParam) {
        runCatching {
            val mapperClass = param.classLoader.loadClass("com.android.server.display.LogicalDisplayMapper")
            val deviceStateClass = param.classLoader.loadClass("android.hardware.devicestate.DeviceState")
            val method = mapperClass.method("setDeviceStateLocked", deviceStateClass)
            val closedState = deviceStateClass.getDeclaredConstructor(java.lang.Integer.TYPE).newInstance(0)
            hook(method) { chain ->
                if (isOuterScreen()) chain.args[0] = closedState
                chain.proceed()
            }
            log("DisplayStateForceClosed: LogicalDisplayMapper → CLOSED")
        }.onFailure { log("DisplayStateForceClosed: LogicalDisplayMapper failed", it) }
    }

    private fun hookDisplayLayoutGet(param: SystemServerStartingParam) {
        runCatching {
            val cls = param.classLoader.loadClass("com.android.server.display.DeviceStateToLayoutMap")
            val method = cls.getDeclaredMethod("get", Int::class.javaPrimitiveType!!)
            method.isAccessible = true
            hook(method) { chain ->
                if (!isOuterScreen()) return@hook chain.proceed()
                val layoutMap = chain.thisObject.getField("mLayoutMap")
                val dualLayout = (layoutMap as android.util.SparseArray<*>).get(6)
                dualLayout ?: chain.proceed()
            }
            log("DisplayStateForceClosed: DeviceStateToLayoutMap.get → state=6")
        }.onFailure { log("DisplayStateForceClosed: DeviceStateToLayoutMap failed", it) }
    }
}
