package com.example.onetapvpn

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONObject

internal sealed interface UpdateCheck {
    data object Current : UpdateCheck
    data class Available(val release: AppRelease) : UpdateCheck
}

internal data class AppRelease(
    val versionName: String,
    val apkUrl: String,
    val sha256: String
)

internal data class DownloadSnapshot(
    val status: Int,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val reason: Int
) {
    val progressPercent: Int
        get() = if (totalBytes > 0L) {
            ((bytesDownloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
        } else 0
}

internal class AppUpdater(context: Context) {
    private val appContext = context.applicationContext
    private val downloadManager =
        appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val preferences =
        appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Throws(IOException::class)
    fun checkForUpdate(): UpdateCheck {
        val connection = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", USER_AGENT)
        }

        val releaseJson = try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IOException("Update service returned HTTP $code.")
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }

        val release = JSONObject(releaseJson)
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) {
            return UpdateCheck.Current
        }

        val remoteVersion = release.optString("tag_name")
            .trim()
            .removePrefix("v")
            .takeIf { it.isNotBlank() }
            ?: throw IOException("The latest release has no version tag.")

        if (compareVersions(remoteVersion, BuildConfig.VERSION_NAME) <= 0) {
            return UpdateCheck.Current
        }

        val assets = release.optJSONArray("assets")
            ?: throw IOException("The latest release has no downloadable files.")

        var apkUrl: String? = null
        var apkDigest: String? = null
        var checksumUrl: String? = null

        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.optString("name")
            val browserUrl = asset.optString("browser_download_url")
            if (!isTrustedReleaseUrl(browserUrl)) continue

            if (name.equals(APK_ASSET_NAME, ignoreCase = true)) {
                apkUrl = browserUrl
                apkDigest = asset.optString("digest")
                    .removePrefix("sha256:")
                    .takeIf { SHA256_PATTERN.matches(it) }
            } else if (name.equals(CHECKSUM_ASSET_NAME, ignoreCase = true)) {
                checksumUrl = browserUrl
            }
        }

        val downloadUrl = apkUrl ?: throw IOException("The release APK asset is missing.")
        val checksum = apkDigest ?: checksumUrl?.let(::readChecksum)
            ?: throw IOException("The release does not provide a SHA-256 checksum.")

        return UpdateCheck.Available(
            AppRelease(
                versionName = remoteVersion,
                apkUrl = downloadUrl,
                sha256 = checksum.lowercase()
            )
        )
    }

    fun enqueueDownload(release: AppRelease): Long {
        require(isTrustedReleaseUrl(release.apkUrl)) { "Untrusted update download URL." }
        require(SHA256_PATTERN.matches(release.sha256)) { "Invalid update checksum." }

        val destination = File(
            appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: throw IOException("App download folder is unavailable."),
            APK_FILE_NAME
        )
        if (destination.exists() && !destination.delete()) {
            throw IOException("Could not remove the previous update file.")
        }

        val request = DownloadManager.Request(Uri.parse(release.apkUrl))
            .setTitle("Raahat VPN update")
            .setDescription("Downloading version ${release.versionName}")
            .setMimeType(APK_MIME_TYPE)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalFilesDir(
                appContext,
                Environment.DIRECTORY_DOWNLOADS,
                APK_FILE_NAME
            )

        val downloadId = downloadManager.enqueue(request)
        check(
            preferences.edit()
                .putLong(KEY_DOWNLOAD_ID, downloadId)
                .putString(KEY_EXPECTED_SHA256, release.sha256.lowercase())
                .putString(KEY_TARGET_VERSION, release.versionName)
                .commit()
        ) { "Could not save update download state." }
        return downloadId
    }

    fun pendingDownloadId(): Long =
        preferences.getLong(KEY_DOWNLOAD_ID, INVALID_DOWNLOAD_ID)

    fun downloadSnapshot(downloadId: Long): DownloadSnapshot {
        val cursor = downloadManager.query(
            DownloadManager.Query().setFilterById(downloadId)
        ) ?: throw IOException("Could not read update download status.")

        cursor.use {
            if (!it.moveToFirst()) throw IOException("The update download no longer exists.")
            return DownloadSnapshot(
                status = it.getIntColumn(DownloadManager.COLUMN_STATUS),
                bytesDownloaded = it.getLongColumn(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                totalBytes = it.getLongColumn(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                reason = it.getIntColumn(DownloadManager.COLUMN_REASON)
            )
        }
    }

    @Throws(IOException::class)
    fun verifiedDownloadedApk(downloadId: Long): File {
        val snapshot = downloadSnapshot(downloadId)
        if (snapshot.status != DownloadManager.STATUS_SUCCESSFUL) {
            throw IOException("The update download did not complete successfully (code ${snapshot.reason}).")
        }

        val apkFile = File(
            appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: throw IOException("App download folder is unavailable."),
            APK_FILE_NAME
        )
        if (!apkFile.isFile || apkFile.length() <= 0L) {
            throw IOException("The downloaded update file is missing.")
        }

        val expected = preferences.getString(KEY_EXPECTED_SHA256, null)
            ?: throw IOException("The expected update checksum is missing.")
        val actual = sha256(apkFile)
        if (!actual.equals(expected, ignoreCase = true)) {
            apkFile.delete()
            clearPendingDownload()
            throw IOException("The update file failed its SHA-256 integrity check.")
        }
        return apkFile
    }

    fun clearPendingDownload() {
        preferences.edit()
            .remove(KEY_DOWNLOAD_ID)
            .remove(KEY_EXPECTED_SHA256)
            .remove(KEY_TARGET_VERSION)
            .apply()
    }

    private fun readChecksum(url: String): String {
        val body = readText(url)
        val checksum = SHA256_PATTERN.find(body)?.value
            ?: throw IOException("The release checksum file is invalid.")
        return checksum.lowercase()
    }

    private fun readText(url: String): String {
        if (!isTrustedReleaseUrl(url)) throw IOException("Untrusted update metadata URL.")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IOException("Could not read update checksum (HTTP $code).")
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun isTrustedReleaseUrl(value: String): Boolean = try {
        val parsed = Uri.parse(value)
        parsed.scheme == "https" &&
            parsed.host.equals("github.com", ignoreCase = true) &&
            parsed.path.orEmpty().contains("/releases/download/")
    } catch (_: Exception) {
        false
    }

    private fun compareVersions(left: String, right: String): Int {
        fun parts(value: String): List<Int> {
            val match = VERSION_PATTERN.find(value)
                ?: throw IOException("Invalid application version: $value")
            return match.groupValues.drop(1).map { it.toInt() }
        }
        val a = parts(left)
        val b = parts(right)
        for (index in 0..2) {
            val comparison = a[index].compareTo(b[index])
            if (comparison != 0) return comparison
        }
        return 0
    }

    private fun Cursor.getIntColumn(name: String): Int =
        getInt(getColumnIndexOrThrow(name))

    private fun Cursor.getLongColumn(name: String): Long =
        getLong(getColumnIndexOrThrow(name))

    private companion object {
        const val LATEST_RELEASE_API =
            "https://api.github.com/repos/idan8005/OneTapVPN/releases/latest"
        const val USER_AGENT = "RaahatVPN-Android-Updater"
        const val PREFERENCES_NAME = "raahat_vpn_updater"
        const val KEY_DOWNLOAD_ID = "download_id"
        const val KEY_EXPECTED_SHA256 = "expected_sha256"
        const val KEY_TARGET_VERSION = "target_version"
        const val INVALID_DOWNLOAD_ID = -1L
        const val APK_ASSET_NAME = "raahat-vpn.apk"
        const val CHECKSUM_ASSET_NAME = "raahat-vpn.apk.sha256"
        const val APK_FILE_NAME = "raahat-vpn-update.apk"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        const val CONNECT_TIMEOUT_MS = 12_000
        const val READ_TIMEOUT_MS = 20_000
        val SHA256_PATTERN = Regex("(?i)\\b[0-9a-f]{64}\\b")
        val VERSION_PATTERN = Regex("^(?:v)?(\\d+)\\.(\\d+)\\.(\\d+)")
    }
}
