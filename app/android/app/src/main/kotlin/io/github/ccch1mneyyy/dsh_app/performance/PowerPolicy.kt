package io.github.ccch1mneyyy.dsh_app.performance

/**
 * 策略化省电的**纯决策层**：把「什么时候跑、要不要攒、能跑多重」收敛成一个
 * 无平台依赖的纯函数 [decide]，便于在没有 Android SDK / 真机的 JVM 上穷举单测。
 *
 * 导入方法（给未来维护者，按仓库 AGENTS.md「in-code 注释记录导入方法和细节」）：
 *  1. 本文件属于 `performance` 包，与 [PerformanceRules] 并列；**不 import 任何 android.* 类**，
 *     因此可以用 kotlin-compiler-embeddable + JUnit 直接在 JVM 编译运行（见 PowerPolicyTest.kt）。
 *  2. 真接入点只有两处，都由现成链路挂进去，不新增常驻轮询/服务（避免「为了省电而费电」）：
 *     - [PerformancePolicy.applyEngineEnvironment]：把 `DSH_POWER_MODE` / `DSH_POWER_MAX_TOOL_MS`
 *       注入引擎环境（软提示载体），并在启动时置位 [strategyEnabled]。
 *     - `PhoneCore.runCall`：用 [toolBudgetMs] 收紧重工具预算（硬闸门）。
 *  3. Dart 侧开关落在 `FlutterSharedPreferences` 的 `flutter.performance.power_strategy`
 *     （见 app/lib/performance/performance_state.dart），与 [PerformancePolicy] 读
 *     `flutter.performance.mode` 用的是同一套 prefs，无需新增 Channel。
 *  4. 决策只回答「何时、何地、做多大」，**不回答「允不允许」**：三档确认、交还用户、
 *     应用名单、打码规则一律不在此处，也不得被省电档放宽（见 [decide] 护栏）。
 *
 * 明确不在本文件范围的：落盘攒块队列、JobScheduler、连线恢复 flush 的执行器。
 * 本层只产出 [Decision.BATCH] 与 [shouldFlush] 的判据；执行器需要真机验证国产 ROM 触发率，
 * 见 docs/power-saver-strategy.md「边界」。判据先落地是为了让门控与护栏可独立验证。
 */
object PowerPolicy {

    /** 改判据时 +1，供诊断与缓存失效对齐（沿用 PerformanceRules.VERSION 的惯例）。 */
    const val VERSION = 1

    /** 攒块最长等待：防止「等 WiFi 永远等不到」把后台任务饿死。 */
    const val DEFAULT_DEADLINE_MS = 30L * 60_000L

    /** 攒块条数上限，达到即 flush，不再等触发信号。 */
    const val BATCH_LIMIT = 20

    /** 省电档重工具预算上限：原 ANDROID_SHELL / COLLECT 为 120 s。 */
    const val LIGHT_TOOL_BUDGET_MS = 30_000L

    /** 注入引擎的环境变量名（引擎侧技能 / 提示模板可读取，App 侧不解析）。 */
    const val ENV_MODE = "DSH_POWER_MODE"
    const val ENV_MAX_TOOL_MS = "DSH_POWER_MAX_TOOL_MS"

    /** 谁置位：`PerformancePolicy.applyEngineEnvironment` 每次启动引擎时按开关置位。
     * 谁清除：进程退出自然清除；不需要显式复位（与 ThermalRuntime 同构，进程内单例）。
     * 清不掉会怎样：只会让本机引擎沿用上一次的档位，不影响交互与安全档。 */
    @Volatile
    @JvmStatic
    var strategyEnabled: Boolean = false
        private set

    /** 由启动链路调用；测试里也可直接置位来覆盖全局标志分支。 */
    @JvmStatic
    fun setStrategyEnabled(enabled: Boolean) {
        strategyEnabled = enabled
    }

    enum class Supply { CHARGING, BATTERY, LOW }
    enum class Network { WIFI, CELLULAR, OFFLINE }
    enum class Host { LOCAL, REMOTE, NONE }

    /** 交互 / 手机操控永不进入 BATCH；BATCHABLE 才是可攒的后台任务。 */
    enum class TaskClass { INTERACTIVE, PHONE_CONTROL, BATCHABLE, HEAVY_TOOL }
    enum class Action { RUN_NOW, BATCH, DEGRADE, REFUSE }
    enum class Trigger { WIFI, CHARGING, REMOTE_HOST }

    /**
     * 门控信号。默认值按「最保守」取：未知时按电池 + 蜂窝 + 本机 + 后台，
     * 保证缺信号时倾向攒块/降载，而不是偷偷放开重负载。
     */
    data class Context(
        val enabled: Boolean = false,
        val supply: Supply = Supply.BATTERY,
        val network: Network = Network.CELLULAR,
        val host: Host = Host.LOCAL,
        val lightweight: Boolean = false,
        val foreground: Boolean = false,
        val screenOn: Boolean = true,
        val queued: Int = 0,
        val oldestQueuedAgeMs: Long = 0L,
        val deadlineMs: Long = DEFAULT_DEADLINE_MS,
        val triggers: Set<Trigger> = setOf(Trigger.WIFI, Trigger.CHARGING, Trigger.REMOTE_HOST),
    )

    /** [reason] 是给状态条/诊断看的「为什么这么决定」，避免「点了没反应」。 */
    data class Decision(
        val action: Action,
        val reason: String,
        val deadlineMs: Long = 0L,
        val maxToolMs: Long = 0L,
    )

    /**
     * 决策矩阵。护栏（顺序即优先级，改这里必须同步 PowerPolicyTest.kt 的穷举断言）：
     *  1. 开关关闭 → 一律 RUN_NOW，行为与未开启时完全相同（默认值可回滚）。
     *  2. INTERACTIVE（用户在等）/ PHONE_CONTROL（亮屏、确认卡 30 s、idle 3 min、熄屏即停）
     *     **永不 BATCH**；手机操控在低电量下 REFUSE 并给出可恢复提示。
     *  3. HEAVY_TOOL：低电量/离线直接 REFUSE；否则 DEGRADE（收紧预算 + 软提示）。
     *  4. BATCHABLE：满足任一触发信号 / 前台 / 已达攒块上限 / 已到 deadline → RUN_NOW；
     *     否则 BATCH，且**必带 deadline > 0**。
     *  5. 全程不产生任何放宽安全档的动作：不含确认档、交还用户、名单、打码相关字段。
     */
    fun decide(ctx: Context, task: TaskClass): Decision {
        if (!ctx.enabled) return Decision(Action.RUN_NOW, "strategy_off")
        // 有效 deadline：配置成 0/负数时回落到默认值，否则「age(0) >= deadline(0)」会在
        // 边界配置下立刻判定到期，把攒块错误地变成直接放行（护栏失效）。
        val effectiveDeadline = effectiveDeadline(ctx.deadlineMs)
        return when (task) {
            TaskClass.INTERACTIVE ->
                Decision(Action.RUN_NOW, "interactive_never_batched")

            TaskClass.PHONE_CONTROL -> when (ctx.supply) {
                Supply.LOW -> Decision(Action.REFUSE, "low_battery_phone_control")
                else -> Decision(Action.RUN_NOW, "phone_control_never_batched")
            }

            TaskClass.HEAVY_TOOL -> when {
                ctx.supply == Supply.LOW -> Decision(Action.REFUSE, "low_battery_heavy_tool")
                ctx.network == Network.OFFLINE -> Decision(Action.REFUSE, "offline_heavy_tool")
                // 重活在远程主机跑，手机不承负担；但仍降载提示，避免模型在手机端发起长命令。
                else -> Decision(Action.DEGRADE, "degrade_heavy_tool", maxToolMs = LIGHT_TOOL_BUDGET_MS)
            }

            TaskClass.BATCHABLE -> when {
                ctx.foreground -> Decision(Action.RUN_NOW, "foreground")
                ctx.host == Host.REMOTE && Trigger.REMOTE_HOST in ctx.triggers ->
                    Decision(Action.RUN_NOW, "remote_host_connected")
                ctx.network == Network.WIFI && Trigger.WIFI in ctx.triggers ->
                    Decision(Action.RUN_NOW, "wifi_connected")
                ctx.supply == Supply.CHARGING && Trigger.CHARGING in ctx.triggers ->
                    Decision(Action.RUN_NOW, "charging")
                ctx.queued >= BATCH_LIMIT -> Decision(Action.RUN_NOW, "batch_limit_reached")
                ctx.oldestQueuedAgeMs >= effectiveDeadline -> Decision(Action.RUN_NOW, "deadline_reached")
                else -> Decision(Action.BATCH, "defer_until_trigger", deadlineMs = effectiveDeadline)
            }
        }
    }

    /** 配置的 deadline 非正时回落到默认值，保证「攒块必有正的等待上限」。 */
    fun effectiveDeadline(deadlineMs: Long): Long =
        if (deadlineMs > 0) deadlineMs else DEFAULT_DEADLINE_MS

    /** 硬闸门：省电档把 ANDROID_SHELL / COLLECT 从 120 s 收到 30 s，其余工具预算不变。
     * 用 [baseMs] 传入原预算，避免本层重复维护一份工具清单（与 PhoneCore.budgetMs 单点真源）。 */
    fun toolBudgetMs(baseMs: Long, heavyTool: Boolean, enabled: Boolean): Long =
        if (enabled && heavyTool && baseMs > LIGHT_TOOL_BUDGET_MS) LIGHT_TOOL_BUDGET_MS else baseMs

    /** 连线/供电/条数/deadline 任一满足即应 flush；执行器在恢复事件里调用它。
     * 注意：network_watch.dart 不发初始连接状态，所以「打开 App / 连上主机」也要显式调一次。 */
    fun shouldFlush(ctx: Context): Boolean {
        if (!ctx.enabled) return true // 关掉策略时不留未投递的块，立即排空。
        if (ctx.foreground || ctx.queued >= BATCH_LIMIT) return true
        if (ctx.oldestQueuedAgeMs >= effectiveDeadline(ctx.deadlineMs)) return true
        if (ctx.host == Host.REMOTE && Trigger.REMOTE_HOST in ctx.triggers) return true
        if (ctx.network == Network.WIFI && Trigger.WIFI in ctx.triggers) return true
        if (ctx.supply == Supply.CHARGING && Trigger.CHARGING in ctx.triggers) return true
        return false
    }

    /** 软提示载体：注入引擎环境，供技能包/提示模板据此提示模型别跑重负载。
     * 不改 NODE_OPTIONS/UV_THREADPOOL_SIZE 语义；显式 env 由调用方决定是否覆盖。 */
    fun powerEnvironment(enabled: Boolean, lightweight: Boolean): Map<String, String> = if (!enabled) emptyMap() else mapOf(
        ENV_MODE to if (lightweight) "light" else "balanced",
        ENV_MAX_TOOL_MS to LIGHT_TOOL_BUDGET_MS.toString(),
    )
}
