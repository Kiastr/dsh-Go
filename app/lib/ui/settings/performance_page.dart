import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../l10n/l10n.dart';
import '../../performance/performance_state.dart';
import '../../state/android_features.dart';
import '../shell/page_frame.dart';
import '../theme/app_icons.dart';
import 'settings_widgets.dart';

String performanceModeLabel(L10n l10n, PerformanceMode mode) => switch (mode) {
  PerformanceMode.auto => l10n.performanceAuto,
  PerformanceMode.full => l10n.performanceFull,
  PerformanceMode.light => l10n.performanceLight,
};

class PerformanceSettingsTile extends ConsumerWidget {
  const PerformanceSettingsTile({super.key, this.tint = 0});
  final int tint;

  @override
  Widget build(BuildContext context, WidgetRef ref) => SettingsTile(
    key: const ValueKey('settings-performance'),
    icon: AppIcons.configure,
    tint: tint,
    spot: SettingsIndex.entry(SettingsPageId.performance),
    value: Text(performanceModeLabel(context.l10n, ref.watch(performancePrefsProvider).mode)),
    onTap: () => Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => const PerformancePage())),
  );
}

class PerformancePage extends ConsumerWidget {
  const PerformancePage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = context.l10n;
    final prefs = ref.watch(performancePrefsProvider);
    final defaults = ref.watch(performanceDefaultsProvider);
    final thermal = ref.watch(performanceProvider.select((s) => s.thermalLightweight));
    final notifier = ref.read(performancePrefsProvider.notifier);
    final ohos = ref.watch(operatingSystemProvider) == 'ohos';
    return AppPage.list(
      title: l10n.performanceTitle,
      children: [
        SettingsSection(
          footer: Text(thermal ? l10n.performanceCooling : l10n.performanceChoiceHint),
          children: [
            for (final mode in PerformanceMode.values)
              Semantics(
                checked: prefs.mode == mode,
                inMutuallyExclusiveGroup: true,
                child: SettingsTile(
                  key: ValueKey('performance-mode-${mode.name}'),
                  title: performanceModeLabel(l10n, mode),
                  subtitle: switch (mode) {
                    PerformanceMode.auto => l10n.performanceAutoHint,
                    PerformanceMode.full => l10n.performanceFullHint,
                    PerformanceMode.light => l10n.performanceLightHint,
                  },
                  trailing: SizedBox.square(
                    dimension: 32,
                    child: Icon(prefs.mode == mode ? AppIcons.radioOn : AppIcons.radioOff, size: 24),
                  ),
                  onTap: () => unawaited(notifier.setMode(mode)),
                ),
              ),
          ],
        ),
        // iOS paces frames itself (RefreshRateService leaves it alone).
        if (ref.watch(operatingSystemProvider) != 'ios')
          SettingsSection(
            footer: Text(ohos ? l10n.performanceRefreshHintOhos : l10n.performanceRefreshHint),
            children: [
              SettingsSegmentTile<RefreshRateMode>(
                key: const ValueKey('performance-refresh-rate'),
                spot: Spots.refreshRate,
                segments: [
                  PillSegment(RefreshRateMode.auto, l10n.performanceAuto),
                  PillSegment(RefreshRateMode.max, l10n.performanceRefreshMax),
                  PillSegment(RefreshRateMode.system, l10n.performanceRefreshSystem),
                ],
                selected: prefs.refreshRate,
                onChanged: (mode) => unawaited(notifier.setRefreshRate(mode)),
              ),
            ],
          ),
        if (ohos)
          SettingsSection(
            children: [
              SettingsSwitchTile(
                key: const ValueKey('performance-frame-cache'),
                title: l10n.performanceFrameCache,
                subtitle: l10n.performanceFrameCacheHint,
                value: prefs.frameCache,
                onChanged: (value) => unawaited(notifier.setFrameCache(value)),
              ),
            ],
          ),
        // 策略化省电单独成组，不与「自动/完整/轻量」三档、刷新率、界面效果混在一起。
        // footer 是 app 内引导：说清它做什么、不做什么、何时生效，避免「点了没反应」。
        // 只有一行：开关本身显示状态，副标题跟着切换，避免多一行「当前行为」把页面撑高。
        SettingsSection(
          title: l10n.powerStrategyTitle,
          footer: Text(l10n.powerStrategyFooter),
          children: [
            SettingsSwitchTile(
              key: const ValueKey('performance-power-strategy'),
              spot: Spots.powerStrategy,
              title: l10n.powerStrategySwitch,
              subtitle: prefs.powerStrategy ? l10n.powerStrategyStatusOn : l10n.powerStrategyHint,
              value: prefs.powerStrategy,
              onChanged: (value) => unawaited(notifier.setPowerStrategy(value)),
            ),
          ],
        ),
        SettingsSection(
          title: l10n.performanceEffects,
          children: [
            SettingsSwitchTile(
              key: const ValueKey('performance-blur'),
              spot: Spots.blur,
              value: defaults.blur,
              onChanged: (value) => unawaited(notifier.setBlur(value)),
            ),
            SettingsSwitchTile(
              key: const ValueKey('performance-animations'),
              spot: Spots.animations,
              subtitle: l10n.performanceSystemMotion,
              value: !defaults.reduceMotion,
              onChanged: (value) => unawaited(notifier.setAnimations(value)),
            ),
            // A finer choice inside animations: with them off there is nothing to choose.
            if (!defaults.reduceMotion && !MediaQuery.disableAnimationsOf(context))
              SettingsSwitchTile(
                key: const ValueKey('performance-fluid'),
                spot: Spots.fluidMotion,
                subtitle: l10n.performanceFluidHint,
                value: defaults.fluidMotion,
                onChanged: (value) => unawaited(notifier.setFluid(value)),
              ),
            if (prefs.blur != null || prefs.animations != null || prefs.fluid != null)
              SettingsTile(
                key: const ValueKey('performance-reset-effects'),
                title: l10n.performanceFollowMode,
                chevron: false,
                onTap: () => unawaited(notifier.resetEffects()),
              ),
          ],
        ),
        // 大字号下最后一行也要能整个滚进视野:滚动到底时留出一点余量。
        const SizedBox(height: 72),
      ],
    );
  }
}
