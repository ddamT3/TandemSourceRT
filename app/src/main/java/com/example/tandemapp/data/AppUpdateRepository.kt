package com.example.tandemapp.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Public GitHub access only: no Tandem credentials or authorization headers. */
class AppUpdateRepository(private val context: Context) {
	companion object { const val PACKAGE = "com.example.tandemapp.st" }

	suspend fun latest(): GitHubRelease? = withContext(Dispatchers.IO) {
		val connection = connection(GitHubReleaseParser.LATEST_URL, setOf("api.github.com"), "application/vnd.github+json")
		try {
			val bytes = ByteArrayOutputStream()
			connection.inputStream.use { input ->
				val buffer = ByteArray(8192)
				while (true) {
					currentCoroutineContext().ensureActive()
					val count = input.read(buffer)
					if (count < 0) break
					require(bytes.size() + count <= 2_097_152) { "GitHub release response is too large" }
					bytes.write(buffer, 0, count)
				}
			}
			GitHubReleaseParser.parse(bytes.toString("UTF-8"))
		} finally { connection.disconnect() }
	}

	suspend fun download(release: GitHubRelease, progress: suspend (Int) -> Unit): File = withContext(Dispatchers.IO) {
		val directory = context.cacheDir.resolve("updates")
		check(directory.isDirectory || directory.mkdirs()) { "Cannot create APK download directory" }
		val file = File.createTempFile("update-", ".apk", directory)
		var complete = false
		try {
			val connection = connection(release.apkUrl,
				setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com"),
				"application/octet-stream")
			try {
				val length = connection.contentLengthLong
				require(length < 0 || length == release.apkSize) { "APK size differs from the GitHub asset" }
				connection.inputStream.use { input ->
					file.outputStream().use { output ->
						val buffer = ByteArray(32_768)
						var total = 0L
						var lastPercent = -1
						while (true) {
							currentCoroutineContext().ensureActive()
							val count = input.read(buffer)
							if (count < 0) break
							total += count
							require(total <= release.apkSize) { "APK exceeds the published asset size" }
							output.write(buffer, 0, count)
							val percent = (total * 100 / release.apkSize).toInt()
							if (percent != lastPercent) { progress(percent); lastPercent = percent }
						}
						require(total == release.apkSize) { "APK download is incomplete" }
					}
				}
			} finally { connection.disconnect() }
			verify(file, release)
			complete = true
			file
		} finally { if (!complete) file.delete() }
	}

	/** Repeated immediately before handing the content URI to Android. Fails closed. */
	@Suppress("DEPRECATION")
	suspend fun verify(file: File, release: GitHubRelease) = withContext(Dispatchers.IO) {
		require(file.isFile && file.length() == release.apkSize) { "Downloaded APK is missing or incomplete" }
		if (release.sha256 != null) {
			val digest = MessageDigest.getInstance("SHA-256")
			file.inputStream().use { input ->
				val buffer = ByteArray(32_768)
				while (true) {
					currentCoroutineContext().ensureActive()
					val count = input.read(buffer)
					if (count < 0) break
					digest.update(buffer, 0, count)
				}
			}
			val actual = digest.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
			require(actual == release.sha256) { "APK SHA-256 does not match GitHub. Installation blocked." }
		}
		val pm = context.packageManager
		val apk = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
			?: error("Android cannot verify APK metadata or signing certificates. Installation blocked.")
		require(context.packageName == PACKAGE && apk.packageName == PACKAGE) { "APK package is not $PACKAGE. Installation blocked." }
		val installed = pm.getPackageInfo(PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES)
		require(apk.longVersionCode > installed.longVersionCode) { "APK versionCode is not newer than the installed app" }
		require(apk.longVersionCode == release.version.versionCode) { "APK versionCode does not match the GitHub release tag" }
		val installedSigners = signers(installed)
		val candidateSigners = signers(apk)
		require(installedSigners.isNotEmpty() && candidateSigners.isNotEmpty()) { "Signing certificates unavailable. Installation blocked." }
		// Exact current-signer equality also handles multiple signers. Rotation is deliberately rejected.
		require(installedSigners == candidateSigners) { "APK signing key differs from the installed app. Installation blocked (key rotation is not supported)." }
	}

	private fun signers(info: PackageInfo): Set<String> {
		val signing = info.signingInfo ?: return emptySet()
		val certificates = if (signing.hasMultipleSigners()) signing.apkContentsSigners?.toList().orEmpty()
			else signing.signingCertificateHistory?.lastOrNull()?.let { listOf(it) }.orEmpty()
		return certificates.map { signature ->
			MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") {
				(it.toInt() and 255).toString(16).padStart(2, '0')
			}
		}.toSet()
	}

	@Suppress("DEPRECATION")
	fun installer(file: File, release: GitHubRelease): Intent {
		val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file, release.apkName)
		return Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
			setDataAndType(uri, "application/vnd.android.package-archive")
			addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
			clipData = ClipData.newRawUri("APK update", uri)
			putExtra(Intent.EXTRA_RETURN_RESULT, true)
		}
	}

	private fun connection(initial: String, hosts: Set<String>, accept: String): HttpURLConnection {
		var uri = URI(initial)
		repeat(6) {
			require(uri.scheme == "https" && uri.host in hosts && uri.userInfo == null && (uri.port == -1 || uri.port == 443)) {
				"GitHub redirected the download to an untrusted address"
			}
			val connection = uri.toURL().openConnection() as HttpURLConnection
			connection.instanceFollowRedirects = false
			connection.connectTimeout = 15_000
			connection.readTimeout = 10_000
			connection.setRequestProperty("Accept", accept)
			connection.setRequestProperty("User-Agent", "TandemSourceRT/Android")
			connection.setRequestProperty("Accept-Encoding", "identity")
			try {
				when (val status = connection.responseCode) {
					200 -> return connection
					301, 302, 303, 307, 308 -> {
						val location = connection.getHeaderField("Location") ?: error("GitHub redirect has no destination")
						uri = uri.resolve(location)
						connection.disconnect()
					}
					else -> error("GitHub HTTP $status. Check connectivity or try again later.")
				}
			} catch (error: Exception) { connection.disconnect(); throw error }
		}
		error("Too many GitHub redirects")
	}
}
