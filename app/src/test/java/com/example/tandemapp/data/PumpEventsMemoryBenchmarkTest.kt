package com.example.tandemapp.data

import java.io.ByteArrayInputStream
import kotlin.system.measureTimeMillis
import org.junit.Assert.assertEquals
import org.junit.Test

class PumpEventsMemoryBenchmarkTest {

    private fun payload(count: Int): String = buildString {
        append("""{"clockChanges":[],"unknownTop":{"test":true},"events":[""")
        repeat(count) { index ->
            if (index > 0) append(',')
            val code = if (index % 5 == 0) 399 else 191
            append("""{"pumpDateTime":"2026-11-01T12:00:00Z","estimatedDateTime":"2026-11-01T12:00:01Z","deviceAssignmentId":"synthetic","eventCode":""")
            append(code)
            append(""","sequenceGroup":1,"sequenceNumber":""")
            append(index)
            append(""","eventProperties":{"egvTimeStamp":562420800,"currentGlucoseDisplayValue":125,"cgmDataType":[1],"unknownProperty":{"value":123}}}""")
        }
        append("]}")
    }

    private fun usedMiB(): Long {
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
    }

    @Test
    fun parity2000() {
        val input = payload(2000)
        val original = PumpEventsAdapter.decode(input, "synthetic")
        val streamed = ByteArrayInputStream(input.toByteArray(Charsets.UTF_8)).use {
            PumpEventsAdapter.decodeStream(it, "synthetic")
        }

        assertEquals(original, streamed)
        assertEquals(400, streamed.cgm.size)
        assertEquals(0, streamed.cgm.first().source?.eventIndex)
        assertEquals(1995, streamed.cgm.last().source?.eventIndex)
        println("BENCH_PARITY_2000_PASS")
    }

    @Test
    fun streaming20000() {
        val input = payload(20000)
        val before = usedMiB()
        lateinit var result: com.example.tandemapp.model.DayDataset

        val elapsed = measureTimeMillis {
            result = ByteArrayInputStream(input.toByteArray(Charsets.UTF_8)).use {
                PumpEventsAdapter.decodeStream(it, "synthetic")
            }
        }

        val after = usedMiB()

        assertEquals(4000, result.cgm.size)
        assertEquals(0, result.cgm.first().source?.eventIndex)
        assertEquals(19995, result.cgm.last().source?.eventIndex)
        assertEquals(1, result.pumpEventsSources.size)

        println("BENCH_STREAM_20000_PASS")
        println("BENCH_TIME_MS=$elapsed")
        println("BENCH_HEAP_BEFORE_MIB=$before")
        println("BENCH_HEAP_AFTER_MIB=$after")
        println("BENCH_HEAP_MAX_MIB=${Runtime.getRuntime().maxMemory() / (1024 * 1024)}")
    }

    @Test
    fun streaming100000LimitedHeap() {
        val count = 100000
        val input = buildString {
            append("""{"clockChanges":[],"unknownTop":{"test":true},"events":[""")
            repeat(count) { index ->
                if (index > 0) append(',')
                val code = if (index % 5 == 0) 399 else 191
                append("""{"pumpDateTime":"2026-11-01T12:00:00Z","estimatedDateTime":"2026-11-01T12:00:01Z","deviceAssignmentId":"synthetic","eventCode":""")
                append(code)
                append(""","sequenceGroup":1,"sequenceNumber":""")
                append(index)
                append(""","eventProperties":{"egvTimeStamp":562420800,"currentGlucoseDisplayValue":125,"cgmDataType":[1],"unknownProperty":{"nested":[1,2,3,4,5],"description":"synthetic-data-only"}}}""")
            }
            append("]}")
        }

        val runtime = Runtime.getRuntime()
        val sampling = java.util.concurrent.atomic.AtomicBoolean(true)
        val peak = java.util.concurrent.atomic.AtomicLong(0L)

        val sampler = Thread {
            while (sampling.get()) {
                val used = runtime.totalMemory() - runtime.freeMemory()
                peak.updateAndGet { previous -> maxOf(previous, used) }
                try {
                    Thread.sleep(10)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }

        sampler.isDaemon = true
        sampler.start()

        try {
            lateinit var result: com.example.tandemapp.model.DayDataset

            val elapsed = measureTimeMillis {
                result = ByteArrayInputStream(input.toByteArray(Charsets.UTF_8)).use {
                    PumpEventsAdapter.decodeStream(it, "synthetic")
                }
            }

            assertEquals(20000, result.cgm.size)
            assertEquals(0, result.cgm.first().source?.eventIndex)
            assertEquals(99995, result.cgm.last().source?.eventIndex)
            assertEquals(1, result.pumpEventsSources.size)
            assertEquals(count, result.pumpEventsSources.single().rawPayload["events"]?.let {
                (it as kotlinx.serialization.json.JsonArray).size
            })

            println("BENCH_100000_PASS")
            println("BENCH_100000_TIME_MS=$elapsed")
            println("BENCH_100000_PEAK_MIB=${peak.get() / (1024 * 1024)}")
            println("BENCH_100000_HEAP_MAX_MIB=${runtime.maxMemory() / (1024 * 1024)}")
        } finally {
            sampling.set(false)
            sampler.join(1000)
        }
    }
}