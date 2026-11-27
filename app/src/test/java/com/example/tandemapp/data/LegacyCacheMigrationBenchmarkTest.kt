package com.example.tandemapp.data

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class LegacyCacheMigrationBenchmarkTest {
	private val runtime = Runtime.getRuntime()
	private fun used() = runtime.totalMemory() - runtime.freeMemory()

	private fun <T> measure(phase: String, block: () -> T): T {
		val before = used()
		val peak = AtomicLong(before)
		val running = AtomicBoolean(true)
		fun sample() {
			val value = used()
			while (true) {
				val previous = peak.get()
				if (value <= previous || peak.compareAndSet(previous, value)) return
			}
		}
		val sampler = Thread {
			while (running.get()) {
				sample()
				try { Thread.sleep(2) } catch (_: InterruptedException) { break }
			}
		}
		sampler.isDaemon = true
		sampler.start()
		val start = System.nanoTime()
		val result = try { block() } finally {
			running.set(false)
			sampler.interrupt()
			sampler.join(5000)
		}
		val elapsed = (System.nanoTime() - start) / 1000000.0
		val after = used()
		sample()
		assertTrue(!sampler.isAlive)
		println("PHASE=$phase HEAP_MAX_BYTES=${runtime.maxMemory()}" +
			" HEAP_BEFORE_BYTES=$before HEAP_PEAK_OBSERVED_BYTES=${peak.get()}" +
			" HEAP_AFTER_BYTES=$after ELAPSED_MS=$elapsed")
		return result
	}

	@Test(timeout = 300000L)
	fun benchmarkConfiguredMigration() {
		val profile = System.getProperty("tandem.migration.profile")
		assumeTrue(profile != null)
		val (sources, events, points) = when (profile) {
			"small" -> Triple(2, 10000, 500)
			"representative" -> Triple(17, 187484, 9849)
			"stress" -> Triple(32, 500000, 25000)
			else -> error("Unknown synthetic profile")
		}
		val heap = requireNotNull(System.getProperty("tandem.benchmark.maxHeapMiB")).toLong()
		assertTrue(runtime.maxMemory() <= heap * 1048576L)
		val parent = File(requireNotNull(System.getProperty("tandem.migration.directory"))).canonicalFile
		require(parent.isDirectory)
		val work = java.nio.file.Files.createTempDirectory(parent.toPath(), "migration-").toFile()
		fun count(total: Int, index: Int) = total / sources + if (index < total % sources) 1 else 0
		try {
			val source = File(work, "legacy.json")
			val padding = "x".repeat(220)
			source.bufferedWriter(Charsets.UTF_8).use { writer ->
				writer.write("""{"latestTimestamp":"2008-01-01T00:02:03Z","dataset":{"cgm":[""")
				var first = true
				repeat(sources) { p ->
					repeat(count(points, p)) { e ->
						if (!first) writer.write(",")
						first = false
						writer.write("""{"time":"2008-01-01T00:02:03Z","value":100,"source":{"payloadIndex":$p,"eventIndex":$e,"sequenceNumber":$e,"sequenceGroup":2,"pumpDateTime":"synthetic","estimatedDateTime":"estimated","egvTimeStamp":123,"deviceAssignmentId":"test"}}""")
					}
				}
				writer.write("""],"bolus":[],"carbs":[],"iob":[],"basal":[],"deviceStates":[],"supplementalEvents":[],"pumpEventsSources":[""")
				repeat(sources) { p ->
					if (p > 0) writer.write(",")
					writer.write("""{"requestedAssignmentId":"test","rawPayload":{"events":[""")
					repeat(count(events, p)) { e ->
						if (e > 0) writer.write(",")
						if (e < count(points, p)) {
							writer.write("""{"eventCode":399,"sequenceNumber":$e,"sequenceGroup":2,"pumpDateTime":"synthetic","estimatedDateTime":"estimated","deviceAssignmentId":"test","eventProperties":{"currentGlucoseDisplayValue":100,"egvTimeStamp":123},"syntheticPadding":"$padding"}""")
						} else {
							writer.write("""{"eventCode":13,"syntheticIndex":$e,"syntheticPadding":"$padding"}""")
						}
					}
					writer.write("""],"clockChanges":null}}""")
				}
				writer.write("]}}")
			}
			val original = ChartCacheManifestFiles.fingerprint(source, 512L * 1024 * 1024)
			println("PROFILE=$profile SOURCES=$sources EVENTS=$events CGM=$points INPUT_BYTES=${source.length()} DIRECTORY_SYNC=SIMULATED")
			val store = File(work, "store")
			val generations = File(store, "generations")
			assertTrue(generations.mkdirs())
			val id = ChartCacheManifest.newGenerationId()
			val stage = File(generations, ".stage-$id")
			val descriptors = measure("prepare_extract_validate") {
				LegacyCacheMigration.prepare(source, stage)
			}
			assertEquals(sources, descriptors.size)
			assertEquals(events.toLong(), descriptors.sumOf { it.eventCount })
			val manifest = measure("manifest") {
				ChartCacheManifestFiles.build(stage, id, descriptors)
			}
			assertEquals(ChartCachePublication.Result.COMMITTED, measure("publish") {
				ChartCachePublication.publish(store, stage, manifest, syncDirectory = {})
			})
			val recovered = measure("recovery") { ChartCacheRecovery.inspect(store) }
			assertTrue(recovered is ChartCacheRecovery.Result.Published)
			assertEquals(manifest, (recovered as ChartCacheRecovery.Result.Published).manifest)
			assertEquals(original, ChartCacheManifestFiles.fingerprint(source, original.archiveBytes))
			println("INTEGRITY=PASS")
		} finally {
			assertTrue("Synthetic migration cleanup failed", work.deleteRecursively())
		}
	}
}
