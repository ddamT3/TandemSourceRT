package com.example.tandemapp.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class GitHubReleaseTest {
	private val checksum = "ab".repeat(32)
	private fun payload(
		tag: String = "v26.11.002", draft: Boolean = false, prerelease: Boolean = false,
		url: String? = null, state: String = "uploaded", size: Long = 23_000_000,
		digest: JsonElement? = JsonPrimitive("sha256:$checksum"), duplicate: Boolean = false, includeApk: Boolean = true
	): String {
		val name = "TandemSourceRT-$tag.apk"
		val apk = buildJsonObject {
			put("name", name); put("state", state); put("size", size)
			put("browser_download_url", url ?: "https://github.com/ddamT3/TandemSourceRT/releases/download/$tag/$name")
			if (digest != null) put("digest", digest)
		}
		return buildJsonObject {
			put("tag_name", tag); put("draft", draft); put("prerelease", prerelease); put("body", "Release notes")
			put("assets", buildJsonArray {
				add(buildJsonObject { put("name", "TandemSourceRT-$tag-source.zip") })
				if (includeApk) add(apk)
				if (duplicate) add(apk)
			})
		}.toString()
	}

    @Test
    fun `versions use the Android numeric mapping and never lexical comparison`() {
        assertEquals(2611002L, UpdateVersion.parse("v26.11.002").versionCode)
        assertEquals(2611002L, UpdateVersion.parse("26.11.002").versionCode)
        assertTrue(UpdateVersion.parse("v26.11.004").versionCode > UpdateVersion.parse("v26.11.003").versionCode)
        assertTrue(UpdateVersion.parse("v26.12.001").versionCode > UpdateVersion.parse("v26.11.999").versionCode)
        assertTrue(UpdateVersion.parse("v27.01.001").versionCode > UpdateVersion.parse("v26.12.999").versionCode)
        assertFalse(UpdateVersion.parse("v26.11.001").versionCode > 2611001L)
        assertThrows(RuntimeException::class.java) { UpdateVersion.parse("2.1.8") }
    }

	@Test
	fun `malformed unsupported and overflowing versions are rejected`() {
		listOf("v02.01", "v26.11.1000", "v26.13.001", "v26.11.002-rc1", "v21474.99.999", "../v26.11.002", "v26.00.000").forEach {
			assertThrows(RuntimeException::class.java) { UpdateVersion.parse(it) }
		}
	}

	@Test
	fun `stable release selects the exact official APK and preserves notes and checksum`() {
		val release = requireNotNull(GitHubReleaseParser.parse(payload()))
		assertEquals("v26.11.002", release.version.tag)
		assertEquals("Release notes", release.notes)
		assertEquals("TandemSourceRT-v26.11.002.apk", release.apkName)
		assertEquals("https://github.com/ddamT3/TandemSourceRT/releases/download/v26.11.002/TandemSourceRT-v26.11.002.apk", release.apkUrl)
		assertEquals(23_000_000L, release.apkSize)
		assertEquals(checksum, release.sha256)
	}

	@Test
	fun `official published filename follows buildAPK versioned asset convention`() {
		val release = requireNotNull(GitHubReleaseParser.parse(payload(tag = "v26.11.001")))
		assertEquals("TandemSourceRT-v26.11.001.apk", release.apkName)
		assertEquals("https://github.com/ddamT3/TandemSourceRT/releases/download/v26.11.001/TandemSourceRT-v26.11.001.apk", release.apkUrl)
		assertThrows(RuntimeException::class.java) {
			GitHubReleaseParser.parse(payload().replace("TandemSourceRT-v26.11.002.apk", "TandemSourceRT.apk"))
		}
		assertThrows(RuntimeException::class.java) {
			GitHubReleaseParser.parse(payload(url = "https://github.com/ddamT3/TandemSourceRT/releases/download/v26.11.001/TandemSourceRT-v26.11.002.apk"))
		}
	}

	@Test
	fun `draft and prerelease are ignored`() {
		assertNull(GitHubReleaseParser.parse(payload(draft = true)))
		assertNull(GitHubReleaseParser.parse(payload(prerelease = true)))
	}

	@Test
	fun `missing duplicate or incomplete official assets are rejected`() {
		assertThrows(RuntimeException::class.java) { GitHubReleaseParser.parse(payload(includeApk = false)) }
		assertThrows(RuntimeException::class.java) { GitHubReleaseParser.parse(payload(duplicate = true)) }
		assertThrows(RuntimeException::class.java) { GitHubReleaseParser.parse(payload(state = "new")) }
		listOf(0L, -1L, 536_870_913L).forEach { size ->
			assertThrows(RuntimeException::class.java) { GitHubReleaseParser.parse(payload(size = size)) }
		}
	}

	@Test
	fun `foreign APK URLs query strings and alternate repositories cannot be downloaded`() {
		listOf(
			"https://example.com/TandemSourceRT-v26.11.002.apk",
			"http://github.com/ddamT3/TandemSourceRT/releases/download/v26.11.002/TandemSourceRT-v26.11.002.apk",
			"https://github.com/other/TandemSourceRT/releases/download/v26.11.002/TandemSourceRT-v26.11.002.apk",
			"https://github.com/ddamT3/TandemSourceRT/releases/download/v26.11.002/TandemSourceRT-v26.11.002.apk?redirect=elsewhere"
		).forEach { url -> assertThrows(RuntimeException::class.java) { GitHubReleaseParser.parse(payload(url = url)) } }
	}

	@Test
	fun `missing checksum is distinct from a published unverifiable checksum`() {
		assertNull(requireNotNull(GitHubReleaseParser.parse(payload(digest = null))).sha256)
		assertNull(requireNotNull(GitHubReleaseParser.parse(payload(digest = JsonNull))).sha256)
		listOf(JsonPrimitive(""), JsonPrimitive("sha256:123"), JsonPrimitive("sha512:$checksum"), buildJsonObject {}).forEach { digest ->
			assertThrows(RuntimeException::class.java) { GitHubReleaseParser.parse(payload(digest = digest)) }
		}
		assertEquals(checksum, requireNotNull(GitHubReleaseParser.parse(payload(digest = JsonPrimitive("sha256:${checksum.uppercase()}")))).sha256)
	}

	@Test
	fun `malformed response and missing stable flags fail explicitly`() {
		assertThrows(RuntimeException::class.java) { GitHubReleaseParser.parse("not json") }
		assertThrows(RuntimeException::class.java) { GitHubReleaseParser.parse("{}") }
	}
}
