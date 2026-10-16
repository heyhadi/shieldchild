package com.shieldchild.app.ui.onboarding

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
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

    var currentStep by remember { mutableStateOf(1) } // 1: Child Details, 2: Parent Security, 3: Permissions

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
    val colBg = Color(0xFFF5F6F1)
    val colPrimary = Color(0xFF1E5B3E)
    val colPrimaryContainer = Color(0xFFDCEBDD)
    val colOnPrimaryContainer = Color(0xFF123D28)
    val colInk = Color(0xFF17201A)
    val colInkMuted = Color(0xFF4B5750)
    val colLineSubtle = Color(0xFFE3E7DF)
    val colError = Color(0xFF8C2118)

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
                            text = "ShieldChild Setup",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = colInk
                        )
                        Text(
                            text = when (currentStep) {
                                1 -> "Step 1 of 3: Child Profile"
                                2 -> "Step 2 of 3: Parent Security"
                                else -> "Step 3 of 3: Protection Permissions"
                            },
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colInkMuted
                        )
                    }
                }

                // 3 Progress Dots / Pills
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (step in 1..3) {
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
                            biometricHelper.promptBiometricAuth(
                                title = "Parent Biometric Setup",
                                subtitle = "Scan fingerprint to authorize biometric parent unlock",
                                negativeButtonText = "Cancel",
                                onSuccess = {
                                    isFingerprintVerified = true
                                    repo.setBiometricEnabled(true)
                                    Toast.makeText(context, "Parent fingerprint verified!", Toast.LENGTH_SHORT).show()
                                },
                                onError = { _, err ->
                                    Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                },
                                onFailed = {
                                    Toast.makeText(context, "Fingerprint not recognized.", Toast.LENGTH_SHORT).show()
                                }
                            )
                        },
                        onOpenBiometricSettings = {
                            try {
                                context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                            } catch (_: Exception) {
                                Toast.makeText(context, "Please open Android Settings > Security.", Toast.LENGTH_SHORT).show()
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
                        onGrantUsage = {
                            try {
                                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                            } catch (_: Exception) {
                                Toast.makeText(context, "Open Settings > Apps > Special app access > Usage access", Toast.LENGTH_LONG).show()
                            }
                        },
                        onGrantOverlay = {
                            try {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                Toast.makeText(context, "Open Settings > Apps > Special app access > Display over apps", Toast.LENGTH_LONG).show()
                            }
                        },
                        onGrantDeviceAdmin = {
                            try {
                                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ShieldDeviceAdminReceiver.getComponentName(context))
                                    putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "ShieldChild requires Device Administrator to prevent unauthorized uninstallation by children.")
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                Toast.makeText(context, "Open Settings > Security > Device admin apps", Toast.LENGTH_LONG).show()
                            }
                        },
                        onGrantAccessibility = {
                            try {
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            } catch (_: Exception) {
                                Toast.makeText(context, "Open Settings > Accessibility > ShieldChild", Toast.LENGTH_LONG).show()
                            }
                        },
                        onGrantNotification = {
                            try {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    }
                                    context.startActivity(intent)
                                }
                            } catch (_: Exception) {
                                Toast.makeText(context, "Open Settings > Apps > ShieldChild > Notifications", Toast.LENGTH_LONG).show()
                            }
                        },
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
                if (currentStep > 1) {
                    Button(
                        onClick = { currentStep-- },
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFEEF1EA),
                            contentColor = colInkMuted
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Back", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Button(
                    onClick = {
                        when (currentStep) {
                            1 -> {
                                if (childName.trim().isEmpty()) {
                                    step1Error = "Please enter your child's name"
                                } else {
                                    currentStep = 2
                                }
                            }
                            2 -> {
                                when {
                                    pin.length < 4 -> {
                                        step2Error = "PIN must be at least 4 digits"
                                    }
                                    pin != confirmPin -> {
                                        step2Error = "PINs do not match"
                                    }
                                    else -> {
                                        currentStep = 3
                                    }
                                }
                            }
                            3 -> {
                                // Finalize Onboarding
                                repo.saveChildName(childName.trim())
                                authManager.setMasterPin(pin)

                                val currentPolicy = repo.getCurfewPolicy()
                                val updatedPolicy = currentPolicy.copy(
                                    dailyTotalLimitMinutes = dailyLimitMinutes,
                                    emergencyPhoneNumber = emergencyPhone.trim().ifEmpty { "911" }
                                )
                                repo.saveCurfewPolicy(updatedPolicy)
                                repo.setSetupCompleted(true)

                                // Start monitor service
                                try {
                                    val serviceIntent = Intent(context, ShieldMonitorService::class.java)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        context.startForegroundService(serviceIntent)
                                    } else {
                                        context.startService(serviceIntent)
                                    }
                                } catch (_: Exception) {}

                                Toast.makeText(context, "Protection activated for ${childName.trim()}!", Toast.LENGTH_SHORT).show()
                                onSetupFinished()
                            }
                        }
                    },
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colPrimary,
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .weight(if (currentStep > 1) 1.5f else 1f)
                        .height(52.dp)
                ) {
                    Text(
                        text = if (currentStep == 3) "Start Protecting" else "Continue",
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
                color = Color.White,
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
                        text = "Who is this phone for?",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = colInk,
                        letterSpacing = (-0.5).sp
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Enter your child's name to personalize screen time rules, notifications, and emergency dialing.",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = colInkMuted,
                        lineHeight = 19.sp
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        text = "CHILD'S NAME",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = colInkMuted,
                        letterSpacing = 0.8.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFFEEF1EA),
                        border = BorderStroke(1.dp, if (errorMessage != null) colError else Color(0xFFE3E7DF)),
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
                                            text = "e.g. Leo, Emma, Alex",
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
                color = Color.White,
                border = BorderStroke(1.dp, colLineSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(22.dp)) {
                    Text(
                        text = "Daily Screen Time Allowance",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = colInk
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Target limit before total phone curfew locks the device.",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = colInkMuted
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    val presets = listOf(
                        90L to "1h 30m",
                        120L to "2h 00m",
                        150L to "2h 30m",
                        180L to "3h 00m"
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        presets.forEach { (minutes, label) ->
                            val isSelected = dailyLimitMinutes == minutes
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (isSelected) colPrimary else Color(0xFFF5F6F1),
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
                                        color = if (isSelected) Color.White else colInk
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
                color = Color.White,
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
                            Text("Set Master PIN", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = colInk)
                            Text("4 to 8 digit parent passcode", fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = colInkMuted)
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
                            color = Color(0xFFEEF1EA),
                            border = BorderStroke(1.dp, Color(0xFFE3E7DF)),
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
                                                text = "PIN (••••)",
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
                            color = Color(0xFFEEF1EA),
                            border = BorderStroke(1.dp, Color(0xFFE3E7DF)),
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
                                                text = "Confirm (••••)",
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
                color = Color.White,
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
                                color = Color(0xFFEEF1EA),
                                modifier = Modifier.size(42.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Fingerprint, contentDescription = null, tint = colPrimary, modifier = Modifier.size(24.dp))
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Parent Biometric", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = colInk)
                                Text(
                                    text = when {
                                        isFingerprintVerified -> "Fingerprint verified!"
                                        isBiometricAvailable -> "Sensor ready"
                                        else -> "Hardware not enrolled"
                                    },
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isFingerprintVerified || isBiometricAvailable) colPrimary else Color(0xFF8C2118)
                                )
                            }
                        }

                        if (isBiometricAvailable) {
                            Button(
                                onClick = onVerifyFingerprint,
                                shape = RoundedCornerShape(20.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isFingerprintVerified) colPrimaryContainer else colPrimary,
                                    contentColor = if (isFingerprintVerified) colOnPrimaryContainer else Color.White
                                ),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                if (isFingerprintVerified) {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Verified", fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                                } else {
                                    Text("Scan", fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        } else {
                            OutlinedButton(
                                onClick = onOpenBiometricSettings,
                                shape = RoundedCornerShape(20.dp),
                                border = BorderStroke(1.dp, colPrimary),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text("Enroll", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colPrimary)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Android biometrics (fingerprint / face) unlock parent mode in one touch without typing your PIN each time.",
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
                color = Color.White,
                border = BorderStroke(1.dp, colLineSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFFEEF1EA),
                            modifier = Modifier.size(42.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Phone, contentDescription = null, tint = colPrimary, modifier = Modifier.size(22.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text("Emergency SOS Contact", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = colInk)
                            Text(
                                text = "${childName.ifEmpty { "Child" }} can call even when locked.",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = colInkMuted
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFFEEF1EA),
                        border = BorderStroke(1.dp, Color(0xFFE3E7DF)),
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
                                            text = "e.g. 911 or Parent's Phone",
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
                color = Color.White,
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
                        text = "Activate Protection Armor",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = colInk,
                        letterSpacing = (-0.5).sp
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Grant these permissions to enforce screen time limits, block unauthorized app deletion, and prevent bypass attempts.",
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
                color = Color.White,
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
                            Text("Anti-Uninstall Protection", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = colInk)
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Disables the Android uninstall button so your child cannot delete ShieldChild.",
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
                                Text("Active", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colOnPrimaryContainer)
                            }
                        }
                    } else {
                        Button(
                            onClick = onGrantDeviceAdmin,
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colPrimary,
                                contentColor = Color.White
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text("Activate", fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Permission Item 2: Tamper & Bypass Guard (Accessibility)
        item {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = Color.White,
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
                        Text("Settings & App Guard", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = colInk)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Instantly deflects attempts to force stop or tamper with ShieldChild in Settings.",
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
                                Text("Active", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colOnPrimaryContainer)
                            }
                        }
                    } else {
                        Button(
                            onClick = onGrantAccessibility,
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colPrimary,
                                contentColor = Color.White
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text("Grant", fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Permission Item 3: Usage Access
        item {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = Color.White,
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
                        Text("Usage Access", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = colInk)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Tracks time spent in apps like YouTube and TikTok accurately.",
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
                                Text("Granted", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colOnPrimaryContainer)
                            }
                        }
                    } else {
                        Button(
                            onClick = onGrantUsage,
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colPrimary,
                                contentColor = Color.White
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text("Grant", fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Permission Item 4: Display Over Apps
        item {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = Color.White,
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
                        Text("Display Over Apps", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = colInk)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Enables bedtime curfew and instant downtime lock screen overlays.",
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
                                Text("Granted", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colOnPrimaryContainer)
                            }
                        }
                    } else {
                        Button(
                            onClick = onGrantOverlay,
                            shape = RoundedCornerShape(20.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colPrimary,
                                contentColor = Color.White
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text("Grant", fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
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
                    color = Color.White,
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
                            Text("Lockdown Notifications", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = colInk)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Required to immediately launch curfew lock screens on modern Android.",
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
                                    Text("Granted", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colOnPrimaryContainer)
                                }
                            }
                        } else {
                            Button(
                                onClick = onGrantNotification,
                                shape = RoundedCornerShape(20.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colPrimary,
                                    contentColor = Color.White
                                ),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Text("Grant", fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
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
