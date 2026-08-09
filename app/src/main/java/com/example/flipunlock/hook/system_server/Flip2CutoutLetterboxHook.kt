package com.example.flipunlock.hook.system_server

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * Flip2 (bixi) DISPLAY_CUTOUT letterbox 服务端开关（refMD §34.3/§34.6 候选 1）。
 *
 * flip2 应用不全屏的真凶是 AOSP DISPLAY_CUTOUT letterbox（非 MIUI size-compat）：
 *   WindowState.isLetterboxedForDisplayCutout()（flip2-services L2860）：
 *     ① parentFrame 被挖孔裁剪 && ② layoutInDisplayCutoutMode!=3
 *     && ③ mWindowStateStub.isMiuiLayoutInCutoutAlways(attrs)
 *     && ④ 全屏窗口 → letterbox
 *   ③ 号豁免开关 WindowStateStubImpl.isMiuiLayoutInCutoutAlways()
 *   （flip2-miui-services L175）硬编码 return false → hook → true → letterbox 短路。
 *
 * 与候选 2（WindowState.isLetterboxedForDisplayCutout→false）等价，二选一；
 * 候选 1 更干净（只豁免 MIUI 开关，不动 AOSP 判定）。
 *
 * 注意：mWindowStateStub 实际实例为 WindowStateStubImpl（MiuiStub 注册机制），
 * hook Impl 方法有效（§36.3）。若装机发现未命中，fallback 用候选 2。
 *
 * 进程：system_server（flip2 zygisk_lsposed 注入正常才生效；flip1 corepatch 断路无害）
 * 机型：仅 FLIP2（DeviceGuard 区分）
 */
object Flip2CutoutLetterboxHook {

    fun hook(param: SystemServerStartingParam) {
        if (!DeviceGuard.isFlip2) {
            log("Flip2CutoutLetterbox: skip (gen=${DeviceGuard.gen})")
            return
        }
        log("Flip2CutoutLetterbox: setting up")
        safeHook("Flip2CutoutLetterbox") {
            runCatching {
                val cls = param.classLoader.loadClass("com.android.server.wm.WindowStateStubImpl")
                val lp = param.classLoader.loadClass("android.view.WindowManager\$LayoutParams")
                hook(cls.method("isMiuiLayoutInCutoutAlways", lp), replaceResult(true))
                log("Flip2CutoutLetterbox: ✓ isMiuiLayoutInCutoutAlways → true")
            }.onFailure { log("Flip2CutoutLetterbox: isMiuiLayoutInCutoutAlways hook failed", it) }
        }
    }
}
