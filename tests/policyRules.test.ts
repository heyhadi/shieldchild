import {
  isWithinCurfewWindow,
  calculateRemainingTime,
  evaluateDeviceLock,
  evaluateAppLock,
  formatMinutes,
  validatePin,
  validateRecoveryKey,
  formatRecoveryKey,
  validateChildName,
  filterInstalledApps,
  createAppLimit,
  removeAppLimit,
  formatCurfewTime,
  calculateCurfewDurationMinutes,
  formatCurfewDuration,
  verifyAndRecoverMasterPin,
  canAccessParentDashboard,
  shouldEnforceLockdown,
  evaluateRebootLockdown,
  isUninstallOrTamperAttempt,
  canUninstallApp,
  isCurfewOverrideValid,
  PolicyInput,
} from '../src/engine/policyRules';

describe('Curfew Window Calculation', () => {
  test('evaluates daytime curfew windows correctly', () => {
    // 14:00 to 16:00
    expect(isWithinCurfewWindow(14, 0, 16, 0, 13, 59)).toBe(false);
    expect(isWithinCurfewWindow(14, 0, 16, 0, 14, 0)).toBe(true);
    expect(isWithinCurfewWindow(14, 0, 16, 0, 15, 30)).toBe(true);
    expect(isWithinCurfewWindow(14, 0, 16, 0, 16, 0)).toBe(false);
    expect(isWithinCurfewWindow(14, 0, 16, 0, 17, 0)).toBe(false);
  });

  test('evaluates overnight bedtime curfew windows correctly', () => {
    // 21:00 to 07:00
    expect(isWithinCurfewWindow(21, 0, 7, 0, 20, 59)).toBe(false);
    expect(isWithinCurfewWindow(21, 0, 7, 0, 21, 0)).toBe(true);
    expect(isWithinCurfewWindow(21, 0, 7, 0, 23, 45)).toBe(true);
    expect(isWithinCurfewWindow(21, 0, 7, 0, 0, 15)).toBe(true);
    expect(isWithinCurfewWindow(21, 0, 7, 0, 4, 30)).toBe(true);
    expect(isWithinCurfewWindow(21, 0, 7, 0, 6, 59)).toBe(true);
    expect(isWithinCurfewWindow(21, 0, 7, 0, 7, 0)).toBe(false);
    expect(isWithinCurfewWindow(21, 0, 7, 0, 14, 0)).toBe(false);
  });
});

describe('Screen Time Accounting', () => {
  test('calculates remaining time balance with bonus time', () => {
    const res = calculateRemainingTime(120, 15, 100);
    expect(res.effectiveLimit).toBe(135);
    expect(res.remainingMinutes).toBe(35);
    expect(res.isExceeded).toBe(false);
  });

  test('detects when daily total limit is exceeded', () => {
    const res = calculateRemainingTime(120, 0, 125);
    expect(res.effectiveLimit).toBe(120);
    expect(res.remainingMinutes).toBe(0);
    expect(res.isExceeded).toBe(true);
  });
});

describe('Device Lockdown Policy Evaluator', () => {
  const basePolicy: PolicyInput = {
    dailyTotalLimitMinutes: 120,
    bonusMinutesToday: 0,
    isCurfewScheduled: true,
    curfewStartHour: 21,
    curfewStartMinute: 0,
    curfewEndHour: 7,
    curfewEndMinute: 0,
    isInstantLocked: false,
  };

  test('enforces parent instant freeze over everything else', () => {
    const policy = { ...basePolicy, isInstantLocked: true };
    const result = evaluateDeviceLock(policy, 30, 14, 0);
    expect(result.state).toBe('LOCKED');
    if (result.state === 'LOCKED') {
      expect(result.reason).toBe('INSTANT_FREEZE');
    }
  });

  test('enforces bedtime curfew when within curfew hours', () => {
    const result = evaluateDeviceLock(basePolicy, 30, 22, 15);
    expect(result.state).toBe('LOCKED');
    if (result.state === 'LOCKED') {
      expect(result.reason).toBe('BEDTIME_CURFEW');
    }
  });

  test('locks phone when daily screen time quota is exhausted', () => {
    const result = evaluateDeviceLock(basePolicy, 130, 15, 0);
    expect(result.state).toBe('LOCKED');
    if (result.state === 'LOCKED') {
      expect(result.reason).toBe('DAILY_LIMIT_EXCEEDED');
      expect(result.usedMinutes).toBe(130);
    }
  });

  test('allows device usage when within quota and outside curfew', () => {
    const result = evaluateDeviceLock(basePolicy, 60, 15, 0);
    expect(result.state).toBe('UNLOCKED');
    if (result.state === 'UNLOCKED') {
      expect(result.remainingMinutes).toBe(60);
    }
  });
});

describe('Single App Lock Evaluation', () => {
  test('blocks app if marked explicitly as isBlocked', () => {
    const result = evaluateAppLock(60, true, 10);
    expect(result.isLocked).toBe(true);
    expect(result.remainingMinutes).toBe(0);
  });

  test('locks single app when daily limit reached', () => {
    const result = evaluateAppLock(45, false, 45);
    expect(result.isLocked).toBe(true);
    expect(result.remainingMinutes).toBe(0);
  });

  test('keeps single app open when usage is below limit', () => {
    const result = evaluateAppLock(45, false, 30);
    expect(result.isLocked).toBe(false);
    expect(result.remainingMinutes).toBe(15);
  });

  test('ignores unconstrained apps with 0 limit', () => {
    const result = evaluateAppLock(0, false, 90);
    expect(result.isLocked).toBe(false);
  });
});

describe('Formatters and Security Validators', () => {
  test('formats minutes cleanly into hours and minutes', () => {
    expect(formatMinutes(45)).toBe('45m');
    expect(formatMinutes(120)).toBe('2h');
    expect(formatMinutes(135)).toBe('2h 15m');
    expect(formatMinutes(0)).toBe('0m');
  });

  test('validates parent master PIN format', () => {
    expect(validatePin('1234').isValid).toBe(true);
    expect(validatePin('987654').isValid).toBe(true);
    expect(validatePin('123').isValid).toBe(false);
    expect(validatePin('123456789').isValid).toBe(false);
    expect(validatePin('12a4').isValid).toBe(false);
    expect(validatePin('').isValid).toBe(false);
  });

  test('validates emergency master recovery key format', () => {
    expect(validateRecoveryKey('SHLD-8821-XQ92-KM41').isValid).toBe(true);
    expect(validateRecoveryKey('shld8821xq92km41').isValid).toBe(true);
    expect(validateRecoveryKey('SHLD-8821-XQ92').isValid).toBe(false); // Too short
    expect(validateRecoveryKey('SHLD-8821-XQ92-KM41-EXTRA').isValid).toBe(false); // Too long
    expect(validateRecoveryKey('SHLD-8821-XQ92-KM!@').isValid).toBe(false); // Invalid characters
  });

  test('formats raw recovery keys cleanly into 4-character chunks', () => {
    expect(formatRecoveryKey('shld8821xq92km41')).toBe('SHLD-8821-XQ92-KM41');
    expect(formatRecoveryKey('SHLD8821')).toBe('SHLD-8821');
    expect(formatRecoveryKey('')).toBe('');
  });

  test('validates child name correctly', () => {
    expect(validateChildName('Leo').isValid).toBe(true);
    expect(validateChildName('  Emma  ').isValid).toBe(true);
    expect(validateChildName('').isValid).toBe(false);
    expect(validateChildName('   ').isValid).toBe(false);
    expect(validateChildName('A'.repeat(31)).isValid).toBe(false);
  });
});

describe('App Limits Management Logic', () => {
  const sampleInstalled = [
    { packageName: 'com.google.android.youtube', appName: 'YouTube' },
    { packageName: 'com.whatsapp', appName: 'WhatsApp' },
    { packageName: 'com.roblox.client', appName: 'Roblox' },
    { packageName: 'com.instagram.android', appName: 'Instagram' },
  ];

  test('filters installed applications by search query', () => {
    expect(filterInstalledApps(sampleInstalled, '').length).toBe(4);
    expect(filterInstalledApps(sampleInstalled, '   ').length).toBe(4);
    expect(filterInstalledApps(sampleInstalled, 'rob')).toEqual([
      { packageName: 'com.roblox.client', appName: 'Roblox' },
    ]);
    expect(filterInstalledApps(sampleInstalled, 'instagram')).toEqual([
      { packageName: 'com.instagram.android', appName: 'Instagram' },
    ]);
    expect(filterInstalledApps(sampleInstalled, 'com.google')).toEqual([
      { packageName: 'com.google.android.youtube', appName: 'YouTube' },
    ]);
    expect(filterInstalledApps(sampleInstalled, 'nonexistent')).toEqual([]);
  });

  test('creates new AppLimit item correctly', () => {
    const valid = createAppLimit('com.discord', 'Discord', 45, false);
    expect(valid.isValid).toBe(true);
    expect(valid.item?.packageName).toBe('com.discord');
    expect(valid.item?.appName).toBe('Discord');
    expect(valid.item?.dailyLimitMinutes).toBe(45);
    expect(valid.item?.isBlocked).toBe(false);

    const blocked = createAppLimit('com.tiktok', 'TikTok', 60, true);
    expect(blocked.isValid).toBe(true);
    expect(blocked.item?.isBlocked).toBe(true);
    expect(blocked.item?.dailyLimitMinutes).toBe(0);

    const fallbackName = createAppLimit('com.game.app', '', 30, false);
    expect(fallbackName.isValid).toBe(true);
    expect(fallbackName.item?.appName).toBe('com.game.app');

    const invalid = createAppLimit('', 'Test', 30, false);
    expect(invalid.isValid).toBe(false);
    expect(invalid.error).toBe('Package name is required.');
  });

  test('removes app limit from list', () => {
    const list = [
      { packageName: 'com.whatsapp', appName: 'WhatsApp', dailyLimitMinutes: 30, isBlocked: false },
      { packageName: 'com.discord', appName: 'Discord', dailyLimitMinutes: 45, isBlocked: false },
    ];
    const updated = removeAppLimit(list, 'com.whatsapp');
    expect(updated).toHaveLength(1);
    expect(updated[0].packageName).toBe('com.discord');
  });
});

describe('Curfew Time Customization Formatters', () => {
  test('formats curfew times cleanly into 12-hour AM/PM format', () => {
    expect(formatCurfewTime(21, 0)).toBe('9:00 PM');
    expect(formatCurfewTime(7, 0)).toBe('7:00 AM');
    expect(formatCurfewTime(0, 30)).toBe('12:30 AM');
    expect(formatCurfewTime(12, 15)).toBe('12:15 PM');
    expect(formatCurfewTime(23, 5)).toBe('11:05 PM');
  });

  test('calculates duration in minutes across midnight', () => {
    // 21:00 to 07:00 -> 10 hours = 600 mins
    expect(calculateCurfewDurationMinutes(21, 0, 7, 0)).toBe(600);
    // 22:30 to 06:15 -> 7 hours 45 mins = 465 mins
    expect(calculateCurfewDurationMinutes(22, 30, 6, 15)).toBe(465);
    // 14:00 to 18:00 (daytime) -> 4 hours = 240 mins
    expect(calculateCurfewDurationMinutes(14, 0, 18, 0)).toBe(240);
  });

  test('formats curfew duration string cleanly', () => {
    expect(formatCurfewDuration(21, 0, 7, 0)).toBe('10 hours locked every night');
    expect(formatCurfewDuration(21, 30, 7, 0)).toBe('9 hours 30 mins locked every night');
  });
});

describe('Offline Emergency Recovery Key PIN Reset', () => {
  const masterKey = 'SHLD-9A4F-K82X-7M1P';

  test('resets PIN successfully with valid recovery key', () => {
    const res = verifyAndRecoverMasterPin(masterKey, 'shld-9a4f-k82x-7m1p', '5678');
    expect(res.success).toBe(true);
    expect(res.error).toBeUndefined();
  });

  test('rejects reset if recovery key is invalid', () => {
    const res = verifyAndRecoverMasterPin(masterKey, 'SHLD-0000-0000-0000', '5678');
    expect(res.success).toBe(false);
    expect(res.error).toBe('Invalid recovery key.');
  });

  test('rejects reset if recovery key length is invalid', () => {
    const res = verifyAndRecoverMasterPin(masterKey, 'SHLD-TOO-SHORT', '5678');
    expect(res.success).toBe(false);
    expect(res.error).toBe('Recovery Key must be 16 characters.');
  });

  test('rejects reset if new PIN format is invalid', () => {
    const res = verifyAndRecoverMasterPin(masterKey, masterKey, '12');
    expect(res.success).toBe(false);
    expect(res.error).toBe('PIN must be between 4 and 8 digits.');
  });
});

describe('Anti-Bypass Security Gate & Lock Screen Enforcement', () => {
  const PKG_SHIELD = 'com.shieldchild.app';
  const PKG_GAME = 'com.roblox.client';

  test('blocks parent dashboard access when parent is not authenticated', () => {
    expect(canAccessParentDashboard(false)).toBe(false);
    expect(canAccessParentDashboard(true)).toBe(true);
  });

  test('enforces lockdown on third-party apps during curfew', () => {
    expect(shouldEnforceLockdown(true, PKG_GAME, PKG_SHIELD, false)).toBe(true);
  });

  test('enforces lockdown even if child taps ShieldChild app without parent auth', () => {
    // Child taps ShieldChild during lockdown: must enforce lockdown because parent is NOT authenticated
    expect(shouldEnforceLockdown(true, PKG_SHIELD, PKG_SHIELD, false)).toBe(true);
  });

  test('allows ShieldChild app to open only when parent is authenticated', () => {
    expect(shouldEnforceLockdown(true, PKG_SHIELD, PKG_SHIELD, true)).toBe(false);
  });

  test('does not enforce lockdown when device is not locked', () => {
    expect(shouldEnforceLockdown(false, PKG_GAME, PKG_SHIELD, false)).toBe(false);
  });
});

describe('Reboot Lockdown Persistence & Anti-Bypass', () => {
  const defaultUnlimited = ['Sat', 'Sun'];

  test('re-enforces instant lock immediately upon reboot', () => {
    const res = evaluateRebootLockdown(
      true, // isInstantLocked
      false, // isCurfewScheduled
      21, 0, 7, 0,
      false, // isDailyLimitExceededToday
      true,
      'Wed',
      defaultUnlimited,
      14, 30
    );
    expect(res.isLocked).toBe(true);
    expect(res.reason).toBe('PARENT_INSTANT_LOCK');
  });

  test('re-enforces scheduled bedtime curfew immediately upon reboot during night', () => {
    const res = evaluateRebootLockdown(
      false,
      true, // isCurfewScheduled: 21:00 to 07:00
      21, 0, 7, 0,
      false,
      true,
      'Wed',
      defaultUnlimited,
      22, 15 // 10:15 PM
    );
    expect(res.isLocked).toBe(true);
    expect(res.reason).toBe('SCHEDULED_BEDTIME_CURFEW');
  });

  test('re-enforces daily screen time limit reached upon reboot', () => {
    const res = evaluateRebootLockdown(
      false,
      false,
      21, 0, 7, 0,
      true, // isDailyLimitExceededToday
      true, // isDailyLimitEnabled
      'Wed', // not an unlimited day
      defaultUnlimited,
      15, 0
    );
    expect(res.isLocked).toBe(true);
    expect(res.reason).toBe('TOTAL_SCREEN_TIME_EXCEEDED');
  });

  test('does not lock on reboot if day is in unlimitedDays', () => {
    const res = evaluateRebootLockdown(
      false,
      false,
      21, 0, 7, 0,
      true, // isDailyLimitExceededToday
      true,
      'Sat', // unlimited day!
      defaultUnlimited,
      15, 0
    );
    expect(res.isLocked).toBe(false);
  });

  test('does not lock on reboot if within normal allowances and daytime', () => {
    const res = evaluateRebootLockdown(
      false,
      true, // curfew 21:00 to 07:00
      21, 0, 7, 0,
      false,
      true,
      'Wed',
      defaultUnlimited,
      14, 0 // 2:00 PM
    );
    expect(res.isLocked).toBe(false);
  });
});

describe('Anti-Uninstall & Tamper Protection During Usage Time', () => {
  test('detects unauthorized attempt to uninstall via Package Installer when kid has screen time', () => {
    const isTamper = isUninstallOrTamperAttempt(
      'com.google.android.packageinstaller',
      ['Do you want to uninstall this app?', 'ShieldChild', 'Cancel', 'OK'],
      false // parent has NOT authorized uninstall
    );
    expect(isTamper).toBe(true);
  });

  test('detects attempt to force stop or clear cache in Android Settings', () => {
    const isTamper = isUninstallOrTamperAttempt(
      'com.android.settings',
      ['App info', 'ShieldChild', 'Force stop', 'Storage & cache'],
      false
    );
    expect(isTamper).toBe(true);
  });

  test('detects attempt to deactivate Device Admin or Accessibility in Settings', () => {
    const isTamper = isUninstallOrTamperAttempt(
      'com.android.settings.SubSettings',
      ['Device admin apps', 'ShieldChild', 'Deactivate this device admin app'],
      false
    );
    expect(isTamper).toBe(true);
  });

  test('detects attempt to uninstall from Google Play Store', () => {
    const isTamper = isUninstallOrTamperAttempt(
      'com.android.vending',
      ['ShieldChild Parental Control', 'Uninstall', 'Open'],
      false
    );
    expect(isTamper).toBe(true);
  });

  test('allows settings/uninstall screens when parent has explicitly authorized uninstall', () => {
    const isTamper = isUninstallOrTamperAttempt(
      'com.google.android.packageinstaller',
      ['Do you want to uninstall this app?', 'ShieldChild'],
      true // Parent unlocked and authorized
    );
    expect(isTamper).toBe(false);
  });

  test('ignores third-party apps like YouTube or games during normal daytime', () => {
    const isTamper = isUninstallOrTamperAttempt(
      'com.google.android.youtube',
      ['Home', 'Subscriptions', 'Library', 'Search'],
      false
    );
    expect(isTamper).toBe(false);
  });

  test('validates OS-level uninstallation blocked if Device Admin is active', () => {
    const check1 = canUninstallApp(true, false);
    expect(check1.allowed).toBe(false);
    expect(check1.reason).toContain('Device Administrator active');

    const check2 = canUninstallApp(false, false);
    expect(check2.allowed).toBe(false);
    expect(check2.reason).toContain('Uninstallation requires parent authentication');

    const check3 = canUninstallApp(true, true);
    expect(check3.allowed).toBe(true);
  });
});

describe('Temporary Curfew Extension & Override (+15m Bonus)', () => {
  const nightCurfewPolicy: PolicyInput = {
    dailyTotalLimitMinutes: 120,
    bonusMinutesToday: 15,
    isCurfewScheduled: true,
    curfewStartHour: 21,
    curfewStartMinute: 0,
    curfewEndHour: 7,
    curfewEndMinute: 0,
    isInstantLocked: false,
  };

  test('validates whether temporary curfew override timestamp is currently active', () => {
    const now = 1000000;
    const future = now + 15 * 60 * 1000;
    const past = now - 1000;

    expect(isCurfewOverrideValid(future, now)).toBe(true);
    expect(isCurfewOverrideValid(past, now)).toBe(false);
    expect(isCurfewOverrideValid(now, now)).toBe(false);
    expect(isCurfewOverrideValid(Date.now() + 60000)).toBe(true);
  });

  test('allows device usage during bedtime curfew when parent has granted an extension', () => {
    // 22:30 (during night curfew) without override -> LOCKED
    const lockedRes = evaluateDeviceLock(nightCurfewPolicy, 30, 22, 30, false);
    expect(lockedRes.state).toBe('LOCKED');
    if (lockedRes.state === 'LOCKED') {
      expect(lockedRes.reason).toBe('BEDTIME_CURFEW');
    }

    // 22:30 with parent extension active -> UNLOCKED
    const unlockedRes = evaluateDeviceLock(nightCurfewPolicy, 30, 22, 30, true);
    expect(unlockedRes.state).toBe('UNLOCKED');
    if (unlockedRes.state === 'UNLOCKED') {
      expect(unlockedRes.remainingMinutes).toBe(105); // (120 + 15) - 30 = 105
    }
  });

  test('instant freeze still takes absolute priority even if parent extension is active', () => {
    const freezePolicy = { ...nightCurfewPolicy, isInstantLocked: true };
    const res = evaluateDeviceLock(freezePolicy, 30, 22, 30, true);
    expect(res.state).toBe('LOCKED');
    if (res.state === 'LOCKED') {
      expect(res.reason).toBe('INSTANT_FREEZE');
    }
  });

  test('evaluateRebootLockdown unlocks device if parent extension is currently active', () => {
    const defaultUnlimited = ['Sun', 'Sat'];
    const res = evaluateRebootLockdown(
      false,
      true, // isCurfewScheduled: 21:00 to 07:00
      21, 0, 7, 0,
      false,
      true,
      'Wed',
      defaultUnlimited,
      22, 15, // 10:15 PM
      true // isOverrideActive
    );
    expect(res.isLocked).toBe(false);
  });
});




