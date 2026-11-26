package com.example.tandemapp.data

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChartCacheRecoveryTest {
	@get:Rule val temp = TemporaryFolder(File("build").apply { require(isDirectory || mkdirs()) })

	private fun store() = temp.newFolder().canonicalFile.apply {
		assertTrue(File(this, "generations").mkdir())
	}

	private fun publish(store: File): ChartCacheManifest {
		val id = ChartCacheManifest.newGenerationId()
		val stage = File(store, "generations/.stage-$id").apply { assertTrue(mkdir()) }
		File(stage, "dataset.json").writeText("{}")
		val manifest = ChartCacheManifestFiles.build(stage, id, emptyList())
		assertEquals(ChartCachePublication.Result.COMMITTED,
			ChartCachePublication.publish(store, stage, manifest, syncDirectory = {}))
		return manifest
	}

	@Test(timeout = 30000L)
	fun publishedGenerationSelected() {
		val store = store()
		val manifest = publish(store)
		val result = ChartCacheRecovery.inspect(store) as ChartCacheRecovery.Result.Published
		assertEquals(manifest, result.manifest)
		assertEquals(manifest.generationId, result.directory.name)
	}

	@Test(timeout = 30000L)
	fun missingPointerNeverPromotesGeneration() {
		val store = store()
		val manifest = publish(store)
		assertTrue(File(store, "current.json").delete())
		assertSame(ChartCacheRecovery.Result.Missing, ChartCacheRecovery.inspect(store))
		assertTrue(File(store, "generations/${manifest.generationId}/manifest.json").isFile)
		assertFalse(File(store, "current.json").exists())
	}

	@Test(timeout = 30000L)
	fun malformedPointersPreserved() {
		val store = store()
		val pointer = File(store, "current.json")
		for (value in listOf("", "{", "{}", "{}{}", "{\"schemaVersion\":1,\"schemaVersion\":1}", " ".repeat(1025))) {
			pointer.writeText(value)
			assertSame(ChartCacheRecovery.Result.Invalid, ChartCacheRecovery.inspect(store))
			assertEquals(value, pointer.readText())
		}
	}

	@Test(timeout = 30000L)
	fun manifestMutationRejected() {
		val store = store()
		val manifest = publish(store)
		val file = File(store, "generations/${manifest.generationId}/manifest.json")
		file.appendText(" ")
		assertSame(ChartCacheRecovery.Result.Invalid, ChartCacheRecovery.inspect(store))
		assertTrue(file.isFile)
	}

	@Test(timeout = 30000L)
	fun datasetMutationRejected() {
		val store = store()
		val manifest = publish(store)
		val file = File(store, "generations/${manifest.generationId}/dataset.json")
		file.writeText("[]")
		assertSame(ChartCacheRecovery.Result.Invalid, ChartCacheRecovery.inspect(store))
		assertEquals("[]", file.readText())
	}

	@Test(timeout = 30000L)
	fun missingManifestRejected() {
		val store = store()
		val manifest = publish(store)
		assertTrue(File(store, "generations/${manifest.generationId}/manifest.json").delete())
		assertSame(ChartCacheRecovery.Result.Invalid, ChartCacheRecovery.inspect(store))
		assertTrue(File(store, "current.json").isFile)
	}

	@Test(timeout = 30000L)
	fun inventoryDistinguishesGenerations() {
		val store = store()
		val old = publish(store)
		val current = publish(store)
		val stage = ".stage-${ChartCacheManifest.newGenerationId()}"
		assertTrue(File(store, "generations/$stage").mkdir())
		File(store, "generations/unknown").writeText("preserve")
		val result = ChartCacheInventory.inspect(store, ChartCacheRecovery.inspect(store))
		val kinds = result.entries.associate { it.name to it.kind }
		assertFalse(result.truncated)
		assertEquals(ChartCacheInventory.Kind.PUBLISHED, kinds[current.generationId])
		assertEquals(ChartCacheInventory.Kind.UNREFERENCED, kinds[old.generationId])
		assertEquals(ChartCacheInventory.Kind.STAGING, kinds[stage])
		assertEquals(ChartCacheInventory.Kind.UNKNOWN, kinds["unknown"])
		assertEquals(4, kinds.size)
	}

	@Test(timeout = 30000L)
	fun inventoryBoundedAndUnresolved() {
		val store = store()
		repeat(3) {
			assertTrue(File(store, "generations/${ChartCacheManifest.newGenerationId()}").mkdir())
		}
		val result = ChartCacheInventory.inspect(store, ChartCacheRecovery.Result.Missing, 2)
		assertTrue(result.truncated)
		assertEquals(2, result.entries.size)
		assertTrue(result.entries.all { it.kind == ChartCacheInventory.Kind.UNRESOLVED })
		assertEquals(3, File(store, "generations").listFiles()!!.size)
	}

	@Test(timeout = 30000L)
	fun fallbackNeverMutatesLegacy() {
		val store = store()
		val legacy = File(store, "legacy.json")
		val missing = ChartCacheRecovery.Result.Missing
		assertEquals(ChartCacheFallback.Action.UNAVAILABLE, ChartCacheFallback.decide(missing, legacy))
		legacy.writeText("")
		assertEquals(ChartCacheFallback.Action.RECOVERY_REQUIRED, ChartCacheFallback.decide(missing, legacy))
		legacy.writeText("synthetic")
		assertEquals(ChartCacheFallback.Action.MIGRATION_CANDIDATE, ChartCacheFallback.decide(missing, legacy))
		assertEquals(ChartCacheFallback.Action.RECOVERY_REQUIRED,
			ChartCacheFallback.decide(ChartCacheRecovery.Result.Invalid, legacy))
		publish(store)
		assertEquals(ChartCacheFallback.Action.USE_PUBLISHED,
			ChartCacheFallback.decide(ChartCacheRecovery.inspect(store), legacy))
		assertEquals("synthetic", legacy.readText())
	}
}
