package com.example.tandemapp.data

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class PumpPayloadDescriptorBenchmarkTest {
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
	fun benchmarkConfiguredArchive() {
		val count = System.getProperty("tandem.descriptor.events")?.toInt()
		assumeTrue(count != null)
		val events = requireNotNull(count)
		require(events in listOf(10000, 100000, 1000000))
		val limit = requireNotNull(System.getProperty("tandem.benchmark.maxHeapMiB")).toLong()
		assertTrue(runtime.maxMemory() <= limit * 1048576L)
		val directory = File(requireNotNull(System.getProperty("tandem.descriptor.directory"))).canonicalFile
		require(directory.isDirectory)
		val file = File(directory, "synthetic-descriptor.json")
		assertTrue(file.createNewFile())
		try {
			file.bufferedWriter(Charsets.UTF_8).use { writer ->
				writer.write("{\"schema\":\"synthetic\",\"events\":[")
				repeat(events) { index ->
					if (index > 0) writer.write(",")
					writer.write("""{"syntheticIndex":$index,"eventCode":399,"sequenceNumber":$index,"sequenceGroup":0,"pumpDateTime":"2026-01-01T00:00:00","eventProperties":{"syntheticValue":123,"syntheticText":"benchmark","nested":[1,2,3]}}""")
				}
				writer.write("],\"clockChanges\":[],\"syntheticComplete\":true}")
			}
			println("EVENTS=$events PAYLOAD_BYTES=${file.length()} REQUESTED_HEAP_MIB=$limit")
			val descriptor = measure("describe") {
				PumpPayloadAccess.describe(directory, file.name, "synthetic", file.length())
			}
			assertEquals(events.toLong(), descriptor.eventCount)
			assertEquals(file.length(), descriptor.archiveBytes)
			val index = events.toLong() / 2
			val event = measure("readEvent") {
				PumpPayloadAccess.readEvent(directory, descriptor, index)
			}
			assertEquals(index, (event["syntheticIndex"] as JsonPrimitive).content.toLong())
			assertEquals("399", (event["eventCode"] as JsonPrimitive).content)
			println("INTEGRITY=PASS")
		} finally {
			assertTrue("Synthetic archive cleanup failed", file.delete())
		}
	}
}
