package com.example.flipunlock.hook.system_server

import android.graphics.Rect
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * Fix appBounds in activity launch / config-change transactions.
 * Forces appBounds = bounds (FULL_SCREEN) so the system doesn't apply
 * letterbox offsets to toasts/popups.
 * Source: AppBoundsHook — hookLaunchActivityItem + hookScheduleConfigurationChanged
 *         + hookScheduleClientTransactionItem + fixConfigurationAppBounds
 */
object AppBoundsFix {

    fun hook(param: SystemServerStartingParam) {
        safeHook("AppBoundsFix") {
            hookLaunchActivityItem(param)
            hookScheduleConfigurationChanged(param)
            hookScheduleClientTransactionItem(param)
        }
    }

    private fun hookLaunchActivityItem(param: SystemServerStartingParam) {
        val launchActivityItemClass =
            param.classLoader.loadClass("android.app.servertransaction.LaunchActivityItem")
        hook(
            launchActivityItemClass.constructors.first { it.parameterCount > 10 },
            after { chain, result ->
                runCatching {
                    chain.thisObject?.getField("mOverrideConfig")
                        ?.let { fixConfigurationAppBounds(it) }
                    chain.thisObject?.getField("mCurConfig")
                        ?.let { fixConfigurationAppBounds(it) }
                }
                result
            }
        )
    }

    private fun hookScheduleConfigurationChanged(param: SystemServerStartingParam) {
        val activityRecord = param.classLoader.loadClass("com.android.server.wm.ActivityRecord")
        val activityWindowInfoClass =
            param.classLoader.loadClass("android.window.ActivityWindowInfo")
        hook(
            activityRecord.method(
                "scheduleConfigurationChanged",
                android.content.res.Configuration::class.java,
                activityWindowInfoClass
            )
        ) { chain ->
            val windowConfig =
                runCatching { chain.args[0].getField("windowConfiguration") }.getOrNull()
            val originalAppBounds =
                (windowConfig?.callMethod("getAppBounds") as? Rect)?.let { Rect(it) }
            val bounds = windowConfig?.callMethod("getBounds") as? Rect
            if (bounds != null && !bounds.isEmpty) {
                windowConfig.callMethod("setAppBounds", bounds)
            }
            runWithCleanup({ windowConfig?.callMethod("setAppBounds", originalAppBounds) }) {
                chain.proceed()
            }
        }
    }

    private fun hookScheduleClientTransactionItem(param: SystemServerStartingParam) {
        val windowProcessController =
            param.classLoader.loadClass("com.android.server.wm.WindowProcessController")
        val iApplicationThread =
            param.classLoader.loadClass("android.app.IApplicationThread")
        val clientTransactionItem =
            param.classLoader.loadClass("android.app.servertransaction.ClientTransactionItem")
        val configurationChangeItemClass =
            param.classLoader.loadClass("android.app.servertransaction.ConfigurationChangeItem")
        hook(
            windowProcessController.method(
                "scheduleClientTransactionItem",
                iApplicationThread,
                clientTransactionItem
            )
        ) { chain ->
            val item = chain.args[1]
            if (item != null && configurationChangeItemClass.isInstance(item)) {
                item.getField("mConfiguration")?.let { fixConfigurationAppBounds(it) }
            }
            chain.proceed()
        }
    }

    private fun fixConfigurationAppBounds(configuration: Any?) {
        val config = configuration ?: return
        runCatching {
            val windowConfiguration =
                config.getField("windowConfiguration") ?: return@runCatching
            val bounds = windowConfiguration.callMethod("getBounds") as? Rect
            if (bounds != null && !bounds.isEmpty) {
                windowConfiguration.callMethod("setAppBounds", bounds)
            }
        }
    }
}
