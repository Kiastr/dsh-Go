package io.github.ccch1mneyyy.dsh_app.performance

import org.junit.Assert.*
import org.junit.Test

/**
 * [PowerPolicy] 的 JVM 单元测试：决策层无 Android 依赖，可在没有 Flutter / Android SDK /
 * 真机的环境里穷举验证「策略护栏不因任何信号组合而失守」。
 *
 * 运行（仓库内）：cd app/android && ./gradlew :app:testArm64DebugUnitTest --tests '*PowerPolicyTest*'
 * 沙箱内离线运行方式见 docs/power-saver-strategy.md「验证」。
 */
class PowerPolicyTest {

    private val allTriggers = setOf(
        PowerPolicy.Trigger.WIFI, PowerPolicy.Trigger.CHARGING, PowerPolicy.Trigger.REMOTE_HOST,
    )

    private fun ctx(
        enabled: Boolean = true,
        supply: PowerPolicy.Supply = PowerPolicy.Supply.BATTERY,
        network: PowerPolicy.Network = PowerPolicy.Network.CELLULAR,
        host: PowerPolicy.Host = PowerPolicy.Host.LOCAL,
        queued: Int = 0,
        ageMs: Long = 0L,
        foreground: Boolean = false,
        triggers: Set<PowerPolicy.Trigger> = allTriggers,
    ) = PowerPolicy.Context(
        enabled = enabled, supply = supply, network = network, host = host,
        queued = queued, oldestQueuedAgeMs = ageMs, foreground = foreground, triggers = triggers,
    )

    // ── 护栏 1：默认关闭，行为与未开启完全一致 ───────────────────────────────

    @Test fun strategyOffIsAlwaysRunNow() {
        for (task in PowerPolicy.TaskClass.entries) {
            for (supply in PowerPolicy.Supply.entries) {
                for (network in PowerPolicy.Network.entries) {
                    val d = PowerPolicy.decide(ctx(enabled = false, supply = supply, network = network), task)
                    assertEquals("off/$supply/$network/$task", PowerPolicy.Action.RUN_NOW, d.action)
                    assertEquals("strategy_off", d.reason)
                }
            }
        }
    }

    @Test fun strategyOffNeverInjectsEnvironmentOrShrinksBudget() {
        assertTrue(PowerPolicy.powerEnvironment(enabled = false, lightweight = true).isEmpty())
        assertEquals(120_000L, PowerPolicy.toolBudgetMs(120_000L, heavyTool = true, enabled = false))
        assertTrue(PowerPolicy.shouldFlush(ctx(enabled = false))) // 关掉策略要立即排空残留块
    }

    // ── 护栏 2：交互 / 手机操控永不攒块 ─────────────────────────────────────

    @Test fun interactiveIsAlwaysRunNowEvenAtLowBatteryOffline() {
        val d = PowerPolicy.decide(
            ctx(supply = PowerPolicy.Supply.LOW, network = PowerPolicy.Network.OFFLINE),
            PowerPolicy.TaskClass.INTERACTIVE,
        )
        assertEquals(PowerPolicy.Action.RUN_NOW, d.action)
    }

    @Test fun phoneControlIsNeverBatchedAndOnlyLowBatteryRefuses() {
        for (supply in PowerPolicy.Supply.entries) {
            for (network in PowerPolicy.Network.entries) {
                val d = PowerPolicy.decide(ctx(supply = supply, network = network), PowerPolicy.TaskClass.PHONE_CONTROL)
                assertNotEquals("phone control must not be batched: $supply/$network", PowerPolicy.Action.BATCH, d.action)
                if (supply == PowerPolicy.Supply.LOW) {
                    assertEquals(PowerPolicy.Action.REFUSE, d.action)
                    assertEquals("low_battery_phone_control", d.reason)
                } else {
                    assertEquals(PowerPolicy.Action.RUN_NOW, d.action)
                }
            }
        }
    }

    // ── 护栏 3：重工具只降载 / 拒绝，绝不静默放行 ────────────────────────────

    @Test fun heavyToolDegradesWithTightenedBudget() {
        val d = PowerPolicy.decide(ctx(), PowerPolicy.TaskClass.HEAVY_TOOL)
        assertEquals(PowerPolicy.Action.DEGRADE, d.action)
        assertEquals(PowerPolicy.LIGHT_TOOL_BUDGET_MS, d.maxToolMs)
    }

    @Test fun heavyToolRefusesWhenLowBatteryOrOffline() {
        assertEquals(
            PowerPolicy.Action.REFUSE,
            PowerPolicy.decide(ctx(supply = PowerPolicy.Supply.LOW), PowerPolicy.TaskClass.HEAVY_TOOL).action,
        )
        assertEquals(
            PowerPolicy.Action.REFUSE,
            PowerPolicy.decide(ctx(network = PowerPolicy.Network.OFFLINE), PowerPolicy.TaskClass.HEAVY_TOOL).action,
        )
    }

    @Test fun toolBudgetOnlyShrinksHeavyToolsAndNeverGrows() {
        assertEquals(30_000L, PowerPolicy.toolBudgetMs(120_000L, heavyTool = true, enabled = true))
        assertEquals(12_000L, PowerPolicy.toolBudgetMs(12_000L, heavyTool = true, enabled = true)) // 已低于上限，不放大
        assertEquals(120_000L, PowerPolicy.toolBudgetMs(120_000L, heavyTool = false, enabled = true))
        assertEquals(120_000L, PowerPolicy.toolBudgetMs(120_000L, heavyTool = true, enabled = false))
    }

    // ── 护栏 4：攒块门控 + deadline ─────────────────────────────────────────

    @Test fun batchableDefersOnBatteryCellularThenRunsOnTriggers() {
        assertEquals(
            PowerPolicy.Action.BATCH,
            PowerPolicy.decide(ctx(), PowerPolicy.TaskClass.BATCHABLE).action,
        )
        for ((c, why) in listOf(
            ctx(network = PowerPolicy.Network.WIFI) to "wifi",
            ctx(supply = PowerPolicy.Supply.CHARGING) to "charging",
            ctx(host = PowerPolicy.Host.REMOTE) to "remote",
            ctx(foreground = true) to "foreground",
        )) {
            assertEquals(why, PowerPolicy.Action.RUN_NOW, PowerPolicy.decide(c, PowerPolicy.TaskClass.BATCHABLE).action)
        }
    }

    @Test fun disabledTriggerDoesNotForceRunNow() {
        val c = ctx(network = PowerPolicy.Network.WIFI, triggers = setOf(PowerPolicy.Trigger.CHARGING))
        assertEquals(PowerPolicy.Action.BATCH, PowerPolicy.decide(c, PowerPolicy.TaskClass.BATCHABLE).action)
    }

    @Test fun batchLimitAndDeadlineAlwaysFlush() {
        assertEquals(
            PowerPolicy.Action.RUN_NOW,
            PowerPolicy.decide(ctx(queued = PowerPolicy.BATCH_LIMIT), PowerPolicy.TaskClass.BATCHABLE).action,
        )
        assertEquals(
            PowerPolicy.Action.RUN_NOW,
            PowerPolicy.decide(ctx(ageMs = PowerPolicy.DEFAULT_DEADLINE_MS), PowerPolicy.TaskClass.BATCHABLE).action,
        )
    }

    @Test fun everyBatchDecisionCarriesAPositiveDeadlineEvenIfConfiguredToZero() {
        val d = PowerPolicy.decide(
            ctx().copy(deadlineMs = 0L),
            PowerPolicy.TaskClass.BATCHABLE,
        )
        assertEquals(PowerPolicy.Action.BATCH, d.action)
        assertEquals(PowerPolicy.DEFAULT_DEADLINE_MS, d.deadlineMs)
        assertTrue(d.deadlineMs > 0)
    }

    @Test fun shouldFlushMatchesTheRunNowTriggers() {
        assertTrue(PowerPolicy.shouldFlush(ctx(network = PowerPolicy.Network.WIFI)))
        assertTrue(PowerPolicy.shouldFlush(ctx(supply = PowerPolicy.Supply.CHARGING)))
        assertTrue(PowerPolicy.shouldFlush(ctx(host = PowerPolicy.Host.REMOTE)))
        assertTrue(PowerPolicy.shouldFlush(ctx(foreground = true)))
        assertTrue(PowerPolicy.shouldFlush(ctx(queued = PowerPolicy.BATCH_LIMIT)))
        assertTrue(PowerPolicy.shouldFlush(ctx(ageMs = PowerPolicy.DEFAULT_DEADLINE_MS)))
        assertFalse(PowerPolicy.shouldFlush(ctx())) // 电池 + 蜂窝 + 后台 + 无块可排空
    }

    // ── 软提示载体 / 全局标志 ──────────────────────────────────────────────

    @Test fun powerEnvironmentInjectsModeAndBudgetOnlyWhenEnabled() {
        val light = PowerPolicy.powerEnvironment(enabled = true, lightweight = true)
        assertEquals("light", light[PowerPolicy.ENV_MODE])
        assertEquals(PowerPolicy.LIGHT_TOOL_BUDGET_MS.toString(), light[PowerPolicy.ENV_MAX_TOOL_MS])
        assertEquals("balanced", PowerPolicy.powerEnvironment(enabled = true, lightweight = false)[PowerPolicy.ENV_MODE])
        assertTrue(PowerPolicy.powerEnvironment(enabled = false, lightweight = false).isEmpty())
    }

    @Test fun strategyEnabledFlagIsSettableAndObservable() {
        PowerPolicy.setStrategyEnabled(false)
        assertFalse(PowerPolicy.strategyEnabled)
        PowerPolicy.setStrategyEnabled(true)
        assertTrue(PowerPolicy.strategyEnabled)
        PowerPolicy.setStrategyEnabled(false)
    }

    // ── 穷举：任何信号组合下护栏都不失守 ────────────────────────────────────

    /** 2×3×3×3×2×2×4×3×3 个 Context × 4 种 TaskClass = 31,104 条决策。 */
    @Test fun exhaustiveGuardsHoldForEveryContextAndTaskCombination() {
        val triggerSets = listOf(
            allTriggers,
            emptySet(),
            setOf(PowerPolicy.Trigger.WIFI),
        )
        val queueSizes = listOf(0, 1, PowerPolicy.BATCH_LIMIT - 1, PowerPolicy.BATCH_LIMIT)
        val ages = listOf(0L, PowerPolicy.DEFAULT_DEADLINE_MS - 1, PowerPolicy.DEFAULT_DEADLINE_MS)
        var checked = 0
        val refuseReasons = setOf("low_battery_phone_control", "low_battery_heavy_tool", "offline_heavy_tool")

        for (enabled in listOf(false, true))
            for (supply in PowerPolicy.Supply.entries)
                for (network in PowerPolicy.Network.entries)
                    for (host in PowerPolicy.Host.entries)
                        for (foreground in listOf(false, true))
                            for (screenOn in listOf(false, true))
                                for (triggers in triggerSets)
                                    for (queued in queueSizes)
                                        for (ageMs in ages) {
                                            val c = PowerPolicy.Context(
                                                enabled = enabled, supply = supply, network = network, host = host,
                                                foreground = foreground, screenOn = screenOn, triggers = triggers,
                                                queued = queued, oldestQueuedAgeMs = ageMs,
                                            )
                                            for (task in PowerPolicy.TaskClass.entries) {
                                                val d = PowerPolicy.decide(c, task)
                                                checked++
                                                // 关闭时一律放行。
                                                if (!enabled) {
                                                    assertEquals(PowerPolicy.Action.RUN_NOW, d.action)
                                                }
                                                // 交互绝不攒块；手机操控绝不攒块。
                                                if (task == PowerPolicy.TaskClass.INTERACTIVE) {
                                                    assertEquals(PowerPolicy.Action.RUN_NOW, d.action)
                                                }
                                                if (task == PowerPolicy.TaskClass.PHONE_CONTROL) {
                                                    assertNotEquals(PowerPolicy.Action.BATCH, d.action)
                                                }
                                                // 每条 BATCH 必带正的 deadline（防饿死）。
                                                if (d.action == PowerPolicy.Action.BATCH) {
                                                    assertTrue("batch without deadline: $c/$task", d.deadlineMs > 0)
                                                }
                                                // 拒绝理由有界，不会出现「省电顺手放宽安全档」的新动作。
                                                if (d.action == PowerPolicy.Action.REFUSE) {
                                                    assertTrue("unexpected refuse reason ${d.reason}", d.reason in refuseReasons)
                                                }
                                                // 降载一定带收紧后的预算，且不超过上限。
                                                if (d.action == PowerPolicy.Action.DEGRADE) {
                                                    assertEquals(PowerPolicy.LIGHT_TOOL_BUDGET_MS, d.maxToolMs)
                                                }
                                                // shouldFlush 与 BATCHABLE 的 RUN_NOW 判据一致：
                                                // 只要策略判定该执行，恢复链路也必须认为该 flush。
                                                val runNow = PowerPolicy.decide(c, PowerPolicy.TaskClass.BATCHABLE).action ==
                                                    PowerPolicy.Action.RUN_NOW
                                                if (runNow) assertTrue("run now but shouldFlush=false: $c", PowerPolicy.shouldFlush(c))
                                            }
                                        }
        assertEquals(31_104, checked)
    }
}
