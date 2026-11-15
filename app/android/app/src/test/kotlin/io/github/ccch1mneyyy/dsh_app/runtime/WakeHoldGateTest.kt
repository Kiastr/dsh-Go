package io.github.ccch1mneyyy.dsh_app.runtime

import org.junit.Assert.*
import org.junit.Test

/**
 * 回归：改动前 DshService 只要引擎启动就永久持有 PARTIAL_WAKE_LOCK（直到 onDestroy），
 * 这里验证新的 WakeHoldGate 在任何事件序列下都不会「泄漏」唤醒锁，且只在
 * 安装解压 / 手机操控任务进行中才持锁。纯 JVM 测试，无需 Android 设备或模拟器。
 */
class WakeHoldGateTest {
    private val INSTALL = WakeHoldGate.Reason.INSTALL
    private val PHONE_TASK = WakeHoldGate.Reason.PHONE_TASK
    private val NONE = WakeHoldGate.Change.NONE
    private val ACQUIRE = WakeHoldGate.Change.ACQUIRE
    private val RELEASE = WakeHoldGate.Change.RELEASE

    @Test fun idleEngineNeverHoldsTheLock() {
        val g = WakeHoldGate()
        assertFalse(g.isHeld())
        assertEquals(NONE, g.set(PHONE_TASK, false))
        assertEquals(NONE, g.set(INSTALL, false))
        assertFalse(g.isHeld())
        assertTrue(g.reasons().isEmpty())
    }

    @Test fun installAcquiresOnceAndReleases() {
        val g = WakeHoldGate()
        assertEquals(ACQUIRE, g.set(INSTALL, true))
        assertTrue(g.isHeld())
        assertEquals(NONE, g.set(INSTALL, true))      // 幂等：不重复 acquire
        assertEquals(RELEASE, g.set(INSTALL, false))
        assertFalse(g.isHeld())
        assertEquals(NONE, g.set(INSTALL, false))     // 幂等：不重复 release
    }

    @Test fun phoneTaskAloneHoldsAndReleases() {
        val g = WakeHoldGate()
        assertEquals(ACQUIRE, g.set(PHONE_TASK, true))
        assertEquals(setOf(PHONE_TASK), g.reasons())
        assertEquals(NONE, g.set(PHONE_TASK, true))
        assertEquals(RELEASE, g.set(PHONE_TASK, false))
        assertFalse(g.isHeld())
    }

    @Test fun overlappingReasonsReleaseOnlyAfterTheLastOneEnds() {
        val g = WakeHoldGate()
        assertEquals(ACQUIRE, g.set(INSTALL, true))
        assertEquals(NONE, g.set(PHONE_TASK, true))   // 已在持有中，不重复 acquire
        assertEquals(setOf(INSTALL, PHONE_TASK), g.reasons())
        assertEquals(NONE, g.set(INSTALL, false))     // 任务还在，绝不能放锁
        assertTrue(g.isHeld())
        assertEquals(RELEASE, g.set(PHONE_TASK, false))
        assertFalse(g.isHeld())
    }

    @Test fun clearReleasesAndIsIdempotent() {
        val g = WakeHoldGate()
        g.set(INSTALL, true)
        g.set(PHONE_TASK, true)
        assertEquals(RELEASE, g.clear())
        assertFalse(g.isHeld())
        assertTrue(g.reasons().isEmpty())
        assertEquals(NONE, g.clear())
        assertEquals(NONE, g.set(INSTALL, false))
    }

    @Test fun noEventSequenceLeaksTheLock() {
        // 穷举 6 种事件、长度 6 的全部 6^6 = 46656 条序列，
        // 每条结束时 clear() 必须能回到「未持有」，否则就是唤醒锁泄漏。
        val events: List<(WakeHoldGate) -> Unit> = listOf(
            { it.set(INSTALL, true) },
            { it.set(INSTALL, false) },
            { it.set(PHONE_TASK, true) },
            { it.set(PHONE_TASK, false) },
            { it.clear() },
            { _ -> Unit },
        )
        var checked = 0
        fun walk(g: WakeHoldGate, depth: Int) {
            if (depth == 0) {
                g.clear()
                assertFalse("事件序列结束后仍持有唤醒锁", g.isHeld())
                assertTrue(g.reasons().isEmpty())
                checked++
                return
            }
            for (e in events) {
                e(g)
                walk(g, depth - 1)
            }
        }
        walk(WakeHoldGate(), 6)
        assertEquals(46656, checked)
    }
}
