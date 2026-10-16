package com.shieldchild.app.ui.dashboard

import com.shieldchild.app.i18n.str
import com.shieldchild.app.R
import com.shieldchild.app.ui.theme.Shield
import androidx.compose.runtime.saveable.rememberSaveable
import com.shieldchild.app.ui.components.ShieldDialog
import com.shieldchild.app.ui.components.PinField
import com.shieldchild.app.ui.components.DialogError
import com.shieldchild.app.ui.components.DialogOption
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.enableEdgeToEdge
import android.provider.Settings
import com.shieldchild.app.ui.onboarding.FirstTimeSetupScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import com.shieldchild.app.ShieldApplication
import com.shieldchild.app.data.AppLimit
import com.shieldchild.app.data.DeviceCurfewPolicy
import com.shieldchild.app.data.LockReason
import com.shieldchild.app.engine.AuthResult
import com.shieldchild.app.engine.BiometricAuthHelper
import com.shieldchild.app.engine.ParentAuthManager
import com.shieldchild.app.engine.AppDiscoveryManager
import com.shieldchild.app.engine.DiscoveredApp
import com.shieldchild.app.engine.UsageMonitor
import com.shieldchild.app.engine.PolicyEngine
import com.shieldchild.app.engine.RemoteCodeManager
import com.shieldchild.app.engine.Totp
import android.app.admin.DevicePolicyManager
import com.shieldchild.app.service.ShieldAccessibilityService
import com.shieldchild.app.service.ShieldDeviceAdminReceiver
import com.shieldchild.app.service.ShieldMonitorService
import com.shieldchild.app.ui.curfew.CurfewLockActivity
import com.shieldchild.app.ui.theme.ShieldChildTheme
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ParentDashboardActivity : FragmentActivity() {

    private lateinit var authManager: ParentAuthManager
    private lateinit var biometricHelper: BiometricAuthHelper

    override fun onResume() {
        super.onResume()
        // During a parent session the parent already signed in on the lock screen.
        if (ShieldApplication.instance.preferencesRepository.isParentSessionActive()) {
            isParentAuthenticatedState.value = true
        }
        checkIfLockedAndRedirect()
    }

    override fun onStop() {
        super.onStop()
        // A restart for a language switch isn't the parent leaving.
        if (!isChangingConfigurations) isParentAuthenticatedState.value = false
    }

    companion object {
        /**
         * Parent sign-in for the dashboard. Shared so the lock screen can sign the parent out:
         * whatever unlocks the phone afterwards (a code, extra time) must not reopen the dashboard.
         */
        val isParentAuthenticatedState = mutableStateOf(false)
    }

    private fun checkIfLockedAndRedirect() {
        val repo = ShieldApplication.instance.preferencesRepository
        if (repo.isSetupCompleted() && !isParentAuthenticatedState.value) {
            val policy = repo.getCurfewPolicy()
            val engine = PolicyEngine()
            val usedMinutes = UsageMonitor(this).queryDailyUsage().totalDeviceUsageMinutes
            val reason = engine.evaluateDeviceLock(
                policy = policy,
                isDailyLimitReached = engine.isDailyLimitReached(policy, usedMinutes),
                access = repo.getAccessState()
            )
            if (reason != null) {
                val intent = Intent(this, CurfewLockActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(CurfewLockActivity.EXTRA_LOCK_REASON, reason.name)
                }
                startActivity(intent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Ensure status bar & navigation bar icons are dark against light background
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)

        // Keeps the recovery key and PIN screens out of Recents thumbnails and screenshots.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        val repo = ShieldApplication.instance.preferencesRepository
        authManager = ParentAuthManager(repo)
        biometricHelper = BiometricAuthHelper(this)

        if (repo.isSetupCompleted()) {
            ShieldMonitorService.start(this)
        }

        setContent {
            ShieldChildTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Shield.colors.canvas // Ground background from specs
                ) {
                    var isSetupCompleted by remember { mutableStateOf(repo.isSetupCompleted()) }
                    var isParentAuthenticated by remember { isParentAuthenticatedState }

                    // Re-running setup changes the PIN, so it is only reachable while the parent
                    // is authenticated; leaving the app mid-way falls back to the PIN gate.
                    if (!repo.isSetupCompleted() || (!isSetupCompleted && isParentAuthenticated)) {
                        FirstTimeSetupScreen(
                            authManager = authManager,
                            biometricHelper = biometricHelper,
                            isBiometricHardwareAvailable = biometricHelper.isHardwareReady(),
                            onSetupFinished = {
                                isSetupCompleted = true
                                isParentAuthenticated = true
                                ShieldMonitorService.start(this@ParentDashboardActivity)
                            }
                        )
                    } else if (!isParentAuthenticated) {
                        ParentSecurityGateScreen(
                            authManager = authManager,
                            biometricHelper = biometricHelper,
                            isBiometricAvailable = biometricHelper.isBiometricReady(),
                            onAuthenticated = {
                                isParentAuthenticated = true
                            }
                        )
                    } else {
                        ReleaseParentDashboardScreen(
                            onEmergencyDial = { phone ->
                                val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                                    data = Uri.parse("tel:$phone")
                                }
                                startActivity(dialIntent)
                            },
                            onInstantLockActivated = {
                                val intent = Intent(this, CurfewLockActivity::class.java).apply {
                                    putExtra(CurfewLockActivity.EXTRA_LOCK_REASON, LockReason.PARENT_INSTANT_FREEZE.name)
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                }
                                startActivity(intent)
                            },
                            onRerunSetup = {
                                isSetupCompleted = false
                            },
                            authManager = authManager,
                            biometricHelper = biometricHelper
                        )
                    }
                }
            }
        }
    }
}

enum class ReleaseNavTab(val label: String, val icon: ImageVector) {
    OVERVIEW(str(R.string.overview), Icons.Default.Home),
    APP_LIMITS(str(R.string.app_limits), Icons.Default.GridView),
    CURFEW(str(R.string.curfew), Icons.Default.Schedule),
    SECURITY(str(R.string.security), Icons.Default.Shield)
}

@Composable
fun ReleaseParentDashboardScreen(
    onEmergencyDial: (String) -> Unit,
    onInstantLockActivated: () -> Unit,
    onRerunSetup: () -> Unit,
    authManager: ParentAuthManager,
    biometricHelper: BiometricAuthHelper
) {
    val repo = ShieldApplication.instance.preferencesRepository
    val context = androidx.compose.ui.platform.LocalContext.current
    val usageMonitor = remember { UsageMonitor(context) }
    var usageSummary by remember { mutableStateOf(usageMonitor.queryDailyUsage()) }
    var childName by remember { mutableStateOf(repo.getChildName()) }
    var selectedTab by remember { mutableStateOf(ReleaseNavTab.OVERVIEW) }
    var needsRecoveryKey by remember { mutableStateOf(!authManager.hasRecoveryKey()) }
    if (needsRecoveryKey) {
        // The parent just authenticated to get here, so no second PIN prompt.
        CreateRecoveryKeyDialog(
            authManager = authManager,
            requirePin = false,
            onDone = { needsRecoveryKey = false }
        )
    }
    var policy by remember { mutableStateOf(repo.getCurfewPolicy()) }
    var appLimitsMap by remember { mutableStateOf(repo.getAppLimits()) }

    LaunchedEffect(selectedTab) {
        withContext(Dispatchers.IO) {
            usageSummary = usageMonitor.queryDailyUsage()
        }
    }

    // Design Tokens from Spec
    val colBg = Shield.colors.canvas
    val colPrimary = Shield.colors.primary
    val colPrimaryContainer = Shield.colors.primaryContainer
    val colOnPrimaryContainer = Shield.colors.onPrimaryContainer
    val colInk = Shield.colors.ink
    val colInkMuted = Shield.colors.inkMuted
    val colLineSubtle = Shield.colors.line

    var showSettings by rememberSaveable { mutableStateOf(false) }
    if (showSettings) {
        com.shieldchild.app.ui.settings.SettingsScreen(onBack = { showSettings = false })
        return
    }

    Scaffold(
        containerColor = colBg,
        topBar = {
            // Header Bar with comfortable breathing room below status bar
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colBg)
                    .statusBarsPadding()
            ) {
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Shield Badge
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = colPrimaryContainer,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Shield,
                                contentDescription = null,
                                tint = colPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = str(R.string.s_phone, childName),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = colInk,
                            letterSpacing = (-0.3).sp
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (policy.isInstantLocked) Shield.colors.warning else Shield.colors.primary)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (policy.isInstantLocked) str(R.string.locked_now) else str(R.string.status_protected),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (policy.isInstantLocked) Shield.colors.onWarningContainer else Shield.colors.primary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = { showSettings = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Settings, contentDescription = str(R.string.settings), tint = colInkMuted)
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }
        },
        bottomBar = {
            // Clean Bottom Navigation Bar with navigation bar insets
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Shield.colors.card,
                border = androidx.compose.foundation.BorderStroke(1.dp, colLineSubtle)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .height(78.dp)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ReleaseNavTab.values().forEach { tab ->
                        val isSelected = selectedTab == tab
                        val interactionSource = remember { MutableInteractionSource() }
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(18.dp))
                                .clickable(
                                    interactionSource = interactionSource,
                                    indication = null
                                ) { selectedTab = tab }
                                .padding(vertical = 2.dp)
                        ) {
                            if (isSelected) {
                                Surface(
                                    shape = RoundedCornerShape(18.dp),
                                    color = colPrimaryContainer,
                                    modifier = Modifier.size(width = 68.dp, height = 36.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            tab.icon,
                                            contentDescription = tab.label,
                                            tint = colOnPrimaryContainer,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                            } else {
                                Box(
                                    modifier = Modifier.size(width = 68.dp, height = 36.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        tab.icon,
                                        contentDescription = tab.label,
                                        tint = colInkMuted,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = tab.label,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                                color = if (isSelected) colOnPrimaryContainer else colInkMuted
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                ReleaseNavTab.OVERVIEW -> ReleaseOverviewScreen(
                    policy = policy,
                    appLimits = appLimitsMap.values.map { app ->
                        val used = usageSummary.perAppUsageMinutes[app.packageName] ?: 0L
                        app.copy(usageTodayMinutes = used)
                    },
                    usedMinutesToday = usageSummary.totalDeviceUsageMinutes,
                    onNavigateToAppLimits = { selectedTab = ReleaseNavTab.APP_LIMITS },
                    onAddBonus = { minutes ->
                        repo.addBonusMinutes(minutes)
                        policy = repo.getCurfewPolicy()
                    },
                    onRemoveBonus = {
                        repo.saveCurfewPolicy(policy.copy(bonusMinutesToday = 0))
                        policy = repo.getCurfewPolicy()
                    },
                    onToggleInstantLock = { isLocked ->
                        repo.setInstantLock(isLocked)
                        policy = repo.getCurfewPolicy()
                        if (isLocked) {
                            onInstantLockActivated()
                        }
                    }
                )

                ReleaseNavTab.APP_LIMITS -> ReleaseAppLimitsScreen(
                    appLimits = appLimitsMap.values.map { app ->
                        val used = usageSummary.perAppUsageMinutes[app.packageName] ?: 0L
                        app.copy(usageTodayMinutes = used)
                    },
                    onUpdateLimit = { updated ->
                        repo.setAppLimit(updated)
                        appLimitsMap = repo.getAppLimits()
                    },
                    onDeleteLimit = { pkg ->
                        repo.removeAppLimit(pkg)
                        appLimitsMap = repo.getAppLimits()
                    }
                )

                ReleaseNavTab.CURFEW -> ReleaseCurfewScreen(
                    policy = policy,
                    childName = childName,
                    onUpdatePolicy = { updated ->
                        repo.saveCurfewPolicy(updated)
                        policy = repo.getCurfewPolicy()
                    },
                    onEmergencyDial = onEmergencyDial
                )

                ReleaseNavTab.SECURITY -> ReleaseSecurityScreen(
                    authManager = authManager,
                    biometricHelper = biometricHelper,
                    childName = childName,
                    onChildNameChanged = { newName ->
                        childName = newName
                    },
                    onRerunSetup = onRerunSetup
                )
            }
        }
    }
}

/* =========================================================================
   1. OVERVIEW SCREEN (Pixel-perfect matching HTML mockup)
   ========================================================================= */

@Composable
fun ReleaseOverviewScreen(
    policy: DeviceCurfewPolicy,
    appLimits: List<AppLimit>,
    usedMinutesToday: Long,
    onNavigateToAppLimits: () -> Unit,
    onAddBonus: (Long) -> Unit,
    onRemoveBonus: () -> Unit,
    onToggleInstantLock: (Boolean) -> Unit
) {
    val effectiveLimit = policy.effectiveDailyLimitMinutes
    val isUnlimitedToday = policy.isWholeDayAllowed(PolicyEngine().dayName(java.util.Calendar.getInstance()))
    val remainingMinutes = if (effectiveLimit > usedMinutesToday) effectiveLimit - usedMinutesToday else 0L
    val progress = if (isUnlimitedToday) 1f else if (effectiveLimit > 0) (remainingMinutes.toFloat() / effectiveLimit.toFloat()).coerceIn(0f, 1f) else 1f

    val colPrimary = Shield.colors.primary
    val colPrimaryContainer = Shield.colors.primaryContainer
    val colOnPrimaryContainer = Shield.colors.onPrimaryContainer
    val colInk = Shield.colors.ink
    val colInkMuted = Shield.colors.inkMuted
    val colLineSubtle = Shield.colors.line

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 10.dp, bottom = 24.dp)
    ) {
        // Hero: Screen time today Card
        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Shield.colors.card,
                border = androidx.compose.foundation.BorderStroke(1.dp, colLineSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    // Top header: Title & Optional Bonus pill
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = str(R.string.screen_time_today),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = colInk
                        )

                        if (policy.bonusMinutesToday > 0) {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = colPrimaryContainer,
                                modifier = Modifier
                                    .height(40.dp)
                                    .clickable { onRemoveBonus() }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(start = 14.dp, end = 10.dp)
                                ) {
                                    Text(
                                        text = str(R.string.m_bonus, policy.bonusMinutesToday),
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = colOnPrimaryContainer
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = str(R.string.remove_bonus),
                                        tint = colOnPrimaryContainer,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Circular Gauge & Stats
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(22.dp)
                    ) {
                        // Exact 124dp Circular Progress Ring from spec
                        Box(
                            modifier = Modifier.size(124.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(modifier = Modifier.size(124.dp)) {
                                val strokeWidth = 12.dp.toPx()
                                val diameter = size.minDimension - strokeWidth
                                val topLeft = Offset(strokeWidth / 2, strokeWidth / 2)
                                val arcSize = Size(diameter, diameter)

                                // Background subtle ring track
                                drawArc(
                                    color = colLineSubtle,
                                    startAngle = -90f,
                                    sweepAngle = 360f,
                                    useCenter = false,
                                    topLeft = topLeft,
                                    size = arcSize,
                                    style = Stroke(width = strokeWidth)
                                )

                                // Primary Green Arc
                                val sweep = (progress * 360f).coerceIn(0f, 360f)
                                drawArc(
                                    color = colPrimary,
                                    startAngle = -90f,
                                    sweepAngle = sweep,
                                    useCenter = false,
                                    topLeft = topLeft,
                                    size = arcSize,
                                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                                )
                            }

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = if (isUnlimitedToday) str(R.string.no_limit) else str(R.string.h_m, remainingMinutes / 60, remainingMinutes % 60),
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = colInk,
                                    letterSpacing = (-0.5).sp
                                )
                                Text(
                                    text = if (isUnlimitedToday) "today" else str(R.string.left_today),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colInkMuted
                                )
                            }
                        }

                        // Right side stats: Used and Daily allowance
                        Column(
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column {
                                Text(str(R.string.used), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = colInkMuted)
                                Text(
                                    text = str(R.string.h_m, usedMinutesToday / 60, usedMinutesToday % 60),
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = colInk
                                )
                            }
                            Column {
                                Text(str(R.string.daily_allowance), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = colInkMuted)
                                Text(
                                    text = str(R.string.h_m, effectiveLimit / 60, effectiveLimit % 60),
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = colInk
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // 15 min & 30 min quick action pills
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { onAddBonus(15L) },
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colPrimaryContainer,
                                contentColor = colOnPrimaryContainer
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(str(R.string.t_15_min), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = { onAddBonus(30L) },
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colPrimaryContainer,
                                contentColor = colOnPrimaryContainer
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(str(R.string.t_30_min), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Bedtime lock footer exactly from spec
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(androidx.compose.foundation.BorderStroke(1.dp, colLineSubtle))
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp)
                    ) {
                        Icon(
                            Icons.Default.Bedtime,
                            contentDescription = null,
                            tint = colInkMuted,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (policy.isCurfewScheduled) {
                                str(R.string.bedtime_lock_9_00_pm, clockText(policy.curfewStartHour, policy.curfewStartMinute), clockText(policy.curfewEndHour, policy.curfewEndMinute))
                            } else {
                                str(R.string.bedtime_lock_off)
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colInkMuted
                        )
                    }
                }
            }
        }

        // Instant Lock Card (Amber active state from spec)
        item {
            val isLocked = policy.isInstantLocked
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isLocked) Shield.colors.warningContainer else Shield.colors.card,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isLocked) Shield.colors.warning.copy(alpha = 0.4f) else colLineSubtle
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (isLocked) Shield.colors.warning else Shield.colors.surfaceVariant,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = if (isLocked) Shield.colors.onPrimary else colPrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(
                                text = str(R.string.instant_lock),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (isLocked) Shield.colors.onWarningContainer else colInk
                            )
                            Text(
                                text = if (isLocked) str(R.string.phone_is_locked_right_now) else str(R.string.lock_phone_immediately),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (isLocked) Shield.colors.onWarningContainer else colInkMuted
                            )
                        }
                    }

                    Switch(
                        checked = isLocked,
                        onCheckedChange = { onToggleInstantLock(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Shield.colors.onPrimary,
                            checkedTrackColor = Shield.colors.warning,
                            uncheckedThumbColor = Shield.colors.outline,
                            uncheckedTrackColor = Shield.colors.surfaceVariant,
                            uncheckedBorderColor = Shield.colors.outline
                        )
                    )
                }
            }
        }

        // Section Title: Apps today
        item {
            Text(
                text = str(R.string.apps_today),
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold,
                color = colInk,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
            )
        }

        // Apps today grouped container card
        item {
            if (appLimits.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Shield.colors.card,
                    border = androidx.compose.foundation.BorderStroke(1.dp, colLineSubtle),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToAppLimits() }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = colPrimaryContainer,
                            modifier = Modifier.size(42.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = null,
                                    tint = colPrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = str(R.string.no_app_limits_set_yet),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = colInk
                            )
                            Text(
                                text = str(R.string.tap_to_choose_games_social),
                                fontSize = 12.5.sp,
                                color = colInkMuted
                            )
                        }
                        Icon(
                            Icons.Default.ArrowForward,
                            contentDescription = null,
                            tint = colPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Shield.colors.card,
                    border = androidx.compose.foundation.BorderStroke(1.dp, colLineSubtle),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        appLimits.take(4).forEachIndexed { index, app ->
                            if (index > 0) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(1.dp)
                                        .background(colLineSubtle)
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(64.dp)
                                    .padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    AppIconView(
                                        packageName = app.packageName,
                                        appName = app.appName,
                                        isBlocked = app.isBlocked
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = app.appName,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = colInk,
                                            maxLines = 1
                                        )
                                        Text(
                                            text = if (app.isBlocked) str(R.string.no_access) else str(R.string.m_used_of_m, app.usageTodayMinutes, app.dailyLimitMinutes),
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = colInkMuted
                                        )
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (app.isBlocked) Shield.colors.errorContainer else Shield.colors.surfaceVariant
                                ) {
                                    Text(
                                        text = if (app.isBlocked) str(R.string.blocked) else str(R.string.m_limit, app.dailyLimitMinutes),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (app.isBlocked) Shield.colors.error else colOnPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }

                        // Footer row to manage all app limits
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(colLineSubtle)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onNavigateToAppLimits() }
                                .padding(vertical = 13.dp, horizontal = 16.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = str(R.string.manage_all_app_limits, appLimits.size),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = colPrimary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                Icons.Default.ArrowForward,
                                contentDescription = null,
                                tint = colPrimary,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AppIconView(
    packageName: String,
    appName: String,
    isBlocked: Boolean,
    modifier: Modifier = Modifier.size(40.dp)
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val discovery = remember { AppDiscoveryManager(context) }
    val iconBitmap = remember(packageName) {
        val drawable = discovery.getAppIcon(packageName)
        drawable?.let { d ->
            try {
                val w = d.intrinsicWidth.coerceIn(1, 192)
                val h = d.intrinsicHeight.coerceIn(1, 192)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                d.setBounds(0, 0, c.width, c.height)
                d.draw(c)
                bmp.asImageBitmap()
            } catch (_: Exception) {
                null
            }
        }
    }

    if (iconBitmap != null) {
        Image(
            bitmap = iconBitmap,
            contentDescription = appName,
            modifier = modifier.clip(RoundedCornerShape(10.dp))
        )
    } else {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = if (isBlocked) Shield.colors.errorContainer else Shield.colors.surfaceVariant,
            modifier = modifier
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    when {
                        appName.contains("YouTube", ignoreCase = true) -> Icons.Default.PlayArrow
                        appName.contains("TikTok", ignoreCase = true) || appName.contains("Music", ignoreCase = true) -> Icons.Default.MusicNote
                        appName.contains("Chrome", ignoreCase = true) || appName.contains("Browser", ignoreCase = true) -> Icons.Default.Language
                        appName.contains("Chat", ignoreCase = true) || appName.contains("WhatsApp", ignoreCase = true) -> Icons.Default.Chat
                        else -> Icons.Default.SportsEsports
                    },
                    contentDescription = null,
                    tint = if (isBlocked) Shield.colors.error else Shield.colors.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/* =========================================================================
   2. APP LIMITS SCREEN (With Add App, Real Package Discovery, & Delete)
   ========================================================================= */

@Composable
fun ReleaseAppLimitsScreen(
    appLimits: List<AppLimit>,
    onUpdateLimit: (AppLimit) -> Unit,
    onDeleteLimit: (String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val discoveryManager = remember { AppDiscoveryManager(context) }
    var installedApps by remember { mutableStateOf<List<DiscoveredApp>>(emptyList()) }
    var isLoadingApps by remember { mutableStateOf(true) }

    var showAddAppDialog by remember { mutableStateOf(false) }
    var appToConfigure by remember { mutableStateOf<DiscoveredApp?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var configureLimitMinutes by remember { mutableStateOf(45L) }
    var configureIsBlocked by remember { mutableStateOf(false) }

    var appToDeleteConfirm by remember { mutableStateOf<AppLimit?>(null) }

    val colInk = Shield.colors.ink
    val colInkMuted = Shield.colors.inkMuted
    val colPrimary = Shield.colors.primary
    val colPrimaryContainer = Shield.colors.primaryContainer
    val colOnPrimaryContainer = Shield.colors.onPrimaryContainer
    val colLineSubtle = Shield.colors.line

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            installedApps = discoveryManager.getInstalledLaunchableApps()
            isLoadingApps = false
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 10.dp, bottom = 24.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = str(R.string.app_limits),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = colInk
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = str(R.string.set_daily_limits_or_block),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = colInkMuted
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // + Add App Button
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = colPrimary,
                    modifier = Modifier
                        .height(38.dp)
                        .clickable {
                            searchQuery = ""
                            appToConfigure = null
                            showAddAppDialog = true
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = str(R.string.add_app),
                            tint = Shield.colors.onPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = str(R.string.add_app),
                            color = Shield.colors.onPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        if (appLimits.isEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Shield.colors.card,
                    border = androidx.compose.foundation.BorderStroke(1.dp, colLineSubtle),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = colPrimaryContainer,
                            modifier = Modifier.size(56.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.GridView,
                                    contentDescription = null,
                                    tint = colPrimary,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = str(R.string.no_app_limits_set),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = colInk
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = str(R.string.add_specific_games_social_media),
                            fontSize = 13.sp,
                            color = colInkMuted,
                            textAlign = TextAlign.Center,
                            lineHeight = 18.sp
                        )
                        Spacer(modifier = Modifier.height(18.dp))
                        Button(
                            onClick = {
                                searchQuery = ""
                                appToConfigure = null
                                showAddAppDialog = true
                            },
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = colPrimary)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(str(R.string.add_first_app), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        } else {
            items(appLimits) { app ->
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Shield.colors.card,
                    border = androidx.compose.foundation.BorderStroke(1.dp, colLineSubtle),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        // Header Row with App Icon, Name, Subtitle, Switch, and Delete
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                AppIconView(
                                    packageName = app.packageName,
                                    appName = app.appName,
                                    isBlocked = app.isBlocked
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = app.appName,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = colInk,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = if (app.isBlocked) str(R.string.blocked_completely) else str(R.string.m_used_m_limit, app.usageTodayMinutes, app.dailyLimitMinutes),
                                        fontSize = 12.5.sp,
                                        fontWeight = if (app.isBlocked) FontWeight.SemiBold else FontWeight.Medium,
                                        color = if (app.isBlocked) Shield.colors.error else colInkMuted
                                    )
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Switch(
                                    checked = !app.isBlocked,
                                    onCheckedChange = { isAllowed ->
                                        // Blocked apps saved by v1.0.0 have limit 0, which would mean "unlimited" once unblocked.
                                        val limit = if (app.dailyLimitMinutes > 0) app.dailyLimitMinutes else 45L
                                        onUpdateLimit(app.copy(isBlocked = !isAllowed, dailyLimitMinutes = limit))
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Shield.colors.onPrimary,
                                        checkedTrackColor = colPrimary,
                                        uncheckedThumbColor = Shield.colors.outline,
                                        uncheckedTrackColor = Shield.colors.surfaceVariant,
                                        uncheckedBorderColor = Shield.colors.outline
                                    )
                                )

                                IconButton(
                                    onClick = { appToDeleteConfirm = app },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.Default.DeleteOutline,
                                        contentDescription = str(R.string.remove_limit),
                                        tint = Shield.colors.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }

                        // Interactive Slider if app is allowed
                        if (!app.isBlocked) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Slider(
                                    value = app.dailyLimitMinutes.coerceAtLeast(15L).toFloat(),
                                    onValueChange = { newLimit ->
                                        onUpdateLimit(app.copy(dailyLimitMinutes = newLimit.toLong()))
                                    },
                                    valueRange = 15f..180f,
                                    steps = 10,
                                    modifier = Modifier.weight(1f),
                                    colors = SliderDefaults.colors(
                                        thumbColor = colPrimary,
                                        activeTrackColor = colPrimary,
                                        inactiveTrackColor = colLineSubtle
                                    )
                                )
                                Text(
                                    text = str(R.string.min, app.dailyLimitMinutes),
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = colInk
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Add App Limit Dialog
    if (showAddAppDialog) {
        Dialog(onDismissRequest = { showAddAppDialog = false; appToConfigure = null }) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Shield.colors.card,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 580.dp)
            ) {
                if (appToConfigure == null) {
                    // Step A: App Picker from Installed Apps
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = str(R.string.add_application_limit),
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = colInk
                                )
                                Text(
                                    text = str(R.string.choose_an_installed_app_on),
                                    fontSize = 12.5.sp,
                                    color = colInkMuted
                                )
                            }
                            IconButton(
                                onClick = { showAddAppDialog = false },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = str(R.string.close), tint = colInkMuted)
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Search Input Field
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text(str(R.string.search_installed_apps), fontSize = 13.sp) },
                            leadingIcon = {
                                Icon(Icons.Default.Search, contentDescription = null, tint = colInkMuted, modifier = Modifier.size(20.dp))
                            },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Default.Close, contentDescription = str(R.string.clear), modifier = Modifier.size(16.dp))
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = colPrimary,
                                unfocusedBorderColor = colLineSubtle,
                                focusedContainerColor = Shield.colors.cardSubtle,
                                unfocusedContainerColor = Shield.colors.cardSubtle
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        val filteredApps = remember(installedApps, searchQuery) {
                            if (searchQuery.trim().isEmpty()) {
                                installedApps
                            } else {
                                val q = searchQuery.trim().lowercase()
                                installedApps.filter {
                                    it.appName.lowercase().contains(q) || it.packageName.lowercase().contains(q)
                                }
                            }
                        }

                        if (isLoadingApps) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = colPrimary)
                            }
                        } else if (filteredApps.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = str(R.string.no_apps_found_matching, searchQuery),
                                    fontSize = 13.sp,
                                    color = colInkMuted
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f, fill = false),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(filteredApps) { item ->
                                    val isAlreadyAdded = appLimits.any { it.packageName == item.packageName }
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = if (isAlreadyAdded) Shield.colors.cardSubtle else Shield.colors.card,
                                        border = androidx.compose.foundation.BorderStroke(1.dp, colLineSubtle),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = !isAlreadyAdded) {
                                                appToConfigure = item
                                                configureLimitMinutes = 45L
                                                configureIsBlocked = false
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                val iconBmp = item.getIconBitmap()
                                                if (iconBmp != null) {
                                                    Image(
                                                        bitmap = iconBmp,
                                                        contentDescription = item.appName,
                                                        modifier = Modifier
                                                            .size(36.dp)
                                                            .clip(RoundedCornerShape(8.dp))
                                                    )
                                                } else {
                                                    Surface(
                                                        shape = RoundedCornerShape(8.dp),
                                                        color = Shield.colors.surfaceVariant,
                                                        modifier = Modifier.size(36.dp)
                                                    ) {
                                                        Box(contentAlignment = Alignment.Center) {
                                                            Icon(
                                                                Icons.Default.SportsEsports,
                                                                contentDescription = null,
                                                                tint = colPrimary,
                                                                modifier = Modifier.size(18.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                                Spacer(modifier = Modifier.width(10.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = item.appName,
                                                        fontSize = 14.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (isAlreadyAdded) colInkMuted else colInk,
                                                        maxLines = 1
                                                    )
                                                    Text(
                                                        text = item.packageName,
                                                        fontSize = 11.sp,
                                                        color = Shield.colors.outline,
                                                        maxLines = 1
                                                    )
                                                }
                                            }

                                            if (isAlreadyAdded) {
                                                Surface(
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = Shield.colors.line
                                                ) {
                                                    Text(
                                                        text = str(R.string.added),
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = Shield.colors.inkMuted,
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                    )
                                                }
                                            } else {
                                                Surface(
                                                    shape = RoundedCornerShape(12.dp),
                                                    color = colPrimaryContainer
                                                ) {
                                                    Text(
                                                        text = str(R.string.select),
                                                        fontSize = 11.5.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = colOnPrimaryContainer,
                                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Step B: Configure limit for selected app
                    val selected = appToConfigure!!
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(22.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val bmp = selected.getIconBitmap()
                            if (bmp != null) {
                                Image(
                                    bitmap = bmp,
                                    contentDescription = selected.appName,
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                )
                            } else {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = colPrimaryContainer,
                                    modifier = Modifier.size(46.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.SportsEsports, contentDescription = null, tint = colPrimary, modifier = Modifier.size(24.dp))
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column {
                                Text(
                                    text = selected.appName,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = colInk
                                )
                                Text(
                                    text = selected.packageName,
                                    fontSize = 11.5.sp,
                                    color = colInkMuted
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        // Block completely toggle
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (configureIsBlocked) Shield.colors.errorContainer else Shield.colors.cardSubtle,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(
                                        text = str(R.string.block_completely),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (configureIsBlocked) Shield.colors.error else colInk
                                    )
                                    Text(
                                        text = str(R.string.child_cannot_open_this_app),
                                        fontSize = 12.sp,
                                        color = if (configureIsBlocked) Shield.colors.error else colInkMuted
                                    )
                                }
                                Switch(
                                    checked = configureIsBlocked,
                                    onCheckedChange = { configureIsBlocked = it },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Shield.colors.onPrimary,
                                        checkedTrackColor = Shield.colors.error
                                    )
                                )
                            }
                        }

                        if (!configureIsBlocked) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = str(R.string.daily_time_limit_m, configureLimitMinutes),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = colInk
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            // Quick preset chips
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(15L, 30L, 45L, 60L, 90L, 120L).forEach { mins ->
                                    val isSelected = configureLimitMinutes == mins
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (isSelected) colPrimary else Shield.colors.surfaceVariant,
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { configureLimitMinutes = mins }
                                    ) {
                                        Text(
                                            text = str(R.string.min, mins),
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) Shield.colors.onPrimary else colInk,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(vertical = 8.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Slider(
                                value = configureLimitMinutes.toFloat(),
                                onValueChange = { configureLimitMinutes = it.toLong() },
                                valueRange = 15f..180f,
                                steps = 10,
                                colors = SliderDefaults.colors(
                                    thumbColor = colPrimary,
                                    activeTrackColor = colPrimary
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        // Action Buttons: Back / Save Limit
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = { appToConfigure = null },
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(str(R.string.back), color = colInk)
                            }

                            Button(
                                onClick = {
                                    onUpdateLimit(
                                        AppLimit(
                                            packageName = selected.packageName,
                                            appName = selected.appName,
                                            dailyLimitMinutes = configureLimitMinutes,
                                            isBlocked = configureIsBlocked,
                                            usageTodayMinutes = 0L
                                        )
                                    )
                                    showAddAppDialog = false
                                    appToConfigure = null
                                },
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = colPrimary),
                                modifier = Modifier.weight(1.3f)
                            ) {
                                Text(str(R.string.save_limit), color = Shield.colors.onPrimary, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }

    if (appToDeleteConfirm != null) {
        val target = appToDeleteConfirm!!
        ShieldDialog(
            icon = Icons.Default.Delete,
            title = str(R.string.remove_app_limit),
            message = str(R.string.your_child_will_be_able, target.appName),
            onDismiss = { appToDeleteConfirm = null },
            confirmText = str(R.string.remove_limit),
            destructive = true,
            onConfirm = {
                onDeleteLimit(target.packageName)
                appToDeleteConfirm = null
            }
        )
    }
}

/* =========================================================================
   3. CURFEW SCREEN (Pixel-perfect matching HTML mockup)
   ========================================================================= */

@Composable
fun ReleaseCurfewScreen(
    policy: DeviceCurfewPolicy,
    childName: String,
    onUpdatePolicy: (DeviceCurfewPolicy) -> Unit,
    onEmergencyDial: (String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var emergencyPhone by remember { mutableStateOf(policy.emergencyPhoneNumber) }

    val colInk = Shield.colors.ink
    val colInkMuted = Shield.colors.inkMuted
    val colPrimary = Shield.colors.primary
    val colPrimaryContainer = Shield.colors.primaryContainer
    val colOnPrimaryContainer = Shield.colors.onPrimaryContainer
    val colLineSubtle = Shield.colors.line

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 10.dp, bottom = 24.dp)
    ) {
        item {
            Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
                Text(
                    text = str(R.string.curfew),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = colInk
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = str(R.string.daily_limit_bedtime_and_emergency),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = colInkMuted
                )
            }
        }

        // Daily screen time card
        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Shield.colors.card,
                border = androidx.compose.foundation.BorderStroke(1.dp, colLineSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = str(R.string.daily_screen_time),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = colInk
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (policy.isDailyLimitEnabled) str(R.string.total_phone_use_allowed_per) else str(R.string.daily_limit_disabled_unlimited_allowance),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = colInkMuted
                            )
                        }

                        Switch(
                            checked = policy.isDailyLimitEnabled,
                            onCheckedChange = { onUpdatePolicy(policy.copy(isDailyLimitEnabled = it)) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Shield.colors.onPrimary,
                                checkedTrackColor = colPrimary,
                                uncheckedThumbColor = Shield.colors.outline,
                                uncheckedTrackColor = Shield.colors.surfaceVariant,
                                uncheckedBorderColor = Shield.colors.outline
                            )
                        )
                    }

                    if (policy.isDailyLimitEnabled) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = str(R.string.h_m, policy.dailyTotalLimitMinutes / 60, policy.dailyTotalLimitMinutes % 60),
                            fontSize = 30.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = colPrimary
                        )
                        Slider(
                            value = policy.dailyTotalLimitMinutes.toFloat(),
                            onValueChange = { newLimit ->
                                onUpdatePolicy(policy.copy(dailyTotalLimitMinutes = newLimit.toLong()))
                            },
                            valueRange = 30f..360f,
                            steps = 10,
                            colors = SliderDefaults.colors(
                                thumbColor = colPrimary,
                                activeTrackColor = colPrimary,
                                inactiveTrackColor = colLineSubtle
                            )
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        // Whole Day Allowed (Free Days) - Retained feature
                        Text(
                            text = str(R.string.whole_day_allowed_free_days),
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = colInk
                        )
                        Text(
                            text = str(R.string.selected_days_have_unlimited_daily),
                            fontSize = 12.sp,
                            color = colInkMuted
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        val daysOfWeek = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
                        // The English short names are what's stored; only the label is translated.
                        val dayLabels = mapOf(
                            "Mon" to R.string.day_mon, "Tue" to R.string.day_tue, "Wed" to R.string.day_wed, "Thu" to R.string.day_thu,
                            "Fri" to R.string.day_fri, "Sat" to R.string.day_sat, "Sun" to R.string.day_sun
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            daysOfWeek.forEach { day ->
                                val isSelected = policy.unlimitedDays.contains(day)
                                val chipInteraction = remember { MutableInteractionSource() }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(46.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(if (isSelected) colPrimary else Shield.colors.canvas)
                                        .border(
                                            width = if (isSelected) 0.dp else 1.dp,
                                            color = if (isSelected) Color.Transparent else colLineSubtle,
                                            shape = RoundedCornerShape(14.dp)
                                        )
                                        .clickable(
                                            interactionSource = chipInteraction,
                                            indication = null
                                        ) {
                                            val updatedDays = policy.unlimitedDays.toMutableSet()
                                            if (isSelected) updatedDays.remove(day) else updatedDays.add(day)
                                            onUpdatePolicy(policy.copy(unlimitedDays = updatedDays))
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text(
                                            text = str(dayLabels.getValue(day)),
                                            fontSize = 12.5.sp,
                                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                                            color = if (isSelected) Shield.colors.onPrimary else colInkMuted
                                        )
                                        if (isSelected) {
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Box(
                                                modifier = Modifier
                                                    .size(4.dp)
                                                    .clip(CircleShape)
                                                    .background(Shield.colors.card)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Bedtime lock card
        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Shield.colors.card,
                border = androidx.compose.foundation.BorderStroke(1.dp, colLineSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = str(R.string.bedtime_lock),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = colInk
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = str(R.string.locks_the_phone_automatically_at),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = colInkMuted
                            )
                        }

                        Switch(
                            checked = policy.isCurfewScheduled,
                            onCheckedChange = { onUpdatePolicy(policy.copy(isCurfewScheduled = it)) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Shield.colors.onPrimary,
                                checkedTrackColor = colPrimary,
                                uncheckedThumbColor = Shield.colors.outline,
                                uncheckedTrackColor = Shield.colors.surfaceVariant,
                                uncheckedBorderColor = Shield.colors.outline
                            )
                        )
                    }

                    if (policy.isCurfewScheduled) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = Shield.colors.surfaceVariant,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        android.app.TimePickerDialog(
                                            context,
                                            { _, h, m ->
                                                onUpdatePolicy(policy.copy(curfewStartHour = h, curfewStartMinute = m))
                                            },
                                            policy.curfewStartHour,
                                            policy.curfewStartMinute,
                                            false
                                        ).show()
                                    }
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(str(R.string.starts), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = colInkMuted)
                                        Icon(Icons.Default.Edit, contentDescription = str(R.string.edit_start_time), tint = colPrimary, modifier = Modifier.size(15.dp))
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    val startFormatted = remember(policy.curfewStartHour, policy.curfewStartMinute) {
                                        val h = if (policy.curfewStartHour % 12 == 0) 12 else policy.curfewStartHour % 12
                                        val amPm = if (policy.curfewStartHour < 12) "AM" else "PM"
                                        String.format(java.util.Locale.getDefault(), "%d:%02d %s", h, policy.curfewStartMinute, amPm)
                                    }
                                    Text(startFormatted, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = colInk)
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = Shield.colors.surfaceVariant,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        android.app.TimePickerDialog(
                                            context,
                                            { _, h, m ->
                                                onUpdatePolicy(policy.copy(curfewEndHour = h, curfewEndMinute = m))
                                            },
                                            policy.curfewEndHour,
                                            policy.curfewEndMinute,
                                            false
                                        ).show()
                                    }
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(str(R.string.ends), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = colInkMuted)
                                        Icon(Icons.Default.Edit, contentDescription = str(R.string.edit_end_time), tint = colPrimary, modifier = Modifier.size(15.dp))
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    val endFormatted = remember(policy.curfewEndHour, policy.curfewEndMinute) {
                                        val h = if (policy.curfewEndHour % 12 == 0) 12 else policy.curfewEndHour % 12
                                        val amPm = if (policy.curfewEndHour < 12) "AM" else "PM"
                                        String.format(java.util.Locale.getDefault(), "%d:%02d %s", h, policy.curfewEndMinute, amPm)
                                    }
                                    Text(endFormatted, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = colInk)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        val durationText = remember(policy.curfewStartHour, policy.curfewStartMinute, policy.curfewEndHour, policy.curfewEndMinute) {
                            val startTotal = policy.curfewStartHour * 60 + policy.curfewStartMinute
                            val endTotal = policy.curfewEndHour * 60 + policy.curfewEndMinute
                            val diff = if (endTotal >= startTotal) endTotal - startTotal else (24 * 60 - startTotal) + endTotal
                            val hours = diff / 60
                            val mins = diff % 60
                            if (mins > 0) str(R.string.hours_mins_locked_every_night, hours, mins) else str(R.string.hours_locked_every_night, hours)
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            Icon(Icons.Default.Bedtime, contentDescription = null, tint = colInkMuted, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(durationText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colInkMuted)
                        }
                    }
                }
            }
        }

        // Emergency calls card
        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Shield.colors.card,
                border = androidx.compose.foundation.BorderStroke(1.dp, colLineSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Shield.colors.surfaceVariant,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Phone, contentDescription = null, tint = colPrimary, modifier = Modifier.size(20.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(str(R.string.emergency_calls), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = colInk)
                            Text(str(R.string.can_always_call_this_number, childName), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = colInkMuted)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = Shield.colors.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(str(R.string.emergency_number), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = colInkMuted)
                                BasicTextField(
                                    value = emergencyPhone,
                                    onValueChange = {
                                        emergencyPhone = it
                                        onUpdatePolicy(policy.copy(emergencyPhoneNumber = it))
                                    },
                                    textStyle = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = colInk),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            Button(
                                onClick = { onEmergencyDial(emergencyPhone) },
                                shape = RoundedCornerShape(22.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colPrimaryContainer,
                                    contentColor = colOnPrimaryContainer
                                ),
                                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
                            ) {
                                Text(str(R.string.call), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

/* =========================================================================
   4. SECURITY SCREEN (Pixel-perfect matching HTML mockup)
   ========================================================================= */

@Composable
fun ReleaseSecurityScreen(
    authManager: ParentAuthManager,
    biometricHelper: BiometricAuthHelper,
    childName: String,
    onChildNameChanged: (String) -> Unit,
    onRerunSetup: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val isBiometricHardwareReady = remember { biometricHelper.isHardwareReady() }
    var isBiometricOn by remember { mutableStateOf(biometricHelper.isBiometricReady()) }
    var hasRecoveryKey by remember { mutableStateOf(authManager.hasRecoveryKey()) }
    var showCreateKeyDialog by remember { mutableStateOf(false) }
    val remoteCodes = remember { RemoteCodeManager(ShieldApplication.instance.preferencesRepository, authManager) }
    var isRemoteCodeOn by remember { mutableStateOf(remoteCodes.isSetUp()) }
    var remoteMinutes by remember { mutableStateOf(ShieldApplication.instance.preferencesRepository.getRemoteCodeMinutes()) }
    var showRemoteSetup by remember { mutableStateOf(false) }
    var showRemoteTurnOff by remember { mutableStateOf(false) }
    var showPinDialog by remember { mutableStateOf(false) }
    var showRecoveryResetDialog by remember { mutableStateOf(false) }
    var showEditNameDialog by remember { mutableStateOf(false) }
    var showUninstallConfirmDialog by remember { mutableStateOf(false) }

    val dpm = remember { context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager }
    val adminComponent = remember { ShieldDeviceAdminReceiver.getComponentName(context) }
    var isDeviceAdminActive by remember {
        mutableStateOf(dpm?.isAdminActive(adminComponent) == true)
    }

    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isDeviceAdminActive = dpm?.isAdminActive(adminComponent) == true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val colInk = Shield.colors.ink
    val colInkMuted = Shield.colors.inkMuted
    val colPrimary = Shield.colors.primary
    val colPrimaryContainer = Shield.colors.primaryContainer
    val colOnPrimaryContainer = Shield.colors.onPrimaryContainer
    val colLineSubtle = Shield.colors.line

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 10.dp, bottom = 24.dp)
    ) {
        item {
            Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
                Text(
                    text = str(R.string.security),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = colInk
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = str(R.string.how_you_unlock_parent_controls),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = colInkMuted
                )
            }
        }

        // Kid's Profile Card
        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Shield.colors.card,
                border = androidx.compose.foundation.BorderStroke(1.dp, colLineSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = colPrimaryContainer,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Face, contentDescription = null, tint = colPrimary, modifier = Modifier.size(24.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(str(R.string.kid_s_profile), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = colInk)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(childName, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colPrimary)
                        }
                    }

                    OutlinedButton(
                        onClick = { showEditNameDialog = true },
                        shape = RoundedCornerShape(22.dp),
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, colPrimary),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
                    ) {
                        Text(str(R.string.edit), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colPrimary)
                    }
                }
            }
        }

        // Biometrics Card
        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Shield.colors.card,
                border = androidx.compose.foundation.BorderStroke(1.dp, colLineSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Shield.colors.surfaceVariant,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Fingerprint, contentDescription = null, tint = colPrimary, modifier = Modifier.size(24.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(str(R.string.biometric_unlock), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = colInk)
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(if (isBiometricOn) colPrimary else colInkMuted)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = when {
                                        isBiometricOn -> str(R.string.on)
                                        isBiometricHardwareReady -> str(R.string.off_pin_only)
                                        else -> str(R.string.no_fingerprint_saved_on_this)
                                    },
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isBiometricOn) colOnPrimaryContainer else colInkMuted
                                )
                            }
                        }
                    }

                    if (isBiometricHardwareReady) {
                        Switch(
                            checked = isBiometricOn,
                            onCheckedChange = { turnOn ->
                                if (turnOn) {
                                    biometricHelper.enable(
                                        onSuccess = { isBiometricOn = true },
                                        onError = { err -> Toast.makeText(context, err, Toast.LENGTH_SHORT).show() }
                                    )
                                } else {
                                    biometricHelper.disable()
                                    isBiometricOn = false
                                }
                            },
                            colors = SwitchDefaults.colors(checkedTrackColor = colPrimary)
                        )
                    } else {
                        OutlinedButton(
                            onClick = {
                                try {
                                    context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                                } catch (_: Exception) {}
                            },
                            shape = RoundedCornerShape(22.dp),
                            border = androidx.compose.foundation.BorderStroke(1.5.dp, colPrimary),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            Text(str(R.string.enroll), fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = colPrimary)
                        }
                    }
                }
            }
        }

        // Master PIN Card
        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Shield.colors.card,
                border = androidx.compose.foundation.BorderStroke(1.dp, colLineSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Shield.colors.surfaceVariant,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Lock, contentDescription = null, tint = colPrimary, modifier = Modifier.size(22.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(str(R.string.master_pin), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = colInk)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(str(R.string.needed_to_change_settings_or), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = colInkMuted)
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { showRecoveryResetDialog = true },
                            shape = RoundedCornerShape(22.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Shield.colors.outline),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Text(str(R.string.reset), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colInkMuted)
                        }

                        Button(
                            onClick = { showPinDialog = true },
                            shape = RoundedCornerShape(22.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colPrimary,
                                contentColor = Shield.colors.onPrimary
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            Text(str(R.string.change), fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Offline recovery key Card
        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Shield.colors.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(str(R.string.offline_recovery_key), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = colInk)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = str(R.string.works_without_internet_if_you),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = colInkMuted
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = if (hasRecoveryKey) {
                            str(R.string.a_private_key_is_set)
                        } else {
                            str(R.string.no_recovery_key_yet_create)
                        },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (hasRecoveryKey) colOnPrimaryContainer else Shield.colors.error
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = { showCreateKeyDialog = true },
                        shape = RoundedCornerShape(22.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colPrimaryContainer,
                            contentColor = colOnPrimaryContainer
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            if (hasRecoveryKey) str(R.string.create_a_new_key) else str(R.string.create_recovery_key),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = str(R.string.creating_a_new_key_asks),
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = colInkMuted
                    )
                }
            }
        }

        // Remote unlock code (parent away): Google Authenticator, fully offline
        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Shield.colors.card,
                border = androidx.compose.foundation.BorderStroke(1.dp, colLineSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(str(R.string.remote_unlock_code), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = colInk)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (isRemoteCodeOn) {
                            str(R.string.on_when_you_re_away, RemoteCodeManager.MAX_UNLOCKS_PER_DAY)
                        } else {
                            str(R.string.off_lets_you_unlock_this)
                        },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = colInkMuted
                    )
                    if (isRemoteCodeOn) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(str(R.string.a_code_gives), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colInk)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(15L, 30L, 60L).forEach { minutes ->
                                FilterChip(
                                    selected = remoteMinutes == minutes,
                                    onClick = {
                                        ShieldApplication.instance.preferencesRepository.setRemoteCodeMinutes(minutes)
                                        remoteMinutes = minutes
                                    },
                                    label = { Text(if (minutes == 60L) str(R.string.t_1_hour) else str(R.string.min, minutes)) }
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { showRemoteSetup = true },
                            shape = RoundedCornerShape(22.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = colPrimary, contentColor = Shield.colors.onPrimary),
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text(if (isRemoteCodeOn) str(R.string.set_up_again) else str(R.string.set_up), fontWeight = FontWeight.Bold) }
                        if (isRemoteCodeOn) {
                            OutlinedButton(
                                onClick = { showRemoteTurnOff = true },
                                shape = RoundedCornerShape(22.dp),
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) { Text(str(R.string.turn_off), color = colInkMuted) }
                        }
                    }
                }
            }
        }

        // Recent unlocks (every time a parent signed in on the lock screen or gave time)
        item {
            val log = remember { ShieldApplication.instance.preferencesRepository.getGrantLog().take(10) }
            val timeFormat = remember { SimpleDateFormat("EEE d MMM, HH:mm", com.shieldchild.app.i18n.Language.locale()) }
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Shield.colors.card,
                border = androidx.compose.foundation.BorderStroke(1.dp, colLineSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(str(R.string.recent_unlocks), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = colInk)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        str(R.string.if_you_see_one_you),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = colInkMuted
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    if (log.isEmpty()) {
                        Text(str(R.string.none_yet), fontSize = 14.sp, color = colInkMuted)
                    }
                    log.forEach { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(grantLogText(entry.description), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colInk)
                            Text(
                                str(R.string.text, str(entry.method.labelRes), timeFormat.format(Date(entry.timestamp))),
                                fontSize = 13.sp,
                                color = colInkMuted
                            )
                        }
                    }
                }
            }
        }

        // Setup Wizard Card
        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Shield.colors.card,
                border = androidx.compose.foundation.BorderStroke(1.dp, colLineSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Shield.colors.surfaceVariant,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Tune, contentDescription = null, tint = colPrimary, modifier = Modifier.size(22.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(str(R.string.setup_wizard), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = colInk)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(str(R.string.reconfigure_child_details_or_permissions), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = colInkMuted)
                        }
                    }

                    OutlinedButton(
                        onClick = onRerunSetup,
                        shape = RoundedCornerShape(22.dp),
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, colPrimary),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
                    ) {
                        Text(str(R.string.rerun), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colPrimary)
                    }
                }
            }
        }

        // Anti-Uninstall Device Admin Card
        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Shield.colors.card,
                border = androidx.compose.foundation.BorderStroke(1.dp, colLineSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (isDeviceAdminActive) colPrimaryContainer else Shield.colors.errorContainer,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Security,
                                    contentDescription = null,
                                    tint = if (isDeviceAdminActive) colPrimary else Shield.colors.error,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(str(R.string.anti_uninstall_protection), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = colInk)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                if (isDeviceAdminActive) str(R.string.active_child_cannot_delete_or) else str(R.string.inactive_tap_to_activate_device),
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (isDeviceAdminActive) colInkMuted else Shield.colors.error
                            )
                        }
                    }

                    if (isDeviceAdminActive) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = colPrimaryContainer
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = colPrimary, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(str(R.string.active), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colOnPrimaryContainer)
                            }
                        }
                    } else {
                        Button(
                            onClick = {
                                try {
                                    val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                                        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                                        putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "ShieldChild requires Device Administrator to prevent unauthorized uninstallation by children.")
                                    }
                                    context.startActivity(intent)
                                } catch (_: Exception) {}
                            },
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colPrimary,
                                contentColor = Shield.colors.onPrimary
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(str(R.string.activate), fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Parent Uninstall ShieldChild Card
        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Shield.colors.card,
                border = androidx.compose.foundation.BorderStroke(1.dp, colLineSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Shield.colors.errorContainer,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Delete, contentDescription = null, tint = Shield.colors.error, modifier = Modifier.size(22.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(str(R.string.uninstall_shieldchild), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = colInk)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(str(R.string.parent_master_pin_required_to), fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = colInkMuted)
                        }
                    }

                    OutlinedButton(
                        onClick = { showUninstallConfirmDialog = true },
                        shape = RoundedCornerShape(22.dp),
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, Shield.colors.error),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(str(R.string.uninstall), fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = Shield.colors.error)
                    }
                }
            }
        }
    }

    if (showPinDialog) {
        var currentPin by remember { mutableStateOf("") }
        var newPin by remember { mutableStateOf("") }
        var confirmPin by remember { mutableStateOf("") }
        var errorMsg by remember { mutableStateOf<String?>(null) }

        Dialog(
            onDismissRequest = { showPinDialog = false }
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = Shield.colors.card,
                border = androidx.compose.foundation.BorderStroke(1.dp, colLineSubtle),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Header Lock Icon Badge
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = colPrimaryContainer,
                        modifier = Modifier.size(52.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = null,
                                tint = colPrimary,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = str(R.string.set_master_pin),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = colInk,
                        letterSpacing = (-0.3).sp
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = str(R.string.enter_your_current_pin_then),
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = colInkMuted,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    PinBox(value = currentPin, onValueChange = { currentPin = it; errorMsg = null }, label = str(R.string.label_current_pin))
                    Spacer(modifier = Modifier.height(10.dp))
                    PinBox(value = newPin, onValueChange = { newPin = it; errorMsg = null }, label = str(R.string.label_new_pin))
                    Spacer(modifier = Modifier.height(10.dp))
                    PinBox(value = confirmPin, onValueChange = { confirmPin = it; errorMsg = null }, label = str(R.string.label_confirm_new_pin))

                    if (errorMsg != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = errorMsg ?: "",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Shield.colors.error
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Action Buttons (Save PIN & Cancel)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { showPinDialog = false },
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Shield.colors.surfaceVariant,
                                contentColor = colInkMuted
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Text(
                                text = str(R.string.cancel),
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Button(
                            onClick = {
                                val check = authManager.verifyPin(currentPin)
                                if (check is AuthResult.InvalidPin) {
                                    errorMsg = str(R.string.current_pin_is_wrong_attempts, check.remainingAttempts)
                                } else if (check is AuthResult.LockedOut) {
                                    errorMsg = str(R.string.too_many_attempts_try_again, check.remainingSeconds / 60 + 1)
                                } else if (newPin.length < 4) {
                                    errorMsg = str(R.string.new_pin_must_be_at)
                                } else if (newPin != confirmPin) {
                                    errorMsg = str(R.string.new_pins_do_not_match)
                                } else {
                                    authManager.setMasterPin(newPin)
                                    Toast.makeText(
                                        ShieldApplication.instance,
                                        str(R.string.master_pin_updated_successfully),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    showPinDialog = false
                                }
                            },
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colPrimary,
                                contentColor = Shield.colors.onPrimary
                            ),
                            modifier = Modifier
                                .weight(1.2f)
                                .height(48.dp)
                        ) {
                            Text(
                                text = str(R.string.save_pin),
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }

    if (showRemoteSetup) {
        RemoteCodeSetupDialog(
            authManager = authManager,
            remoteCodes = remoteCodes,
            childName = childName,
            onDone = {
                showRemoteSetup = false
                isRemoteCodeOn = remoteCodes.isSetUp()
            }
        )
    }

    if (showRemoteTurnOff) {
        ConfirmWithPinDialog(
            title = str(R.string.turn_off_remote_codes),
            message = str(R.string.codes_from_your_authenticator_app),
            authManager = authManager,
            onConfirmed = {
                remoteCodes.turnOff()
                isRemoteCodeOn = false
                showRemoteTurnOff = false
            },
            onDismiss = { showRemoteTurnOff = false }
        )
    }

    if (showCreateKeyDialog) {
        CreateRecoveryKeyDialog(
            authManager = authManager,
            requirePin = true,
            onDone = {
                showCreateKeyDialog = false
                hasRecoveryKey = authManager.hasRecoveryKey()
            }
        )
    }

    if (showRecoveryResetDialog) {
        DashboardRecoveryKeyDialog(
            authManager = authManager,
            onDismiss = { showRecoveryResetDialog = false },
            onRecoverySuccess = {
                showRecoveryResetDialog = false
                Toast.makeText(context, str(R.string.pin_successfully_reset), Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showEditNameDialog) {
        var tempName by remember { mutableStateOf(childName) }
        var nameError by remember { mutableStateOf<String?>(null) }

        Dialog(onDismissRequest = { showEditNameDialog = false }) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = Shield.colors.card,
                border = androidx.compose.foundation.BorderStroke(1.dp, colLineSubtle),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = colPrimaryContainer,
                        modifier = Modifier.size(52.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Face,
                                contentDescription = null,
                                tint = colPrimary,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = str(R.string.edit_child_s_name),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = colInk,
                        letterSpacing = (-0.3).sp
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = str(R.string.personalize_rules_dashboard_header_and),
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = colInkMuted,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Shield.colors.surfaceVariant,
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (nameError != null) Shield.colors.error else Shield.colors.line),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BasicTextField(
                                value = tempName,
                                onValueChange = {
                                    tempName = it
                                    nameError = null
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                                textStyle = TextStyle(
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = colInk
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    if (nameError != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = nameError ?: "",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Shield.colors.error
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { showEditNameDialog = false },
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Shield.colors.surfaceVariant,
                                contentColor = colInkMuted
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Text(str(R.string.cancel), fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                if (tempName.trim().isEmpty()) {
                                    nameError = str(R.string.name_cannot_be_empty)
                                } else {
                                    val cleaned = tempName.trim()
                                    ShieldApplication.instance.preferencesRepository.saveChildName(cleaned)
                                    onChildNameChanged(cleaned)
                                    showEditNameDialog = false
                                }
                            },
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colPrimary,
                                contentColor = Shield.colors.onPrimary
                            ),
                            modifier = Modifier
                                .weight(1.2f)
                                .height(48.dp)
                        ) {
                            Text(str(R.string.save_name), fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    if (showUninstallConfirmDialog) {
        var uninstallPin by remember { mutableStateOf("") }
        var uninstallError by remember { mutableStateOf<String?>(null) }

        Dialog(onDismissRequest = { showUninstallConfirmDialog = false }) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = Shield.colors.card,
                border = androidx.compose.foundation.BorderStroke(1.dp, colLineSubtle),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Shield.colors.errorContainer,
                        modifier = Modifier.size(52.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = Shield.colors.error, modifier = Modifier.size(26.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = str(R.string.uninstall_shieldchild_2),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = colInk,
                        letterSpacing = (-0.3).sp
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = str(R.string.enter_parent_master_pin_to),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = colInkMuted,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Shield.colors.surfaceVariant,
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (uninstallError != null) Shield.colors.error else Shield.colors.line),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BasicTextField(
                                value = uninstallPin,
                                onValueChange = {
                                    if (it.length <= 8 && it.all { ch -> ch.isDigit() }) {
                                        uninstallPin = it
                                        uninstallError = null
                                    }
                                },
                                visualTransformation = PasswordVisualTransformation(),
                                textStyle = TextStyle(
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = colInk,
                                    letterSpacing = 6.sp,
                                    textAlign = TextAlign.Center
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    if (uninstallError != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = uninstallError ?: "",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Shield.colors.error
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { showUninstallConfirmDialog = false },
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Shield.colors.surfaceVariant,
                                contentColor = colInkMuted
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Text(str(R.string.cancel), fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                if (authManager.verifyPin(uninstallPin) is AuthResult.Success) {
                                    ShieldAccessibilityService.allowParentUninstall(60000L)
                                    try {
                                        dpm?.removeActiveAdmin(adminComponent)
                                    } catch (_: Exception) {}
                                    try {
                                        val intent = Intent(Intent.ACTION_DELETE).apply {
                                            data = Uri.parse("package:${context.packageName}")
                                        }
                                        context.startActivity(intent)
                                    } catch (_: Exception) {}
                                    showUninstallConfirmDialog = false
                                } else {
                                    uninstallError = str(R.string.incorrect_parent_master_pin)
                                }
                            },
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Shield.colors.error,
                                contentColor = Shield.colors.onPrimary
                            ),
                            modifier = Modifier
                                .weight(1.2f)
                                .height(48.dp)
                        ) {
                            Text(str(R.string.uninstall), fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DashboardRecoveryKeyDialog(
    authManager: ParentAuthManager,
    onDismiss: () -> Unit,
    onRecoverySuccess: () -> Unit
) {
    var recoveryKeyInput by remember { mutableStateOf("") }
    var newPinInput by remember { mutableStateOf("") }
    var confirmPinInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val colInk = Shield.colors.ink
    val colInkMuted = Shield.colors.inkMuted
    val colPrimary = Shield.colors.primary
    val colPrimaryContainer = Shield.colors.primaryContainer
    val colLineSubtle = Shield.colors.line

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = Shield.colors.card,
            border = androidx.compose.foundation.BorderStroke(1.dp, colLineSubtle),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = colPrimaryContainer,
                    modifier = Modifier.size(52.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Key, contentDescription = null, tint = colPrimary, modifier = Modifier.size(26.dp))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = str(R.string.emergency_recovery_key),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = colInk,
                    letterSpacing = (-0.3).sp
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = str(R.string.enter_your_16_character_master),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = colInkMuted,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = recoveryKeyInput,
                    onValueChange = { recoveryKeyInput = it.uppercase() },
                    label = { Text(str(R.string.recovery_key)) },
                    placeholder = { Text(str(R.string.xxxx_xxxx_xxxx_xxxx)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = newPinInput,
                    onValueChange = { if (it.length <= 8 && it.all { c -> c.isDigit() }) newPinInput = it },
                    label = { Text(str(R.string.new_4_digit_pin)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = confirmPinInput,
                    onValueChange = { if (it.length <= 8 && it.all { c -> c.isDigit() }) confirmPinInput = it },
                    label = { Text(str(R.string.confirm_new_pin)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(errorMessage!!, color = Shield.colors.error, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Shield.colors.surfaceVariant,
                            contentColor = colInkMuted
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Text(str(R.string.cancel), fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            when {
                                recoveryKeyInput.isBlank() -> errorMessage = str(R.string.please_enter_your_recovery_key)
                                newPinInput.length < 4 -> errorMessage = str(R.string.pin_must_be_at_least)
                                newPinInput != confirmPinInput -> errorMessage = str(R.string.pins_do_not_match)
                                else -> {
                                    val success = authManager.recoverWithMasterKey(recoveryKeyInput, newPinInput)
                                    if (success) {
                                        onRecoverySuccess()
                                    } else {
                                        errorMessage = str(R.string.wrong_recovery_key_or_too)
                                    }
                                }
                            }
                        },
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colPrimary,
                            contentColor = Shield.colors.onPrimary
                        ),
                        modifier = Modifier
                            .weight(1.3f)
                            .height(48.dp)
                    ) {
                        Text(str(R.string.reset_pin), fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun ParentSecurityGateScreen(
    authManager: ParentAuthManager,
    biometricHelper: BiometricAuthHelper,
    isBiometricAvailable: Boolean,
    onAuthenticated: () -> Unit
) {
    var enteredPin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showRecoveryDialog by remember { mutableStateOf(false) }

    val colInk = Shield.colors.ink
    val colInkMuted = Shield.colors.inkMuted
    val colPrimary = Shield.colors.primary
    val colPrimaryContainer = Shield.colors.primaryContainer
    val colLineSubtle = Shield.colors.line

    // Trigger fingerprint prompt automatically if available
    LaunchedEffect(Unit) {
        if (isBiometricAvailable) {
            biometricHelper.promptBiometricAuth(
                title = str(R.string.parent_unlock),
                subtitle = str(R.string.scan_fingerprint_to_access_parent),
                negativeButtonText = str(R.string.use_pin),
                onSuccess = onAuthenticated,
                onError = { _, _ -> },
                onFailed = {}
            )
        }
    }

    if (showRecoveryDialog) {
        DashboardRecoveryKeyDialog(
            authManager = authManager,
            onDismiss = { showRecoveryDialog = false },
            onRecoverySuccess = {
                showRecoveryDialog = false
                onAuthenticated()
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Shield.colors.canvas)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = Shield.colors.card,
            border = androidx.compose.foundation.BorderStroke(1.dp, colLineSubtle),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Shield Badge
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = colPrimaryContainer,
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = str(R.string.security),
                            tint = colPrimary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text = str(R.string.parent_security_gate),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = colInk,
                    letterSpacing = (-0.5).sp
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = str(R.string.enter_your_parent_master_pin),
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = colInkMuted,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                // PIN Input Field
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Shield.colors.surfaceVariant,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Shield.colors.line),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        BasicTextField(
                            value = enteredPin,
                            onValueChange = {
                                if (it.length <= 8 && it.all { c -> c.isDigit() }) {
                                    // Checked only on "Unlock Dashboard": checking each digit would
                                    // count every partial entry as a failed attempt.
                                    enteredPin = it
                                    errorMessage = null
                                }
                            },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            textStyle = TextStyle(
                                fontSize = 24.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = colInk,
                                letterSpacing = 8.sp,
                                textAlign = TextAlign.Center
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            decorationBox = { inner ->
                                if (enteredPin.isEmpty()) {
                                    Text(
                                        text = "••••",
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = colInkMuted.copy(alpha = 0.4f),
                                        letterSpacing = 8.sp,
                                        textAlign = TextAlign.Center
                                    )
                                }
                                inner()
                            }
                        )
                    }
                }

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = errorMessage!!,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Shield.colors.error,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Unlock Button
                Button(
                    onClick = {
                        when (val res = authManager.verifyPin(enteredPin)) {
                            AuthResult.Success -> onAuthenticated()
                            is AuthResult.InvalidPin -> {
                                errorMessage = str(R.string.incorrect_pin_attempts_remaining, res.remainingAttempts)
                            }
                            is AuthResult.LockedOut -> {
                                errorMessage = str(R.string.too_many_attempts_locked_for, res.remainingSeconds)
                            }
                        }
                    },
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colPrimary,
                        contentColor = Shield.colors.onPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Text(str(R.string.unlock_dashboard), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }

                if (isBiometricAvailable) {
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            biometricHelper.promptBiometricAuth(
                                title = str(R.string.parent_unlock),
                                subtitle = str(R.string.scan_fingerprint_to_access_parent),
                                negativeButtonText = str(R.string.cancel),
                                onSuccess = onAuthenticated,
                                onError = { _, err -> errorMessage = err.toString() },
                                onFailed = { errorMessage = str(R.string.fingerprint_not_recognized) }
                            )
                        },
                        shape = RoundedCornerShape(24.dp),
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, colPrimary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Fingerprint,
                            contentDescription = null,
                            tint = colPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(str(R.string.use_fingerprint), fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = colPrimary)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                TextButton(onClick = { showRecoveryDialog = true }) {
                    Text(
                        text = str(R.string.forgot_pin_use_recovery_key),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colPrimary
                    )
                }
            }
        }
    }
}


@Composable
fun PinBox(value: String, onValueChange: (String) -> Unit, label: String) =
    PinField(value = value, onValueChange = onValueChange, label = label)

/**
 * Creates a private recovery key and shows it once. Only a hash is stored, so the
 * key can never be displayed again; creating a new key replaces the old one.
 */
@Composable
fun CreateRecoveryKeyDialog(
    authManager: ParentAuthManager,
    requirePin: Boolean,
    onDone: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var newKey by remember { mutableStateOf<String?>(if (requirePin) null else authManager.generateRecoveryKey()) }
    val key = newKey

    if (key == null) {
        ShieldDialog(
            icon = Icons.Default.Key,
            title = str(R.string.create_a_new_recovery_key),
            message = str(R.string.enter_your_pin_the_new),
            onDismiss = onDone,
            confirmText = str(R.string.action_continue),
            confirmEnabled = pin.length >= 4,
            onConfirm = {
                when (val res = authManager.verifyPin(pin)) {
                    AuthResult.Success -> newKey = authManager.generateRecoveryKey()
                    is AuthResult.InvalidPin -> error = str(R.string.wrong_pin_attempts_left, res.remainingAttempts)
                    is AuthResult.LockedOut -> error = str(R.string.too_many_attempts_try_again, res.remainingSeconds / 60 + 1)
                }
            }
        ) {
            PinField(value = pin, onValueChange = { pin = it; error = null }, label = str(R.string.label_parent_pin))
            DialogError(error)
        }
    } else {
        ShieldDialog(
            icon = Icons.Default.Key,
            title = str(R.string.your_recovery_key),
            message = str(R.string.if_you_forget_your_pin),
            onDismiss = onDone,
            confirmText = str(R.string.i_ve_written_it_down),
            onConfirm = onDone,
            dismissText = null,
            dismissOnOutsideTap = false
        ) {
            Surface(shape = RoundedCornerShape(16.dp), color = Shield.colors.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = key,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 2.sp,
                    color = Shield.colors.ink,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }
        }
    }
}

@Composable
fun ConfirmWithPinDialog(
    title: String,
    message: String,
    authManager: ParentAuthManager,
    onConfirmed: () -> Unit,
    onDismiss: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    ShieldDialog(
        icon = Icons.Default.Lock,
        title = title,
        message = message,
        onDismiss = onDismiss,
        confirmText = str(R.string.action_continue),
        confirmEnabled = pin.length >= 4,
        onConfirm = {
            when (val res = authManager.verifyPin(pin)) {
                AuthResult.Success -> onConfirmed()
                is AuthResult.InvalidPin -> error = str(R.string.wrong_pin_attempts_left, res.remainingAttempts)
                is AuthResult.LockedOut -> error = str(R.string.too_many_attempts_try_again, res.remainingSeconds / 60 + 1)
            }
        }
    ) {
        PinField(value = pin, onValueChange = { pin = it; error = null }, label = str(R.string.label_parent_pin))
        DialogError(error)
    }
}

/**
 * PIN → QR code (scan with Google Authenticator) → type the current code to confirm.
 * The secret is saved only after the confirmation and the QR is never shown again.
 */
@Composable
fun RemoteCodeSetupDialog(
    authManager: ParentAuthManager,
    remoteCodes: RemoteCodeManager,
    childName: String,
    onDone: () -> Unit
) {
    var pinOk by remember { mutableStateOf(false) }
    if (!pinOk) {
        ConfirmWithPinDialog(
            title = str(R.string.set_up_remote_unlock_code),
            message = str(R.string.setting_up_again_replaces_the),
            authManager = authManager,
            onConfirmed = { pinOk = true },
            onDismiss = onDone
        )
        return
    }

    val secret = remember { remoteCodes.newSecret() }
    val uri = remember { remoteCodes.otpauthUri(secret, childName) }
    val qr = remember { qrBitmap(uri, 640) }
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    ShieldDialog(
        icon = Icons.Default.QrCode,
        title = str(R.string.scan_with_your_phone),
        message = str(R.string.on_your_phone_open_google),
        onDismiss = onDone,
        dismissOnOutsideTap = false,
        confirmText = str(R.string.confirm),
        confirmEnabled = code.length == 6,
        onConfirm = {
            if (remoteCodes.confirmSetup(secret, code)) {
                Toast.makeText(ShieldApplication.instance, str(R.string.remote_unlock_code_is_on), Toast.LENGTH_SHORT).show()
                onDone()
            } else {
                error = str(R.string.that_code_doesn_t_match)
            }
        }
    ) {
        qr?.let {
            Image(
                bitmap = it,
                contentDescription = str(R.string.setup_qr_code_for_your),
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White) // QR codes need white in both themes
                    .padding(12.dp)
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(str(R.string.can_t_scan_choose_enter), fontSize = 13.sp, color = Shield.colors.inkMuted)
        Text(
            Totp.base32(secret).chunked(4).joinToString(" "),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = Shield.colors.ink,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
        )
        Spacer(modifier = Modifier.height(14.dp))
        Text(str(R.string.then_type_the_6_digit), fontSize = 14.sp, color = Shield.colors.ink)
        Spacer(modifier = Modifier.height(8.dp))
        PinField(value = code, onValueChange = { code = it; error = null }, label = str(R.string.label_6_digit_code), maxLength = 6, hidden = false)
        DialogError(error)
    }
}

private fun qrBitmap(text: String, size: Int): androidx.compose.ui.graphics.ImageBitmap? = try {
    val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size) { i ->
        if (matrix.get(i % size, i / size)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
} catch (_: Exception) {
    null
}

/** "8:00 PM" or "20.00", following the phone's 12/24-hour setting and the app language. */
private fun clockText(hour: Int, minute: Int): String {
    val cal = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, hour)
        set(java.util.Calendar.MINUTE, minute)
    }
    val is24h = android.text.format.DateFormat.is24HourFormat(ShieldApplication.instance)
    return SimpleDateFormat(if (is24h) "HH:mm" else "h:mm a", com.shieldchild.app.i18n.Language.locale()).format(cal.time)
}

/** Text for a stored grant-log code. Entries saved before the codes existed were English text. */
private fun grantLogText(stored: String): String {
    val code = when (stored) {
        "Parent access" -> com.shieldchild.app.data.GrantLog.PARENT_ACCESS
        "No limit rest of today" -> com.shieldchild.app.data.GrantLog.REST_OF_TODAY
        "Bedtime off tonight" -> com.shieldchild.app.data.GrantLog.BEDTIME_OFF
        "Instant lock off" -> com.shieldchild.app.data.GrantLog.INSTANT_OFF
        else -> Regex("""^\+(\d+) min$""").find(stored)?.let { "MINUTES:" + it.groupValues[1] } ?: stored
    }
    return grantLogCodeText(code)
}

private fun grantLogCodeText(code: String): String = when {
    code == com.shieldchild.app.data.GrantLog.PARENT_ACCESS -> str(R.string.parent_access)
    code == com.shieldchild.app.data.GrantLog.REST_OF_TODAY -> str(R.string.no_limit_rest_of_today)
    code == com.shieldchild.app.data.GrantLog.BEDTIME_OFF -> str(R.string.bedtime_off_tonight)
    code == com.shieldchild.app.data.GrantLog.INSTANT_OFF -> str(R.string.instant_lock_off)
    code.startsWith("MINUTES:") -> str(R.string.min_2, code.removePrefix("MINUTES:"))
    else -> code
}
