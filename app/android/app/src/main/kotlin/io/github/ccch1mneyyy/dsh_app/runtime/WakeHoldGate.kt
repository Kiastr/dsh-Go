package io.github.ccch1mneyyy.dsh_app.runtime

/**
 * 唤醒锁持有原因的状态机。刻意不依赖任何 Android API，这样可以在 JVM 单元测试里直接跑
 * （见 app/android/app/src/test/kotlin/.../runtime/WakeHoldGateTest.kt），不必起模拟器。
 *
 * 改动前的行为：DshService 在本机引擎一启动时就 acquire() 一把没有超时的
 * PARTIAL_WAKE_LOCK，直到 onDestroy 才释放。结果只要引擎开着，CPU 就不允许深睡：
 * 前台服务 + 电池优化白名单已经能保活，网络包到达由内核唤醒，并不需要 App 自己举着 CPU。
 *
 * 现在只有两个真实需要 CPU 常醒的原因：
 *  - INSTALL：首次解压 / 升级运行环境，中途睡眠会拖慢甚至中断解压；
 *  - PHONE_TASK：正在执行手机操控任务（多数情况下亮屏由系统持锁，但「不打扰模式」
 *    在隐藏屏幕上操作、以及后台 CPU 密集步骤仍需要兜底）。
 * 其余时间（引擎空闲、只是挂在后台等消息）一律不持锁，让系统正常进入 Doze。
 *
 * 用法：每次原因状态变化时调用 [set]，按返回的 [Change] 执行一次 acquire/release。
 * [set] 幂等 —— 同一原因重复设置 true 不会重复 acquire；多个原因重叠时，
 * 要等最后一个原因撤销才 RELEASE，避免任务中途被放掉锁。
 */
class WakeHoldGate {
    enum class Reason { INSTALL, PHONE_TASK }
    enum class Change { NONE, ACQUIRE, RELEASE }

    private val reasons = mutableSetOf<Reason>()
    private var acquired = false

    /** 设置某原因是否成立，返回需要对真实唤醒锁执行的动作。线程安全、幂等。 */
    @Synchronized
    fun set(reason: Reason, active: Boolean): Change {
        if (active) reasons.add(reason) else reasons.remove(reason)
        val want = reasons.isNotEmpty()
        if (want == acquired) return Change.NONE
        acquired = want
        return if (want) Change.ACQUIRE else Change.RELEASE
    }

    /** 服务销毁 / 主动停止时清空所有原因，返回需要执行的动作。 */
    @Synchronized
    fun clear(): Change {
        reasons.clear()
        if (!acquired) return Change.NONE
        acquired = false
        return Change.RELEASE
    }

    /** 当前是否应持有唤醒锁（测试与诊断用）。 */
    @Synchronized
    fun isHeld(): Boolean = acquired

    /** 当前成立的持有原因快照（测试与诊断用）。 */
    @Synchronized
    fun reasons(): Set<Reason> = reasons.toSet()
}
