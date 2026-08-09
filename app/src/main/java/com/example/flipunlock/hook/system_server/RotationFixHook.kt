package com.example.flipunlock.hook.system_server

import com.example.flipunlock.hook.util.*
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * 方向修复：DisplayRotationStubImpl 折叠态旋转解锁（isFlipDevice→false 副作用）。
 *
 * 根因（refMD §1 + flip2-miui-services 实机反编译）：
 *   DisplayRotationStubImpl 构造：
 *     mUserRotationModeOuter = isFlipDevice() ? 0(FREE) : 1(LOCKED)
 *   isFlipDevice→false 的进程（miuihome/app）→ 折叠态（外屏）旋转被锁 LOCKED
 *   → 屏幕方向定死无法旋转；SystemUI（真实身份）→ FREE → 锁屏能转。
 *
 * 修复：hook 私有 setUserRotation(int userRotationMode, int userRotation)
 *   → mode==1(LOCKED) 时改写为 0(FREE)，折叠态外屏/内屏方向全部解锁。
 *   （DisplayRotationStubImpl 一个类管外屏/内屏/折叠方向策略 = 类级多方面）
 *
 * 进程：system_server（flip2 zygisk 注入正常生效；flip1 corepatch 断路则装不上）
 * 类不存在（旧固件）自动跳过。
 */
object RotationFixHook {

    fun hook(param: SystemServerStartingParam) {
        log("RotationFix: setting up")
        safeHook("RotationFix") {
            runCatching {
                val cls = param.classLoader.loadClass("com.android.server.wm.DisplayRotationStubImpl")
                val method = cls.method(
                    "setUserRotation",
                    Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!)
                hook(method) { chain ->
                    val mode = chain.args[0] as? Int
                    if (mode == 1) {
                        log("RotationFix: LOCKED→FREE (mode=1→0)")
                        chain.proceed(arrayOf<Any?>(0, chain.args[1]))
                    } else {
                        chain.proceed()
                    }
                }
                log("RotationFix: ✓ setUserRotation mode 1(LOCKED)→0(FREE)")
            }.onFailure { log("RotationFix: setUserRotation hook failed: ${it.message}") }
        }
    }
}
