package com.example.tandemapp.data

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PumpPayloadStreamBenchmarkTest {

	@get:Rule
	val temporaryFolder = TemporaryFolder()

	private val runtime = Runtime.getRuntime()

	private fun usedHeap(): Long {
		return runtime.totalMemory() - runtime.freeMemory()
	}

	private fun updatePeak(
		peak: AtomicLong,
		value: Long
	) {
		while (true) {
			val previous = peak.get()

			if (value <= previous) {
				return
			}

			if (peak.compareAndSet(previous, value)) {
				return
			}
		}
	}

	private fun generatePayload(
		file: File,
		eventCount: Int
	) {
		file.bufferedWriter(Charsets.UTF_8).use { writer ->
			writer.write("{\"schema\":\"synthetic\",\"events\":[")

			repeat(eventCount) { index ->
				if (index > 0) {
					writer.write(",")
				}

				writer.write(
					"""{"syntheticIndex":$index,"eventCode":399,"sequenceNumber":$index,"sequenceGroup":0,"pumpDateTime":"2026-01-01T00:00:00","eventProperties":{"syntheticValue":123,"syntheticText":"benchmark","nested":[1,2,3]}}"""
				)
			}

			writer.write(
				"],\"clockChanges\":[],\"syntheticComplete\":true}"
			)
		}
	}

	private fun benchmark(eventCount: Int) {
		val configuredLimit = System.getProperty(
			"tandem.benchmark.maxHeapMiB"
		)?.toLong()

		if (configuredLimit != null) {
			require(configuredLimit in 1L..4096L)

			assertTrue(
				"Effective JVM heap exceeds requested limit",
				runtime.maxMemory() <= configuredLimit * 1048576L
			)
		}

		val file = temporaryFolder.newFile(
			"synthetic-$eventCount.json"
		)

		try {
			generatePayload(file, eventCount)

			val payloadBytes = file.length()
			val heapBefore = usedHeap()
			val committedBefore = runtime.totalMemory()
			val peak = AtomicLong(heapBefore)
			val running = AtomicBoolean(true)

			var delivered = 0L
			var metadata = 0L
			var checksum = 0L

			val sampler = Thread {
				while (running.get()) {
					updatePeak(peak, usedHeap())

					try {
						Thread.sleep(2)
					} catch (_: InterruptedException) {
						break
					}
				}
			}

			sampler.isDaemon = true
			sampler.name = "synthetic-heap-sampler"
			sampler.start()

			val started = System.nanoTime()

			val result = try {
				file.inputStream().buffered().use { input ->
					PumpPayloadStreamParser.parse(
						input,
						onEvent = { index, event ->
							assertEquals(delivered, index)

							val originalIndex =
								(event["syntheticIndex"] as JsonPrimitive)
								.content.toLong()

							assertEquals(index, originalIndex)

							checksum += originalIndex
							delivered++

							if (delivered % 256L == 0L) {
								updatePeak(peak, usedHeap())
							}
						},
						onMetadata = { name, _ ->
							when (metadata) {
								0L -> assertEquals("schema", name)
								1L -> assertEquals("clockChanges", name)
								2L -> assertEquals("syntheticComplete", name)
								else -> throw AssertionError(
									"Unexpected metadata"
								)
							}
							metadata++
						}
					)
				}
			} finally {
				running.set(false)
				sampler.interrupt()
				sampler.join(5000)
			}

			val elapsedNs = System.nanoTime() - started
			val heapAfter = usedHeap()

			updatePeak(peak, heapAfter)

			assertTrue(!sampler.isAlive)

			assertEquals(eventCount.toLong(), delivered)
			assertEquals(eventCount.toLong(), result.eventCount)
			assertEquals(3L, metadata)
			assertEquals(3L, result.metadataCount)

			val expectedChecksum =
				eventCount.toLong() * (eventCount - 1L) / 2L

			assertEquals(expectedChecksum, checksum)

			val elapsedMs = elapsedNs / 1000000.0
			val throughput =
				eventCount * 1000000000.0 / elapsedNs

			println(
				"BENCHMARK_EVENTS=$eventCount" +
				" PAYLOAD_BYTES=$payloadBytes" +
				" HEAP_MAX_BYTES=${runtime.maxMemory()}" +
				" HEAP_BEFORE_BYTES=$heapBefore" +
				" HEAP_COMMITTED_BEFORE_BYTES=$committedBefore" +
				" HEAP_PEAK_OBSERVED_BYTES=${peak.get()}" +
				" HEAP_AFTER_BYTES=$heapAfter" +
				" HEAP_PEAK_DELTA_BYTES=${peak.get() - heapBefore}" +
				" HEAP_COMMITTED_AFTER_BYTES=${runtime.totalMemory()}" +
				" ELAPSED_MS=$elapsedMs" +
				" EVENTS_PER_SECOND=$throughput" +
				" EVENTS_DELIVERED=$delivered" +
				" METADATA_DELIVERED=$metadata" +
				" INDEX_CHECKSUM=$checksum" +
				" REQUESTED_HEAP_MIB=${configuredLimit ?: 0}" +
				" INTEGRITY=PASS"
			)

		} finally {
			if (file.exists() && !file.delete()) {
				throw AssertionError("Synthetic payload cleanup failed")
			}
		}
	}

	@Test(timeout = 120000L)
	fun benchmark2000Events() {
		benchmark(2000)
	}

	@Test(timeout = 120000L)
	fun benchmark20000Events() {
		benchmark(20000)
	}

	@Test(timeout = 120000L)
	fun benchmark100000Events() {
		benchmark(100000)
	}

	@Test(timeout = 300000L)
	fun benchmark1000000Events() {
		benchmark(1000000)
	}
}
