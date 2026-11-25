package com.example.tandemapp.data

import java.io.File
import java.io.IOException
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChartCachePublicationTest {
	@get:Rule val temp = TemporaryFolder(File("build").apply { require(isDirectory || mkdirs()) })

	private fun store(): File = temp.newFolder().canonicalFile.apply {
		assertTrue(File(this, "generations").mkdir())
	}

	private fun stage(store: File): Pair<File, ChartCacheManifest> {
		val id = ChartCacheManifest.newGenerationId()
		val directory = File(store, "generations/.stage-$id")
		assertTrue(directory.mkdir())
		File(directory, "dataset.json").writeText("{}")
		return directory to ChartCacheManifestFiles.build(directory, id, emptyList())
	}

	private fun pointer(store: File): ChartCacheCurrent =
		Json.decodeFromString<ChartCacheCurrent>(File(store, "current.json").readText())

	private fun assertPublished(store: File, manifest: ChartCacheManifest) {
		val current = pointer(store)
		assertEquals(manifest.generationId, current.generationId)
		val directory = File(store, "generations/${current.generationId}")
		val file = File(directory, "manifest.json")
		assertEquals(manifest, Json.decodeFromString<ChartCacheManifest>(file.readText()))
		assertEquals(current.manifestSha256, ChartCacheManifestFiles.fingerprint(file, 8192).sha256)
		ChartCacheManifestFiles.validate(directory, manifest, current.generationId)
		assertFalse(store.listFiles()!!.any { it.name.startsWith(".current-") })
	}

	private fun initial(store: File): ChartCacheManifest {
		val (stage, manifest) = stage(store)
		assertEquals(ChartCachePublication.Result.COMMITTED,
			ChartCachePublication.publish(store, stage, manifest, syncDirectory = {}))
		assertFalse(stage.exists())
		assertPublished(store, manifest)
		return manifest
	}

	@Test(timeout = 30000L)
	fun replacesPointerAndPreservesOldGeneration() {
		val store = store()
		val old = initial(store)
		val (stage, manifest) = stage(store)
		assertEquals(ChartCachePublication.Result.COMMITTED,
			ChartCachePublication.publish(store, stage, manifest, syncDirectory = {}))
		assertPublished(store, manifest)
		assertTrue(File(store, "generations/${old.generationId}/manifest.json").isFile)
		assertFalse(stage.exists())
	}

	@Test(timeout = 30000L)
	fun failuresBeforeCommitPreservePointer() {
		for (failureAt in 1..3) {
			val store = store()
			val old = initial(store)
			val previous = File(store, "current.json").readBytes()
			val (stage, manifest) = stage(store)
			var calls = 0
			try {
				ChartCachePublication.publish(store, stage, manifest, syncDirectory = {
					if (++calls == failureAt) throw IOException("Synthetic sync failure")
				})
				fail("Expected sync failure")
			} catch (failure: IOException) {
				assertEquals("Synthetic sync failure", failure.message)
			}
			assertEquals(failureAt, calls)
			assertArrayEquals(previous, File(store, "current.json").readBytes())
			assertPublished(store, old)
		}
	}

	@Test(timeout = 30000L)
	fun finalSyncFailureDoesNotRollBack() {
		val store = store()
		val old = initial(store)
		val (stage, manifest) = stage(store)
		var calls = 0
		assertEquals(ChartCachePublication.Result.COMMIT_DURABILITY_UNCERTAIN,
			ChartCachePublication.publish(store, stage, manifest, syncDirectory = {
				if (++calls == 4) throw IOException("Synthetic final sync failure")
			}))
		assertEquals(4, calls)
		assertPublished(store, manifest)
		assertTrue(File(store, "generations/${old.generationId}/manifest.json").isFile)
	}

	@Test(timeout = 30000L)
	fun invalidDatasetCannotPublish() {
		val store = store()
		val old = initial(store)
		val (stage, manifest) = stage(store)
		File(stage, "dataset.json").writeText("[]")
		try {
			ChartCachePublication.publish(store, stage, manifest, syncDirectory = {})
			fail("Expected integrity failure")
		} catch (failure: IOException) {
			assertEquals("Dataset integrity mismatch", failure.message)
		}
		assertPublished(store, old)
		assertFalse(File(stage, "manifest.json").exists())
	}

	@Test(timeout = 30000L)
	fun existingGenerationCannotBeReplaced() {
		val store = store()
		val old = initial(store)
		val (stage, manifest) = stage(store)
		val target = File(store, "generations/${manifest.generationId}")
		assertTrue(target.mkdir())
		val sentinel = File(target, "sentinel").apply { writeText("preserve") }
		try {
			ChartCachePublication.publish(store, stage, manifest, syncDirectory = {})
			fail("Expected collision failure")
		} catch (failure: IOException) {
			assertEquals("Generation already exists", failure.message)
		}
		assertEquals("preserve", sentinel.readText())
		assertPublished(store, old)
	}
}
