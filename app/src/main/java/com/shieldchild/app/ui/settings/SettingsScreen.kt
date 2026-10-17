package com.shieldchild.app.ui.settings

import com.shieldchild.app.i18n.str
import com.shieldchild.app.i18n.AppLanguage
import com.shieldchild.app.i18n.Language
import com.shieldchild.app.R
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shieldchild.app.ShieldApplication
import com.shieldchild.app.ui.theme.Shield
import com.shieldchild.app.ui.theme.ThemeMode
import com.shieldchild.app.ui.theme.ThemeState
import com.shieldchild.app.update.AvailableUpdate
import com.shieldchild.app.update.InstallResult
import com.shieldchild.app.update.UpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PRIVACY_URL = "https://heyhadi.github.io/shieldchild/privacy.html"

private sealed class UpdateUi {
    object Idle : UpdateUi()
    object Checking : UpdateUi()
    object UpToDate : UpdateUi()
    data class Available(val update: AvailableUpdate) : UpdateUi()
    data class Downloading(val percent: Int?) : UpdateUi()
    data class Message(val text: String) : UpdateUi()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val c = Shield.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updates = remember { UpdateManager(context.applicationContext) }
    val repo = remember { ShieldApplication.instance.preferencesRepository }
    val version = remember { str(R.string.build, updates.installedVersionName(), updates.installedBuild()) }
    val cachedUpdate = remember {
        val cached = repo.getCachedUpdate()
        if (cached != null && cached.build > updates.installedBuild()) cached else null
    }
    var updateUi by remember { mutableStateOf<UpdateUi>(cachedUpdate?.let { UpdateUi.Available(it) } ?: UpdateUi.Idle) }

    fun install(update: AvailableUpdate) {
        updateUi = UpdateUi.Downloading(null)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                updates.downloadAndInstall(update) { done, total ->
                    val percent = if (total > 0) (done * 100 / total).toInt() else null
                    scope.launch { updateUi = UpdateUi.Downloading(percent) }
                }
            }
            updateUi = when (result) {
                InstallResult.InstallerOpened -> UpdateUi.Message(str(R.string.android_s_installer_is_open))
                InstallResult.NeedsInstallPermission -> UpdateUi.Message(str(R.string.allow_install_unknown_apps_for)).also {
                    updateUi = UpdateUi.Available(update)
                }
                is InstallResult.Failed -> UpdateUi.Message(result.message)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(c.canvas).statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = str(R.string.back), tint = c.ink)
            }
            Text(str(R.string.settings), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = c.ink)
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item {
                Section(str(R.string.appearance)) {
                    Text(str(R.string.theme), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.ink)
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(ThemeMode.SYSTEM to str(R.string.system), ThemeMode.LIGHT to str(R.string.light), ThemeMode.DARK to str(R.string.dark)).forEach { (mode, label) ->
                            FilterChip(
                                selected = ThemeState.mode.value == mode,
                                onClick = { ThemeState.set(mode) },
                                label = { Text(label) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(str(R.string.the_lock_screen_is_always), fontSize = 13.sp, color = c.inkMuted)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(str(R.string.language), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.ink)
                    Spacer(modifier = Modifier.height(8.dp))
                    val activity = context as? android.app.Activity
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(
                            AppLanguage.SYSTEM to str(R.string.language_system),
                            AppLanguage.ENGLISH to str(R.string.language_english),
                            AppLanguage.INDONESIAN to str(R.string.language_indonesian)
                        ).forEach { (language, label) ->
                            FilterChip(
                                selected = Language.current() == language,
                                onClick = { if (activity != null && Language.current() != language) Language.set(activity, language) },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }

            item {
                Section(str(R.string.app_updates)) {
                    Text(str(R.string.shieldchild, version), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.ink)
                    Spacer(modifier = Modifier.height(4.dp))
                    val status = when (val u = updateUi) {
                        UpdateUi.Idle -> null
                        UpdateUi.Checking -> str(R.string.checking_github)
                        UpdateUi.UpToDate -> str(R.string.you_have_the_latest_version)
                        is UpdateUi.Available -> str(R.string.is_available, u.update.title)
                        is UpdateUi.Downloading -> if (u.percent != null) str(R.string.downloading, u.percent) else str(R.string.downloading_2)
                        is UpdateUi.Message -> u.text
                    }
                    if (status != null) Text(status, fontSize = 13.5.sp, color = c.inkMuted)
                    val downloading = updateUi as? UpdateUi.Downloading
                    if (downloading != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        if (downloading.percent != null) {
                            LinearProgressIndicator(progress = { downloading.percent / 100f }, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    val available = updateUi as? UpdateUi.Available
                    Button(
                        onClick = {
                            if (available != null) {
                                install(available.update)
                            } else {
                                updateUi = UpdateUi.Checking
                                scope.launch {
                                    updateUi = try {
                                        val update = withContext(Dispatchers.IO) { updates.checkForUpdate() }
                                        val now = System.currentTimeMillis()
                                        repo.setLastUpdateCheckTime(now)
                                        repo.setCachedUpdate(update)
                                        update?.let { UpdateUi.Available(it) } ?: UpdateUi.UpToDate
                                    } catch (e: java.io.IOException) {
                                        android.util.Log.w("ShieldChildUpdate", "Update check failed", e)
                                        // Some phones (e.g. LineageOS) switch internet off per app; the lookup then fails.
                                        UpdateUi.Message(str(R.string.couldn_t_reach_github_if))
                                    } catch (e: Exception) {
                                        android.util.Log.w("ShieldChildUpdate", "Update check failed", e)
                                        UpdateUi.Message(str(R.string.couldn_t_check_for_updates, e.message ?: e.javaClass.simpleName))
                                    }
                                }
                            }
                        },
                        enabled = updateUi !is UpdateUi.Checking && updateUi !is UpdateUi.Downloading,
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = c.primary, contentColor = c.onPrimary),
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (available != null) str(R.string.download_and_install) else str(R.string.check_for_updates), fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        str(R.string.shieldchild_only_goes_online_when),
                        fontSize = 12.5.sp, color = c.inkMuted, lineHeight = 17.sp
                    )
                }
            }

            item {
                Section(str(R.string.feedback)) {
                    LinkRow(Icons.Default.BugReport, str(R.string.report_a_problem), str(R.string.opens_a_github_issue_with)) {
                        open(context, issueUrl(context, bug = true, version = version))
                    }
                    LinkRow(Icons.Default.Lightbulb, str(R.string.suggest_an_idea), str(R.string.tell_us_what_would_help)) {
                        open(context, issueUrl(context, bug = false, version = version))
                    }
                    LinkRow(Icons.Default.Code, str(R.string.source_code_on_github), UpdateManager.REPO_URL.removePrefix("https://")) {
                        open(context, UpdateManager.REPO_URL)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        str(R.string.issues_on_github_are_public),
                        fontSize = 12.5.sp, color = c.inkMuted, lineHeight = 17.sp
                    )
                }
            }

            item {
                Section(str(R.string.about)) {
                    Text(str(R.string.shieldchild, version), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.ink)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        str(R.string.open_source_screen_time_and),
                        fontSize = 13.5.sp, color = c.inkMuted, lineHeight = 19.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    LinkRow(Icons.Default.Policy, str(R.string.privacy_policy), str(R.string.what_shieldchild_can_see_and)) {
                        open(context, PRIVACY_URL)
                    }
                    Text(str(R.string.mit_license_made_by_heyhadi), fontSize = 12.5.sp, color = c.inkMuted)
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    val c = Shield.colors
    Column {
        Text(
            title.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.inkMuted,
            letterSpacing = 0.8.sp, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
        )
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = c.card,
            border = BorderStroke(1.dp, c.line),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp), content = content)
        }
    }
}

@Composable
private fun LinkRow(icon: ImageVector, title: String, hint: String, onClick: () -> Unit) {
    val c = Shield.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(shape = RoundedCornerShape(12.dp), color = c.surfaceVariant, modifier = Modifier.size(40.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = c.primary, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.ink)
            Text(hint, fontSize = 12.5.sp, color = c.inkMuted)
        }
        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = c.outline, modifier = Modifier.size(18.dp))
    }
}

private fun open(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        Toast.makeText(context, url, Toast.LENGTH_LONG).show()
    }
}

/** Only app version and Android version go in: nothing about the child or their usage. */
private fun issueUrl(context: Context, bug: Boolean, version: String): String {
    val details = str(R.string.app_shieldchild_android, version, Build.VERSION.RELEASE, Build.MANUFACTURER, Build.MODEL)
    val body = if (bug) {
        str(R.string.what_happened_what_you_expected, details)
    } else {
        str(R.string.the_idea_why_it_would, details)
    }
    return Uri.parse("${UpdateManager.REPO_URL}/issues/new").buildUpon()
        .appendQueryParameter("title", if (bug) "Problem: " else "Idea: ")
        .appendQueryParameter("body", body)
        .appendQueryParameter("labels", if (bug) "bug" else "enhancement")
        .build().toString()
}
