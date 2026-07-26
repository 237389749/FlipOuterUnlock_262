package com.example.flipunlock.hook.applaunch

import android.content.ComponentName
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

object InterceptHook {

    fun hook(param: SystemServerStartingParam) {
        log("InterceptHook: setting up")
        safeHook("InterceptHook") {
            hookIsInterceptListUnCheckFold(param)
        }
        log("InterceptHook: done")
    }

    private fun hookIsInterceptListUnCheckFold(param: SystemServerStartingParam) {
        runCatching {
            val interceptClass = param.classLoader.loadClass(
                "com.android.server.wm.InterceptActivityController"
            )
            val method = interceptClass.method(
                "isInterceptListUnCheckFold", ComponentName::class.java
            )
            hook(method, replaceResult(false))
            log("forced isInterceptListUnCheckFold -> false")
        }.onFailure { log("failed to hook isInterceptListUnCheckFold", it) }
    }

    // v2.9: isInterceptListForProperty removed — isInterceptListUnCheckFold→false
    // already covers all 5 priority levels of the interception chain (§3, §27.2).
}
