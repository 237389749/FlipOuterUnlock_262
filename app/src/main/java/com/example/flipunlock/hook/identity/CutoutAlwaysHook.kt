package com.example.flipunlock.hook.identity

import com.example.flipunlock.hook.BaseHook
import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * App 端 cutout 全屏（§34.6 候选 3 / §36.3 首推）——无需 system_server 注入。
 *
 * flip2 DISPLAY_CUTOUT letterbox 链（客户端源头）：
 *   app 进程 WindowLayout.computeFrames L88:
 *     cutoutMode2 = WindowLayoutStubImpl.getLayoutInDisplayCutoutMode(attrs)
 *   flip2-miui-framework L33 实现：非 FLAG_LAYOUT_IN_SCREEN 窗口直接返回
 *     原始 mode（DEFAULT=0）→ L94 mode!=ALWAYS → 裁剪 →
 *     isParentFrameClippedByDisplayCutout=true → services 侧
 *     isLetterboxedForDisplayCutout 条件①成立 → letterbox。
 *
 * hook 恒返回 3（ALWAYS）→ L94 跳过全部裁剪 → 不 letterbox → 内容覆盖挖孔区
 * （真全屏）。与 system_server 方案（isMiuiLayoutInCutoutAlways→true）互补：
 * app 端方案在注入正常的 app 进程即可生效，无需 system_server。
 *
 * 影响：所有作用域 app 窗口 layoutInDisplayCutoutMode=ALWAYS（全局，flip1 的
 * CutoutRemove 同为 getLayoutInDisplayCutoutMode→ALWAYS 语义，可接受）。
 * 类不存在（旧固件）时 runCatching 自动跳过。
 */
object CutoutAlwaysHook : BaseHook() {
    override val targetPackages = listOf("*")

    override fun setupHooks(param: PackageReadyParam) {
        log("CutoutAlwaysHook: loading for ${param.packageName}")
        safeHook("CutoutAlwaysHook") {
            runCatching {
                val cls = param.classLoader.loadClass("android.view.WindowLayoutStubImpl")
                val lp = param.classLoader.loadClass("android.view.WindowManager\$LayoutParams")
                hook(cls.method("getLayoutInDisplayCutoutMode", lp), replaceResult(3))
                log("CutoutAlwaysHook: ✓ getLayoutInDisplayCutoutMode → 3 (ALWAYS)")
            }.onFailure { log("CutoutAlwaysHook: failed: ${it.message}") }
        }
    }
}
