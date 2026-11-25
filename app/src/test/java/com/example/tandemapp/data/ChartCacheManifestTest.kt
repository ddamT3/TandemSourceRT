package com.example.tandemapp.data

import java.io.File
import java.io.IOException
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChartCacheManifestTest {
	@get:Rule val temp = TemporaryFolder(File("build").apply { require(isDirectory || mkdirs()) })
	private val raw = """{"events":[{"eventCode":13}],"clockChanges":null}"""

	private fun fixture(): Pair<File, ChartCacheManifest> {
		val directory = temp.newFolder()
		File(directory, "dataset.json").writeText("{}")
		File(directory, "payload_0.json").writeText(raw)
		val descriptor = PumpPayloadAccess.describe(directory, "payload_0.json", "synthetic", 1024)
		return directory to ChartCacheManifestFiles.build(directory,
			ChartCacheManifest.newGenerationId(), listOf(descriptor))
	}

	private fun rejected(block: () -> Unit) {
		try { block(); fail("Expected IOException") } catch (_: IOException) { }
	}

	@Test(timeout = 30000L)
	fun validManifestAndPersistentHash() {
		val (directory, manifest) = fixture()
		ChartCacheManifestFiles.validate(directory, manifest, manifest.generationId)
		val current = ChartCacheManifestPersistence.writeManifest(directory, manifest)
		val file = File(directory, "manifest.json")
		assertEquals(manifest, Json.decodeFromString<ChartCacheManifest>(file.readText()))
		assertEquals(current.manifestSha256, ChartCacheManifestFiles.fingerprint(file, 8192).sha256)
		assertEquals(current, Json.decodeFromString<ChartCacheCurrent>(
			ChartCacheManifestPersistence.currentBytes(current).toString(Charsets.UTF_8)))
		val original = file.readText()
		rejected { ChartCacheManifestPersistence.writeManifest(directory, manifest) }
		assertEquals(original, file.readText())
		assertFalse(File(directory, "current.json").exists())
	}

	@Test(timeout = 30000L)
	fun rejectsDatasetMutation() {
		val (directory, manifest) = fixture()
		File(directory, "dataset.json").writeText("[]")
		rejected { ChartCacheManifestFiles.validate(directory, manifest, manifest.generationId) }
	}

	@Test(timeout = 30000L)
	fun rejectsSameSizePayloadMutation() {
		val (directory, manifest) = fixture()
		File(directory, "payload_0.json").writeText(raw.replace("13", "14"))
		rejected { ChartCacheManifestFiles.validate(directory, manifest, manifest.generationId) }
	}

	@Test(timeout = 30000L)
	fun rejectsMissingPayload() {
		val (directory, manifest) = fixture()
		assertTrue(File(directory, "payload_0.json").delete())
		rejected { ChartCacheManifestFiles.validate(directory, manifest, manifest.generationId) }
	}

	@Test(timeout = 30000L)
	fun rejectsWrongGeneration() {
		val (directory, manifest) = fixture()
		rejected { ChartCacheManifestFiles.validate(directory, manifest, ChartCacheManifest.newGenerationId()) }
	}

	@Test(timeout = 30000L)
	fun rejectsIncorrectEventCount() {
		val (directory, manifest) = fixture()
		val descriptor = manifest.payloads.single().copy(eventCount = 2)
		val invalid = manifest.copy(payloads = listOf(descriptor))
		rejected { ChartCacheManifestFiles.validate(directory, invalid, invalid.generationId) }
	}

	@Test(timeout = 30000L)
	fun rejectsArchiveBudget() {
		val (directory, manifest) = fixture()
		rejected { ChartCacheManifestFiles.validate(directory, manifest, manifest.generationId,
			LegacyCacheExtractor.Limits(maxOutputBytes = 2)) }
	}

	@Test(timeout = 30000L)
	fun rejectsMetadataBudget() {
		val (directory, manifest) = fixture()
		rejected { ChartCacheManifestFiles.validate(directory, manifest, manifest.generationId,
			LegacyCacheExtractor.Limits(maxRetainedMetadataBytes = 1)) }
	}
}
