# 策略化省电（Power Strategy）

> **状态：🟡 部分落地** — 决策层、设置开关、重工具预算硬闸门已接入；攒块执行器与引擎侧软提示消费尚未接入。
>
> **入口**：`设置 › 性能 › 策略化省电`（独立分组，不与自动/完整/轻量、刷新率、界面效果混排）
> **默认**：关闭。打开后需**重启本机引擎**生效（与 `performance.mode` 一样在引擎启动时读 prefs）。

---

## 一、这次做了什么 / 没做什么

| 层 | 内容 | 状态 |
|---|---|---|
| L2 决策层 | `PowerPolicy`：门控信号、决策矩阵、护栏、有效 deadline、flush 判据 | ✅ 已落地，JVM 单测覆盖 |
| 设置项 | 独立 `SettingsSection` + 开关（副标题随状态切换）+ 引导 footer；已注册 `Spots.powerStrategy`，可被设置搜索到 | ✅ 已落地 |
| **硬闸门** | `PhoneCore.runCall`：开启时把重工具预算从 120 s 收到 30 s | ✅ **本次唯一真正生效的省电效果** |
| 软提示载体 | `DSH_POWER_MODE` / `DSH_POWER_MAX_TOOL_MS` 注入引擎环境 | 🟡 只注入，引擎侧尚无消费点 |
| 攒块执行器 | 落盘队列、`JobScheduler`、连线恢复 flush、打开 App 兜底 | ❌ 未接入 |
| L0 唤醒锁 | `WakeHoldGate` / `DshService` / `PhoneControl` 旧补丁 | ❌ 不在本 PR，见第五节 |

**为什么先做到这一步**：按此前《省电策略全景》的 ROI 排序，攒块不是最短路径。先把「现成旋钮」收编（重工具预算）并让门控判据可以脱离 Android SDK 独立验证；执行器要动任务调度链路、依赖国产 ROM 的后台触发率，必须真机验证，单独开 PR 更稳妥。

> ⚠️ 因此 **UI 文案不宣称攒块已生效**。若后续接入执行器，需同步修改 `parts/performance_*.arb` 里 6 个 key 并跑生成链（第三节）。

---

## 二、决策矩阵与护栏

`PowerPolicy.decide(ctx, task)` 是纯函数，无 Android 依赖，可在 JVM 上穷举。返回值只有 `RUN_NOW / BATCH / DEGRADE / REFUSE` 四种动作，外加给状态条看的 `reason`。

| 优先级 | 条件 | 动作 |
|---|---|---|
| 1 | 开关关闭 | `RUN_NOW`（`strategy_off`，与未开启时行为完全一致，可随时回滚） |
| 2 | `INTERACTIVE`（用户在等） | `RUN_NOW`，**永不攒块** |
| 2 | `PHONE_CONTROL` | 低电量 `REFUSE`；其余 `RUN_NOW`，**永不攒块** |
| 3 | `HEAVY_TOOL` | 低电量/离线 `REFUSE`；其余 `DEGRADE`（`maxToolMs = 30_000`） |
| 4 | `BATCHABLE` + 前台 / 连上远程主机 / WiFi / 充电 / 达条数上限 / 到 deadline | `RUN_NOW` |
| 4 | 其余 `BATCHABLE` | `BATCH`，且**必带正的 deadline** |

**护栏（改 `decide()` 必须同步 `PowerPolicyTest.kt` 的穷举断言）**

- 交互式对话与手机操控永不进入 `BATCH`：亮屏、确认卡 30 s、idle 3 min、熄屏即停这些既有行为不受影响。
- `deadlineMs <= 0` 会被 `effectiveDeadline()` 回落到 `DEFAULT_DEADLINE_MS = 30 min`。否则 `age(0) >= deadline(0)` 会在边界配置下把攒块错误地变成直接放行。
- 攒块条数上限 `BATCH_LIMIT = 20`，到顶即 flush，防止「等 WiFi 永远等不到」把任务饿死。
- `shouldFlush()` 在「关闭策略」时返回 `true`，保证关掉开关不留未投递的块。
- **省电不得降安全档**：决策对象里不含确认档、交还用户、应用名单、打码规则任何字段；硬闸门只改工具预算，不改确认流程。

**默认值取最保守**：`Context` 默认按电池 + 蜂窝 + 本机 + 后台，缺信号时倾向攒块/降载，而不是偷偷放开重负载。

---

## 三、接入链路与文件清单

### 3.1 三条链路

```text
Dart 设置开关  performance.power_strategy  (SharedPreferences)
      │
      ├─(FlutterSharedPreferences 自动加 flutter. 前缀)
      ▼
原生 PerformancePolicy.applyEngineEnvironment()
      ├─ 读 flutter.performance.power_strategy → PowerPolicy.setStrategyEnabled()
      ├─ 注入 DSH_POWER_MODE / DSH_POWER_MAX_TOOL_MS 到引擎 env（软提示载体）
      ▼
PhoneCore.runCall() → PowerPolicy.toolBudgetMs(base, heavy, enabled)  ← 硬闸门
```

`PhoneCore` 的判据用「原预算超过 `LIGHT_TOOL_BUDGET_MS`」，与 `budgetMs(name)` 保持单点真源，不在 `PowerPolicy` 里重复维护一份工具清单。

### 3.2 key 对齐表（改动时最容易踩的坑）

| 侧 | 常量 | 值 |
|---|---|---|
| Dart `performance_state.dart` | `powerStrategyKey` | `performance.power_strategy` |
| 原生 `PerformancePolicy.kt` | `POWER_STRATEGY` | `flutter.performance.power_strategy` |

两者必须一致，靠 `FlutterSharedPreferences` 的 `flutter.` 前缀规则衔接，不需要新增 MethodChannel。

### 3.3 文件清单

| 文件 | 类型 | 说明 |
|---|---|---|
| `app/android/.../performance/PowerPolicy.kt` | 新增 | 纯决策层，无 `android.*` import |
| `app/android/.../performance/PowerPolicyTest.kt` | 新增 | 15 个 JUnit 用例 + 31,104 条穷举 |
| `app/android/.../performance/PerformancePolicy.kt` | 改 | 读开关、置位单例、注入 env |
| `app/android/.../phone/PhoneCore.kt` | 改 | 硬闸门收紧预算 |
| `app/lib/performance/performance_policy.dart` | 改 | `PerformancePrefs.powerStrategy` 字段 |
| `app/lib/performance/performance_state.dart` | 改 | 持久化 / 读取 / 诊断输出 |
| `app/lib/ui/settings/performance_page.dart` | 改 | 独立设置分组 + **app 内引导** |
| `app/lib/ui/settings/index/spots.dart` | 改 | 注册 `Spots.powerStrategy`（仓库要求每个设置行都在索引里） |
| `app/test/performance/performance_ui_test.dart` | 改 | 页面变高后的窗口高度与 pump 时序 |
| `app/test/performance/goldens/performance_{dark,light}_{en,zh}.png` | 改 | 多一组后重出的基准图（由仓库 `goldens.yml` 渲染，勿手改） |
| `app/lib/l10n/parts/performance_zh.arb` / `_en.arb` | 改 | 6 个文案真源（含 1 个搜索关键词） |
| `app/lib/l10n/app_zh.arb` / `app_en.arb` | 生成物 | 由生成器产出 |
| `app/lib/l10n/gen/app_localizations*.dart` | 生成物 | 由 `flutter gen-l10n` 产出 |
| `docs/power-saver-strategy.md` | 新增 | 本文档（README 侧） |

### 3.4 改文案的正确姿势（导入方法）

界面文案**只能改 `parts/*.arb`**，中英同步，再跑生成器；直接改生成物会被 CI 打回：

```bash
cd app
python3 tool/l10n_merge.py --check       # 校验 parts 合法性
python3 tool/l10n_merge.py               # 生成 app_*.arb + gen/*.dart（需 Flutter SDK）
python3 tool/l10n_merge.py --no-gen      # 只生成 app_*.arb（无 Flutter 时用，需手工同步 gen）
```

CI 的 Flutter shard 0 会执行 `python3 tool/l10n_merge.py` 后 `git diff --exit-code -- lib/l10n`，即 **`gen/*.dart` 必须与生成器输出字节级一致**。生成器规则：合并顺序按 parts 拼接顺序（非字母序）；完整行 `  String get k => 'v';` ≤ 120 字符走单行，否则换行成 `String get k =>\n      'v';`。

`docs/guide/` 与 `app/assets/skills/dsh-go-guide/` 由 `guide_sync.py` 校验；本文档放在 `docs/` 根，不进 guide 链，不会触发该检查。

---

## 四、验证记录（真实执行）

| 项 | 命令 / 方法 | 结果 |
|---|---|---|
| 决策层单测 | `K2JVMCompiler` 编译 `PowerPolicy.kt` + `PowerPolicyTest.kt` → `JUnitCore` | ✅ `OK (15 tests)`，含 31,104 条状态组合穷举 |
| CI（GitHub Actions） | `CI` workflow，PR #1 head `3c5b2c7`，run [43703041651](https://github.com/Kiastr/dsh-Go/actions/runs/43703041651) | ✅ **全绿**：`changes` / `Generated files, tooling, SDK, web` / `Flutter 0` / `Flutter 1` / `Flutter 2` / `Android JVM` / `CI OK` = success（`Runtime and bridge`、`Dart packages` 由 `changes` 过滤跳过） |
| Kotlin 语法解析 | `K2JVMCompiler` 编译 3 个改动文件（无 Android SDK） | ✅ 693 条诊断中 647 条 `unresolved reference`、46 条连锁类型推断，**0 条语法错误** |
| l10n 合法性 | `python3 app/tool/l10n_merge.py --check` | ✅ `70 parts, 7758 keys`（基线 7752，+6） |
| 生成器闭环 | 干净基线 `--no-gen` 重现远程 `app_*.arb`；改后 parts `--no-gen` 重现交付物 | ✅ 两次均**逐字节一致** |
| gen 模板复现 | 用反推模板重建现有 22 个 performance getter | ✅ abstract / en / zh 三文件**逐字节 MATCH**；新增 6 个按同规则插入，diff 仅新增、无删除 |
| golden 基准图 | 仓库自带 `.github/workflows/goldens.yml`（`workflow_dispatch`，`paths=test/performance/performance_golden_test.dart`） | ✅ run [43702722242](https://github.com/Kiastr/dsh-Go/actions/runs/43702722242) `success`，artifact `golden-updates-7d13187`（4 张 PNG）重出后目视核对中英 × 明暗四种组合，布局正常无溢出 |
| 静态一致性 | key 对齐、调用点唯一、括号平衡、getter 唯一、文案与 parts 一致、凭据扫描 | ✅ 全部通过 |
| 敏感信息自检 | 扫描全部交付物中的 PAT / Bearer 模式 | ✅ 0 命中；凭据仅存于本地临时文件，未进入仓库 |
| `guide_sync.py --check` | 副本不完整导致 `missing SKILL.md` | ⚠️ 与本次改动无关（未触碰 `docs/guide/`），CI 在完整仓库中运行 |

**未运行 / 未覆盖（诚实边界）**

- ✅ `flutter analyze` 与 `gen-l10n` 一致性：**由 CI 的 Flutter 0 验证通过**（`Analyze, and generated localization matches its source` = success），证明手工复现的 `gen/*.dart` 与真实 `flutter gen-l10n` 输出逐字节一致。
- ✅ `flutter test`：**CI 三个 shard 全 success**。首轮曝出的 4 类回归已全部修掉并复跑确认——golden 变旧（重出 4 张基准图）、320dp/2.0x 溢出（分组压成单行 + 抬高测试窗口）、fluid 时序（`ensureVisible` 后补 `pumpAndSettle`）、设置索引未注册（新增 `Spots.powerStrategy`）。
- ❌ 本地 `flutter analyze` / `flutter test`：沙箱无 Flutter SDK，内置 Dart 2.17 与项目 Dart 3 语法不匹配；上述两项只在 CI 上跑过。
- ❌ APK 构建、真机、`batterystats` 功耗实测、端到端功能验证：沙箱无 Android SDK / NDK / 设备。
- ❌ 攒块队列、连线恢复 flush、引擎侧读取 `DSH_POWER_*` 的提示模板：尚未实现，**开关打开后这些不会生效**。

---

## 五、与旧 WakeHoldGate 补丁的关系：**不融入本 PR**

| 维度 | 融入 | 不融入（采用） |
|---|---|---|
| 主题 | L0 唤醒锁 bug fix + L2 策略功能混在一个 PR，违反仓库「一 PR 一主题」 | 主题纯净：策略化省电 |
| 验证 | `DshService` / `PhoneControl` 涉及 PRoot、手机操控、Android 进程生命周期，仓库要求真机验证 | 本 PR 决策层可 JVM 穷举，边界清楚 |
| 上游状态 | 旧补丁基于 11/04 基线，fork 里 `DshService` 签名已变、`PhoneControl` 大幅增长，patch 已不能直接应用 | 保留设计，按当前 fork 源码重做，另开 PR |
| 互补性 | — | 两者互补不冲突：L0 管「CPU 能不能睡」，L2 管「何时、何地、做多大」 |

旧补丁（`WakeHoldGate` 状态机 + 10 分钟超时 `PARTIAL_WAKE_LOCK` + 2 s→5 s 轮询 + 三个事件点补偿）仍是独立后续项；`AGENTS.md` 明确不接受外部 PR，因此两个 PR 都只提到用户自己的 fork。

---

## 六、后续待办（按 ROI）

1. **P0**：把 `PowerPolicy.decide()` 接到真实调度点，落盘攒块队列 + `JobScheduler`（`TRANSPORT_WIFI`）+ 连线恢复 flush + 打开 App 兜底。**必须在真机上验证国产 ROM 的后台触发率**，否则攒块会把任务饿死。
2. **P0**：引擎侧技能/提示模板消费 `DSH_POWER_MODE`，把「别跑重负载」真正注入模型上下文。
3. **P1**：`network_watch.dart` 不发初始连接状态，接入执行器时要显式补一次状态。
4. **P1**：性能档（`lightweight`）与策略档联动，统一 `powerEnvironment()` 的 `MODE`。
5. **P2**：状态条展示实时门控信号（供电 / 网络 / 主机 / 队列深度），让「点了没反应」可自查。
