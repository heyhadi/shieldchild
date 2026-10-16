package com.shieldchild.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.shieldchild.app.ui.theme.Shield
import com.shieldchild.app.R
import com.shieldchild.app.i18n.str

/**
 * The one dialog style for the whole app (dashboard, setup and lock screen):
 * card with 28dp corners, icon badge, title, message, content, then Cancel + action buttons.
 * Colours come from the theme, so it follows light/dark mode automatically.
 */
@Composable
fun ShieldDialog(
    icon: ImageVector,
    title: String,
    message: String? = null,
    onDismiss: () -> Unit,
    confirmText: String? = null,
    onConfirm: () -> Unit = {},
    confirmEnabled: Boolean = true,
    dismissText: String? = str(R.string.cancel),
    dismissOnOutsideTap: Boolean = true,
    destructive: Boolean = false,
    content: @Composable ColumnScope.() -> Unit = {}
) {
    val c = Shield.colors
    Dialog(
        onDismissRequest = { if (dismissOnOutsideTap) onDismiss() },
        properties = DialogProperties(dismissOnClickOutside = dismissOnOutsideTap, dismissOnBackPress = dismissOnOutsideTap)
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = c.card,
            border = BorderStroke(1.dp, c.line),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(shape = RoundedCornerShape(16.dp), color = c.primaryContainer, modifier = Modifier.size(52.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = null, tint = c.primary, modifier = Modifier.size(26.dp))
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = c.ink, textAlign = TextAlign.Center)
                if (message != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(message, fontSize = 14.sp, color = c.inkMuted, textAlign = TextAlign.Center, lineHeight = 19.sp)
                }
                Spacer(modifier = Modifier.height(18.dp))
                Column(modifier = Modifier.fillMaxWidth(), content = content)
                if (confirmText != null || dismissText != null) {
                    Spacer(modifier = Modifier.height(18.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (dismissText != null) {
                            Button(
                                onClick = onDismiss,
                                shape = RoundedCornerShape(24.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = c.surfaceVariant, contentColor = c.inkMuted),
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                            ) { Text(dismissText, fontWeight = FontWeight.Bold) }
                        }
                        if (confirmText != null) {
                            Button(
                                onClick = onConfirm,
                                enabled = confirmEnabled,
                                shape = RoundedCornerShape(24.dp),
                                colors = if (destructive) {
                                    ButtonDefaults.buttonColors(containerColor = c.error, contentColor = c.card)
                                } else {
                                    ButtonDefaults.buttonColors(containerColor = c.primary, contentColor = c.onPrimary)
                                },
                                modifier = Modifier.weight(1.2f).heightIn(min = 48.dp)
                            ) { Text(confirmText, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
        }
    }
}

/** A full-width option button inside a [ShieldDialog] (e.g. the lock screen's choices). */
@Composable
fun DialogOption(text: String, onClick: () -> Unit, primary: Boolean = false, modifier: Modifier = Modifier.fillMaxWidth()) {
    val c = Shield.colors
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        colors = if (primary) {
            ButtonDefaults.buttonColors(containerColor = c.primary, contentColor = c.onPrimary)
        } else {
            ButtonDefaults.buttonColors(containerColor = c.surfaceVariant, contentColor = c.ink)
        },
        modifier = modifier.heightIn(min = 48.dp)
    ) { Text(text, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
}

/** Number-only secret entry (PINs and codes): number keypad, hidden digits, centred. */
@Composable
fun PinField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    maxLength: Int = 8,
    hidden: Boolean = true,
    isError: Boolean = false
) {
    val c = Shield.colors
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= maxLength && it.all { ch -> ch.isDigit() }) onValueChange(it) },
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        visualTransformation = if (hidden) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        textStyle = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 6.sp, textAlign = TextAlign.Center, color = c.ink),
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = c.surfaceVariant,
            unfocusedContainerColor = c.surfaceVariant,
            unfocusedBorderColor = c.line,
            focusedBorderColor = c.primary
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

/** Small red message under a field. */
@Composable
fun DialogError(message: String?) {
    if (message == null) return
    Spacer(modifier = Modifier.height(8.dp))
    Text(message, color = Shield.colors.error, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
}
