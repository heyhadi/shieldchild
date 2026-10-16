package com.shieldchild.app.ui.dashboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
    private val isParentAuthenticatedState = mutableStateOf(false)

    override fun onResume() {
        super.onResume()
        checkIfLockedAndRedirect()
    }

    override fun onStop() {
        super.onStop()
        isParentAuthenticatedState.value = false
    }

    private fun checkIfLockedAndRedirect() {
        val repo = ShieldApplication.instance.preferencesRepository
        if (repo.isCurfewOverrideActive()) {
            return
        }
        if (repo.isSetupCompleted() && !isParentAuthenticatedState.value) {
            val policy = repo.getCurfewPolicy()
            val usage = UsageMonitor(this).queryDailyUsage()
            val cal = java.util.Calendar.getInstance()
            val currentMinutes = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
            val startMinutes = policy.curfewStartHour * 60 + policy.curfewStartMinute
            val endMinutes = policy.curfewEndHour * 60 + policy.curfewEndMinute
            val isCurfewNow = if (startMinutes <= endMinutes) {
                currentMinutes in startMinutes until endMinutes
            } else {
                currentMinutes >= startMinutes || currentMinutes < endMinutes
            }

            val isLocked = policy.isInstantLocked ||
                    (policy.isCurfewScheduled && isCurfewNow) ||
                    (policy.effectiveDailyLimitMinutes > 0 && usage.totalDeviceUsageMinutes >= policy.effectiveDailyLimitMinutes)

            if (isLocked) {
                val reason = when {
                    policy.isInstantLocked -> LockReason.PARENT_INSTANT_FREEZE
                    policy.isCurfewScheduled && isCurfewNow -> LockReason.SCHEDULED_BEDTIME_CURFEW
                    else -> LockReason.TOTAL_SCREEN_TIME_EXCEEDED
                }
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
        insetsController.isAppearanceLightStatusBars = true
        insetsController.isAppearanceLightNavigationBars = true

        val repo = ShieldApplication.instance.preferencesRepository
        authManager = ParentAuthManager(repo)
        biometricHelper = BiometricAuthHelper(this)

        initDefaultAppLimitsIfEmpty()

        if (repo.isSetupCompleted()) {
            ShieldMonitorService.start(this)
        }

        setContent {
            ShieldChildTheme(darkTheme = false) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFFF5F6F1) // Ground background from specs
                ) {
                    var isSetupCompleted by remember { mutableStateOf(repo.isSetupCompleted()) }
                    var isParentAuthenticated by remember { isParentAuthenticatedState }

                    if (!isSetupCompleted) {
                        FirstTimeSetupScreen(
                            authManager = authManager,
                            biometricHelper = biometricHelper,
                            isBiometricHardwareAvailable = biometricHelper.isBiometricReady(),
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
                            onBiometricAuth = { onSuccessAction ->
                                biometricHelper.promptBiometricAuth(
                                    title = "Parent Authorization",
                                    subtitle = "Scan fingerprint or face to verify parent identity",
                                    negativeButtonText = "Cancel",
                                    onSuccess = onSuccessAction,
                                    onError = { _, err ->
                                        Toast.makeText(this, err, Toast.LENGTH_SHORT).show()
                                    },
                                    onFailed = {
                                        Toast.makeText(this, "Biometric not recognized.", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            },
                            onRerunSetup = {
                                isSetupCompleted = false
                            },
                            authManager = authManager,
                            isBiometricAvailable = biometricHelper.isBiometricReady()
                        )
                    }
                }
            }
        }
    }

    private fun initDefaultAppLimitsIfEmpty() {
        val repo = ShieldApplication.instance.preferencesRepository
        if (repo.getAppLimits().isEmpty()) {
            val discovery = AppDiscoveryManager(this)
            val installed = discovery.getInstalledLaunchableApps()
            val commonKeywords = listOf("youtube", "tiktok", "roblox", "instagram", "whatsapp", "game", "browser", "chrome")
            val detected = installed.filter { app ->
                commonKeywords.any { kw -> app.appName.contains(kw, ignoreCase = true) || app.packageName.contains(kw, ignoreCase = true) }
            }
            val initial = if (detected.isNotEmpty()) {
                detected.take(4).map { app ->
                    val isBlocked = app.appName.contains("tiktok", ignoreCase = true)
                    val limit = if (isBlocked) 0L else if (app.appName.contains("youtube", ignoreCase = true)) 45L else 60L
                    AppLimit(app.packageName, app.appName, limit, isBlocked = isBlocked, usageTodayMinutes = 0L)
                }
            } else if (installed.isNotEmpty()) {
                installed.take(3).map { app ->
                    AppLimit(app.packageName, app.appName, 45L, isBlocked = false, usageTodayMinutes = 0L)
                }
            } else {
                emptyList()
            }
            if (initial.isNotEmpty()) {
                repo.saveAppLimits(initial)
            }
        }
    }
}

enum class ReleaseNavTab(val label: String, val icon: ImageVector) {
    OVERVIEW("Overview", Icons.Default.Home),
    APP_LIMITS("App limits", Icons.Default.GridView),
    CURFEW("Curfew", Icons.Default.Schedule),
    SECURITY("Security", Icons.Default.Shield)
}

@Composable
fun ReleaseParentDashboardScreen(
    onEmergencyDial: (String) -> Unit,
    onInstantLockActivated: () -> Unit,
    onBiometricAuth: (() -> Unit) -> Unit,
    onRerunSetup: () -> Unit,
    authManager: ParentAuthManager,
    isBiometricAvailable: Boolean
) {
    val repo = ShieldApplication.instance.preferencesRepository
    val context = androidx.compose.ui.platform.LocalContext.current
    val usageMonitor = remember { UsageMonitor(context) }
    var usageSummary by remember { mutableStateOf(usageMonitor.queryDailyUsage()) }
    var childName by remember { mutableStateOf(repo.getChildName()) }
    var selectedTab by remember { mutableStateOf(ReleaseNavTab.OVERVIEW) }
    var policy by remember { mutableStateOf(repo.getCurfewPolicy()) }
    var appLimitsMap by remember { mutableStateOf(repo.getAppLimits()) }

    LaunchedEffect(selectedTab) {
        withContext(Dispatchers.IO) {
            usageSummary = usageMonitor.queryDailyUsage()
        }
    }

    // Design Tokens from Spec
    val colBg = Color(0xFFF5F6F1)
    val colPrimary = Color(0xFF1E5B3E)
    val colPrimaryContainer = Color(0xFFDCEBDD)
    val colOnPrimaryContainer = Color(0xFF123D28)
    val colInk = Color(0xFF17201A)
    val colInkMuted = Color(0xFF4B5750)
    val colLineSubtle = Color(0xFFE3E7DF)

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
                            text = "${childName}'s Phone",
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
                                    .background(if (policy.isInstantLocked) Color(0xFF8F5200) else Color(0xFF1E5B3E))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (policy.isInstantLocked) "Locked now" else "Protected",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (policy.isInstantLocked) Color(0xFF7A4A00) else Color(0xFF1E5B3E)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }
        },
        bottomBar = {
            // Clean Bottom Navigation Bar with navigation bar insets
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color.White,
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
                    isBiometricAvailable = isBiometricAvailable,
                    onBiometricAuth = onBiometricAuth,
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
    val remainingMinutes = if (effectiveLimit > usedMinutesToday) effectiveLimit - usedMinutesToday else 0L
    val progress = if (effectiveLimit > 0) (remainingMinutes.toFloat() / effectiveLimit.toFloat()).coerceIn(0f, 1f) else 1f

    val colPrimary = Color(0xFF1E5B3E)
    val colPrimaryContainer = Color(0xFFDCEBDD)
    val colOnPrimaryContainer = Color(0xFF123D28)
    val colInk = Color(0xFF17201A)
    val colInkMuted = Color(0xFF4B5750)
    val colLineSubtle = Color(0xFFE3E7DF)

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
                color = Color.White,
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
                            text = "Screen time today",
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
                                        text = "+${policy.bonusMinutesToday}m bonus",
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = colOnPrimaryContainer
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Remove bonus",
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
                                    text = "${remainingMinutes / 60}h ${remainingMinutes % 60}m",
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = colInk,
                                    letterSpacing = (-0.5).sp
                                )
                                Text(
                                    text = "left today",
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
                                Text("Used", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = colInkMuted)
                                Text(
                                    text = "${usedMinutesToday / 60}h ${usedMinutesToday % 60}m",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = colInk
                                )
                            }
                            Column {
                                Text("Daily allowance", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = colInkMuted)
                                Text(
                                    text = "${effectiveLimit / 60}h ${effectiveLimit % 60}m",
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
                            Text("15 min", fontSize = 15.sp, fontWeight = FontWeight.Bold)
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
                            Text("30 min", fontSize = 15.sp, fontWeight = FontWeight.Bold)
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
                            text = "Bedtime lock 9:00 PM – 7:00 AM",
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
                color = if (isLocked) Color(0xFFFCEBCB) else Color.White,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isLocked) Color(0xFFF1D49B) else colLineSubtle
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
                            color = if (isLocked) Color(0xFF8F5200) else Color(0xFFEEF1EA),
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = if (isLocked) Color.White else colPrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(
                                text = "Instant lock",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (isLocked) Color(0xFF4A2D00) else colInk
                            )
                            Text(
                                text = if (isLocked) "Phone is locked right now" else "Lock phone immediately",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (isLocked) Color(0xFF6B4300) else colInkMuted
                            )
                        }
                    }

                    Switch(
                        checked = isLocked,
                        onCheckedChange = { onToggleInstantLock(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF8F5200),
                            uncheckedThumbColor = Color(0xFF6F7A73),
                            uncheckedTrackColor = Color(0xFFEEF1EA),
                            uncheckedBorderColor = Color(0xFF6F7A73)
                        )
                    )
                }
            }
        }

        // Section Title: Apps today
        item {
            Text(
                text = "Apps today",
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
                    color = Color.White,
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
                                text = "No app limits set yet",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = colInk
                            )
                            Text(
                                text = "Tap to choose games, social apps & browsers to protect.",
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
                    color = Color.White,
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
                                            text = if (app.isBlocked) "No access" else "${app.usageTodayMinutes}m used of ${app.dailyLimitMinutes}m",
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = colInkMuted
                                        )
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (app.isBlocked) Color(0xFFFBE3E0) else Color(0xFFEEF1EA)
                                ) {
                                    Text(
                                        text = if (app.isBlocked) "Blocked" else "${app.dailyLimitMinutes}m limit",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (app.isBlocked) Color(0xFF8C2118) else colOnPrimaryContainer,
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
                                text = "Manage all app limits (${appLimits.size})",
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
            color = if (isBlocked) Color(0xFFFBE3E0) else Color(0xFFEEF1EA),
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
                    tint = if (isBlocked) Color(0xFF8C2118) else Color(0xFF1E5B3E),
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

    val colInk = Color(0xFF17201A)
    val colInkMuted = Color(0xFF4B5750)
    val colPrimary = Color(0xFF1E5B3E)
    val colPrimaryContainer = Color(0xFFDCEBDD)
    val colOnPrimaryContainer = Color(0xFF123D28)
    val colLineSubtle = Color(0xFFE3E7DF)

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
                        text = "App limits",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = colInk
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Set daily limits, or block apps completely.",
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
                            contentDescription = "Add App",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Add App",
                            color = Color.White,
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
                    color = Color.White,
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
                            text = "No App Limits Set",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = colInk
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Add specific games, social media, or video apps installed on your kid's phone to set daily limits or block access.",
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
                            Text("Add First App", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        } else {
            items(appLimits) { app ->
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.White,
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
                                        text = if (app.isBlocked) "Blocked completely" else "${app.usageTodayMinutes}m used • ${app.dailyLimitMinutes}m limit",
                                        fontSize = 12.5.sp,
                                        fontWeight = if (app.isBlocked) FontWeight.SemiBold else FontWeight.Medium,
                                        color = if (app.isBlocked) Color(0xFF8C2118) else colInkMuted
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
                                        onUpdateLimit(app.copy(isBlocked = !isAllowed))
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = colPrimary,
                                        uncheckedThumbColor = Color(0xFF6F7A73),
                                        uncheckedTrackColor = Color(0xFFEEF1EA),
                                        uncheckedBorderColor = Color(0xFF6F7A73)
                                    )
                                )

                                IconButton(
                                    onClick = { appToDeleteConfirm = app },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.Default.DeleteOutline,
                                        contentDescription = "Remove limit",
                                        tint = Color(0xFF8C2118),
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
                                    text = "${app.dailyLimitMinutes}m",
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
                color = Color.White,
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
                                    text = "Add Application Limit",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = colInk
                                )
                                Text(
                                    text = "Choose an installed app on this phone",
                                    fontSize = 12.5.sp,
                                    color = colInkMuted
                                )
                            }
                            IconButton(
                                onClick = { showAddAppDialog = false },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Close", tint = colInkMuted)
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Search Input Field
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search installed apps...", fontSize = 13.sp) },
                            leadingIcon = {
                                Icon(Icons.Default.Search, contentDescription = null, tint = colInkMuted, modifier = Modifier.size(20.dp))
                            },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = colPrimary,
                                unfocusedBorderColor = colLineSubtle,
                                focusedContainerColor = Color(0xFFF9FAF7),
                                unfocusedContainerColor = Color(0xFFF9FAF7)
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
                                    text = "No apps found matching \"$searchQuery\"",
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
                                        color = if (isAlreadyAdded) Color(0xFFF7F8F5) else Color.White,
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
                                                        color = Color(0xFFEEF1EA),
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
                                                        color = Color(0xFF8A938D),
                                                        maxLines = 1
                                                    )
                                                }
                                            }

                                            if (isAlreadyAdded) {
                                                Surface(
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = Color(0xFFE4E9E2)
                                                ) {
                                                    Text(
                                                        text = "Added",
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = Color(0xFF5F6C64),
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                    )
                                                }
                                            } else {
                                                Surface(
                                                    shape = RoundedCornerShape(12.dp),
                                                    color = colPrimaryContainer
                                                ) {
                                                    Text(
                                                        text = "+ Select",
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
                            color = if (configureIsBlocked) Color(0xFFFBE3E0) else Color(0xFFF7F8F5),
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
                                        text = "Block completely",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (configureIsBlocked) Color(0xFF8C2118) else colInk
                                    )
                                    Text(
                                        text = "Child cannot open this app at all",
                                        fontSize = 12.sp,
                                        color = if (configureIsBlocked) Color(0xFF8C2118) else colInkMuted
                                    )
                                }
                                Switch(
                                    checked = configureIsBlocked,
                                    onCheckedChange = { configureIsBlocked = it },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = Color(0xFF8C2118)
                                    )
                                )
                            }
                        }

                        if (!configureIsBlocked) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Daily Time Limit: ${configureLimitMinutes}m",
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
                                        color = if (isSelected) colPrimary else Color(0xFFEEF1EA),
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { configureLimitMinutes = mins }
                                    ) {
                                        Text(
                                            text = "${mins}m",
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) Color.White else colInk,
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
                                Text("Back", color = colInk)
                            }

                            Button(
                                onClick = {
                                    onUpdateLimit(
                                        AppLimit(
                                            packageName = selected.packageName,
                                            appName = selected.appName,
                                            dailyLimitMinutes = if (configureIsBlocked) 0L else configureLimitMinutes,
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
                                Text("Save Limit", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }

    if (appToDeleteConfirm != null) {
        val target = appToDeleteConfirm!!
        AlertDialog(
            onDismissRequest = { appToDeleteConfirm = null },
            title = {
                Text(
                    text = "Remove App Limit?",
                    fontWeight = FontWeight.Bold,
                    color = colInk
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to remove the limit for \"${target.appName}\"? Your child will have unrestricted access to this app within their global screen time.",
                    fontSize = 13.5.sp,
                    color = colInkMuted
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteLimit(target.packageName)
                        appToDeleteConfirm = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8C2118)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Remove Limit", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { appToDeleteConfirm = null }) {
                    Text("Cancel", color = colInk)
                }
            },
            containerColor = Color.White,
            shape = RoundedCornerShape(20.dp)
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

    val colInk = Color(0xFF17201A)
    val colInkMuted = Color(0xFF4B5750)
    val colPrimary = Color(0xFF1E5B3E)
    val colPrimaryContainer = Color(0xFFDCEBDD)
    val colOnPrimaryContainer = Color(0xFF123D28)
    val colLineSubtle = Color(0xFFE3E7DF)

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
                    text = "Curfew",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = colInk
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Daily limit, bedtime and emergency access.",
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
                color = Color.White,
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
                                text = "Daily screen time",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = colInk
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (policy.isDailyLimitEnabled) "Total phone use allowed per day before it locks." else "Daily limit disabled (Unlimited allowance)",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = colInkMuted
                            )
                        }

                        Switch(
                            checked = policy.isDailyLimitEnabled,
                            onCheckedChange = { onUpdatePolicy(policy.copy(isDailyLimitEnabled = it)) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = colPrimary,
                                uncheckedThumbColor = Color(0xFF6F7A73),
                                uncheckedTrackColor = Color(0xFFEEF1EA),
                                uncheckedBorderColor = Color(0xFF6F7A73)
                            )
                        )
                    }

                    if (policy.isDailyLimitEnabled) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "${policy.dailyTotalLimitMinutes / 60}h ${policy.dailyTotalLimitMinutes % 60}m",
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
                            text = "Whole Day Allowed (Free Days)",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = colInk
                        )
                        Text(
                            text = "Selected days have unlimited daily screen time.",
                            fontSize = 12.sp,
                            color = colInkMuted
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        val daysOfWeek = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
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
                                        .background(if (isSelected) colPrimary else Color(0xFFF5F6F1))
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
                                            text = day,
                                            fontSize = 12.5.sp,
                                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                                            color = if (isSelected) Color.White else colInkMuted
                                        )
                                        if (isSelected) {
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Box(
                                                modifier = Modifier
                                                    .size(4.dp)
                                                    .clip(CircleShape)
                                                    .background(Color.White)
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
                color = Color.White,
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
                                text = "Bedtime lock",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = colInk
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Locks the phone automatically at night.",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = colInkMuted
                            )
                        }

                        Switch(
                            checked = policy.isCurfewScheduled,
                            onCheckedChange = { onUpdatePolicy(policy.copy(isCurfewScheduled = it)) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = colPrimary,
                                uncheckedThumbColor = Color(0xFF6F7A73),
                                uncheckedTrackColor = Color(0xFFEEF1EA),
                                uncheckedBorderColor = Color(0xFF6F7A73)
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
                                color = Color(0xFFEEF1EA),
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
                                        Text("Starts", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = colInkMuted)
                                        Icon(Icons.Default.Edit, contentDescription = "Edit start time", tint = colPrimary, modifier = Modifier.size(15.dp))
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
                                color = Color(0xFFEEF1EA),
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
                                        Text("Ends", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = colInkMuted)
                                        Icon(Icons.Default.Edit, contentDescription = "Edit end time", tint = colPrimary, modifier = Modifier.size(15.dp))
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
                            if (mins > 0) "$hours hours $mins mins locked every night" else "$hours hours locked every night"
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
                color = Color.White,
                border = androidx.compose.foundation.BorderStroke(1.dp, colLineSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFFEEF1EA),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Phone, contentDescription = null, tint = colPrimary, modifier = Modifier.size(20.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("Emergency calls", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = colInk)
                            Text("${childName} can always call this number, even when locked.", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = colInkMuted)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = Color(0xFFEEF1EA),
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
                                Text("Emergency number", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = colInkMuted)
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
                                Text("Call", fontSize = 14.sp, fontWeight = FontWeight.Bold)
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
    isBiometricAvailable: Boolean,
    onBiometricAuth: (() -> Unit) -> Unit,
    childName: String,
    onChildNameChanged: (String) -> Unit,
    onRerunSetup: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var isKeyRevealed by remember { mutableStateOf(false) }
    var showPinDialog by remember { mutableStateOf(false) }
    var showRecoveryResetDialog by remember { mutableStateOf(false) }
    var showEditNameDialog by remember { mutableStateOf(false) }
    var showUninstallConfirmDialog by remember { mutableStateOf(false) }
    val recoveryKey = remember { authManager.getOrInitMasterRecoveryKey() }

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

    val colInk = Color(0xFF17201A)
    val colInkMuted = Color(0xFF4B5750)
    val colPrimary = Color(0xFF1E5B3E)
    val colPrimaryContainer = Color(0xFFDCEBDD)
    val colOnPrimaryContainer = Color(0xFF123D28)
    val colLineSubtle = Color(0xFFE3E7DF)

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
                    text = "Security",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = colInk
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "How you unlock parent controls.",
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
                color = Color.White,
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
                            Text("Kid's Profile", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = colInk)
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
                        Text("Edit", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colPrimary)
                    }
                }
            }
        }

        // Biometrics Card
        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color.White,
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
                            color = Color(0xFFEEF1EA),
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Fingerprint, contentDescription = null, tint = colPrimary, modifier = Modifier.size(24.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text("Biometric unlock", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = colInk)
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(if (isBiometricAvailable) colPrimary else Color(0xFF8C2118))
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isBiometricAvailable) "Fingerprint or face is ready" else "Hardware not enrolled",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isBiometricAvailable) colOnPrimaryContainer else Color(0xFF8C2118)
                                )
                            }
                        }
                    }

                    if (isBiometricAvailable) {
                        Button(
                            onClick = { onBiometricAuth {} },
                            shape = RoundedCornerShape(22.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colPrimary,
                                contentColor = Color.White
                            ),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
                        ) {
                            Text("Scan", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
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
                            Text("Enroll", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = colPrimary)
                        }
                    }
                }
            }
        }

        // Master PIN Card
        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color.White,
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
                            color = Color(0xFFEEF1EA),
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Lock, contentDescription = null, tint = colPrimary, modifier = Modifier.size(22.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text("Master PIN", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = colInk)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text("Change it if you still use the default.", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = colInkMuted)
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { showRecoveryResetDialog = true },
                            shape = RoundedCornerShape(22.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF6F7A73)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Text("Reset", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colInkMuted)
                        }

                        Button(
                            onClick = { showPinDialog = true },
                            shape = RoundedCornerShape(22.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colPrimary,
                                contentColor = Color.White
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            Text("Change", fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Offline recovery key Card
        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color(0xFFEEF1EA),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text("Offline recovery key", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = colInk)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Works without internet if you forget your PIN. Keep it somewhere safe.",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = colInkMuted
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color.White,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.CenterStart,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        ) {
                            Text(
                                text = if (isKeyRevealed) recoveryKey else "SHLD-••••-••••-••••",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 2.sp,
                                color = colOnPrimaryContainer
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                onBiometricAuth { isKeyRevealed = !isKeyRevealed }
                            },
                            shape = RoundedCornerShape(22.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colPrimaryContainer,
                                contentColor = colOnPrimaryContainer
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                        ) {
                            Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (isKeyRevealed) "Hide" else "Reveal", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                onBiometricAuth {
                                    val clipboard = ShieldApplication.instance.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Recovery Key", recoveryKey))
                                    Toast.makeText(ShieldApplication.instance, "Recovery key copied to clipboard!", Toast.LENGTH_SHORT).show()
                                }
                            },
                            shape = RoundedCornerShape(22.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colPrimaryContainer,
                                contentColor = colOnPrimaryContainer
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Copy", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Revealing or copying asks for your fingerprint or face first.",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = colInkMuted
                    )
                }
            }
        }

        // Setup Wizard Card
        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color.White,
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
                            color = Color(0xFFEEF1EA),
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Tune, contentDescription = null, tint = colPrimary, modifier = Modifier.size(22.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text("Setup Wizard", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = colInk)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text("Reconfigure child details or permissions.", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = colInkMuted)
                        }
                    }

                    OutlinedButton(
                        onClick = onRerunSetup,
                        shape = RoundedCornerShape(22.dp),
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, colPrimary),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
                    ) {
                        Text("Rerun", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colPrimary)
                    }
                }
            }
        }

        // Anti-Uninstall Device Admin Card
        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color.White,
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
                            color = if (isDeviceAdminActive) colPrimaryContainer else Color(0xFFFFEBEE),
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Security,
                                    contentDescription = null,
                                    tint = if (isDeviceAdminActive) colPrimary else Color(0xFFC62828),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text("Anti-Uninstall Protection", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = colInk)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                if (isDeviceAdminActive) "Active • Child cannot delete or clear data." else "Inactive • Tap to activate Device Admin.",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (isDeviceAdminActive) colInkMuted else Color(0xFFC62828)
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
                                Text("Active", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colOnPrimaryContainer)
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
                                contentColor = Color.White
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text("Activate", fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Parent Uninstall ShieldChild Card
        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color.White,
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
                            color = Color(0xFFFFEBEE),
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFC62828), modifier = Modifier.size(22.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text("Uninstall ShieldChild", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = colInk)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text("Parent Master PIN required to remove.", fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = colInkMuted)
                        }
                    }

                    OutlinedButton(
                        onClick = { showUninstallConfirmDialog = true },
                        shape = RoundedCornerShape(22.dp),
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFC62828)),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text("Uninstall", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFFC62828))
                    }
                }
            }
        }
    }

    if (showPinDialog) {
        var newPin by remember { mutableStateOf("") }
        var errorMsg by remember { mutableStateOf<String?>(null) }

        Dialog(
            onDismissRequest = { showPinDialog = false }
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = Color.White,
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
                        text = "Set Master PIN",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = colInk,
                        letterSpacing = (-0.3).sp
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Enter a 4 to 8 digit parent security passcode.",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = colInkMuted,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Input Card
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFFEEF1EA),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE3E7DF)),
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
                                value = newPin,
                                onValueChange = {
                                    if (it.length <= 8 && it.all { ch -> ch.isDigit() }) {
                                        newPin = it
                                        errorMsg = null
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
                                modifier = Modifier.fillMaxWidth(),
                                decorationBox = { innerTextField ->
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        if (newPin.isEmpty()) {
                                            Text(
                                                text = "••••",
                                                fontSize = 22.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = colInkMuted.copy(alpha = 0.5f),
                                                letterSpacing = 6.sp
                                            )
                                        }
                                        innerTextField()
                                    }
                                }
                            )
                        }
                    }

                    if (errorMsg != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = errorMsg ?: "",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF8C2118)
                        )
                    }

                    TextButton(
                        onClick = {
                            showPinDialog = false
                            showRecoveryResetDialog = true
                        }
                    ) {
                        Text(
                            text = "Forgot PIN? Use Recovery Key",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colPrimary
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
                                containerColor = Color(0xFFEEF1EA),
                                contentColor = colInkMuted
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Text(
                                text = "Cancel",
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Button(
                            onClick = {
                                if (newPin.length < 4) {
                                    errorMsg = "PIN must be at least 4 digits"
                                } else {
                                    authManager.setMasterPin(newPin)
                                    Toast.makeText(
                                        ShieldApplication.instance,
                                        "Master PIN updated successfully",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    showPinDialog = false
                                }
                            },
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colPrimary,
                                contentColor = Color.White
                            ),
                            modifier = Modifier
                                .weight(1.2f)
                                .height(48.dp)
                        ) {
                            Text(
                                text = "Save PIN",
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }

    if (showRecoveryResetDialog) {
        DashboardRecoveryKeyDialog(
            authManager = authManager,
            onDismiss = { showRecoveryResetDialog = false },
            onRecoverySuccess = {
                showRecoveryResetDialog = false
                Toast.makeText(context, "PIN successfully reset!", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showEditNameDialog) {
        var tempName by remember { mutableStateOf(childName) }
        var nameError by remember { mutableStateOf<String?>(null) }

        Dialog(onDismissRequest = { showEditNameDialog = false }) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = Color.White,
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
                        text = "Edit Child's Name",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = colInk,
                        letterSpacing = (-0.3).sp
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Personalize rules, dashboard header, and emergency calls.",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = colInkMuted,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFFEEF1EA),
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (nameError != null) Color(0xFF8C2118) else Color(0xFFE3E7DF)),
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
                            color = Color(0xFF8C2118)
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
                                containerColor = Color(0xFFEEF1EA),
                                contentColor = colInkMuted
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Text("Cancel", fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                if (tempName.trim().isEmpty()) {
                                    nameError = "Name cannot be empty"
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
                                contentColor = Color.White
                            ),
                            modifier = Modifier
                                .weight(1.2f)
                                .height(48.dp)
                        ) {
                            Text("Save Name", fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
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
                color = Color.White,
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
                        color = Color(0xFFFFEBEE),
                        modifier = Modifier.size(52.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFC62828), modifier = Modifier.size(26.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Uninstall ShieldChild?",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = colInk,
                        letterSpacing = (-0.3).sp
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Enter Parent Master PIN to deactivate device admin protection and proceed with uninstallation.",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = colInkMuted,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFFEEF1EA),
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (uninstallError != null) Color(0xFF8C2118) else Color(0xFFE3E7DF)),
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
                            color = Color(0xFF8C2118)
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
                                containerColor = Color(0xFFEEF1EA),
                                contentColor = colInkMuted
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Text("Cancel", fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
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
                                    uninstallError = "Incorrect Parent Master PIN"
                                }
                            },
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFC62828),
                                contentColor = Color.White
                            ),
                            modifier = Modifier
                                .weight(1.2f)
                                .height(48.dp)
                        ) {
                            Text("Uninstall", fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
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

    val colInk = Color(0xFF17201A)
    val colInkMuted = Color(0xFF4B5750)
    val colPrimary = Color(0xFF1E5B3E)
    val colPrimaryContainer = Color(0xFFDCEBDD)
    val colLineSubtle = Color(0xFFE3E7DF)

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = Color.White,
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
                    text = "Emergency Recovery Key",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = colInk,
                    letterSpacing = (-0.3).sp
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Enter your 16-character master recovery key to reset your parent PIN offline.",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = colInkMuted,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = recoveryKeyInput,
                    onValueChange = { recoveryKeyInput = it.uppercase() },
                    label = { Text("Recovery Key") },
                    placeholder = { Text("SHLD-XXXX-XXXX-XXXX") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = newPinInput,
                    onValueChange = { if (it.length <= 8 && it.all { c -> c.isDigit() }) newPinInput = it },
                    label = { Text("New 4-digit PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = confirmPinInput,
                    onValueChange = { if (it.length <= 8 && it.all { c -> c.isDigit() }) confirmPinInput = it },
                    label = { Text("Confirm New PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(errorMessage!!, color = Color(0xFF8C2118), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
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
                            containerColor = Color(0xFFEEF1EA),
                            contentColor = colInkMuted
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Text("Cancel", fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            when {
                                recoveryKeyInput.isBlank() -> errorMessage = "Please enter your recovery key"
                                newPinInput.length < 4 -> errorMessage = "PIN must be at least 4 digits"
                                newPinInput != confirmPinInput -> errorMessage = "PINs do not match"
                                else -> {
                                    val success = authManager.recoverWithMasterKey(recoveryKeyInput, newPinInput)
                                    if (success) {
                                        onRecoverySuccess()
                                    } else {
                                        errorMessage = "Invalid recovery key. Please check the code."
                                    }
                                }
                            }
                        },
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colPrimary,
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .weight(1.3f)
                            .height(48.dp)
                    ) {
                        Text("Reset PIN", fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
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

    val colInk = Color(0xFF17201A)
    val colInkMuted = Color(0xFF4B5750)
    val colPrimary = Color(0xFF1E5B3E)
    val colPrimaryContainer = Color(0xFFDCEBDD)
    val colLineSubtle = Color(0xFFE3E7DF)

    // Trigger fingerprint prompt automatically if available
    LaunchedEffect(Unit) {
        if (isBiometricAvailable) {
            biometricHelper.promptBiometricAuth(
                title = "Parent Unlock",
                subtitle = "Scan fingerprint to access parent dashboard",
                negativeButtonText = "Use PIN",
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
            .background(Color(0xFFF5F6F1))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = Color.White,
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
                            contentDescription = "Security",
                            tint = colPrimary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text = "Parent Security Gate",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = colInk,
                    letterSpacing = (-0.5).sp
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Enter your Parent Master PIN to access controls.",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = colInkMuted,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                // PIN Input Field
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFFEEF1EA),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE3E7DF)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        BasicTextField(
                            value = enteredPin,
                            onValueChange = {
                                if (it.length <= 8 && it.all { c -> c.isDigit() }) {
                                    enteredPin = it
                                    errorMessage = null
                                    if (it.length >= 4) {
                                        when (val res = authManager.verifyPin(it)) {
                                            AuthResult.Success -> onAuthenticated()
                                            is AuthResult.InvalidPin -> {
                                                if (it.length == 4) {
                                                    errorMessage = "Incorrect PIN. ${res.remainingAttempts} attempts remaining."
                                                }
                                            }
                                            is AuthResult.LockedOut -> {
                                                errorMessage = "Too many attempts. Locked for ${res.remainingSeconds}s."
                                            }
                                        }
                                    }
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
                        color = Color(0xFF8C2118),
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
                                errorMessage = "Incorrect PIN. ${res.remainingAttempts} attempts remaining."
                            }
                            is AuthResult.LockedOut -> {
                                errorMessage = "Too many attempts. Locked for ${res.remainingSeconds}s."
                            }
                        }
                    },
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colPrimary,
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Text("Unlock Dashboard", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }

                if (isBiometricAvailable) {
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            biometricHelper.promptBiometricAuth(
                                title = "Parent Unlock",
                                subtitle = "Scan fingerprint to access parent dashboard",
                                negativeButtonText = "Cancel",
                                onSuccess = onAuthenticated,
                                onError = { _, err -> errorMessage = err.toString() },
                                onFailed = { errorMessage = "Fingerprint not recognized." }
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
                        Text("Use Fingerprint", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = colPrimary)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                TextButton(onClick = { showRecoveryDialog = true }) {
                    Text(
                        text = "Forgot PIN? Use Recovery Key",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colPrimary
                    )
                }
            }
        }
    }
}

