import React, { useState, useEffect } from 'react';
import {
  View,
  Text,
  StyleSheet,
  ScrollView,
  TouchableOpacity,
  Switch,
  Alert,
} from 'react-native';
import { ColorTokens, Spacing, Radius } from '../theme/tokens';
import {
  ShieldBridgeService,
  UsageSummary,
  CurfewPolicy,
  AppLimitItem,
} from '../bridge/ShieldBridge';
import { formatMinutes } from '../engine/policyRules';

export const ParentDashboardScreen: React.FC = () => {
  const [usage, setUsage] = useState<UsageSummary>({ totalMinutes: 130, perApp: {} });
  const [policy, setPolicy] = useState<CurfewPolicy | null>(null);
  const [appLimits, setAppLimits] = useState<AppLimitItem[]>([
    { packageName: 'com.google.android.youtube', appName: 'YouTube', dailyLimitMinutes: 45, isBlocked: false },
    { packageName: 'com.zhiliaoapp.musically', appName: 'TikTok', dailyLimitMinutes: 0, isBlocked: true },
    { packageName: 'com.mojang.minecraftpe', appName: 'Minecraft', dailyLimitMinutes: 60, isBlocked: false },
    { packageName: 'com.roblox.client', appName: 'Roblox', dailyLimitMinutes: 45, isBlocked: false },
  ]);
  const [isInstantLocked, setIsInstantLocked] = useState(false);

  useEffect(() => {
    loadData();
  }, []);

  const loadData = async () => {
    const currentUsage = await ShieldBridgeService.getDailyUsage();
    const currentPolicy = await ShieldBridgeService.getCurfewPolicy();
    setUsage(currentUsage);
    setPolicy(currentPolicy);
    setIsInstantLocked(currentPolicy.isInstantLocked);
  };

  const handleToggleInstantLock = async (val: boolean) => {
    const auth = await ShieldBridgeService.authenticateWithBiometrics();
    if (auth.success) {
      setIsInstantLocked(val);
      await ShieldBridgeService.triggerInstantLock(val);
    } else {
      Alert.alert('Authentication Required', 'Fingerprint or Master PIN required to change lock status.');
    }
  };

  const handleAddBonusTime = async (minutes: number) => {
    const auth = await ShieldBridgeService.authenticateWithBiometrics();
    if (auth.success) {
      await ShieldBridgeService.addBonusMinutes(minutes);
      Alert.alert('Bonus Granted', `Added +${minutes} minutes to today's screen time allowance.`);
      loadData();
    }
  };

  const dailyLimit = policy?.dailyTotalLimitMinutes ?? 150;
  const used = usage.totalMinutes;
  const percentUsed = Math.min(100, Math.round((used / dailyLimit) * 100));

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      {/* Device Header */}
      <View style={styles.header}>
        <View>
          <Text style={styles.childName}>Emma's Phone</Text>
          <Text style={styles.deviceModel}>Samsung Galaxy A54 • Android 14</Text>
        </View>
        <View style={styles.protectedBadge}>
          <View style={styles.statusDot} />
          <Text style={styles.protectedText}>Protected</Text>
        </View>
      </View>

      {/* Daily Usage Donut Card */}
      <View style={styles.card}>
        <Text style={styles.cardTitle}>Daily Screen Time</Text>
        <View style={styles.chartContainer}>
          <View style={styles.chartCircle}>
            <Text style={styles.chartTime}>{formatMinutes(used)}</Text>
            <Text style={styles.chartSubtitle}>of {formatMinutes(dailyLimit)} used</Text>
            <Text style={styles.chartPercent}>{percentUsed}%</Text>
          </View>
        </View>

        <View style={styles.quickActionsRow}>
          <TouchableOpacity
            style={styles.bonusButton}
            onPress={() => handleAddBonusTime(15)}
          >
            <Text style={styles.bonusButtonText}>+15m Bonus</Text>
          </TouchableOpacity>
          <TouchableOpacity
            style={styles.bonusButton}
            onPress={() => handleAddBonusTime(30)}
          >
            <Text style={styles.bonusButtonText}>+30m Bonus</Text>
          </TouchableOpacity>
        </View>
      </View>

      {/* Instant Device Freeze Toggle */}
      <View style={[styles.card, styles.freezeCard]}>
        <View style={styles.freezeInfo}>
          <Text style={styles.freezeTitle}>Instant Device Freeze</Text>
          <Text style={styles.freezeDesc}>
            Lock the phone immediately for dinner or family time.
          </Text>
        </View>
        <Switch
          value={isInstantLocked}
          onValueChange={handleToggleInstantLock}
          trackColor={{ false: '#CBD5E1', true: ColorTokens.state.warning }}
          thumbColor="#FFFFFF"
        />
      </View>

      {/* Bedtime Curfew Schedule Card */}
      <View style={styles.card}>
        <View style={styles.curfewRow}>
          <View>
            <Text style={styles.cardTitle}>Bedtime Curfew</Text>
            <Text style={styles.curfewTimes}>09:00 PM – 07:00 AM</Text>
          </View>
          <View style={styles.scheduleBadge}>
            <Text style={styles.scheduleText}>Scheduled</Text>
          </View>
        </View>
      </View>

      {/* Per-App Restrictions Card */}
      <View style={styles.card}>
        <Text style={styles.cardTitle}>App Restrictions</Text>
        <Text style={styles.cardSubtitle}>
          Set individual limits or block specific apps completely.
        </Text>

        {appLimits.map((app) => {
          const appUsed = usage.perApp[app.packageName] || 0;
          const isAppLocked = app.isBlocked || (app.dailyLimitMinutes > 0 && appUsed >= app.dailyLimitMinutes);

          return (
            <View key={app.packageName} style={styles.appRow}>
              <View style={styles.appMeta}>
                <Text style={styles.appName}>{app.appName}</Text>
                <Text style={styles.appUsageText}>
                  {app.isBlocked
                    ? 'Blocked'
                    : `${formatMinutes(appUsed)} / ${formatMinutes(app.dailyLimitMinutes)}`}
                </Text>
              </View>

              {isAppLocked ? (
                <View style={styles.lockedBadge}>
                  <Text style={styles.lockedBadgeText}>
                    {app.isBlocked ? 'Blocked' : 'Locked'}
                  </Text>
                </View>
              ) : (
                <View style={styles.activeBadge}>
                  <Text style={styles.activeBadgeText}>
                    {formatMinutes(Math.max(0, app.dailyLimitMinutes - appUsed))} left
                  </Text>
                </View>
              )}
            </View>
          );
        })}
      </View>
    </ScrollView>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: ColorTokens.surface.canvasLight,
  },
  content: {
    padding: Spacing.md,
    paddingBottom: Spacing.xxl,
  },
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: Spacing.md,
    marginTop: Spacing.sm,
  },
  childName: {
    fontSize: 22,
    fontWeight: '700',
    color: ColorTokens.text.primaryLight,
  },
  deviceModel: {
    fontSize: 13,
    color: ColorTokens.text.secondaryLight,
    marginTop: 2,
  },
  protectedBadge: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: ColorTokens.state.successContainer,
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: Radius.pill,
  },
  statusDot: {
    width: 8,
    height: 8,
    borderRadius: 4,
    backgroundColor: ColorTokens.state.success,
    marginRight: 6,
  },
  protectedText: {
    color: ColorTokens.state.success,
    fontWeight: '600',
    fontSize: 12,
  },
  card: {
    backgroundColor: ColorTokens.surface.cardLight,
    borderRadius: Radius.md,
    padding: Spacing.md,
    marginBottom: Spacing.md,
    shadowColor: '#000',
    shadowOpacity: 0.04,
    shadowOffset: { width: 0, height: 2 },
    shadowRadius: 8,
    elevation: 2,
  },
  cardTitle: {
    fontSize: 17,
    fontWeight: '700',
    color: ColorTokens.text.primaryLight,
  },
  cardSubtitle: {
    fontSize: 13,
    color: ColorTokens.text.secondaryLight,
    marginTop: 2,
    marginBottom: Spacing.md,
  },
  chartContainer: {
    alignItems: 'center',
    marginVertical: Spacing.md,
  },
  chartCircle: {
    width: 160,
    height: 160,
    borderRadius: 80,
    borderWidth: 12,
    borderColor: ColorTokens.brand.primary,
    alignItems: 'center',
    justifyContent: 'center',
  },
  chartTime: {
    fontSize: 26,
    fontWeight: '800',
    color: ColorTokens.text.primaryLight,
  },
  chartSubtitle: {
    fontSize: 12,
    color: ColorTokens.text.secondaryLight,
    marginTop: 2,
  },
  chartPercent: {
    fontSize: 11,
    fontWeight: '700',
    color: ColorTokens.brand.primary,
    marginTop: 4,
  },
  quickActionsRow: {
    flexDirection: 'row',
    justifyContent: 'center',
    gap: Spacing.sm,
    marginTop: Spacing.sm,
  },
  bonusButton: {
    backgroundColor: ColorTokens.brand.primaryContainer,
    paddingVertical: 8,
    paddingHorizontal: 16,
    borderRadius: Radius.pill,
  },
  bonusButtonText: {
    color: ColorTokens.brand.primary,
    fontWeight: '700',
    fontSize: 13,
  },
  freezeCard: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  freezeInfo: {
    flex: 1,
    paddingRight: Spacing.md,
  },
  freezeTitle: {
    fontSize: 16,
    fontWeight: '700',
    color: ColorTokens.text.primaryLight,
  },
  freezeDesc: {
    fontSize: 13,
    color: ColorTokens.text.secondaryLight,
    marginTop: 2,
  },
  curfewRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  curfewTimes: {
    fontSize: 14,
    color: ColorTokens.text.secondaryLight,
    marginTop: 4,
  },
  scheduleBadge: {
    backgroundColor: ColorTokens.brand.primaryContainer,
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: Radius.pill,
  },
  scheduleText: {
    color: ColorTokens.brand.primary,
    fontWeight: '600',
    fontSize: 12,
  },
  appRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: Spacing.sm + 2,
    borderBottomWidth: 1,
    borderBottomColor: ColorTokens.border.subtleLight,
  },
  appMeta: {
    flex: 1,
  },
  appName: {
    fontSize: 15,
    fontWeight: '600',
    color: ColorTokens.text.primaryLight,
  },
  appUsageText: {
    fontSize: 13,
    color: ColorTokens.text.secondaryLight,
    marginTop: 2,
  },
  lockedBadge: {
    backgroundColor: ColorTokens.state.lockedContainer,
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: Radius.pill,
  },
  lockedBadgeText: {
    color: ColorTokens.state.locked,
    fontWeight: '700',
    fontSize: 12,
  },
  activeBadge: {
    backgroundColor: '#F1F5F9',
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: Radius.pill,
  },
  activeBadgeText: {
    color: ColorTokens.text.secondaryLight,
    fontWeight: '600',
    fontSize: 12,
  },
});
