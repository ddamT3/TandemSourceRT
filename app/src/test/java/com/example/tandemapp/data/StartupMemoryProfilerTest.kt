package com.example.tandemapp.data

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class StartupMemoryProfilerTest {

    @Before
    fun prepare() {
        StartupMemoryProfiler.setEnabled(false)
        StartupMemoryProfiler.resetStatistics()
    }

    @Test
    fun disabledByDefaultInTestSession() {
        assertFalse(StartupMemoryProfiler.getSnapshot().enabled)
    }

    @Test
    fun measuredOperationRunsExactlyOnce() {
        var calls = 0

        val result = StartupMemoryProfiler.measure("synthetic") {
            calls++
            42
        }

        assertEquals(1, calls)
        assertEquals(42, result)
    }

    @Test
    fun applicationExceptionIsPreserved() {
        val expected = IllegalStateException("synthetic")

        val actual = assertThrows(IllegalStateException::class.java) {
            StartupMemoryProfiler.measure("synthetic") {
                throw expected
            }
        }

        assertSame(expected, actual)
    }

    @Test
    fun nestedOperationsPreserveResults() {
        val result = StartupMemoryProfiler.measure("outer") {
            StartupMemoryProfiler.measure("inner") {
                21
            } * 2
        }

        assertEquals(42, result)
    }

    @Test
    fun disabledProfilerDoesNotCollectStatistics() {
        repeat(1000) {
            StartupMemoryProfiler.sample("synthetic")
            StartupMemoryProfiler.measure("synthetic") {
                it + 1
            }
        }

        val snapshot = StartupMemoryProfiler.getSnapshot()

        assertFalse(snapshot.enabled)
        assertNull(snapshot.current)
        assertEquals(0L, snapshot.peakBytes)
        assertTrue(snapshot.history.isEmpty())
        assertTrue(snapshot.phases.isEmpty())
    }

    @Test
    fun disabledPhaseDoesNotAllocateIdentifier() {
        val id = StartupMemoryProfiler.beginPhase("synthetic")
        assertNull(id)
        StartupMemoryProfiler.endPhase(id)
    }

    @Test
    fun diagnosticSuccessPreservesValue() {
        var calls = 0
        val result = StartupMemoryProfiler.diagnosticOr(-1) {
            calls++
            42
        }

        assertEquals(42, result)
        assertEquals(1, calls)
    }

    @Test
    fun diagnosticExceptionReturnsFallback() {
        val result = StartupMemoryProfiler.diagnosticOr(-1) {
            throw IllegalStateException("synthetic diagnostic failure")
        }

        assertEquals(-1, result)
    }

    @Test
    fun diagnosticFailureDoesNotPreventFollowingOperation() {
        var calls = 0

        StartupMemoryProfiler.diagnosticOr(Unit) {
            throw IllegalStateException("synthetic diagnostic failure")
        }

        calls++

        assertEquals(1, calls)
    }

    @Test
    fun fatalJvmErrorIsNotSwallowed() {
        val expected = OutOfMemoryError("synthetic")

        val actual = assertThrows(OutOfMemoryError::class.java) {
            StartupMemoryProfiler.diagnosticOr(Unit) {
                throw expected
            }
        }

        assertSame(expected, actual)
    }


    private val profiler = StartupMemoryProfiler

    private fun profilerField(name: String): java.lang.reflect.Field {
        return profiler.javaClass.getDeclaredField(name).apply {
            isAccessible = true
        }
    }

    private class SyntheticSampler {
        var elapsed = 0L
        var used = 100L
        var fail = false

        fun collect(includePss: Boolean): StartupMemoryProfiler.Sample {
            if (fail) {
                throw IllegalStateException("synthetic measurement failure")
            }

            elapsed += 10L

            return StartupMemoryProfiler.Sample(
                elapsed,
                used,
                4096L,
                8192L,
                512L,
                if (includePss) 1024 else null
            )
        }
    }

    private fun withEnabledProfiler(
        action: (SyntheticSampler) -> Unit
    ) {
        val sampler = SyntheticSampler()

        val overrideField = profilerField("sampleOverride")
        val automaticField = profilerField("automaticSampling")

        val previousOverride = overrideField.get(profiler)
        val previousAutomatic = automaticField.getBoolean(profiler)

        val provider: (Boolean) -> StartupMemoryProfiler.Sample =
            { includePss -> sampler.collect(includePss) }

        profiler.setEnabled(false)
        profiler.resetStatistics()

        try {
            overrideField.set(profiler, provider)
            automaticField.setBoolean(profiler, false)

            profiler.setEnabled(true)

            assertTrue(profiler.getSnapshot().enabled)

            action(sampler)
        } finally {
            profiler.setEnabled(false)
            profiler.resetStatistics()

            overrideField.set(profiler, previousOverride)
            automaticField.setBoolean(profiler, previousAutomatic)
        }
    }

    private fun activeCount(): Int {
        val active = profilerField("active").get(profiler)
            as Map<*, *>

        return active.size
    }

    @Test(timeout = 10000)
    fun enabledPhaseMeasuresDurationDeltaAndPeak() {
        withEnabledProfiler { sampler ->
            sampler.used = 100L

            val id = profiler.beginPhase("synthetic")
            assertNotNull(id)

            sampler.used = 300L
            profiler.sample("synthetic")

            sampler.used = 150L
            profiler.endPhase(id)

            val snapshot = profiler.getSnapshot()
            val phase = snapshot.phases.single()

            assertEquals(20L, phase.durationMs)
            assertEquals(100L, phase.startBytes)
            assertEquals(150L, phase.endBytes)
            assertEquals(50L, phase.deltaBytes)
            assertEquals(300L, phase.peakBytes)
            assertEquals(300L, snapshot.peakBytes)
            assertEquals(0, activeCount())
        }
    }

    @Test(timeout = 10000)
    fun nestedPhasesHaveIndependentPeaks() {
        withEnabledProfiler { sampler ->
            sampler.used = 100L
            val outer = profiler.beginPhase("outer")

            sampler.used = 200L
            val inner = profiler.beginPhase("inner")

            assertNotNull(outer)
            assertNotNull(inner)
            assertNotEquals(outer, inner)

            sampler.used = 300L
            profiler.endPhase(inner)

            sampler.used = 500L
            profiler.sample("synthetic")

            sampler.used = 150L
            profiler.endPhase(outer)

            val phases = profiler.getSnapshot()
                .phases.associateBy { it.name }

            assertEquals(2, phases.size)
            assertEquals(300L, phases.getValue("inner").peakBytes)
            assertEquals(500L, phases.getValue("outer").peakBytes)

            assertEquals(10L, phases.getValue("inner").durationMs)
            assertEquals(40L, phases.getValue("outer").durationMs)

            assertEquals(0, activeCount())
        }
    }

    @Test(timeout = 20000)
    fun concurrentPhasesHaveUniqueIdentifiers() {
        withEnabledProfiler {
            val executor =
                java.util.concurrent.Executors.newFixedThreadPool(4)

            val ready =
                java.util.concurrent.CountDownLatch(4)

            val finish =
                java.util.concurrent.CountDownLatch(1)

            try {
                val futures = (0 until 4).map { index ->
                    executor.submit<Long> {
                        val id = profiler.beginPhase("worker-$index")
                            ?: error("Missing phase identifier")

                        ready.countDown()

                        check(
                            finish.await(
                                5,
                                java.util.concurrent.TimeUnit.SECONDS
                            )
                        )

                        profiler.endPhase(id)

                        id
                    }
                }

                assertTrue(
                    ready.await(
                        5,
                        java.util.concurrent.TimeUnit.SECONDS
                    )
                )

                assertEquals(4, activeCount())

                finish.countDown()

                val ids = futures.map {
                    it.get(
                        5,
                        java.util.concurrent.TimeUnit.SECONDS
                    )
                }

                assertEquals(4, ids.toSet().size)

                val phases = profiler.getSnapshot().phases

                assertEquals(4, phases.size)

                assertEquals(
                    (0 until 4).map { "worker-$it" }.toSet(),
                    phases.map { it.name }.toSet()
                )

                assertTrue(phases.all { it.durationMs > 0L })
                assertEquals(0, activeCount())
            } finally {
                finish.countDown()
                executor.shutdownNow()

                assertTrue(
                    executor.awaitTermination(
                        5,
                        java.util.concurrent.TimeUnit.SECONDS
                    )
                )
            }
        }
    }

    @Test(timeout = 10000)
    fun activePhaseLimitIsEnforced() {
        withEnabledProfiler {
            val ids = (0 until 64).map {
                profiler.beginPhase("phase-$it")
                    ?: error("Unexpected phase rejection")
            }

            assertEquals(64, ids.toSet().size)
            assertEquals(64, activeCount())

            assertNull(profiler.beginPhase("overflow"))

            profiler.endPhase(ids.first())

            val replacement = profiler.beginPhase("replacement")

            assertNotNull(replacement)

            ids.drop(1).forEach {
                profiler.endPhase(it)
            }

            profiler.endPhase(replacement)

            assertEquals(0, activeCount())
            assertEquals(65, profiler.getSnapshot().phases.size)
        }
    }

    @Test(timeout = 10000)
    fun sampleHistoryRemainsBounded() {
        withEnabledProfiler { sampler ->
            repeat(600) { index ->
                sampler.used = index.toLong()
                profiler.sample("synthetic")
            }

            val snapshot = profiler.getSnapshot()

            assertEquals(512, snapshot.history.size)
            assertEquals(88L, snapshot.history.first().usedBytes)
            assertEquals(599L, snapshot.history.last().usedBytes)
            assertEquals(599L, snapshot.peakBytes)
        }
    }

    @Test(timeout = 10000)
    fun phaseHistoryRemainsBounded() {
        withEnabledProfiler {
            repeat(150) { index ->
                val id = profiler.beginPhase("phase-$index")
                assertNotNull(id)

                profiler.endPhase(id)
            }

            val phases = profiler.getSnapshot().phases

            assertEquals(128, phases.size)
            assertEquals("phase-22", phases.first().name)
            assertEquals("phase-149", phases.last().name)
            assertEquals(0, activeCount())
        }
    }

    @Test(timeout = 10000)
    fun disablingProfilerInvalidatesActivePhases() {
        withEnabledProfiler {
            val old = profiler.beginPhase("old")
            assertNotNull(old)

            profiler.setEnabled(false)

            assertEquals(0, activeCount())

            profiler.endPhase(old)

            assertTrue(profiler.getSnapshot().phases.isEmpty())

            profiler.setEnabled(true)

            val current = profiler.beginPhase("current")
            assertNotNull(current)
            assertNotEquals(old, current)

            profiler.endPhase(old)

            assertEquals(1, activeCount())

            profiler.endPhase(current)

            assertEquals(
                "current",
                profiler.getSnapshot().phases.single().name
            )
        }
    }

    @Test(timeout = 10000)
    fun resetInvalidatesActivePhasesAndHistory() {
        withEnabledProfiler {
            val old = profiler.beginPhase("old")
            assertNotNull(old)

            profiler.resetStatistics()

            val snapshot = profiler.getSnapshot()

            assertTrue(snapshot.enabled)
            assertNull(snapshot.current)
            assertEquals(0L, snapshot.peakBytes)
            assertTrue(snapshot.history.isEmpty())
            assertTrue(snapshot.phases.isEmpty())
            assertEquals(0, activeCount())

            profiler.endPhase(old)

            assertTrue(profiler.getSnapshot().history.isEmpty())

            val current = profiler.beginPhase("current")
            assertNotNull(current)
            assertNotEquals(old, current)

            profiler.endPhase(current)

            assertEquals(
                "current",
                profiler.getSnapshot().phases.single().name
            )
        }
    }

    @Test(timeout = 10000)
    fun measurementFailureAtStartDoesNotBlockApplication() {
        withEnabledProfiler { sampler ->
            sampler.fail = true

            var calls = 0

            val result = profiler.measure("failure") {
                calls++
                42
            }

            assertEquals(42, result)
            assertEquals(1, calls)
            assertEquals(0, activeCount())

            sampler.fail = false

            val id = profiler.beginPhase("recovered")
            assertNotNull(id)

            profiler.endPhase(id)

            assertEquals(
                "recovered",
                profiler.getSnapshot().phases.single().name
            )
        }
    }

    @Test(timeout = 10000)
    fun measurementFailureAtEndReleasesActivePhase() {
        withEnabledProfiler { sampler ->
            val id = profiler.beginPhase("failure")
            assertNotNull(id)

            sampler.fail = true

            profiler.endPhase(id)

            assertEquals(0, activeCount())
            assertTrue(profiler.getSnapshot().phases.isEmpty())

            sampler.fail = false

            val recovered = profiler.beginPhase("recovered")
            assertNotNull(recovered)

            profiler.endPhase(recovered)

            assertEquals(
                "recovered",
                profiler.getSnapshot().phases.single().name
            )
        }
    }

    @Test(timeout = 10000)
    fun enabledProfilerPreservesApplicationException() {
        withEnabledProfiler { sampler ->
            val expected =
                IllegalArgumentException("synthetic application failure")

            val actual = assertThrows(
                IllegalArgumentException::class.java
            ) {
                profiler.measure("failure") {
                    sampler.fail = true
                    throw expected
                }
            }

            assertSame(expected, actual)
            assertEquals(0, activeCount())
        }
    }


    private fun withSyntheticArchive(
        action: (
            MemoryDiagnosticsStore,
            () -> Unit
        ) -> Unit
    ) {
        val temporary = org.junit.rules.TemporaryFolder()
        temporary.create()

        val archive = MemoryDiagnosticsStore(temporary.root)

        val worker = archive.javaClass
            .getDeclaredField("worker")
            .apply { isAccessible = true }
            .get(archive) as java.util.concurrent.ThreadPoolExecutor

        val maintenance = archive.javaClass
            .getDeclaredField("maintenance")
            .apply { isAccessible = true }
            .get(archive) as java.util.concurrent.ScheduledExecutorService

        val storeField = profilerField("store")
        val previousStore = storeField.get(profiler)

        val drain: () -> Unit = {
            worker.submit {}.get(
                5,
                java.util.concurrent.TimeUnit.SECONDS
            )
            Unit
        }

        try {
            maintenance.shutdownNow()

            assertTrue(
                maintenance.awaitTermination(
                    5,
                    java.util.concurrent.TimeUnit.SECONDS
                )
            )

            drain()

            assertFalse(profilerField("automaticSampling").getBoolean(profiler) && profiler.getSnapshot().enabled)

            storeField.set(profiler, archive)

            action(archive, drain)
        } finally {
            profiler.setEnabled(false)
            storeField.set(profiler, previousStore)

            worker.shutdownNow()
            maintenance.shutdownNow()

            try {
                assertTrue(
                    worker.awaitTermination(
                        5,
                        java.util.concurrent.TimeUnit.SECONDS
                    )
                )

                assertTrue(
                    maintenance.awaitTermination(
                        5,
                        java.util.concurrent.TimeUnit.SECONDS
                    )
                )
            } finally {
                temporary.delete()
            }
        }
    }

    private fun csvRows(
        archive: MemoryDiagnosticsStore
    ): List<List<String>> {
        val output = java.io.ByteArrayOutputStream()

        archive.exportTo(output)

        return output.toString("UTF-8")
            .lineSequence()
            .drop(1)
            .filter { it.isNotBlank() }
            .map { line ->
                val fields = line.split(',')

                assertEquals(18, fields.size)

                fields
            }
            .toList()
    }

    @Test(timeout = 15000)
    fun csvPhaseIdentifiersAreCorrelated() {
        withEnabledProfiler {
            withSyntheticArchive { archive, drain ->
                val id = profiler.beginPhase("synthetic")

                assertNotNull(id)

                profiler.endPhase(id)

                drain()

                val rows = csvRows(archive)

                val start = rows.single {
                    it[2] == "PHASE_START"
                }

                val end = rows.single {
                    it[2] == "PHASE_END"
                }

                assertEquals(id.toString(), start[8])
                assertEquals(start[8], end[8])

                assertEquals("synthetic", start[13])
                assertEquals(start[13], end[13])

                assertTrue(start[12].isNotBlank())
                assertEquals(start[12], end[12])

                assertEquals(0L, archive.droppedRecords())
            }
        }
    }

    @Test(timeout = 15000)
    fun csvPhaseStatisticsMatchProfilerSnapshot() {
        withEnabledProfiler { sampler ->
            withSyntheticArchive { archive, drain ->
                sampler.used = 100L

                val id = profiler.beginPhase("synthetic")

                sampler.used = 400L
                profiler.sample("synthetic")

                sampler.used = 150L
                profiler.endPhase(id)

                drain()

                val phase = profiler.getSnapshot()
                    .phases.single()

                val row = csvRows(archive).single {
                    it[2] == "PHASE_END"
                }

                assertEquals(
                    phase.durationMs,
                    row[9].toLong()
                )

                assertEquals(
                    phase.startBytes,
                    row[10].toLong()
                )

                assertEquals(
                    phase.peakBytes,
                    row[11].toLong()
                )

                assertEquals(
                    phase.endBytes,
                    row[3].toLong()
                )

                assertEquals(400L, row[11].toLong())
                assertEquals(20L, row[9].toLong())
            }
        }
    }

    @Test(timeout = 15000)
    fun csvNestedPhasesRemainIndependentlyCorrelated() {
        withEnabledProfiler { sampler ->
            withSyntheticArchive { archive, drain ->
                sampler.used = 100L
                val outer = profiler.beginPhase("outer")

                sampler.used = 200L
                val inner = profiler.beginPhase("inner")

                sampler.used = 300L
                profiler.endPhase(inner)

                sampler.used = 500L
                profiler.sample("synthetic")

                sampler.used = 150L
                profiler.endPhase(outer)

                drain()

                val rows = csvRows(archive)

                val starts = rows.filter {
                    it[2] == "PHASE_START"
                }

                val ends = rows.filter {
                    it[2] == "PHASE_END"
                }

                assertEquals(2, starts.size)
                assertEquals(2, ends.size)

                assertEquals(
                    starts.map { it[8] }.toSet(),
                    ends.map { it[8] }.toSet()
                )

                val outerEnd = ends.single {
                    it[8] == outer.toString()
                }

                val innerEnd = ends.single {
                    it[8] == inner.toString()
                }

                assertEquals("outer", outerEnd[13])
                assertEquals("inner", innerEnd[13])

                assertEquals(500L, outerEnd[11].toLong())
                assertEquals(300L, innerEnd[11].toLong())

                assertEquals(40L, outerEnd[9].toLong())
                assertEquals(10L, innerEnd[9].toLong())

                assertEquals(0L, archive.droppedRecords())
            }
        }
    }

    @Test(timeout = 15000)
    fun disabledProfilerDoesNotWriteDiagnosticRecords() {
        withSyntheticArchive { archive, drain ->
            repeat(1000) {
                profiler.sample("synthetic")

                profiler.measure("synthetic") {
                    it + 1
                }
            }

            drain()

            assertTrue(csvRows(archive).isEmpty())

            assertEquals(0L, archive.droppedRecords())

            val directory = java.io.File(
                archiveDirectoryRoot(archive),
                "memory_diagnostics"
            )

            assertFalse(directory.exists())
        }
    }

    private fun archiveDirectoryRoot(
        archive: MemoryDiagnosticsStore
    ): java.io.File {
        val directory = archive.javaClass
            .getDeclaredField("directory")
            .apply { isAccessible = true }
            .get(archive) as java.io.File

        return directory.parentFile
            ?: error("Missing synthetic archive root")
    }


    private fun observedHeapBytes(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private fun benchmarkCpuNs(): Long {
        return try {
            val factory = Class.forName(
                "java.lang.management.ManagementFactory"
            )

            val beanType = Class.forName(
                "java.lang.management.ThreadMXBean"
            )

            val bean = factory
                .getMethod("getThreadMXBean")
                .invoke(null)

            val supported = beanType
                .getMethod("isCurrentThreadCpuTimeSupported")
                .invoke(bean) as Boolean

            val enabled = beanType
                .getMethod("isThreadCpuTimeEnabled")
                .invoke(bean) as Boolean

            if (supported && enabled) {
                (beanType
                    .getMethod("getCurrentThreadCpuTime")
                    .invoke(bean) as Number).toLong()
            } else {
                -1L
            }
        } catch (_: Exception) {
            -1L
        }
    }

    @Test(timeout = 30000)
    fun syntheticProfilerOverheadBenchmark() {
        val iterations = 100000
        var checksum = 0L

        repeat(10000) {
            profiler.measure("warmup") {
                checksum += it
            }
        }

        val baselineStart = System.nanoTime()

        repeat(iterations) {
            checksum += it
        }

        val baselineNs = System.nanoTime() - baselineStart

        val offHeapBefore = observedHeapBytes()
        val offCpuBefore = benchmarkCpuNs()
        val offStart = System.nanoTime()

        repeat(iterations) {
            profiler.measure("synthetic") {
                checksum += it
            }
        }

        val offNs = System.nanoTime() - offStart
        val offCpuAfter = benchmarkCpuNs()
        val offHeapAfter = observedHeapBytes()

        assertFalse(profiler.getSnapshot().enabled)
        assertTrue(profiler.getSnapshot().history.isEmpty())

        println("MEM07_BASELINE_NS_PER_OP=${baselineNs.toDouble() / iterations}")
        println("MEM07_OFF_NS_PER_OP=${offNs.toDouble() / iterations}")
        println("MEM07_OFF_HEAP_DELTA_BYTES=${offHeapAfter - offHeapBefore}")

        if (offCpuBefore >= 0L && offCpuAfter >= offCpuBefore) {
            println("MEM07_OFF_CPU_NS_PER_OP=${(offCpuAfter - offCpuBefore).toDouble() / iterations}")
        } else {
            println("MEM07_OFF_CPU=UNAVAILABLE")
        }

        withEnabledProfiler { sampler ->
            repeat(10000) {
                profiler.measure("warmup") {
                    checksum += it
                }
            }

            profiler.resetStatistics()

            val onHeapBefore = observedHeapBytes()
            val onCpuBefore = benchmarkCpuNs()
            val onStart = System.nanoTime()

            var observedPeak = onHeapBefore

            repeat(iterations) {
                sampler.used = 100L + it

                profiler.measure("synthetic") {
                    checksum += it
                }

                if (it % 1000 == 0) {
                    observedPeak = maxOf(
                        observedPeak,
                        observedHeapBytes()
                    )
                }
            }

            val onNs = System.nanoTime() - onStart
            val onCpuAfter = benchmarkCpuNs()
            val onHeapAfter = observedHeapBytes()

            observedPeak = maxOf(observedPeak, onHeapAfter)

            val snapshot = profiler.getSnapshot()

            assertEquals(512, snapshot.history.size)
            assertEquals(128, snapshot.phases.size)
            assertEquals(0, activeCount())

            println("MEM07_ON_NS_PER_OP=${onNs.toDouble() / iterations}")
            println("MEM07_ON_HEAP_DELTA_BYTES=${onHeapAfter - onHeapBefore}")
            println("MEM07_ON_OBSERVED_HEAP_GROWTH_BYTES=${observedPeak - onHeapBefore}")

            if (onCpuBefore >= 0L && onCpuAfter >= onCpuBefore) {
                println("MEM07_ON_CPU_NS_PER_OP=${(onCpuAfter - onCpuBefore).toDouble() / iterations}")
            } else {
                println("MEM07_ON_CPU=UNAVAILABLE")
            }

            println("MEM07_HISTORY_SIZE=${snapshot.history.size}")
            println("MEM07_PHASE_HISTORY_SIZE=${snapshot.phases.size}")
        }

        println("MEM07_CHECKSUM=$checksum")
        println("MEM07_PROFILER_BENCHMARK=COMPLETED")
    }

    @Test(timeout = 30000)
    fun syntheticArchiveThroughputBenchmark() {
        withSyntheticArchive { archive, drain ->
            val batches = 100
            val batchSize = 32
            val count = batches * batchSize

            val heapBefore = observedHeapBytes()
            val start = System.nanoTime()

            repeat(batches) { batch ->
                repeat(batchSize) { index ->
                    val id = (batch * batchSize + index).toLong()

                    archive.append(
                        MemoryDiagnosticsStore.Kind.SAMPLE,
                        id,
                        1024L,
                        2048L,
                        4096L,
                        512L,
                        null
                    )
                }

                drain()
            }

            val elapsedNs = System.nanoTime() - start
            val heapAfter = observedHeapBytes()

            val directory = java.io.File(
                archiveDirectoryRoot(archive),
                "memory_diagnostics"
            )

            val bytes = directory.listFiles()
                ?.filter { it.isFile }
                ?.sumOf { it.length() }
                ?: 0L

            val rows = csvRows(archive)

            assertEquals(count, rows.size)
            assertEquals(0L, archive.droppedRecords())
            assertTrue(bytes <= 5L * 1024L * 1024L)

            println("MEM07_IO_RECORDS=$count")
            println("MEM07_IO_BYTES=$bytes")
            println("MEM07_IO_ELAPSED_MS=${elapsedNs / 1000000.0}")
            println("MEM07_IO_RECORDS_PER_SECOND=${count * 1000000000.0 / elapsedNs}")
            println("MEM07_IO_HEAP_DELTA_BYTES=${heapAfter - heapBefore}")
            println("MEM07_IO_DROPPED=${archive.droppedRecords()}")
            println("MEM07_IO_BENCHMARK=COMPLETED")
        }
    }
}
