package com.example.flipunlock.hook.recents

import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.hook
import com.example.flipunlock.hook.util.log
import com.example.flipunlock.hook.util.safeHook
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Gate 7: prevent display-ID-based task filtering in fliphome recents.
 *
 * fliphome's RecentsModel.removeOtherDisplayTask() compares
 * this.mDisplay.getDisplayId() against each task's display ID and removes
 * mismatches. In state=6 (DUAL, outer=displayId=0), tasks may carry
 * different display IDs → all tasks get removed → empty recents.
 *
 * Runs unconditionally (no Config toggle) — correctness fix, not a feature.
 * Extracted from RecentsMenuHook (deleted in v2.9 — miuihome-era compensation).
 */
object DisplayFilterFix : BaseHook() {
    override val targetPackages = listOf("com.miui.fliphome")

    override fun setupHooks(param: PackageReadyParam) {
        safeHook("DisplayFilter") {
            hookRemoveOtherDisplayTask(param)
        }
    }

    private fun hookRemoveOtherDisplayTask(param: PackageReadyParam) {
        runCatching {
            val cls = param.classLoader.loadClass(
                "com.miui.fliphome.recents.RecentsModel")
            val method = cls.getDeclaredMethod("removeOtherDisplayTask",
                java.util.List::class.java)
            method.isAccessible = true
            hook(method) { null }
            log("DisplayFilter: removeOtherDisplayTask → no-op (Gate 7)")
        }.onFailure { log("DisplayFilter: removeOtherDisplayTask failed", it) }
    }
}
