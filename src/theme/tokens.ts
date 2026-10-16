/**
 * ShieldChild - "Warm Guardian" Design Tokens
 */
export const ColorTokens = {
  // Brand
  brand: {
    primary: '#2D60FF',
    onPrimary: '#FFFFFF',
    primaryContainer: '#E0EAFF',
    onPrimaryContainer: '#001C5E',
    glow: 'rgba(45, 96, 255, 0.25)',
  },
  // States
  state: {
    locked: '#E11D48',
    onLocked: '#FFFFFF',
    lockedContainer: '#FFE4E8',
    onLockedContainer: '#5C0B1B',
    warning: '#F59E0B',
    warningContainer: '#FEF3C7',
    success: '#10B981',
    successContainer: '#D1FAE5',
  },
  // Canvas & Surfaces
  surface: {
    canvasLight: '#F8FAFC',
    cardLight: '#FFFFFF',
    canvasDark: '#0F172A',
    cardDark: '#1E293B',
    overlayCurfew: 'rgba(9, 13, 22, 0.98)',
    overlayApp: 'rgba(15, 23, 42, 0.94)',
  },
  // Typography Colors
  text: {
    primaryLight: '#0F172A',
    secondaryLight: '#64748B',
    primaryDark: '#F8FAFC',
    secondaryDark: '#94A3B8',
  },
  // Border & Dividers
  border: {
    subtleLight: '#E2E8F0',
    subtleDark: '#334155',
  },
} as const;

export const Spacing = {
  xs: 4,
  sm: 8,
  md: 16,
  lg: 24,
  xl: 32,
  xxl: 48,
} as const;

export const Radius = {
  sm: 8,
  md: 16,
  lg: 24,
  squircle: 28, // Material You 28dp card squircle
  pill: 9999,
} as const;

/**
 * Material You (Material 3 Dynamic Theming - Monet Palette)
 */
export const MaterialYouTokens = {
  light: {
    primary: '#52643E',
    onPrimary: '#FFFFFF',
    primaryContainer: '#D5E8BF',
    onPrimaryContainer: '#111F03',
    secondary: '#596248',
    secondaryContainer: '#DDE6C6',
    tertiary: '#386667',
    tertiaryContainer: '#BCEBED',
    surface: '#F9FAEF',
    surfaceContainerLowest: '#FFFFFF',
    surfaceContainerLow: '#F3F4ED',
    surfaceContainer: '#EDEEE7',
    surfaceContainerHigh: '#E7E8E1',
    surfaceContainerHighest: '#E2E3DC',
    onSurface: '#1A1C16',
    onSurfaceVariant: '#45483D',
    error: '#BA1A1A',
    errorContainer: '#FFDAD9',
    outline: '#75786C',
    outlineVariant: '#C6C8B9',
  },
  dark: {
    primary: '#B8CCA4',
    onPrimary: '#253514',
    primaryContainer: '#3B4C28',
    onPrimaryContainer: '#D5E8BF',
    secondary: '#C1CAAAB',
    secondaryContainer: '#414A33',
    tertiary: '#A0CFD0',
    tertiaryContainer: '#1E4E4F',
    surface: '#12140E',
    surfaceContainerLowest: '#0D0F09',
    surfaceContainerLow: '#1A1C16',
    surfaceContainer: '#1E201A',
    surfaceContainerHigh: '#292B24',
    surfaceContainerHighest: '#33362E',
    onSurface: '#E2E3DC',
    onSurfaceVariant: '#C6C8B9',
    error: '#FFB4AB',
    errorContainer: '#93000A',
    outline: '#8F9285',
    outlineVariant: '#45483D',
  },
} as const;

