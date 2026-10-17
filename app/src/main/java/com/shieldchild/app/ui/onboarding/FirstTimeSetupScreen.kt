package com.shieldchild.app.ui.onboarding

import com.shieldchild.app.i18n.str
import com.shieldchild.app.R
import com.shieldchild.app.ui.theme.Shield
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.shieldchild.app.service.ShieldAccessibilityService
import com.shieldchild.app.service.ShieldDeviceAdminReceiver
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.shieldchild.app.ShieldApplication
import com.shieldchild.app.data.DeviceCurfewPolicy
import com.shieldchild.app.engine.BiometricAuthHelper
import com.shieldchild.app.engine.ParentAuthManager
import com.shieldchild.app.service.ShieldMonitorService

@Composable
fun FirstTimeSetupScreen(
    authManager: ParentAuthManager,
    biometricHelper: BiometricAuthHelper,
    isBiometricHardwareAvailable: Boolean,
    onSetupFinished: () -> Unit
) {
    val context = LocalContext.current
    val repo = ShieldApplication.instance.preferencesRepository

    var currentStep by remember { mutableStateOf(1) } // 1: Child Details, 2: Parent Security, 3: Permissions, 4: Protection Active
    var generatedRecoveryKey by remember { mutableStateOf<String?>(null) }

    // --- Step 1 State: Child Details ---
    var childName by remember { mutableStateOf(repo.getChildName().ifEmpty { "Leo" }) }
    var dailyLimitMinutes by remember { mutableStateOf(120L) } // 2h default
    var step1Error by remember { mutableStateOf<String?>(null) }

    // --- Step 2 State: Parent Security ---
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var emergencyPhone by remember { mutableStateOf(repo.getCurfewPolicy().emergencyPhoneNumber) }
    var isFingerprintVerified by remember { mutableStateOf(false) }
    var step2Error by remember { mutableStateOf<String?>(null) }

    // --- Step 3 State: Permissions ---
    var hasUsageStats by remember { mutableStateOf(hasUsageStatsPermission(context)) }
    var hasOverlay by remember { mutableStateOf(hasOverlayPermission(context)) }
    var hasDeviceAdmin by remember { mutableStateOf(hasDeviceAdminPermission(context)) }
    var hasAccessibility by remember { mutableStateOf(hasAccessibilityPermission(context)) }
    var hasNotification by remember { mutableStateOf(hasNotificationPermission(context)) }

    // Lifecycle observer to re-check permissions when user returns from system settings
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasUsageStats = hasUsageStatsPermission(context)
                hasOverlay = hasOverlayPermission(context)
                hasDeviceAdmin = hasDeviceAdminPermission(context)
                hasAccessibility = hasAccessibilityPermission(context)
                hasNotification = hasNotificationPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Design Tokens
    val colBg = Shield.colors.canvas
    val colPrimary = Shield.colors.primary
    val colPrimaryContainer = Shield.colors.primaryContainer
    val colOnPrimaryContainer = Shield.colors.onPrimaryContainer
    val colInk = Shield.colors.ink
    val colInkMuted = Shield.colors.inkMuted
    val colLineSubtle = Shield.colors.line
    val colError = Shield.colors.error

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colBg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
        ) {
            Spacer(modifier = Modifier.height(14.dp))

            // Header & Step Indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = colPrimaryContainer,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Shield,
                                contentDescription = null,
                                tint = colPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = str(R.string.shieldchild_setup),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = colInk
                        )
                        Text(
                            text = when (currentStep) {
                                1 -> str(R.string.step_1_of_3_child)
                                2 -> str(R.string.step_2_of_3_parent)
                                3 -> str(R.string.step_3_of_3_protection)
                                else -> str(R.string.step_4_of_4)
                            },
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colInkMuted
                        )
                    }
                }

                // 4 Progress Dots / Pills
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (step in 1..4) {
                        val isActive = step == currentStep
                        val isDone = step < currentStep
                        Box(
                            modifier = Modifier
                                .size(if (isActive) 24.dp else 10.dp, 10.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(
                                    when {
                                        isActive -> colPrimary
                                        isDone -> colPrimaryContainer
                                        else -> colLineSubtle
                                    }
                                )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Body Content per Step
            Box(modifier = Modifier.weight(1f)) {
                when (currentStep) {
                    1 -> Step1ChildDetails(
                        childName = childName,
                        onChildNameChange = {
                            childName = it
                            step1Error = null
                        },
                        dailyLimitMinutes = dailyLimitMinutes,
                        onDailyLimitChange = { dailyLimitMinutes = it },
                        errorMessage = step1Error,
                        colPrimary = colPrimary,
                        colPrimaryContainer = colPrimaryContainer,
                        colOnPrimaryContainer = colOnPrimaryContainer,
                        colInk = colInk,
                        colInkMuted = colInkMuted,
                        colLineSubtle = colLineSubtle,
                        colError = colError
                    )
                    2 -> Step2ParentSecurity(
                        childName = childName,
                        pin = pin,
                        onPinChange = {
                            if (it.length <= 8 && it.all { ch -> ch.isDigit() }) {
                                pin = it
                                step2Error = null
                            }
                        },
                        confirmPin = confirmPin,
                        onConfirmPinChange = {
                            if (it.length <= 8 && it.all { ch -> ch.isDigit() }) {
                                confirmPin = it
                                step2Error = null
                            }
                        },
                        emergencyPhone = emergencyPhone,
                        onEmergencyPhoneChange = { emergencyPhone = it },
                        isBiometricAvailable = isBiometricHardwareAvailable,
                        isFingerprintVerified = isFingerprintVerified,
                        onVerifyFingerprint = {
                            biometricHelper.enable(
                                onSuccess = {
                                    isFingerprintVerified = true
                                    Toast.makeText(context, str(R.string.parent_fingerprint_turned_on), Toast.LENGTH_SHORT).show()
                                },
                                onError = { err ->
                                    Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                }
                            )
                        },
                        onOpenBiometricSettings = {
                            try {
                                ShieldAccessibilityService.allowParentUninstall() // our own Settings screens below, not tampering
                                context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                            } catch (_: Exception) {
                                Toast.makeText(context, str(R.string.please_open_android_settings_security), Toast.LENGTH_SHORT).show()
                            }
                        },
                        errorMessage = step2Error,
                        colPrimary = colPrimary,
                        colPrimaryContainer = colPrimaryContainer,
                        colOnPrimaryContainer = colOnPrimaryContainer,
                        colInk = colInk,
                        colInkMuted = colInkMuted,
                        colLineSubtle = colLineSubtle,
                        colError = colError
                    )
                    3 -> Step3Permissions(
                        hasUsageStats = hasUsageStats,
                        hasOverlay = hasOverlay,
                        hasDeviceAdmin = hasDeviceAdmin,
                        hasAccessibility = hasAccessibility,
                        hasNotification = hasNotification,
                        showRestrictedHint = isSideloadedOnAndroid13Plus(context),
                        onOpenAppInfo = {
                            try {
                                ShieldAccessibilityService.allowParentUninstall()
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                                )
                            } catch (_: Exception) {
                                Toast.makeText(context, str(R.string.open_settings_accessibility_shieldchild), Toast.LENGTH_LONG).show()
                            }
                        },
                        onGrantUsage = {
                            try {
                                ShieldAccessibilityService.allowParentUninstall()
                                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                            } catch (_: Exception) {
                                Toast.makeText(context, str(R.string.open_settings_apps_special_app), Toast.LENGTH_LONG).show()
                            }
                        },
                        onGrantOverlay = {
                            try {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                ShieldAccessibilityService.allowParentUninstall()
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                Toast.makeText(context, str(R.string.open_settings_apps_special_app_2), Toast.LENGTH_LONG).show()
                            }
                        },
                        onGrantDeviceAdmin = {
                            try {
                                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ShieldDeviceAdminReceiver.getComponentName(context))
                                    putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "ShieldChild requires Device Administrator to prevent unauthorized uninstallation by children.")
                                }
                                ShieldAccessibilityService.allowParentUninstall()
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                Toast.makeText(context, str(R.string.open_settings_security_device_admin), Toast.LENGTH_LONG).show()
                            }
                        },
                        onGrantAccessibility = {
                            try {
                                ShieldAccessibilityService.allowParentUninstall()
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            } catch (_: Exception) {
                                Toast.makeText(context, str(R.string.open_settings_accessibility_shieldchild), Toast.LENGTH_LONG).show()
                            }
                        },
                        onGrantNotification = {
                            try {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    }
                                    ShieldAccessibilityService.allowParentUninstall()
                                    context.startActivity(intent)
                                }
                            } catch (_: Exception) {
                                Toast.makeText(context, str(R.string.open_settings_apps_shieldchild_notifications), Toast.LENGTH_LONG).show()
                            }
                        },
                        colPrimary = colPrimary,
                        colPrimaryContainer = colPrimaryContainer,
                        colOnPrimaryContainer = colOnPrimaryContainer,
                        colInk = colInk,
                        colInkMuted = colInkMuted,
                        colLineSubtle = colLineSubtle
                    )
                    else -> Step4ProtectionActive(
                        childName = childName,
                        dailyLimitMinutes = dailyLimitMinutes,
                        recoveryKey = generatedRecoveryKey ?: "",
                        colPrimary = colPrimary,
                        colPrimaryContainer = colPrimaryContainer,
                        colOnPrimaryContainer = colOnPrimaryContainer,
                        colInk = colInk,
                        colInkMuted = colInkMuted,
                        colLineSubtle = colLineSubtle
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Bottom Navigation Actions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (currentStep in 2..3) {
                    Button(
                        onClick = { currentStep-- },
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Shield.colors.surfaceVariant,
                            contentColor = colInkMuted
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(str(R.string.back), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Button(
                    onClick = {
                        when (currentStep) {
                            1 -> {
                                if (childName.trim().isEmpty()) {
                                    step1Error = str(R.string.please_enter_your_child_s)
                                } else {
                                    currentStep = 2
                                }
                            }
                            2 -> {
                                when {
                                    pin.length < 4 -> {
                                        step2Error = str(R.string.pin_must_be_at_least)
                                    }
                                    pin != confirmPin -> {
                                        step2Error = str(R.string.pins_do_not_match)
                                    }
                                    else -> {
                                        currentStep = 3
                                    }
                                }
                            }
                            3 -> {
                                // Finalize Onboarding & Save
                                repo.saveChildName(childName.trim())
                                authManager.setMasterPin(pin)

                                val currentPolicy = repo.getCurfewPolicy()
                                val updatedPolicy = currentPolicy.copy(
                                    dailyTotalLimitMinutes = dailyLimitMinutes,
                                    emergencyPhoneNumber = emergencyPhone.trim()
                                )
                                repo.saveCurfewPolicy(updatedPolicy)
                                repo.setSetupCompleted(true)

                                // Generate Master Recovery Key now so it is safely held in Keystore/PBKDF2
                                generatedRecoveryKey = authManager.generateRecoveryKey()

                                // Start monitor service
                                try {
                                    val serviceIntent = Intent(context, ShieldMonitorService::class.java)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        context.startForegroundService(serviceIntent)
                                    } else {
                                        context.startService(serviceIntent)
                                    }
                                } catch (_: Exception) {}

                                currentStep = 4
                            }
                            else -> {
                                // Step 4: Parent noted recovery key and taps "Go to Dashboard Overview"
                                onSetupFinished()
                            }
                        }
                    },
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colPrimary,
                        contentColor = Shield.colors.onPrimary
                    ),
                    modifier = Modifier
                        .weight(if (currentStep in 2..3) 1.5f else 1f)
                        .height(52.dp)
                ) {
                    Text(
                        text = when (currentStep) {
                            4 -> str(R.string.btn_go_to_dashboard)
                            3 -> str(R.string.start_protecting)
                            else -> str(R.string.action_continue)
                        },
                        fontSize = 15.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/* =========================================================================
   STEP 1: CHILD PROFILE
   ========================================================================= */

@Composable
private fun Step1ChildDetails(
    childName: String,
    onChildNameChange: (String) -> Unit,
    dailyLimitMinutes: Long,
    onDailyLimitChange: (Long) -> Unit,
    errorMessage: String?,
    colPrimary: Color,
    colPrimaryContainer: Color,
    colOnPrimaryContainer: Color,
    colInk: Color,
    colInkMuted: Color,
    colLineSubtle: Color,
    colError: Color
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp)
    ) {
        item {
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = Shield.colors.card,
                border = BorderStroke(1.dp, colLineSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(22.dp)) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = colPrimaryContainer,
                        modifier = Modifier.size(52.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Face, contentDescription = null, tint = colPrimary, modifier = Modifier.size(28.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = str(R.string.who_is_this_phone_for),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = colInk,
                        letterSpacing = (-0.5).sp
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = str(R.string.enter_your_child_s_name),
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = colInkMuted,
                        lineHeight = 19.sp
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        text = str(R.string.child_s_name),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = colInkMuted,
                        letterSpacing = 0.8.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Shield.colors.surfaceVariant,
                        border = BorderStroke(1.dp, if (errorMessage != null) colError else Shield.colors.line),
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
                                value = childName,
                                onValueChange = onChildNameChange,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                                textStyle = TextStyle(
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = colInk
                                ),
                                modifier = Modifier.fillMaxWidth(),
                                decorationBox = { innerTextField ->
                                    if (childName.isEmpty()) {
                                        Text(
                                            text = str(R.string.e_g_leo_emma_alex),
                                            fontSize = 17.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = colInkMuted.copy(alpha = 0.5f)
                                        )
                                    }
                                    innerTextField()
                                }
                            )
                        }
                    }

                    if (errorMessage != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = errorMessage,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colError
                        )
                    }
                }
            }
        }

        // Daily Screen Time Allowance Card
        item {
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = Shield.colors.card,
                border = BorderStroke(1.dp, colLineSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(22.dp)) {
                    Text(
                        text = str(R.string.daily_screen_time_allowance),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = colInk
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = str(R.string.target_limit_before_total_phone),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = colInkMuted
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    val presets = listOf(
                        90L to str(R.string.t_1h_30m),
                        120L to str(R.string.t_2h_00m),
                        150L to str(R.string.t_2h_30m),
                        180L to str(R.string.t_3h_00m)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        presets.forEach { (minutes, label) ->
                            val isSelected = dailyLimitMinutes == minutes
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (isSelected) colPrimary else Shield.colors.canvas,
                                border = if (isSelected) null else BorderStroke(1.dp, colLineSubtle),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) { onDailyLimitChange(minutes) }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = label,
                                        fontSize = 13.sp,
                                        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                                        color = if (isSelected) Shield.colors.onPrimary else colInk
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

/* =========================================================================
   STEP 2: PARENT SECURITY CREDENTIALS
   ========================================================================= */

@Composable
private fun Step2ParentSecurity(
    childName: String,
    pin: String,
    onPinChange: (String) -> Unit,
    confirmPin: String,
    onConfirmPinChange: (String) -> Unit,
    emergencyPhone: String,
    onEmergencyPhoneChange: (String) -> Unit,
    isBiometricAvailable: Boolean,
    isFingerprintVerified: Boolean,
    onVerifyFingerprint: () -> Unit,
    onOpenBiometricSettings: () -> Unit,
    errorMessage: String?,
    colPrimary: Color,
    colPrimaryContainer: Color,
    colOnPrimaryContainer: Color,
    colInk: Color,
    colInkMuted: Color,
    colLineSubtle: Color,
    colError: Color
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp)
    ) {
        // Master PIN Card
        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Shield.colors.card,
                border = BorderStroke(1.dp, colLineSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = colPrimaryContainer,
                            modifier = Modifier.size(42.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Lock, contentDescription = null, tint = colPrimary, modifier = Modifier.size(22.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(str(R.string.set_master_pin), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = colInk)
                            Text(str(R.string.t_4_to_8_digit_parent), fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = colInkMuted)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // PIN Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Enter PIN
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Shield.colors.surfaceVariant,
                            border = BorderStroke(1.dp, Shield.colors.line),
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                BasicTextField(
                                    value = pin,
                                    onValueChange = onPinChange,
                                    visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                    textStyle = TextStyle(
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = colInk,
                                        letterSpacing = 4.sp,
                                        textAlign = TextAlign.Center
                                    ),
                                    modifier = Modifier.fillMaxWidth(),
                                    decorationBox = { innerTextField ->
                                        if (pin.isEmpty()) {
                                            Text(
                                                text = str(R.string.pin),
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = colInkMuted.copy(alpha = 0.5f),
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                        innerTextField()
                                    }
                                )
                            }
                        }

                        // Confirm PIN
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Shield.colors.surfaceVariant,
                            border = BorderStroke(1.dp, Shield.colors.line),
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                BasicTextField(
                                    value = confirmPin,
                                    onValueChange = onConfirmPinChange,
                                    visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                    textStyle = TextStyle(
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = colInk,
                                        letterSpacing = 4.sp,
                                        textAlign = TextAlign.Center
                                    ),
                                    modifier = Modifier.fillMaxWidth(),
                                    decorationBox = { innerTextField ->
                                        if (confirmPin.isEmpty()) {
                                            Text(
                                                text = str(R.string.confirm_2),
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = colInkMuted.copy(alpha = 0.5f),
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                        innerTextField()
                                    }
                                )
                            }
                        }
                    }

                    if (errorMessage != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = errorMessage,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colError
                        )
                    }
                }
            }
        }

        // Parent Biometric / Fingerprint Card
        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Shield.colors.card,
                border = BorderStroke(1.dp, colLineSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Shield.colors.surfaceVariant,
                                modifier = Modifier.size(42.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Fingerprint, contentDescription = null, tint = colPrimary, modifier = Modifier.size(24.dp))
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(str(R.string.parent_biometric), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = colInk)
                                Text(
                                    text = when {
                                        isFingerprintVerified -> str(R.string.fingerprint_unlock_on)
                                        isBiometricAvailable -> str(R.string.optional)
                                        else -> str(R.string.hardware_not_enrolled)
                                    },
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isFingerprintVerified || isBiometricAvailable) colPrimary else Shield.colors.error
                                )
                            }
                        }

                        if (isBiometricAvailable) {
                            Button(
                                onClick = onVerifyFingerprint,
                                shape = RoundedCornerShape(20.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isFingerprintVerified) colPrimaryContainer else colPrimary,
                                    contentColor = if (isFingerprintVerified) colOnPrimaryContainer else Shield.colors.onPrimary
                                ),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                if (isFingerprintVerified) {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(str(R.string.on), fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                                } else {
                                    Text(str(R.string.turn_on), fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        } else {
                            OutlinedButton(
                                onClick = onOpenBiometricSettings,
                                shape = RoundedCornerShape(20.dp),
                                border = BorderStroke(1.dp, colPrimary),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(str(R.string.enroll), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colPrimary)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = str(R.string.only_turn_this_on_if),
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = colInkMuted,
                        lineHeight = 17.sp
                    )
                }
            }
        }

        // Emergency SOS Phone Card
        item {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Shield.colors.card,
                border = BorderStroke(1.dp, colLineSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Shield.colors.surfaceVariant,
                            modifier = Modifier.size(42.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Phone, contentDescription = null, tint = colPrimary, modifier = Modifier.size(22.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(str(R.string.emergency_sos_contact), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = colInk)
                            Text(
                                text = str(R.string.can_call_even_when_locked, childName.ifEmpty { str(R.string.your_child) }),
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = colInkMuted
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Shield.colors.surfaceVariant,
                        border = BorderStroke(1.dp, Shield.colors.line),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BasicTextField(
                                value = emergencyPhone,
                                onValueChange = onEmergencyPhoneChange,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                textStyle = TextStyle(
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = colInk
                                ),
                                modifier = Modifier.fillMaxWidth(),
                                decorationBox = { innerTextField ->
                                    if (emergencyPhone.isEmpty()) {
                                        Text(
                                            text = str(R.string.e_g_911_or_parent),
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = colInkMuted.copy(alpha = 0.5f)
                                        )
                                    }
                                    innerTextField()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/* =========================================================================
   STEP 3: ESSENTIAL PERMISSIONS
   ========================================================================= */

@Composable
private fun Step3Permissions(
    hasUsageStats: Boolean,
    hasOverlay: Boolean,
    hasDeviceAdmin: Boolean,
    hasAccessibility: Boolean,
    hasNotification: Boolean,
    showRestrictedHint: Boolean,
    onOpenAppInfo: () -> Unit,
    onGrantUsage: () -> Unit,
    onGrantOverlay: () -> Unit,
    onGrantDeviceAdmin: () -> Unit,
    onGrantAccessibility: () -> Unit,
    onGrantNotification: () -> Unit,
    colPrimary: Color,
    colPrimaryContainer: Color,
    colOnPrimaryContainer: Color,
    colInk: Color,
    colInkMuted: Color,
    colLineSubtle: Color
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 12.dp)
    ) {
        item {
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = Shield.colors.card,
                border = BorderStroke(1.dp, colLineSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(22.dp)) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = colPrimaryContainer,
                        modifier = Modifier.size(52.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Security, contentDescription = null, tint = colPrimary, modifier = Modifier.size(28.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = str(R.string.activate_protection_armor),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = colInk,
                        letterSpacing = (-0.5).sp
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = str(R.string.grant_these_permissions_to_enforce),
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = colInkMuted,
                        lineHeight = 19.sp
                    )
                }
            }
        }

        // Permission Item 1: Anti-Uninstall Device Admin (CRITICAL)
        item {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = Shield.colors.card,
                border = BorderStroke(1.dp, colLineSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(str(R.string.anti_uninstall_protection), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = colInk)
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = str(R.string.disables_the_android_uninstall_button),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = colInkMuted
                        )
                    }

                    if (hasDeviceAdmin) {
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
                            onClick = onGrantDeviceAdmin,
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colPrimary,
                                contentColor = Shield.colors.onPrimary
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(str(R.string.activate), fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Permission Item 2: Tamper & Bypass Guard (Accessibility)
        item {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = Shield.colors.card,
                border = BorderStroke(1.dp, colLineSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(str(R.string.settings_app_guard), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = colInk)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = str(R.string.instantly_deflects_attempts_to_force),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = colInkMuted
                        )
                    }

                    if (hasAccessibility) {
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
                            onClick = onGrantAccessibility,
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colPrimary,
                                contentColor = Shield.colors.onPrimary
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(str(R.string.grant), fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        if (!hasAccessibility && showRestrictedHint) {
            item {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = colPrimaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(str(R.string.restricted_setting_title), fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = colOnPrimaryContainer)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(str(R.string.restricted_setting_steps), fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = colOnPrimaryContainer, lineHeight = 18.sp)
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedButton(onClick = onOpenAppInfo, shape = RoundedCornerShape(20.dp)) {
                            Text(str(R.string.open_app_info), fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = colPrimary)
                        }
                    }
                }
            }
        }

        // Permission Item 3: Usage Access
        item {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = Shield.colors.card,
                border = BorderStroke(1.dp, colLineSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(str(R.string.usage_access), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = colInk)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = str(R.string.tracks_time_spent_in_apps),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = colInkMuted
                        )
                    }

                    if (hasUsageStats) {
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
                                Text(str(R.string.granted), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colOnPrimaryContainer)
                            }
                        }
                    } else {
                        Button(
                            onClick = onGrantUsage,
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colPrimary,
                                contentColor = Shield.colors.onPrimary
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(str(R.string.grant), fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Permission Item 4: Display Over Apps
        item {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = Shield.colors.card,
                border = BorderStroke(1.dp, colLineSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(str(R.string.display_over_apps), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = colInk)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = str(R.string.enables_bedtime_curfew_and_instant),
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = colInkMuted
                        )
                    }

                    if (hasOverlay) {
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
                                Text(str(R.string.granted), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colOnPrimaryContainer)
                            }
                        }
                    } else {
                        Button(
                            onClick = onGrantOverlay,
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colPrimary,
                                contentColor = Shield.colors.onPrimary
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(str(R.string.grant), fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Permission Item 5: Lockdown Notifications (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            item {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = Shield.colors.card,
                    border = BorderStroke(1.dp, colLineSubtle),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(str(R.string.lockdown_notifications), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = colInk)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = str(R.string.required_to_immediately_launch_curfew),
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = colInkMuted
                            )
                        }

                        if (hasNotification) {
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
                                    Text(str(R.string.granted), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colOnPrimaryContainer)
                                }
                            }
                        } else {
                            Button(
                                onClick = onGrantNotification,
                                shape = RoundedCornerShape(20.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colPrimary,
                                    contentColor = Shield.colors.onPrimary
                                ),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Text(str(R.string.grant), fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

// Helpers
private fun hasUsageStatsPermission(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
    } else {
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
    }
    return mode == AppOpsManager.MODE_ALLOWED
}

private fun hasOverlayPermission(context: Context): Boolean {
    return Settings.canDrawOverlays(context)
}

private fun hasDeviceAdminPermission(context: Context): Boolean {
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return false
    return dpm.isAdminActive(ShieldDeviceAdminReceiver.getComponentName(context))
}

/** Android 13+ greys out Accessibility ("Restricted setting") for apps not installed from Play. */
private fun isSideloadedOnAndroid13Plus(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    return try {
        context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName != "com.android.vending"
    } catch (_: Exception) {
        true
    }
}

private fun hasAccessibilityPermission(context: Context): Boolean {
    val expectedComponentName = "${context.packageName}/${ShieldAccessibilityService::class.java.canonicalName}"
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: ""
    return enabledServices.contains(expectedComponentName) || enabledServices.contains(context.packageName)
}

private fun hasNotificationPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
}

@Composable
private fun Step4ProtectionActive(
    childName: String,
    dailyLimitMinutes: Long,
    recoveryKey: String,
    colPrimary: Color,
    colPrimaryContainer: Color,
    colOnPrimaryContainer: Color,
    colInk: Color,
    colInkMuted: Color,
    colLineSubtle: Color
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var isCopied by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 10.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Spacer(modifier = Modifier.height(10.dp))
            // Shield celebration badge
            Surface(
                shape = CircleShape,
                color = colPrimaryContainer,
                border = BorderStroke(2.dp, colPrimary.copy(alpha = 0.6f)),
                modifier = Modifier.size(76.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = colPrimary,
                        modifier = Modifier.size(42.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = str(R.string.protection_is_now_active),
                fontSize = 21.sp,
                fontWeight = FontWeight.ExtraBold,
                color = colInk,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = str(R.string.protection_active_desc, childName.trim()),
                fontSize = 14.sp,
                color = colInkMuted,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }

        item {
            // Master Recovery Key Box
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Shield.colors.card,
                border = BorderStroke(1.dp, colLineSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Key,
                                contentDescription = null,
                                tint = colPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = str(R.string.your_master_recovery_key),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = colInk
                            )
                        }

                        TextButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(recoveryKey))
                                isCopied = true
                                Toast.makeText(context, str(R.string.key_copied_to_clipboard), Toast.LENGTH_SHORT).show()
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            if (isCopied) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = colPrimary,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Text(
                                text = str(R.string.copy_key),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = colPrimary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Monospace Key Text Container
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Shield.colors.surfaceVariant,
                        border = BorderStroke(1.dp, colPrimary.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = recoveryKey,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 2.sp,
                            color = colInk,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 14.dp, horizontal = 8.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = str(R.string.recovery_key_warning),
                        fontSize = 12.sp,
                        color = colInkMuted,
                        lineHeight = 16.5.sp
                    )
                }
            }
        }

        item {
            // Summary Info Row
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Shield.colors.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = str(R.string.daily_allowance),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colInkMuted
                        )
                        Text(
                            text = "${dailyLimitMinutes / 60}h ${dailyLimitMinutes % 60}m",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = colInk
                        )
                    }

                    Box(
                        modifier = Modifier
                            .height(24.dp)
                            .width(1.dp)
                            .background(colLineSubtle)
                    )

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = str(R.string.curfew),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colInkMuted
                        )
                        Text(
                            text = "8:00 PM – 7:00 AM",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = colInk
                        )
                    }

                    Box(
                        modifier = Modifier
                            .height(24.dp)
                            .width(1.dp)
                            .background(colLineSubtle)
                    )

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = str(R.string.method_pin),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colInkMuted
                        )
                        Text(
                            text = "••••",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = colPrimary
                        )
                    }
                }
            }
        }
    }
}

