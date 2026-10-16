package com.shieldchild.app.update

import com.shieldchild.app.i18n.str
import com.shieldchild.app.R
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.shieldchild.app.service.ShieldAccessibilityService
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class AvailableUpdate(val title: String, val apkUrl: String, val build: Long)

sealed class InstallResult {
    object InstallerOpened : InstallResult()
    /** The one-time "Install unknown apps" screen was opened; try again after allowing it. */
    object NeedsInstallPermission : InstallResult()
    data class Failed(val message: String) : InstallResult()
}

/**
 * In-app updates from the repo's GitHub Releases (ShieldChild isn't on the Play Store),
 * ported from Wird's UpdateModule. A download is installed only if it is this app, a newer
 * build and signed with the same key; Android enforces the same-key rule again at install.
 * Network use is limited to this: nothing is checked until the parent taps "Check for updates".
 */
class UpdateManager(private val context: Context) {

    fun installedBuild(): Long = versionCode(installedInfo(0))

    fun installedVersionName(): String = installedInfo(0).versionName ?: "?"

    /** Blocking; call off the main thread. Returns null when there is nothing newer. */
    fun checkForUpdate(): AvailableUpdate? {
        val connection = URL(RELEASES_API).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        val body = try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException(str(R.string.github_answered, connection.responseCode))
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
        val release = JSONObject(body)
        val tag = release.optString("tag_name")
        val latest = buildFromTag(tag)
        if (latest <= installedBuild()) return null
        val assets = release.optJSONArray("assets") ?: return null
        val apkUrl = (0 until assets.length())
            .map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk") }
            ?.optString("browser_download_url") ?: return null
        return AvailableUpdate(release.optString("name").ifBlank { tag }, apkUrl, latest)
    }

    /** Blocking; call off the main thread. [onProgress] gets (downloaded, total or -1). */
    fun downloadAndInstall(update: AvailableUpdate, onProgress: (Long, Long) -> Unit): InstallResult {
        if (!update.apkUrl.startsWith(ALLOWED_URL_PREFIX)) {
            return InstallResult.Failed(str(R.string.updates_only_come_from_shieldchild))
        }
        // The installer and "Install unknown apps" screens mention ShieldChild, which the
        // tamper guard would otherwise send back to Home. Only reachable from the PIN-locked dashboard.
        ShieldAccessibilityService.allowParentUninstall(3 * 60_000L)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            return try {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                InstallResult.NeedsInstallPermission
            } catch (e: Exception) {
                InstallResult.Failed(str(R.string.couldn_t_open_the_install))
            }
        }

        return try {
            val apk = download(update.apkUrl, onProgress)
            verify(apk)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            InstallResult.InstallerOpened
        } catch (e: Exception) {
            InstallResult.Failed(e.message ?: str(R.string.update_failed))
        }
    }

    private fun download(url: String, onProgress: (Long, Long) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // one update on disk at a time
        val apk = File(dir, "update.apk")
        // GitHub redirects release downloads to its CDN; https → https redirects are followed.
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException(str(R.string.download_failed_http, connection.responseCode))
            }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: -1L
            var downloaded = 0L
            var lastReported = 0L
            connection.inputStream.use { input ->
                apk.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        downloaded += n
                        if (downloaded - lastReported >= 256 * 1024) {
                            lastReported = downloaded
                            onProgress(downloaded, total)
                        }
                    }
                }
            }
            onProgress(downloaded, total)
        } finally {
            connection.disconnect()
        }
        return apk
    }

    /** Same package, newer build, same signing key — or no install. */
    private fun verify(apk: File) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val candidate = context.packageManager.getPackageArchiveInfo(apk.path, flags)
            ?: throw IllegalStateException(str(R.string.downloaded_file_isn_t_an))
        val installed = installedInfo(flags)
        if (candidate.packageName != installed.packageName) throw IllegalStateException(str(R.string.downloaded_app_isn_t_shieldchild))
        if (versionCode(candidate) <= versionCode(installed)) throw IllegalStateException(str(R.string.downloaded_build_isn_t_newer))
        val candidateSigners = signatures(candidate)
        if (candidateSigners.isEmpty() || candidateSigners != signatures(installed)) {
            throw IllegalStateException(str(R.string.downloaded_app_isn_t_signed))
        }
    }

    private fun installedInfo(flags: Int): PackageInfo = context.packageManager.getPackageInfo(context.packageName, flags)

    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }

    private fun signatures(info: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        return sigs.orEmpty().map { it.toCharsString() }.toSet()
    }

    companion object {
        const val REPO_URL = "https://github.com/heyhadi/shieldchild"
        private const val RELEASES_API = "https://api.github.com/repos/heyhadi/shieldchild/releases/latest"
        private const val ALLOWED_URL_PREFIX = "$REPO_URL/releases/download/"

        /**
         * The versionCode a release tag stands for, or 0 when it can't tell. Tags are
         * "v1.0.1-2" (version, then versionCode after a dash), the same scheme as Wird.
         * Tags without the dash (v1.0.0) predate the updater and never count as newer.
         */
        fun buildFromTag(tag: String): Long {
            val match = Regex("""^v?\d+\.\d+\.\d+-(\d+)$""").find(tag.trim()) ?: return 0
            return match.groupValues[1].toLong()
        }
    }
}

/** Own subclass so the manifest entry can't collide with a library's provider. */
class UpdateFileProvider : FileProvider()
