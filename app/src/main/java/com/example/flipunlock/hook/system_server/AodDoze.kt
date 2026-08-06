package com.example.flipunlock.hook.system_server

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * AOD outer screen doze settings: force alwaysOn + block timeout kills.
 * Source: DisplayStateHook — hookAodOuterScreen
 */
object AodDoze {

    fun hook(param: SystemServerStartingParam) {
        safeHook("AodDoze") {
            // PowerManagerService.updateRearDozeSettings → force alwaysOn
            runCatching {
                val pmsClass = param.classLoader.loadClass("com.android.server.power.PowerManagerService")
                val method = pmsClass.method("updateRearDozeSettings",
                    Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!)
                hook(method, before { chain ->
                    val groupId = chain.args[0] as? Int ?: return@before
                    if (groupId == 1) {
                        chain.args[1] = true  // alwaysOn
                        chain.args[2] = true  // isFullAod
                    }
                })
            }.onFailure { log("AodDoze: updateRearDozeSettings failed", it) }

            // DreamController.stopDream → block timeout kills for groupId 1
            runCatching {
                val dcClass = param.classLoader.loadClass("com.android.server.dreams.DreamController")
                val method = dcClass.getDeclaredMethod("stopDream",
                    Boolean::class.javaPrimitiveType!!, String::class.java)
                method.isAccessible = true
                hook(method) { chain ->
                    val reason = chain.args[1] as? String ?: return@hook chain.proceed()
                    val groupId = chain.thisObject.getField("mGroupId") as? Int
                    if ((reason == "slow to connect" || reason == "slow to finish") && groupId == 1) {
                        return@hook null
                    }
                    chain.proceed()
                }
            }.onFailure { log("AodDoze: DreamController.stopDream failed", it) }
        }
    }
}
