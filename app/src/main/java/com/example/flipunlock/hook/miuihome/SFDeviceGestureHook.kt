package com.example.flipunlock.hook.miuihome

import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Restore the swipe-up gesture on the outer (folded) screen when the property
 * layer (persist.sys.multi_display_type=1) is active.
 *
 * Root cause (refMD, 2026-08-10 上滑无反应排查):
 *   The property layer only releases SystemUI's nav bar UI (isFlipTinyScreen
 *   becomes false → NavigationBarControllerImpl creates the gesture pill),
 *   but the swipe-up EXECUTOR lives in the launcher:
 *   - fliphome  : GestureInputHelper.onInputEvent drops touch when
 *                 mIsEnableInput=false (folded gate; fliphome not even running
 *                 once miuihome is the default launcher).
 *   - miuihome  : SpecialFDeviceGestureHelper.isInSFDeviceFoldedMode()=true in
 *                 folded state → BaseRecentsImpl.createAndAddNavStubView /
 *                 showNavStubView / updateFsgWindowState / addBackStubWindow all
 *                 short-circuit → no NavStubView, no swipe-up listener.
 *   isSpecialFDevice() is an MD5 device-fingerprint whitelist
 *   (DeviceConfigs.isSpecialFDevice, miuihome) — the property layer cannot
 *   reach it, so miuihome still thinks it is a special F device while folded.
 *
 * Fix: force isInSFDeviceFoldedMode() → false so miuihome treats the folded
 * outer screen like the expanded one: creates NavStubView (gesture pill),
 * registers the swipe-up listener, and also adds the back stub window
 * (side back gesture) — i.e. the outer screen behaves like a normal phone.
 *
 * Side effects (accepted): UnlockAnimationStateMachine:229 and
 * DeviceConfigs:266/268 see folded=false; both are flip-F special-case
 * branches that are exactly what we want to disable.
 *
 * Process: com.miui.home
 * Class/method names are NOT obfuscated in b5c1e89 (classes2.dex).
 */
object SFDeviceGestureHook : BaseHook() {
    override val targetPackages = listOf("com.miui.home")

    override fun setupHooks(param: PackageReadyParam) {
        safeHook("SFDeviceGestureHook") {
            val cls = param.classLoader.findClassUp(
                "com.miui.home.recents.SpecialFDeviceGestureHelper")
                ?: run {
                    log("SFDeviceGestureHook: SpecialFDeviceGestureHelper not found")
                    return@safeHook
                }
            hook(cls.method("isInSFDeviceFoldedMode")) { _ ->
                false
            }
            log("SFDeviceGestureHook: ✓ isInSFDeviceFoldedMode → false (swipe-up restored on outer screen)")
        }
    }
}
