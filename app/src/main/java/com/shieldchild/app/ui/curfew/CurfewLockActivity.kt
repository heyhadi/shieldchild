package com.shieldchild.app.ui.curfew

import com.shieldchild.app.i18n.str
import com.shieldchild.app.R
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
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Key
import com.shieldchild.app.ui.components.DialogError
import com.shieldchild.app.ui.components.PinField
import com.shieldchild.app.ui.components.DialogOption
import com.shieldchild.app.ui.components.ShieldDialog
import com.shieldchild.app.data.GrantMethod
import com.shieldchild.app.data.LockReason
import com.shieldchild.app.engine.AccessGrants
import com.shieldchild.app.engine.PhoneGuard
import com.shieldchild.app.engine.RedeemResult
import com.shieldchild.app.engine.RemoteCodeManager
import com.shieldchild.app.engine.AuthResult
import com.shieldchild.app.engine.BiometricAuthHelper
import com.shieldchild.app.engine.ParentAuthManager
import com.shieldchild.app.ui.theme.*
import java.text.SimpleDateFormat
import kotlinx.coroutines.delay
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
        lockReason = try {
            LockReason.valueOf(lockReasonStr)
        } catch (_: Exception) {
            LockReason.TOTAL_SCREEN_TIME_EXCEEDED
        }

        setContent {
            // Re-check the rules while shown: close once nothing should be locked (e.g. a
            // relaunch that raced with a parent's choice) and keep the reason up to date.
            LaunchedEffect(Unit) {
                while (true) {
                    delay(2_000)
                    val repo = ShieldApplication.instance.preferencesRepository
                    val current = com.shieldchild.app.engine.CurfewLockLauncher.evaluateRebootLockReason(
                        policy = repo.getCurfewPolicy(),
                        isDailyExceeded = repo.isDailyLimitExceededToday(),
                        access = repo.getAccessState()
                    )
                    if (current == null && authMethod == null) {
                        closeLock()
                        break
                    }
                    if (current != null) lockReason = current
                }
            }
            ShieldChildTheme(darkTheme = true) {
                CurfewLockScreen(
                    reason = lockReason,
                    onEmergencyCall = { launchEmergencyDialer() },
                    onCallParent = { callParent() },
                    onFingerprintAuth = { triggerBiometricUnlock() },
                    onSendExtensionSms = { launchExtensionSms() },
                    onParentAuthenticated = { method -> authMethod = method },
                    isFingerprintAvailable = biometricHelper.isBiometricReady(),
                    authManager = authManager,
                    isRemoteCodeSetUp = remoteCodes.isSetUp(),
                    onRedeemCode = { code -> redeemRemoteCode(code, lockReason) }
                )
                authMethod?.let { method ->
                    ParentChoiceDialog(
                        reason = lockReason,
                        onChoice = { choice -> applyParentChoice(choice, lockReason, method) },
                        onDismiss = { authMethod = null }
                    )
                }
            }
        }
    }

    private val remoteCodes by lazy {
        RemoteCodeManager(ShieldApplication.instance.preferencesRepository, authManager)
    }

    /** Returns an error message for the child, or null when the code unlocked the phone. */
    private fun redeemRemoteCode(code: String, reason: LockReason): String? {
        return when (val result = remoteCodes.redeem(code)) {
            is RedeemResult.Success -> {
                AccessGrants.grantMinutes(this, ShieldApplication.instance.preferencesRepository, result.minutes, reason, GrantMethod.REMOTE_CODE)
                Toast.makeText(this, str(R.string.unlocked_by_parent_code_for, result.minutes), Toast.LENGTH_LONG).show()
                closeLock()
                null
            }
            is RedeemResult.Invalid -> str(R.string.code_not_accepted_tries_left, result.remainingAttempts)
            is RedeemResult.LockedOut -> str(R.string.too_many_wrong_codes_try, result.remainingSeconds / 60 + 1)
            RedeemResult.DailyCapReached -> str(R.string.today_s_code_unlocks_are)
            RedeemResult.NotSetUp -> str(R.string.remote_codes_aren_t_set)
        }
    }

    private var lockReason by mutableStateOf(LockReason.TOTAL_SCREEN_TIME_EXCEEDED)

    private fun closeLock() {
        isUnlocked = true
        com.shieldchild.app.engine.CurfewLockLauncher.dismissLockNotification(this)
        tryStopLockTaskMode()
        finish()
    }

    /** Set once a parent has signed in on this screen; shows the choice dialog. */
    private var authMethod by mutableStateOf<GrantMethod?>(null)

    private fun triggerBiometricUnlock() {
        biometricHelper.promptBiometricAuth(
            title = str(R.string.parent_fingerprint),
            subtitle = str(R.string.scan_the_parent_s_fingerprint),
            negativeButtonText = str(R.string.cancel),
            onSuccess = { authMethod = GrantMethod.FINGERPRINT },
            onError = { _, errString ->
                Toast.makeText(this, errString, Toast.LENGTH_SHORT).show()
            },
            onFailed = {
                Toast.makeText(this, str(R.string.fingerprint_not_recognized), Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun applyParentChoice(choice: ParentChoice, reason: LockReason, method: GrantMethod) {
        val repo = ShieldApplication.instance.preferencesRepository
        when (choice) {
            ParentChoice.ParentAccess -> {
                AccessGrants.startParentSession(this, repo, method)
                Toast.makeText(this, str(R.string.parent_access_locks_again_when), Toast.LENGTH_LONG).show()
            }
            is ParentChoice.ExtraMinutes -> {
                AccessGrants.grantMinutes(this, repo, choice.minutes, reason, method)
                Toast.makeText(this, str(R.string.min_given, choice.minutes), Toast.LENGTH_SHORT).show()
            }
            ParentChoice.RestOfToday -> AccessGrants.grantRestOfToday(this, repo, reason, method)
            ParentChoice.InstantLockOff -> AccessGrants.turnOffInstantLock(this, repo, method)
        }
        authMethod = null
        closeLock()
    }

    private fun setupLockWindowFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        if (!resources.getBoolean(R.bool.allow_screenshots)) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
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

    override fun onStart() {
        super.onStart()
        isShowing = true
        // The full-screen notification only exists to bring this screen up (e.g. after a reboot);
        // once it's showing, the notification would just sit on top of it.
        com.shieldchild.app.engine.CurfewLockLauncher.dismissLockNotification(this)
    }

    override fun onStop() {
        super.onStop()
        isShowing = false
    }

    override fun onResume() {
        super.onResume()
        com.shieldchild.app.ui.dashboard.ParentDashboardActivity.isParentAuthenticatedState.value = false
        isLaunchingEmergency = false
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!isUnlocked && !isLaunchingEmergency && !PhoneGuard.isInCall(this)) {
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
        if (!isUnlocked && !isLaunchingEmergency && !PhoneGuard.isInCall(this)) {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            try {
                am?.moveTaskToFront(taskId, ActivityManager.MOVE_TASK_NO_USER_ACTION)
            } catch (_: Exception) {}
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && !isUnlocked && !isLaunchingEmergency && !PhoneGuard.isInCall(this)) {
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

    /** Android's own emergency dialer: knows the local number (112/911/995…) and works without a SIM. */
    private fun launchEmergencyDialer() {
        isLaunchingEmergency = true
        val emergency = Intent("com.android.phone.EmergencyDialer.DIAL").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(emergency)
        } catch (_: Exception) {
            startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun callParent() {
        val number = ShieldApplication.instance.preferencesRepository.getCurfewPolicy().emergencyPhoneNumber
        if (number.isBlank()) return
        isLaunchingEmergency = true
        startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun launchExtensionSms() {
        isLaunchingEmergency = true
        val repo = ShieldApplication.instance.preferencesRepository
        val policy = repo.getCurfewPolicy()
        val childName = repo.getChildName().ifEmpty { "Leo" }
        val message = str(R.string.my_phone_is_locked_can, childName)
        try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:${policy.emergencyPhoneNumber}")
                putExtra("sms_body", message)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, str(R.string.unable_to_open_sms_messaging), Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_LOCK_REASON = "extra_lock_reason"

        /** True while the lock screen is visible, so the monitor doesn't relaunch it every check. */
        @Volatile
        var isShowing = false
            private set
    }
}

@Composable
fun CurfewLockScreen(
    reason: LockReason,
    onEmergencyCall: () -> Unit,
    onCallParent: () -> Unit,
    onFingerprintAuth: () -> Unit,
    onSendExtensionSms: () -> Unit,
    onParentAuthenticated: (GrantMethod) -> Unit,
    isFingerprintAvailable: Boolean,
    authManager: ParentAuthManager,
    isRemoteCodeSetUp: Boolean,
    onRedeemCode: (String) -> String?
) {
    var showPinDialog by remember { mutableStateOf(false) }
    var showCodeDialog by remember { mutableStateOf(false) }
    var showExtensionDialog by remember { mutableStateOf(false) }

    val currentTimeStr = remember {
        SimpleDateFormat("hh:mm", Locale.getDefault()).format(Date())
    }

    val headlineText = when (reason) {
        LockReason.TOTAL_SCREEN_TIME_EXCEEDED -> str(R.string.downtime_active)
        LockReason.SCHEDULED_BEDTIME_CURFEW -> str(R.string.bedtime_curfew)
        LockReason.PARENT_INSTANT_FREEZE -> str(R.string.device_paused)
    }

    val subtitleText = when (reason) {
        LockReason.TOTAL_SCREEN_TIME_EXCEEDED -> str(R.string.time_to_unplug_daily_screen)
        LockReason.SCHEDULED_BEDTIME_CURFEW -> str(R.string.bedtime_hours_active_sleep_well)
        LockReason.PARENT_INSTANT_FREEZE -> str(R.string.focus_mode_paused_by_parent)
    }

    // Material You M3 Dark Palette (Slate / Lavender Monet tones)
    val canvasBg = Shield.colors.canvas
    val squircleCardBg = Shield.colors.card
    val tertiaryPill = Shield.colors.primaryContainer
    val onTertiaryPill = Shield.colors.onPrimaryContainer
    val tonalSurfacePill = Shield.colors.surfaceVariant
    val textPrimary = Shield.colors.ink
    val textSecondary = Shield.colors.inkMuted

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
                    text = str(R.string.downtime_lock),
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
                            .background(Shield.colors.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bedtime,
                            contentDescription = str(R.string.downtime),
                            tint = Shield.colors.onPrimaryContainer,
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
                        text = str(R.string.ask_for_more_time),
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
                            contentDescription = str(R.string.fingerprint),
                            tint = Shield.colors.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = str(R.string.scan_parent_fingerprint),
                            color = textPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Button 3: Outlined Master PIN Pill
                OutlinedButton(
                    onClick = { showPinDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(25.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Shield.colors.line)
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = textSecondary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = str(R.string.parent_passcode),
                        color = textPrimary,
                        fontSize = 14.sp
                    )
                }

                if (isRemoteCodeSetUp) {
                    Spacer(modifier = Modifier.height(10.dp))
                    if (reason == LockReason.PARENT_INSTANT_FREEZE) {
                        Text(
                            str(R.string.locked_by_your_parent_a),
                            fontSize = 13.sp,
                            color = textSecondary,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        TextButton(onClick = { showCodeDialog = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(str(R.string.i_have_a_code_from), color = Shield.colors.primary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                val parentNumber = remember { ShieldApplication.instance.preferencesRepository.getCurfewPolicy().emergencyPhoneNumber }
                if (parentNumber.isNotBlank()) {
                    TextButton(onClick = onCallParent, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Default.Phone, contentDescription = null, tint = Shield.colors.primary, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(str(R.string.call_parent), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Shield.colors.primary)
                    }
                }

                // Button 4: Emergency Calls
                TextButton(
                    onClick = onEmergencyCall,
                    colors = ButtonDefaults.textButtonColors(contentColor = Shield.colors.error)
                ) {
                    Icon(
                        imageVector = Icons.Default.Phone,
                        contentDescription = str(R.string.emergency),
                        tint = Shield.colors.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = str(R.string.emergency_calls_2),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = Shield.colors.error
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
                    onFingerprintAuth()
                },
                onPinGrant = {
                    showExtensionDialog = false
                    showPinDialog = true
                },
                onSendSms = {
                    showExtensionDialog = false
                    onSendExtensionSms()
                },
                onDismiss = { showExtensionDialog = false }
            )
        }

        if (showCodeDialog) {
            RemoteCodeDialog(onRedeem = onRedeemCode, onDismiss = { showCodeDialog = false })
        }

        if (showPinDialog) {
            ParentPinDialog(
                authManager = authManager,
                onDismiss = { showPinDialog = false },
                onAuthenticated = { method ->
                    showPinDialog = false
                    onParentAuthenticated(method)
                }
            )
        }
    }
}

@Composable
fun ParentPinDialog(
    authManager: ParentAuthManager,
    onDismiss: () -> Unit,
    onAuthenticated: (GrantMethod) -> Unit
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
                Toast.makeText(ShieldApplication.instance, str(R.string.pin_reset), Toast.LENGTH_SHORT).show()
                onAuthenticated(GrantMethod.RECOVERY_KEY)
            }
        )
        return
    }

    ShieldDialog(
        icon = Icons.Default.Lock,
        title = str(R.string.parent_pin),
        message = str(R.string.enter_the_parent_pin_you),
        onDismiss = onDismiss,
        confirmText = str(R.string.action_continue),
        confirmEnabled = enteredPin.length >= 4,
        onConfirm = {
            when (val result = authManager.verifyPin(enteredPin)) {
                is AuthResult.Success -> onAuthenticated(GrantMethod.PIN)
                is AuthResult.InvalidPin -> errorMessage = str(R.string.incorrect_pin_attempts_remaining, result.remainingAttempts)
                is AuthResult.LockedOut -> errorMessage = str(R.string.too_many_attempts_try_again, result.remainingSeconds / 60 + 1)
            }
        }
    ) {
        PinField(value = enteredPin, onValueChange = { enteredPin = it; errorMessage = null }, label = str(R.string.method_pin), isError = errorMessage != null)
        DialogError(errorMessage)
        TextButton(onClick = { showRecoveryDialog = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(str(R.string.forgot_pin_use_recovery_key_2), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Shield.colors.primary)
        }
    }
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
    ShieldDialog(
        icon = Icons.Default.Schedule,
        title = str(R.string.ask_for_more_time),
        message = str(R.string.if_your_parent_is_here),
        onDismiss = onDismiss
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (isFingerprintAvailable) DialogOption(str(R.string.parent_here_fingerprint), onFingerprintGrant, primary = true)
            DialogOption(str(R.string.parent_here_pin), onPinGrant, primary = !isFingerprintAvailable)
            if (emergencyPhone.isNotBlank()) DialogOption(str(R.string.text_parent, emergencyPhone), onSendSms)
        }
    }
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

    ShieldDialog(
        icon = Icons.Default.Key,
        title = str(R.string.reset_pin_with_recovery_key),
        message = str(R.string.enter_the_16_character_recovery),
        onDismiss = onDismiss,
        confirmText = str(R.string.reset_pin),
        onConfirm = {
            errorMessage = when {
                recoveryKeyInput.isBlank() -> str(R.string.please_enter_your_recovery_key)
                newPinInput.length < 4 -> str(R.string.new_pin_must_be_at)
                newPinInput != confirmPinInput -> str(R.string.pins_do_not_match)
                authManager.recoverWithMasterKey(recoveryKeyInput, newPinInput) -> {
                    onRecoverySuccess()
                    null
                }
                else -> str(R.string.wrong_recovery_key_or_too)
            }
        }
    ) {
        OutlinedTextField(
            value = recoveryKeyInput,
            onValueChange = { recoveryKeyInput = it.uppercase(); errorMessage = null },
            label = { Text(str(R.string.recovery_key_xxxx_xxxx_xxxx)) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(10.dp))
        PinField(value = newPinInput, onValueChange = { newPinInput = it; errorMessage = null }, label = str(R.string.label_new_pin_4_8))
        Spacer(modifier = Modifier.height(10.dp))
        PinField(value = confirmPinInput, onValueChange = { confirmPinInput = it; errorMessage = null }, label = str(R.string.label_confirm_new_pin))
        DialogError(errorMessage)
    }
}

sealed class ParentChoice {
    object ParentAccess : ParentChoice()
    data class ExtraMinutes(val minutes: Long) : ParentChoice()
    object RestOfToday : ParentChoice()
    object InstantLockOff : ParentChoice()
}

/** Shown after a parent signs in on the lock screen. Signing in alone gives no time. */
@Composable
fun ParentChoiceDialog(
    reason: LockReason,
    onChoice: (ParentChoice) -> Unit,
    onDismiss: () -> Unit
) {
    ShieldDialog(
        icon = Icons.Default.Shield,
        title = str(R.string.what_do_you_want_to),
        onDismiss = onDismiss
    ) {
        DialogOption(str(R.string.use_the_phone_myself), { onChoice(ParentChoice.ParentAccess) }, primary = true)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            str(R.string.no_time_for_your_child),
            fontSize = 12.5.sp, color = Shield.colors.inkMuted, lineHeight = 17.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))
        if (reason == LockReason.PARENT_INSTANT_FREEZE) {
            DialogOption(str(R.string.turn_off_instant_lock), { onChoice(ParentChoice.InstantLockOff) })
        } else {
            Text(str(R.string.give_your_child_time), fontWeight = FontWeight.SemiBold, color = Shield.colors.ink, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                listOf(15L, 30L, 60L).forEach { minutes ->
                    DialogOption(
                        if (minutes == 60L) str(R.string.t_1_h) else str(R.string.min_2, minutes),
                        { onChoice(ParentChoice.ExtraMinutes(minutes)) },
                        modifier = Modifier.weight(1f),
                        compact = true
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            DialogOption(
                if (reason == LockReason.SCHEDULED_BEDTIME_CURFEW) str(R.string.no_bedtime_tonight) else str(R.string.no_limit_for_the_rest),
                { onChoice(ParentChoice.RestOfToday) }
            )
        }
    }
}

/** Child types the 6-digit code a parent read out from their authenticator app. */
@Composable
fun RemoteCodeDialog(onRedeem: (String) -> String?, onDismiss: () -> Unit) {
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    ShieldDialog(
        icon = Icons.Default.Key,
        title = str(R.string.code_from_your_parent),
        message = str(R.string.call_your_parent_and_ask),
        onDismiss = onDismiss,
        confirmText = str(R.string.unlock),
        confirmEnabled = code.length == 6,
        onConfirm = { error = onRedeem(code) }
    ) {
        PinField(value = code, onValueChange = { code = it; error = null }, label = str(R.string.label_6_digit_code), maxLength = 6, hidden = false, isError = error != null)
        DialogError(error)
    }
}
