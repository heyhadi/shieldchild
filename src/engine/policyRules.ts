/**
 * Pure business logic and policy rule validation.
 * Shared and independently verifiable with unit tests.
 */

export interface PolicyInput {
  dailyTotalLimitMinutes: number;
  bonusMinutesToday: number;
  isCurfewScheduled: boolean;
  curfewStartHour: number;
  curfewStartMinute: number;
  curfewEndHour: number;
  curfewEndMinute: number;
  isInstantLocked: boolean;
}

export type LockEvaluationResult =
  | { state: 'UNLOCKED'; remainingMinutes: number }
  | { state: 'LOCKED'; reason: 'INSTANT_FREEZE' | 'BEDTIME_CURFEW' | 'DAILY_LIMIT_EXCEEDED'; usedMinutes: number };

/**
 * Evaluates whether a given time (hour & minute) falls inside the curfew schedule.
 * Correctly accounts for overnight schedules (e.g. 21:00 to 07:00).
 */
export function isWithinCurfewWindow(
  startH: number,
  startM: number,
  endH: number,
  endM: number,
  currentH: number,
  currentM: number
): boolean {
  const currentTotal = currentH * 60 + currentM;
  const startTotal = startH * 60 + startM;
  const endTotal = endH * 60 + endM;

  if (startTotal <= endTotal) {
    // Daytime window, e.g. 13:00 to 15:00
    return currentTotal >= startTotal && currentTotal < endTotal;
  } else {
    // Overnight window, e.g. 21:00 to 07:00
    return currentTotal >= startTotal || currentTotal < endTotal;
  }
}

/**
 * Calculates remaining screen time balance.
 */
export function calculateRemainingTime(
  dailyLimitMinutes: number,
  bonusMinutes: number,
  usedMinutes: number
): { effectiveLimit: number; remainingMinutes: number; isExceeded: boolean } {
  const effectiveLimit = Math.max(0, dailyLimitMinutes + bonusMinutes);
  const remaining = Math.max(0, effectiveLimit - usedMinutes);
  return {
    effectiveLimit,
    remainingMinutes: remaining,
    isExceeded: effectiveLimit > 0 && usedMinutes >= effectiveLimit,
  };
}

/**
 * Evaluates full-device lock state.
 */
export function evaluateDeviceLock(
  policy: PolicyInput,
  totalUsedMinutes: number,
  currentHour: number,
  currentMinute: number,
  isOverrideActive: boolean = false
): LockEvaluationResult {
  // 1. Instant manual lock
  if (policy.isInstantLocked) {
    return { state: 'LOCKED', reason: 'INSTANT_FREEZE', usedMinutes: totalUsedMinutes };
  }

  // If a parent approved an extension (e.g. +15m bonus), bypass curfew and quota locks
  if (isOverrideActive) {
    const { remainingMinutes } = calculateRemainingTime(
      policy.dailyTotalLimitMinutes,
      policy.bonusMinutesToday,
      totalUsedMinutes
    );
    return { state: 'UNLOCKED', remainingMinutes };
  }

  // 2. Scheduled bedtime curfew
  if (
    policy.isCurfewScheduled &&
    isWithinCurfewWindow(
      policy.curfewStartHour,
      policy.curfewStartMinute,
      policy.curfewEndHour,
      policy.curfewEndMinute,
      currentHour,
      currentMinute
    )
  ) {
    return { state: 'LOCKED', reason: 'BEDTIME_CURFEW', usedMinutes: totalUsedMinutes };
  }

  // 3. Daily quota exceeded
  const { remainingMinutes, isExceeded } = calculateRemainingTime(
    policy.dailyTotalLimitMinutes,
    policy.bonusMinutesToday,
    totalUsedMinutes
  );

  if (isExceeded) {
    return { state: 'LOCKED', reason: 'DAILY_LIMIT_EXCEEDED', usedMinutes: totalUsedMinutes };
  }

  return { state: 'UNLOCKED', remainingMinutes };
}

/**
 * Evaluates individual app lock.
 */
export function evaluateAppLock(
  dailyLimitMinutes: number,
  isBlocked: boolean,
  usedMinutes: number
): { isLocked: boolean; remainingMinutes: number } {
  if (isBlocked) {
    return { isLocked: true, remainingMinutes: 0 };
  }
  if (dailyLimitMinutes <= 0) {
    return { isLocked: false, remainingMinutes: Infinity };
  }
  const remaining = Math.max(0, dailyLimitMinutes - usedMinutes);
  return {
    isLocked: usedMinutes >= dailyLimitMinutes,
    remainingMinutes: remaining,
  };
}

/**
 * Formats minutes into human-readable duration, e.g. "2h 15m" or "45m".
 */
export function formatMinutes(totalMinutes: number): string {
  if (totalMinutes < 60) {
    return `${Math.max(0, Math.floor(totalMinutes))}m`;
  }
  const hours = Math.floor(totalMinutes / 60);
  const minutes = Math.floor(totalMinutes % 60);
  return minutes > 0 ? `${hours}h ${minutes}m` : `${hours}h`;
}

/**
 * Validates PIN format.
 */
export function validatePin(pin: string): { isValid: boolean; error?: string } {
  if (!/^\d+$/.test(pin)) {
    return { isValid: false, error: 'PIN must contain only numbers.' };
  }
  if (pin.length < 4 || pin.length > 8) {
    return { isValid: false, error: 'PIN must be between 4 and 8 digits.' };
  }
  return { isValid: true };
}

/**
 * Validates a 16-character alphanumeric emergency recovery key.
 * Expected format: 16 characters alphanumeric (ignoring hyphens), e.g. "SHLD-8821-XQ92-KM41"
 */
export function validateRecoveryKey(key: string): { isValid: boolean; error?: string } {
  const sanitized = key.replace(/[-\s]/g, '').toUpperCase();
  if (sanitized.length !== 16) {
    return { isValid: false, error: 'Recovery Key must be exactly 16 characters.' };
  }
  if (!/^[A-Z0-9]+$/.test(sanitized)) {
    return { isValid: false, error: 'Recovery Key must contain only letters and numbers.' };
  }
  return { isValid: true };
}

/**
 * Formats 16 raw characters into a chunked format: "XXXX-XXXX-XXXX-XXXX"
 */
export function formatRecoveryKey(raw: string): string {
  const clean = raw.replace(/[-\s]/g, '').toUpperCase().slice(0, 16);
  const chunks = clean.match(/.{1,4}/g);
  return chunks ? chunks.join('-') : clean;
}

/**
 * Validates child name.
 */
export function validateChildName(name: string): { isValid: boolean; error?: string } {
  const trimmed = name.trim();
  if (trimmed.length === 0) {
    return { isValid: false, error: "Child's name cannot be empty." };
  }
  if (trimmed.length > 30) {
    return { isValid: false, error: "Child's name cannot exceed 30 characters." };
  }
  return { isValid: true };
}

export interface AppLimitItem {
  packageName: string;
  appName: string;
  dailyLimitMinutes: number;
  isBlocked: boolean;
  usageTodayMinutes?: number;
}

/**
 * Filters installed applications list based on search query (by app name or package).
 */
export function filterInstalledApps<T extends { packageName: string; appName: string }>(
  apps: T[],
  query: string
): T[] {
  const clean = query.trim().toLowerCase();
  if (!clean) return apps;
  return apps.filter(
    (app) =>
      app.appName.toLowerCase().includes(clean) ||
      app.packageName.toLowerCase().includes(clean)
  );
}

/**
 * Validates and creates a new AppLimitItem.
 */
export function createAppLimit(
  packageName: string,
  appName: string,
  dailyLimitMinutes: number,
  isBlocked: boolean
): { isValid: boolean; item?: AppLimitItem; error?: string } {
  if (!packageName || packageName.trim().length === 0) {
    return { isValid: false, error: 'Package name is required.' };
  }
  const cleanName = appName.trim() || packageName;
  const validLimit = Math.max(0, Math.floor(dailyLimitMinutes));
  return {
    isValid: true,
    item: {
      packageName: packageName.trim(),
      appName: cleanName,
      dailyLimitMinutes: isBlocked ? 0 : validLimit,
      isBlocked,
      usageTodayMinutes: 0,
    },
  };
}

/**
 * Removes an app limit from a collection by its package name.
 */
export function removeAppLimit(limits: AppLimitItem[], packageName: string): AppLimitItem[] {
  return limits.filter((item) => item.packageName !== packageName);
}

/**
 * Formats hour and minute into 12-hour format string, e.g. "9:00 PM" or "7:00 AM".
 */
export function formatCurfewTime(hour: number, minute: number): string {
  const h = hour % 12 === 0 ? 12 : hour % 12;
  const amPm = hour < 12 ? 'AM' : 'PM';
  const minStr = minute < 10 ? `0${minute}` : `${minute}`;
  return `${h}:${minStr} ${amPm}`;
}

/**
 * Calculates curfew duration in minutes between start and end times, handling overnight boundaries.
 */
export function calculateCurfewDurationMinutes(
  startH: number,
  startM: number,
  endH: number,
  endM: number
): number {
  const startTotal = startH * 60 + startM;
  const endTotal = endH * 60 + endM;
  if (endTotal >= startTotal) {
    return endTotal - startTotal;
  }
  return 24 * 60 - startTotal + endTotal;
}

/**
 * Formats curfew duration in human readable format, e.g. "10 hours locked every night"
 */
export function formatCurfewDuration(
  startH: number,
  startM: number,
  endH: number,
  endM: number
): string {
  const totalMins = calculateCurfewDurationMinutes(startH, startM, endH, endM);
  const hours = Math.floor(totalMins / 60);
  const mins = totalMins % 60;
  if (mins > 0) {
    return `${hours} hours ${mins} mins locked every night`;
  }
  return `${hours} hours locked every night`;
}

/**
 * Validates recovery key and resets PIN offline.
 */
export function verifyAndRecoverMasterPin(
  storedMasterKey: string,
  enteredRecoveryKey: string,
  newPin: string
): { success: boolean; error?: string } {
  const pinValidation = validatePin(newPin);
  if (!pinValidation.isValid) {
    return { success: false, error: pinValidation.error };
  }
  const cleanStored = storedMasterKey.replace(/[-\s]/g, '').toUpperCase();
  const cleanEntered = enteredRecoveryKey.replace(/[-\s]/g, '').toUpperCase();
  if (cleanEntered.length !== 16) {
    return { success: false, error: 'Recovery Key must be 16 characters.' };
  }
  if (cleanStored !== cleanEntered) {
    return { success: false, error: 'Invalid recovery key.' };
  }
  return { success: true };
}

/**
 * Determines whether parent dashboard can be accessed directly or must be gated behind authentication.
 * Without authentication, parent controls can never be accessed.
 */
export function canAccessParentDashboard(isParentAuthenticated: boolean): boolean {
  return isParentAuthenticated;
}

/**
 * Determines if a foreground app must be immediately preempted by the curfew lock screen.
 * Even if the foreground app is the ShieldChild app itself, it cannot bypass the lock screen
 * unless the parent is actively authenticated.
 */
export function shouldEnforceLockdown(
  isDeviceLocked: boolean,
  foregroundPackage: string,
  shieldChildPackage: string,
  isParentAuthenticated: boolean
): boolean {
  if (!isDeviceLocked) {
    return false;
  }
  if (foregroundPackage === shieldChildPackage && isParentAuthenticated) {
    return false;
  }
  return true;
}

/**
 * Evaluates whether the device must immediately enter lockdown upon device reboot.
 * Prevents children from bypassing locks via forced phone restart.
 */
export function evaluateRebootLockdown(
  isInstantLocked: boolean,
  isCurfewScheduled: boolean,
  curfewStartHour: number,
  curfewStartMinute: number,
  curfewEndHour: number,
  curfewEndMinute: number,
  isDailyLimitExceededToday: boolean,
  isDailyLimitEnabled: boolean,
  currentDay: string,
  unlimitedDays: string[],
  currentHour: number,
  currentMinute: number,
  isOverrideActive: boolean = false
): { isLocked: boolean; reason?: 'PARENT_INSTANT_LOCK' | 'SCHEDULED_BEDTIME_CURFEW' | 'TOTAL_SCREEN_TIME_EXCEEDED' } {
  if (isInstantLocked) {
    return { isLocked: true, reason: 'PARENT_INSTANT_LOCK' };
  }
  if (isOverrideActive) {
    return { isLocked: false };
  }
  if (
    isCurfewScheduled &&
    isWithinCurfewWindow(curfewStartHour, curfewStartMinute, curfewEndHour, curfewEndMinute, currentHour, currentMinute)
  ) {
    return { isLocked: true, reason: 'SCHEDULED_BEDTIME_CURFEW' };
  }
  if (isDailyLimitExceededToday && isDailyLimitEnabled && !unlimitedDays.includes(currentDay)) {
    return { isLocked: true, reason: 'TOTAL_SCREEN_TIME_EXCEEDED' };
  }
  return { isLocked: false };
}

/**
 * Evaluates whether a temporary curfew override granted by parent is currently active.
 */
export function isCurfewOverrideValid(overrideUntilMs: number, currentTimeMs: number = Date.now()): boolean {
  return currentTimeMs < overrideUntilMs;
}

/**
 * Evaluates whether a foreground screen represents an unauthorized attempt to uninstall
 * or tamper with ShieldChild (in Settings, Package Installer, or App Stores) during normal daytime usage.
 */
export function isUninstallOrTamperAttempt(
  foregroundPackage: string,
  windowNodeTexts: string[],
  isParentUninstallAllowed: boolean
): boolean {
  if (isParentUninstallAllowed) {
    return false;
  }

  const normalizedPkg = foregroundPackage.toLowerCase();
  const isSettings = normalizedPkg === 'com.android.settings' || normalizedPkg.includes('.settings');
  const isPackageInstaller =
    normalizedPkg.includes('packageinstaller') ||
    normalizedPkg.includes('permissioncontroller') ||
    normalizedPkg.includes('appinstaller') ||
    normalizedPkg === 'com.android.vending';

  if (!isSettings && !isPackageInstaller) {
    return false;
  }

  // Check if any element in the active window hierarchy mentions ShieldChild
  const targetsShield = windowNodeTexts.some((text) =>
    text.toLowerCase().includes('shieldchild')
  );

  return targetsShield;
}

/**
 * Validates whether uninstallation is allowed at the OS level (Device Administrator check)
 * and parent authorization level.
 */
export function canUninstallApp(
  isDeviceAdminActive: boolean,
  isParentAuthorized: boolean
): { allowed: boolean; reason?: string } {
  if (isDeviceAdminActive && !isParentAuthorized) {
    return {
      allowed: false,
      reason: 'Device Administrator active. Uninstallation requires parent authentication.',
    };
  }
  if (!isParentAuthorized) {
    return {
      allowed: false,
      reason: 'Uninstallation requires parent authentication.',
    };
  }
  return { allowed: true };
}
