import { NativeModules, Platform } from 'react-native';

export interface UsageSummary {
  totalMinutes: number;
  perApp: Record<string, number>;
}

export interface CurfewPolicy {
  dailyTotalLimitMinutes: number;
  effectiveLimitMinutes: number;
  isCurfewScheduled: boolean;
  curfewStartHour: number;
  curfewStartMinute: number;
  curfewEndHour: number;
  curfewEndMinute: number;
  isInstantLocked: boolean;
  bonusMinutesToday: number;
  emergencyPhoneNumber: string;
}

export interface AppLimitItem {
  packageName: string;
  appName: string;
  dailyLimitMinutes: number;
  isBlocked: boolean;
}

export interface PermissionsStatus {
  usageStats: boolean;
  overlay: boolean;
  deviceAdmin: boolean;
  deviceOwner: boolean;
}

export interface PinVerifyResult {
  success: boolean;
  remainingAttempts?: number;
  lockedOutSeconds?: number;
}

const { ShieldBridge: NativeShieldBridge } = NativeModules;

export class ShieldBridgeService {
  static async getDailyUsage(): Promise<UsageSummary> {
    if (Platform.OS === 'android' && NativeShieldBridge?.getDailyUsage) {
      const raw = await NativeShieldBridge.getDailyUsage();
      return JSON.parse(raw);
    }
    // Mock for development / web previews
    return {
      totalMinutes: 130,
      perApp: {
        'com.google.android.youtube': 45,
        'com.zhiliaoapp.musically': 35,
        'com.roblox.client': 30,
        'com.mojang.minecraftpe': 20,
      },
    };
  }

  static async getCurfewPolicy(): Promise<CurfewPolicy> {
    if (Platform.OS === 'android' && NativeShieldBridge?.getCurfewPolicy) {
      const raw = await NativeShieldBridge.getCurfewPolicy();
      return JSON.parse(raw);
    }
    return {
      dailyTotalLimitMinutes: 150,
      effectiveLimitMinutes: 150,
      isCurfewScheduled: true,
      curfewStartHour: 21,
      curfewStartMinute: 0,
      curfewEndHour: 7,
      curfewEndMinute: 0,
      isInstantLocked: false,
      bonusMinutesToday: 0,
      emergencyPhoneNumber: '911',
    };
  }

  static async setDailyLimit(minutes: number): Promise<boolean> {
    if (Platform.OS === 'android' && NativeShieldBridge?.setDailyLimit) {
      return await NativeShieldBridge.setDailyLimit(minutes);
    }
    return true;
  }

  static async triggerInstantLock(locked: boolean): Promise<boolean> {
    if (Platform.OS === 'android' && NativeShieldBridge?.triggerInstantLock) {
      return await NativeShieldBridge.triggerInstantLock(locked);
    }
    return true;
  }

  static async addBonusMinutes(minutes: number): Promise<boolean> {
    if (Platform.OS === 'android' && NativeShieldBridge?.addBonusMinutes) {
      return await NativeShieldBridge.addBonusMinutes(minutes);
    }
    return true;
  }

  static async setAppLimit(item: AppLimitItem): Promise<boolean> {
    if (Platform.OS === 'android' && NativeShieldBridge?.setAppLimit) {
      return await NativeShieldBridge.setAppLimit(
        item.packageName,
        item.appName,
        item.dailyLimitMinutes,
        item.isBlocked
      );
    }
    return true;
  }

  static async verifyMasterPin(pin: string): Promise<PinVerifyResult> {
    if (Platform.OS === 'android' && NativeShieldBridge?.verifyMasterPin) {
      const raw = await NativeShieldBridge.verifyMasterPin(pin);
      return JSON.parse(raw);
    }
    return { success: pin === '1234' };
  }

  static async checkPermissions(): Promise<PermissionsStatus> {
    if (Platform.OS === 'android' && NativeShieldBridge?.checkPermissionsStatus) {
      const raw = await NativeShieldBridge.checkPermissionsStatus();
      return JSON.parse(raw);
    }
    return {
      usageStats: true,
      overlay: true,
      deviceAdmin: true,
      deviceOwner: false,
    };
  }

  static async authenticateWithBiometrics(): Promise<{ success: boolean; error?: string }> {
    if (Platform.OS === 'android' && NativeShieldBridge?.authenticateWithBiometrics) {
      const raw = await NativeShieldBridge.authenticateWithBiometrics();
      return JSON.parse(raw);
    }
    return { success: true };
  }
}
