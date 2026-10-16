package com.shieldchild.app.ui.curfew

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Fingerprint
import androidx.fragment.app.FragmentActivity
import com.shieldchild.app.ShieldApplication
import com.shieldchild.app.data.LockReason
import com.shieldchild.app.engine.AuthResult
import com.shieldchild.app.engine.BiometricAuthHelper
import com.shieldchild.app.engine.ParentAuthManager
import com.shieldchild.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CurfewLockActivity : FragmentActivity() {

    private lateinit var authManager: ParentAuthManager
    private lateinit var biometricHelper: BiometricAuthHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        authManager = ParentAuthManager(ShieldApplication.instance.preferencesRepository)
        biometricHelper = BiometricAuthHelper(this)

        setupLockWindowFlags()
        tryEnterLockTaskMode()

        val lockReasonStr = intent.getStringExtra(EXTRA_LOCK_REASON) ?: LockReason.TOTAL_SCREEN_TIME_EXCEEDED.name
        val lockReason = try {
            LockReason.valueOf(lockReasonStr)
        } catch (_: Exception) {
            LockReason.TOTAL_SCREEN_TIME_EXCEEDED
        }

        setContent {
            ShieldChildTheme(darkTheme = true) {
                CurfewLockScreen(
                    reason = lockReason,
                    onEmergencyCall = { launchEmergencyDialer() },
                    onFingerprintAuth = { triggerBiometricUnlock(30) },
                    onFingerprintExtension = { triggerBiometricUnlock(15) },
                    onSendExtensionSms = { launchExtensionSms() },
                    onUnlockSuccess = {
                        isUnlocked = true
                        val repo = ShieldApplication.instance.preferencesRepository
                        repo.setInstantLock(false)
                        repo.setDailyLimitExceededToday(false)
                        com.shieldchild.app.engine.CurfewLockLauncher.dismissLockNotification(this@CurfewLockActivity)
                        tryStopLockTaskMode()
                        finish()
                    },
                    isFingerprintAvailable = biometricHelper.isBiometricReady(),
                    authManager = authManager
                )
            }
        }
    }

    private fun triggerBiometricUnlock(bonusMinutes: Long = 30) {
        biometricHelper.promptBiometricAuth(
            title = if (bonusMinutes == 15L) "Approve +15 Mins" else "Parent Fingerprint Unlock",
            subtitle = "Scan your fingerprint to grant $bonusMinutes mins bonus time",
            negativeButtonText = "Use Master PIN",
            onSuccess = {
                isUnlocked = true
                val repo = ShieldApplication.instance.preferencesRepository
                repo.setInstantLock(false)
                repo.setDailyLimitExceededToday(false)
                repo.addBonusMinutes(bonusMinutes)
                repo.grantTemporaryCurfewOverride(bonusMinutes)
                com.shieldchild.app.engine.CurfewLockLauncher.dismissLockNotification(this@CurfewLockActivity)
                Toast.makeText(this, "Parent Approved! +$bonusMinutes mins added.", Toast.LENGTH_SHORT).show()
                tryStopLockTaskMode()
                finish()
            },
            onError = { _, errString ->
                Toast.makeText(this, errString, Toast.LENGTH_SHORT).show()
            },
            onFailed = {
                Toast.makeText(this, "Fingerprint not recognized.", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun setupLockWindowFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
    }

    private fun tryEnterLockTaskMode() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
        if (dpm != null && dpm.isLockTaskPermitted(packageName)) {
            try {
                startLockTask()
            } catch (_: Exception) {}
        }
    }

    private fun tryStopLockTaskMode() {
        try {
            stopLockTask()
        } catch (_: Exception) {}
    }

    private var isUnlocked = false
    private var isLaunchingEmergency = false

    override fun onResume() {
        super.onResume()
        isLaunchingEmergency = false
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!isUnlocked && !isLaunchingEmergency) {
            val intent = Intent(this, CurfewLockActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
        }
    }

    override fun onPause() {
        super.onPause()
        if (!isUnlocked && !isLaunchingEmergency) {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            try {
                am?.moveTaskToFront(taskId, ActivityManager.MOVE_TASK_NO_USER_ACTION)
            } catch (_: Exception) {}
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && !isUnlocked && !isLaunchingEmergency) {
            @Suppress("DEPRECATION")
            val closeDialogs = Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
            try {
                sendBroadcast(closeDialogs)
            } catch (_: Exception) {}

            val intent = Intent(this, CurfewLockActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Strictly consume back press - lock screen cannot be dismissed via back key
    }

    private fun launchEmergencyDialer() {
        isLaunchingEmergency = true
        val policy = ShieldApplication.instance.preferencesRepository.getCurfewPolicy()
        val dialIntent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:${policy.emergencyPhoneNumber}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(dialIntent)
    }

    private fun launchExtensionSms() {
        isLaunchingEmergency = true
        val repo = ShieldApplication.instance.preferencesRepository
        val policy = repo.getCurfewPolicy()
        val childName = repo.getChildName().ifEmpty { "Leo" }
        val message = "Hi Mom/Dad, my screen time is locked. Can I please have 15 more minutes? - $childName"
        try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:${policy.emergencyPhoneNumber}")
                putExtra("sms_body", message)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "Unable to open SMS messaging app.", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_LOCK_REASON = "extra_lock_reason"
    }
}

@Composable
fun CurfewLockScreen(
    reason: LockReason,
    onEmergencyCall: () -> Unit,
    onFingerprintAuth: () -> Unit,
    onFingerprintExtension: () -> Unit,
    onSendExtensionSms: () -> Unit,
    onUnlockSuccess: () -> Unit,
    isFingerprintAvailable: Boolean,
    authManager: ParentAuthManager
) {
    var showPinDialog by remember { mutableStateOf(false) }
    var showExtensionDialog by remember { mutableStateOf(false) }
    var pinBonusMinutes by remember { mutableStateOf(30L) }

    val currentTimeStr = remember {
        SimpleDateFormat("hh:mm", Locale.getDefault()).format(Date())
    }

    val headlineText = when (reason) {
        LockReason.TOTAL_SCREEN_TIME_EXCEEDED -> "Downtime Active"
        LockReason.SCHEDULED_BEDTIME_CURFEW -> "Bedtime Curfew"
        LockReason.PARENT_INSTANT_FREEZE -> "Device Paused"
    }

    val subtitleText = when (reason) {
        LockReason.TOTAL_SCREEN_TIME_EXCEEDED -> "Time to unplug • Daily screen time limit reached"
        LockReason.SCHEDULED_BEDTIME_CURFEW -> "Bedtime hours active • Sleep well & recharge"
        LockReason.PARENT_INSTANT_FREEZE -> "Focus mode • Paused by parent"
    }

    // Material You M3 Dark Palette (Slate / Lavender Monet tones)
    val canvasBg = Color(0xFF141218)
    val squircleCardBg = Color(0xFF2B2930)
    val tertiaryPill = Color(0xFFEADBFF)
    val onTertiaryPill = Color(0xFF241432)
    val tonalSurfacePill = Color(0xFF332D41)
    val textPrimary = Color(0xFFE6E0E9)
    val textSecondary = Color(0xFFCAC4D0)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(canvasBg)
            .padding(horizontal = 24.dp, vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxSize()
        ) {
            // Top Section: Google Pixel M3 Lock Clock
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 16.dp)
            ) {
                Text(
                    text = currentTimeStr,
                    fontSize = 68.sp,
                    fontWeight = FontWeight.Bold,
                    color = textPrimary,
                    letterSpacing = (-1).sp
                )
                Text(
                    text = "Downtime Lock",
                    fontSize = 14.sp,
                    color = textSecondary,
                    fontWeight = FontWeight.Medium
                )
            }

            // Center Section: Material You 28dp Squircle Card
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = squircleCardBg,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Stylized Bedtime Moon Badge
                    Box(
                        modifier = Modifier
                            .size(68.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF492532)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bedtime,
                            contentDescription = "Downtime",
                            tint = Color(0xFFFFD8E4),
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    Text(
                        text = headlineText,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = textPrimary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = subtitleText,
                        fontSize = 14.sp,
                        color = textSecondary,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp
                    )
                }
            }

            // Bottom Section: Material You Tactile Pill Actions
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                // Button 1: Expressive Tertiary Lilac Pill
                Button(
                    onClick = { showExtensionDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = tertiaryPill,
                        contentColor = onTertiaryPill
                    ),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Text(
                        text = "Ask for 15 More Mins",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = onTertiaryPill
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Button 2: Scan Parent Fingerprint (M3 Tonal Pill)
                if (isFingerprintAvailable) {
                    Button(
                        onClick = onFingerprintAuth,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = tonalSurfacePill,
                            contentColor = textPrimary
                        ),
                        shape = RoundedCornerShape(27.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Fingerprint,
                            contentDescription = "Fingerprint",
                            tint = Color(0xFFD0BCFF),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Scan Parent Fingerprint",
                            color = textPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Button 3: Outlined Master PIN Pill
                OutlinedButton(
                    onClick = {
                        pinBonusMinutes = 30L
                        showPinDialog = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(25.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF49454E))
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = textSecondary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Parent Passcode",
                        color = textPrimary,
                        fontSize = 14.sp
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Button 4: Emergency Calls
                TextButton(
                    onClick = onEmergencyCall,
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFFB4AB))
                ) {
                    Icon(
                        imageVector = Icons.Default.Phone,
                        contentDescription = "Emergency",
                        tint = Color(0xFFFFB4AB),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Emergency Calls",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = Color(0xFFFFB4AB)
                    )
                }
            }
        }

        if (showExtensionDialog) {
            val repo = ShieldApplication.instance.preferencesRepository
            val policy = repo.getCurfewPolicy()
            ExtensionRequestDialog(
                childName = repo.getChildName().ifEmpty { "Leo" },
                emergencyPhone = policy.emergencyPhoneNumber,
                isFingerprintAvailable = isFingerprintAvailable,
                onFingerprintGrant = {
                    showExtensionDialog = false
                    onFingerprintExtension()
                },
                onPinGrant = {
                    showExtensionDialog = false
                    pinBonusMinutes = 15L
                    showPinDialog = true
                },
                onSendSms = {
                    showExtensionDialog = false
                    onSendExtensionSms()
                },
                onDismiss = { showExtensionDialog = false }
            )
        }

        if (showPinDialog) {
            ParentPinDialog(
                authManager = authManager,
                bonusMinutes = pinBonusMinutes,
                onDismiss = { showPinDialog = false },
                onUnlockSuccess = {
                    showPinDialog = false
                    onUnlockSuccess()
                }
            )
        }
    }
}

@Composable
fun ParentPinDialog(
    authManager: ParentAuthManager,
    bonusMinutes: Long = 30,
    onDismiss: () -> Unit,
    onUnlockSuccess: () -> Unit
) {
    var enteredPin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showRecoveryDialog by remember { mutableStateOf(false) }

    if (showRecoveryDialog) {
        CurfewRecoveryKeyDialog(
            authManager = authManager,
            onDismiss = { showRecoveryDialog = false },
            onRecoverySuccess = {
                showRecoveryDialog = false
                val repo = ShieldApplication.instance.preferencesRepository
                repo.setInstantLock(false)
                repo.addBonusMinutes(bonusMinutes)
                repo.grantTemporaryCurfewOverride(bonusMinutes)
                Toast.makeText(ShieldApplication.instance, "PIN reset successfully! Unlocked.", Toast.LENGTH_SHORT).show()
                onUnlockSuccess()
            }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "Parent Master PIN", fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                Text(
                    text = "Enter your parent security PIN to grant +$bonusMinutes minutes and unlock.",
                    fontSize = 14.sp,
                    color = Color(0xFF94A3B8)
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = enteredPin,
                    onValueChange = { if (it.length <= 8) enteredPin = it },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    label = { Text("PIN") },
                    isError = errorMessage != null,
                    modifier = Modifier.fillMaxWidth()
                )
                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                TextButton(
                    onClick = { showRecoveryDialog = true },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(
                        text = "Forgot PIN? Use Recovery Key",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when (val result = authManager.verifyPin(enteredPin)) {
                        is AuthResult.Success -> {
                            val repo = ShieldApplication.instance.preferencesRepository
                            repo.setInstantLock(false)
                            repo.addBonusMinutes(bonusMinutes)
                            repo.grantTemporaryCurfewOverride(bonusMinutes)
                            onUnlockSuccess()
                        }
                        is AuthResult.InvalidPin -> {
                            errorMessage = "Incorrect PIN. ${result.remainingAttempts} attempts remaining."
                        }
                        is AuthResult.LockedOut -> {
                            errorMessage = "Too many attempts. Locked for ${result.remainingSeconds}s."
                        }
                    }
                }
            ) {
                Text("Unlock")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun ExtensionRequestDialog(
    childName: String,
    emergencyPhone: String,
    isFingerprintAvailable: Boolean,
    onFingerprintGrant: () -> Unit,
    onPinGrant: () -> Unit,
    onSendSms: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "Ask for +15 Minutes", fontWeight = FontWeight.Bold, color = Color(0xFFE6E0E9))
        },
        text = {
            Column {
                Text(
                    text = "A parent can approve +15 minutes in person with fingerprint or PIN, or you can send a text request.",
                    fontSize = 14.sp,
                    color = Color(0xFFCAC4D0),
                    lineHeight = 19.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (isFingerprintAvailable) {
                    Button(
                        onClick = onFingerprintGrant,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFD0BCFF),
                            contentColor = Color(0xFF381E72)
                        )
                    ) {
                        Icon(Icons.Default.Fingerprint, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Parent Fingerprint (+15m)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                Button(
                    onClick = onPinGrant,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4A4458),
                        contentColor = Color(0xFFE8DEF8)
                    )
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Parent Master PIN (+15m)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedButton(
                    onClick = onSendSms,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(24.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF938F99))
                ) {
                    Icon(Icons.Default.Phone, contentDescription = null, tint = Color(0xFFD0BCFF), modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Text Parent ($emergencyPhone)", color = Color(0xFFE6E0E9), fontSize = 14.sp)
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color(0xFFCAC4D0))
            }
        },
        containerColor = Color(0xFF2B2930)
    )
}

@Composable
fun CurfewRecoveryKeyDialog(
    authManager: ParentAuthManager,
    onDismiss: () -> Unit,
    onRecoverySuccess: () -> Unit
) {
    var recoveryKeyInput by remember { mutableStateOf("") }
    var newPinInput by remember { mutableStateOf("") }
    var confirmPinInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "Emergency Recovery Key", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Enter your 16-character Master Recovery Key to reset your parent PIN offline.",
                    fontSize = 13.5.sp,
                    color = Color(0xFF6F7A73)
                )
                OutlinedTextField(
                    value = recoveryKeyInput,
                    onValueChange = { recoveryKeyInput = it.uppercase() },
                    label = { Text("Recovery Key (e.g. SHLD-9A4F...)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = newPinInput,
                    onValueChange = { if (it.length <= 8 && it.all { c -> c.isDigit() }) newPinInput = it },
                    label = { Text("New 4-digit PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = confirmPinInput,
                    onValueChange = { if (it.length <= 8 && it.all { c -> c.isDigit() }) confirmPinInput = it },
                    label = { Text("Confirm New PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.5.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when {
                        recoveryKeyInput.isBlank() -> errorMessage = "Please enter your recovery key"
                        newPinInput.length < 4 -> errorMessage = "New PIN must be at least 4 digits"
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
                }
            ) {
                Text("Reset & Unlock")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
