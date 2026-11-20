package com.example.onetapvpn

import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONArray
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
        val release = JSONObject(readText(LATEST_RELEASE_API_URL))
        val remoteVersion = release.optString("tag_name")
            .trim()
            .removePrefix("v")
            .takeIf { VERSION_PATTERN.matches(it) }
            ?: throw IOException("The latest GitHub release has no valid version tag.")

        if (compareVersions(remoteVersion, BuildConfig.VERSION_NAME) <= 0) {
            return UpdateCheck.Current
        }

        val assets = release.optJSONArray("assets")
            ?: throw IOException("The latest GitHub release has no downloadable assets.")
        val apkAsset = assets.findAsset(APK_ASSET_NAME)
            ?: throw IOException("The latest release does not contain $APK_ASSET_NAME.")
        val checksumAsset = assets.findAsset(CHECKSUM_ASSET_NAME)
            ?: throw IOException("The latest release does not contain $CHECKSUM_ASSET_NAME.")

        val apkUrl = apkAsset.optString("browser_download_url")
        if (!isTrustedDownloadUrl(apkUrl)) {
            throw IOException("The latest release contains an untrusted APK URL.")
        }

        val digestFromApi = apkAsset.optString("digest")
            .removePrefix("sha256:")
            .takeIf { SHA256_PATTERN.matches(it) }
        val checksum = digestFromApi ?: readChecksum(
            checksumAsset.optString("browser_download_url")
        )

        return UpdateCheck.Available(
            AppRelease(
                versionName = remoteVersion,
                apkUrl = apkUrl,
                sha256 = checksum.lowercase()
            )
        )
    }

    fun enqueueDownload(release: AppRelease): Long {
        require(isTrustedDownloadUrl(release.apkUrl)) { "Untrusted update download URL." }
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
        val saved = preferences.edit()
            .putLong(KEY_DOWNLOAD_ID, downloadId)
            .putString(KEY_EXPECTED_SHA256, release.sha256.lowercase())
            .putString(KEY_TARGET_VERSION, release.versionName)
            .commit()
        if (!saved) {
            downloadManager.remove(downloadId)
            throw IOException("Could not save update download state.")
        }
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
        verifyApkIdentityAndSignature(apkFile)
        return apkFile
    }

    private fun verifyApkIdentityAndSignature(apkFile: File) {
        val packageManager = appContext.packageManager
        val archiveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageManager.getPackageArchiveInfo(
                apkFile.absolutePath,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageArchiveInfo(
                apkFile.absolutePath,
                PackageManager.GET_SIGNATURES
            )
        } ?: throw IOException("The downloaded file is not a valid Android APK.")

        if (archiveInfo.packageName != appContext.packageName) {
            throw IOException("The update belongs to a different application.")
        }

        val installedInfo = packageManager.getPackageInfo(appContext.packageName, 0)
        val downloadedVersion = packageVersionCode(archiveInfo)
        val installedVersion = packageVersionCode(installedInfo)
        if (downloadedVersion <= installedVersion) {
            throw IOException("The downloaded update is not newer than the installed version.")
        }

        val signers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archiveInfo.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            archiveInfo.signatures.orEmpty()
        }
        val trustedSignerFound = signers.any { signature ->
            sha256Bytes(signature.toByteArray()).equals(EXPECTED_SIGNER_CERT_SHA256, ignoreCase = true)
        }
        if (!trustedSignerFound) {
            apkFile.delete()
            clearPendingDownload()
            throw IOException("The update signature does not match the official Raahat VPN certificate.")
        }
    }

    private fun packageVersionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }

    private fun sha256Bytes(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value)
            .joinToString("") { "%02x".format(it) }

    fun clearPendingDownload() {
        preferences.edit()
            .remove(KEY_DOWNLOAD_ID)
            .remove(KEY_EXPECTED_SHA256)
            .remove(KEY_TARGET_VERSION)
            .apply()
    }

    private fun readChecksum(url: String): String {
        if (!isTrustedDownloadUrl(url) || !url.endsWith(CHECKSUM_ASSET_NAME)) {
            throw IOException("The release checksum URL is invalid.")
        }
        val body = readText(url)
        return SHA256_PATTERN.find(body)?.value?.lowercase()
            ?: throw IOException("The release checksum file is invalid.")
    }

    private fun readText(url: String): String {
        if (!isTrustedMetadataUrl(url) && !isTrustedDownloadUrl(url)) {
            throw IOException("Untrusted update metadata URL.")
        }
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", GITHUB_API_VERSION)
        }
        return try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                val message = if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                    "The update repository is private or no public release has been published."
                } else {
                    "Could not read update information (HTTP $code)."
                }
                throw IOException(message)
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

    private fun isTrustedMetadataUrl(value: String): Boolean = try {
        val parsed = Uri.parse(value)
        parsed.scheme == "https" &&
            parsed.host.equals(GITHUB_API_HOST, ignoreCase = true) &&
            parsed.path == LATEST_RELEASE_API_PATH
    } catch (_: Exception) {
        false
    }

    private fun isTrustedDownloadUrl(value: String): Boolean = try {
        val parsed = Uri.parse(value)
        parsed.scheme == "https" &&
            parsed.host.equals(GITHUB_DOWNLOAD_HOST, ignoreCase = true) &&
            parsed.path.orEmpty().startsWith(RELEASE_ASSET_PATH_PREFIX) &&
            parsed.query.isNullOrEmpty()
    } catch (_: Exception) {
        false
    }

    private fun compareVersions(left: String, right: String): Int {
        fun parts(value: String): List<Int> {
            val match = VERSION_PATTERN.matchEntire(value)
                ?: throw IOException("Invalid application version: $value")
            return match.groupValues.drop(1).filter { it.isNotEmpty() }.map { it.toInt() }
        }
        val a = parts(left)
        val b = parts(right)
        for (index in 0 until maxOf(a.size, b.size)) {
            val comparison = (a.getOrNull(index) ?: 0).compareTo(b.getOrNull(index) ?: 0)
            if (comparison != 0) return comparison
        }
        return 0
    }

    private fun JSONArray.findAsset(expectedName: String): JSONObject? {
        for (index in 0 until length()) {
            val asset = optJSONObject(index) ?: continue
            if (asset.optString("name") == expectedName) return asset
        }
        return null
    }

    private fun Cursor.getIntColumn(name: String): Int =
        getInt(getColumnIndexOrThrow(name))

    private fun Cursor.getLongColumn(name: String): Long =
        getLong(getColumnIndexOrThrow(name))

    private companion object {
        const val GITHUB_API_HOST = "api.github.com"
        const val GITHUB_DOWNLOAD_HOST = "github.com"
        const val LATEST_RELEASE_API_PATH = "/repos/idan8005/OneTapVPN/releases/latest"
        const val RELEASE_ASSET_PATH_PREFIX = "/idan8005/OneTapVPN/releases/download/"
        const val LATEST_RELEASE_API_URL = "https://api.github.com/repos/idan8005/OneTapVPN/releases/latest"
        const val GITHUB_API_VERSION = "2022-11-28"
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
        const val EXPECTED_SIGNER_CERT_SHA256 = "152de76e27a36f9309a0f5391fa9735713abf5498eac2d991138708a67d67d2f"
        const val CONNECT_TIMEOUT_MS = 12_000
        const val READ_TIMEOUT_MS = 20_000
        val SHA256_PATTERN = Regex("(?i)\\b[0-9a-f]{64}\\b")
        val VERSION_PATTERN = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)(?:\\.(\\d+))?$")
    }
}
