package com.shieldchild.app.ui.dashboard.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shieldchild.app.R
import com.shieldchild.app.i18n.str
import com.shieldchild.app.ui.theme.Shield
import com.shieldchild.app.update.AvailableUpdate

/**
 * Elegant, non-intrusive in-app update banner displayed on the Overview tab
 * only when a newer GitHub release is detected.
 */
@Composable
fun UpdateBanner(
    update: AvailableUpdate,
    onInstallClick: () -> Unit,
    onDismissClick: () -> Unit,
    downloadPercent: Int?,
    modifier: Modifier = Modifier
) {
    val isDownloading = downloadPercent != null
    val colPrimary = Shield.colors.primary
    val colPrimaryContainer = Shield.colors.primaryContainer
    val colOnPrimary = Shield.colors.onPrimary
    val colInk = Shield.colors.ink
    val colInkMuted = Shield.colors.inkMuted
    val colLineSubtle = Shield.colors.line

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Shield.colors.card,
        border = BorderStroke(1.5.dp, colPrimary.copy(alpha = 0.5f)),
        shadowElevation = 3.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Icon badge
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(colPrimaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.SystemUpdate,
                        contentDescription = null,
                        tint = colPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = str(R.string.update_banner_title),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = colInk
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = str(R.string.update_banner_desc, update.title),
                        fontSize = 13.sp,
                        color = colInkMuted,
                        lineHeight = 17.sp
                    )
                }

                if (!isDownloading) {
                    IconButton(
                        onClick = onDismissClick,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = str(R.string.update_banner_btn_later),
                            tint = colInkMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Download Progress Bar
            AnimatedVisibility(
                visible = isDownloading,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    val progressText = if (downloadPercent != null && downloadPercent >= 0) {
                        str(R.string.update_banner_downloading, downloadPercent)
                    } else {
                        str(R.string.update_banner_downloading_indeterminate)
                    }

                    Text(
                        text = progressText,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colPrimary
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    if (downloadPercent != null && downloadPercent >= 0) {
                        LinearProgressIndicator(
                            progress = { downloadPercent / 100f },
                            color = colPrimary,
                            trackColor = colPrimaryContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                        )
                    } else {
                        LinearProgressIndicator(
                            color = colPrimary,
                            trackColor = colPrimaryContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                        )
                    }
                }
            }

            // Action Buttons
            if (!isDownloading) {
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onInstallClick,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colPrimary,
                            contentColor = colOnPrimary
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                    ) {
                        Text(
                            text = str(R.string.update_banner_btn_update),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    TextButton(
                        onClick = onDismissClick,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.height(42.dp)
                    ) {
                        Text(
                            text = str(R.string.update_banner_btn_later),
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colInkMuted
                        )
                    }
                }
            }
        }
    }
}
