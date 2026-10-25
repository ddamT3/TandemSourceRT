package com.example.tandemapp.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** Same numeric mapping as app/build.gradle.kts; display tags are never compared lexically. */
data class UpdateVersion(val tag: String, val versionCode: Long) {
	companion object {
		fun parse(tag: String): UpdateVersion {
			val match = Regex("^v?([0-9]{1,5})\\.([0-9]{1,2})\\.([0-9]{1,3})$").matchEntire(tag)
				?: error("Unsupported release version: $tag")
			val (major, minor, revision) = match.destructured
			val majorNumber = major.toLong()
			require(majorNumber <= 21474) { "Release major version out of range" }
			val code = majorNumber * 100_000 + minor.toLong() * 1_000 + revision.toLong()
			require(code in 1..Int.MAX_VALUE.toLong()) { "Release versionCode out of range" }
			return UpdateVersion(tag, code)
		}
	}
}

data class GitHubRelease(
	val version: UpdateVersion, val notes: String, val apkName: String,
	val apkUrl: String, val apkSize: Long, val sha256: String?
)

object GitHubReleaseParser {
	const val LATEST_URL = "https://api.github.com/repos/ddamT3/TandemSourceRT/releases/latest"
	private const val DOWNLOAD_BASE = "https://github.com/ddamT3/TandemSourceRT/releases/download/"
	private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

	fun parse(body: String): GitHubRelease? {
		val root = Json.parseToJsonElement(body).jsonObject
		val draft = (root["draft"] as? JsonPrimitive)?.booleanOrNull ?: error("Missing release draft flag")
		val prerelease = (root["prerelease"] as? JsonPrimitive)?.booleanOrNull ?: error("Missing release prerelease flag")
		if (draft || prerelease) return null
		val version = UpdateVersion.parse(root.string("tag_name") ?: error("Missing release version"))
		val name = "TandemSourceRT-${version.tag}.apk"
		val assets = root["assets"]?.jsonArray.orEmpty().map { it.jsonObject }.filter { it.string("name") == name }
		require(assets.size == 1) { "Release must contain exactly one official APK: $name" }
		val asset = assets.single()
		require(asset.string("state") == "uploaded") { "Official APK upload is incomplete" }
		val url = asset.string("browser_download_url") ?: error("Missing APK URL")
		require(url == "$DOWNLOAD_BASE${version.tag}/$name") { "APK URL is not the official release asset" }
		val size = (asset["size"] as? JsonPrimitive)?.longOrNull ?: error("Missing APK size")
		require(size in 1..536_870_912L) { "Unsupported APK size" }
		val rawDigest = asset["digest"]
		val sha256 = if (rawDigest == null || rawDigest is JsonNull) null else {
			val digest = (rawDigest as? JsonPrimitive)?.contentOrNull ?: error("Invalid GitHub APK digest")
			Regex("^sha256:([a-fA-F0-9]{64})$").matchEntire(digest)?.groupValues?.get(1)?.lowercase()
				?: error("GitHub APK digest cannot be verified as SHA-256")
		}
		return GitHubRelease(version, root.string("body").orEmpty(), name, url, size, sha256)
	}
}
