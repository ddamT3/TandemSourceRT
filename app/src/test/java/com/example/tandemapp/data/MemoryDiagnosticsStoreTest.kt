package com.example.tandemapp.data

import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class MemoryDiagnosticsStoreTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private lateinit var root: File
    private lateinit var store: MemoryDiagnosticsStore
    private lateinit var worker: ThreadPoolExecutor
    private lateinit var maintenance: ScheduledExecutorService
    private lateinit var diskLock: Any

    @Before
    fun prepare() {
        root = temporary.newFolder()
        store = MemoryDiagnosticsStore(root)

        worker = field("worker") as ThreadPoolExecutor
        diskLock = field("diskLock")
        maintenance = field("maintenance") as ScheduledExecutorService

        // Stop periodic maintenance for deterministic unit tests.
        maintenance.shutdownNow()
        assertTrue(maintenance.awaitTermination(5, TimeUnit.SECONDS))
        drain()
    }

    @After
    fun cleanup() {
        if (::worker.isInitialized) {
            worker.shutdownNow()
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun field(name: String): Any {
        return store.javaClass.getDeclaredField(name).apply {
            isAccessible = true
        }.get(store) ?: error("Missing field: $name")
    }

    private fun append(id: Long) {
        store.append(
            MemoryDiagnosticsStore.Kind.SAMPLE,
            id, 1024L, 2048L, 4096L, 512L, null
        )
    }

    private fun drain() {
        worker.submit {}.get(5, TimeUnit.SECONDS)
    }

    private fun records(): List<Long> {
        val output = ByteArrayOutputStream()
        store.exportTo(output)

        return output.toString("UTF-8")
            .lineSequence()
            .drop(1)
            .filter { it.isNotBlank() }
            .map { it.split(',')[1].toLong() }
            .toList()
    }

    @Test(timeout = 10000)
    fun normalResetRemovesOldRecords() {
        append(1)
        drain()
        assertEquals(listOf(1L), records())

        store.resetArchive()
        drain()

        assertTrue(records().isEmpty())
    }

    @Test(timeout = 10000)
    fun fullQueueCannotDiscardReset() {
        append(1)
        drain()

        synchronized(diskLock) {
            repeat(300) {
                append(100L + it)
            }

            assertEquals(0, worker.queue.remainingCapacity())
            assertTrue(store.droppedRecords() > 0)

            store.resetArchive()

            assertTrue(worker.queue.remainingCapacity() > 0)
            append(999)
        }

        drain()

        assertEquals(listOf(999L), records())
    }

    @Test(timeout = 10000)
    fun writesPendingBeforeResetAreInvalidated() {
        synchronized(diskLock) {
            repeat(32) {
                append(it.toLong())
            }

            store.resetArchive()
            append(1000)
        }

        drain()

        assertEquals(listOf(1000L), records())
    }

    @Test(timeout = 10000)
    fun repeatedResetsPreserveOnlyLatestGeneration() {
        synchronized(diskLock) {
            append(1)
            store.resetArchive()
            append(2)
            store.resetArchive()
            append(3)
        }

        drain()

        assertEquals(listOf(3L), records())
    }

    @Test(timeout = 10000)
    fun resetDoesNotDeleteUnrelatedFiles() {
        val unrelated = File(root, "unrelated.txt")
        unrelated.writeText("synthetic")

        append(1)
        drain()

        val directory = File(root, "memory_diagnostics")
        val other = File(directory, "unrelated.txt")
        other.writeText("synthetic")

        store.resetArchive()
        drain()

        assertEquals("synthetic", unrelated.readText())
        assertEquals("synthetic", other.readText())
        assertTrue(records().isEmpty())
    }

    @Test(timeout = 10000)
    fun filesystemFailureDoesNotStopWorker() {
        val directory = File(root, "memory_diagnostics")
        directory.writeText("synthetic obstruction")

        val before = store.droppedRecords()

        append(1)
        drain()

        assertTrue(store.droppedRecords() > before)
        assertTrue(directory.delete())

        append(2)
        drain()

        assertEquals(listOf(2L), records())
    }


    @Test(timeout = 10000)
    fun initialResetStateIsIdle() {
        assertEquals(
            MemoryDiagnosticsStore.ResetState.IDLE,
            store.getResetState()
        )
    }

    @Test(timeout = 10000)
    fun queuedResetIsPending() {
        synchronized(diskLock) {
            store.resetArchive()

            assertEquals(
                MemoryDiagnosticsStore.ResetState.PENDING,
                store.getResetState()
            )
        }

        drain()

        assertEquals(
            MemoryDiagnosticsStore.ResetState.SUCCESS,
            store.getResetState()
        )
    }

    @Test(timeout = 10000)
    fun completedResetReportsSuccess() {
        append(1)
        drain()

        store.resetArchive()
        drain()

        assertTrue(records().isEmpty())

        assertEquals(
            MemoryDiagnosticsStore.ResetState.SUCCESS,
            store.getResetState()
        )
    }

    @Test(timeout = 10000)
    fun resetFilesystemFailureIsReported() {
        val obstruction = File(root, "memory_diagnostics")
        obstruction.writeText("synthetic obstruction")

        store.resetArchive()
        drain()

        assertEquals(
            MemoryDiagnosticsStore.ResetState.FAILED,
            store.getResetState()
        )

        assertTrue(obstruction.isFile)
    }

    @Test(timeout = 10000)
    fun resetCanRecoverAfterFailure() {
        val obstruction = File(root, "memory_diagnostics")
        obstruction.writeText("synthetic obstruction")

        store.resetArchive()
        drain()

        assertEquals(
            MemoryDiagnosticsStore.ResetState.FAILED,
            store.getResetState()
        )

        assertTrue(obstruction.delete())

        append(1)
        drain()

        store.resetArchive()
        drain()

        assertEquals(
            MemoryDiagnosticsStore.ResetState.SUCCESS,
            store.getResetState()
        )

        assertTrue(records().isEmpty())
    }

    @Test(timeout = 10000)
    fun repeatedResetReportsLatestGeneration() {
        synchronized(diskLock) {
            append(1)
            store.resetArchive()
            append(2)
            store.resetArchive()

            assertEquals(
                MemoryDiagnosticsStore.ResetState.PENDING,
                store.getResetState()
            )

            append(3)
        }

        drain()

        assertEquals(
            MemoryDiagnosticsStore.ResetState.SUCCESS,
            store.getResetState()
        )

        assertEquals(listOf(3L), records())
    }


    private fun syntheticRow(id: Long): String {
        val wall = System.currentTimeMillis()
        return "$wall,$id,SAMPLE,1024,2048,4096,512,,,,,,synthetic-session,\n"
    }

    private fun syntheticArchive(
        content: String,
        bucketOffset: Long = 1L
    ): File {
        val directory = File(root, "memory_diagnostics")

        assertTrue(directory.isDirectory || directory.mkdirs())

        val bucket = System.currentTimeMillis() / 60000L - bucketOffset
        val file = File(directory, "memory-v3-$bucket.csv")

        file.writeText(content, Charsets.UTF_8)

        return file
    }

    @Test(timeout = 10000)
    fun crashRecoveryPreservesCompleteRecords() {
        syntheticArchive(
            syntheticRow(1) +
            syntheticRow(2) +
            syntheticRow(3)
        )

        assertEquals(listOf(1L, 2L, 3L), records())
    }

    @Test(timeout = 10000)
    fun exportRejectsIncompleteTail() {
        syntheticArchive(
            syntheticRow(1) +
            syntheticRow(2) +
            "123,456,SAMPLE,1024"
        )

        assertEquals(listOf(1L, 2L), records())
    }

    @Test(timeout = 10000)
    fun exportRejectsValidRowWithoutLf() {
        syntheticArchive(
            syntheticRow(1) +
            syntheticRow(2).removeSuffix("\n")
        )

        assertEquals(listOf(1L), records())
    }

    @Test(timeout = 10000)
    fun appendRepairsIncompleteTail() {
        // Prepare the active bucket and retry if the minute changes.
        var verified = false

        repeat(3) { attempt ->
            if (!verified) {
                store.resetArchive()
                drain()

                val bucket = System.currentTimeMillis() / 60000L
                val firstId = 100L + attempt * 10L
                val nextId = firstId + 1L

                val file = syntheticArchive(
                    syntheticRow(firstId) + "incomplete-tail",
                    0L
                )

                if (file.name == "memory-v3-$bucket.csv") {
                    append(nextId)
                    drain()

                    if (System.currentTimeMillis() / 60000L == bucket) {
                        assertEquals(
                            listOf(firstId, nextId),
                            records()
                        )

                        assertFalse(
                            file.readText().contains("incomplete-tail")
                        )

                        verified = true
                    }
                }
            }
        }

        assertTrue("Unable to verify within a stable bucket", verified)
    }

    @Test(timeout = 10000)
    fun appendRepairsEntirelyIncompleteFile() {
        var verified = false

        repeat(3) { attempt ->
            if (!verified) {
                store.resetArchive()
                drain()

                val bucket = System.currentTimeMillis() / 60000L
                val id = 200L + attempt

                val file = syntheticArchive(
                    "incomplete-record-without-lf",
                    0L
                )

                if (file.name == "memory-v3-$bucket.csv") {
                    append(id)
                    drain()

                    if (System.currentTimeMillis() / 60000L == bucket) {
                        assertEquals(listOf(id), records())

                        assertFalse(
                            file.readText().contains("incomplete-record")
                        )

                        verified = true
                    }
                }
            }
        }

        assertTrue("Unable to verify within a stable bucket", verified)
    }

    @Test(timeout = 10000)
    fun exportSkipsOversizedRecordAndContinues() {
        val oversized = syntheticRow(2).removeSuffix("\n") +
            "x".repeat(2048) + "\n"

        syntheticArchive(
            syntheticRow(1) +
            oversized +
            syntheticRow(3)
        )

        assertEquals(listOf(1L, 3L), records())
    }

    @Test(timeout = 10000)
    fun exportDoesNotModifyDamagedArchive() {
        val file = syntheticArchive(
            syntheticRow(1) +
            syntheticRow(2).removeSuffix("\n")
        )

        val before = file.readBytes()

        assertEquals(listOf(1L), records())

        assertArrayEquals(before, file.readBytes())
    }

    @Test(timeout = 10000)
    fun appendRepairsTailAcrossMultipleBlocks() {
        var verified = false

        repeat(3) { attempt ->
            if (!verified) {
                store.resetArchive()
                drain()

                val bucket = System.currentTimeMillis() / 60000L
                val firstId = 300L + attempt * 10L
                val nextId = firstId + 1L

                val file = syntheticArchive(
                    syntheticRow(firstId) + "x".repeat(10000),
                    0L
                )

                if (file.name == "memory-v3-$bucket.csv") {
                    append(nextId)
                    drain()

                    if (System.currentTimeMillis() / 60000L == bucket) {
                        assertEquals(
                            listOf(firstId, nextId),
                            records()
                        )

                        assertTrue(file.length() < 2048L)
                        assertFalse(file.readText().contains("xxxx"))

                        verified = true
                    }
                }
            }
        }

        assertTrue("Unable to verify within a stable bucket", verified)
    }


    @Test(timeout = 10000)
    fun maintenanceRemovesExpiredFiles() {
        val expired = syntheticArchive(
            syntheticRow(1),
            3000L
        )

        assertTrue(expired.exists())

        store.requestMaintenance()
        drain()

        assertFalse(expired.exists())
    }

    @Test(timeout = 10000)
    fun maintenancePreservesRecentFiles() {
        val recent = syntheticArchive(
            syntheticRow(1),
            60L
        )

        val before = recent.readBytes()

        store.requestMaintenance()
        drain()

        assertTrue(recent.exists())
        assertArrayEquals(before, recent.readBytes())
    }

    @Test(timeout = 10000)
    fun exportExcludesExpiredRecords() {
        val expiredWall = System.currentTimeMillis() -
            49L * 60L * 60L * 1000L

        val expiredRow =
            "$expiredWall,1,SAMPLE,1024,2048,4096,512,,,,,,synthetic-session,\n"

        val file = syntheticArchive(
            expiredRow + syntheticRow(2)
        )

        val before = file.readBytes()

        assertEquals(listOf(2L), records())
        assertArrayEquals(before, file.readBytes())
    }

    @Test(timeout = 10000)
    fun maintenanceWorksWithoutNewSamples() {
        val expired = syntheticArchive(
            syntheticRow(1),
            3000L
        )

        val recent = syntheticArchive(
            syntheticRow(2),
            10L
        )

        val before = recent.readBytes()

        // No append calls are made in this test.
        store.requestMaintenance()
        drain()

        assertFalse(expired.exists())
        assertTrue(recent.exists())
        assertArrayEquals(before, recent.readBytes())
        assertEquals(listOf(2L), records())
    }

    private fun sizedArchive(
        bucketOffset: Long,
        size: Long
    ): File {
        val file = syntheticArchive("", bucketOffset)

        java.io.RandomAccessFile(file, "rw").use {
            it.setLength(size)
        }

        return file
    }

    private fun archiveSize(): Long {
        val directory = File(root, "memory_diagnostics")

        return directory.listFiles()
            ?.filter {
                it.isFile &&
                it.name.matches(Regex("memory-v3-[0-9]+\\.csv"))
            }
            ?.sumOf { it.length() } ?: 0L
    }

    @Test(timeout = 10000)
    fun appendEnforcesArchiveSizeLimit() {
        val limit = 5L * 1024L * 1024L

        sizedArchive(120L, 3L * 1024L * 1024L)
        sizedArchive(60L, 2L * 1024L * 1024L)

        assertEquals(limit, archiveSize())

        append(999L)
        drain()

        assertTrue(archiveSize() <= limit)
        assertEquals(listOf(999L), records())
    }

    @Test(timeout = 10000)
    fun sizeLimitEvictsOldestFileFirst() {
        val oldest = sizedArchive(
            120L,
            3L * 1024L * 1024L
        )

        val newest = sizedArchive(
            60L,
            2L * 1024L * 1024L
        )

        append(999L)
        drain()

        assertFalse(oldest.exists())
        assertTrue(newest.exists())

        assertEquals(
            2L * 1024L * 1024L,
            newest.length()
        )

        assertTrue(
            archiveSize() <= 5L * 1024L * 1024L
        )

        assertEquals(listOf(999L), records())
    }

    @Test(timeout = 10000)
    fun maintenancePreservesUnrelatedFiles() {
        val expired = syntheticArchive(
            syntheticRow(1),
            3000L
        )

        val directory = File(root, "memory_diagnostics")

        val unrelated = File(directory, "unrelated.txt")
        unrelated.writeText("synthetic unrelated data")

        val unrelatedCsv = File(directory, "other.csv")
        unrelatedCsv.writeText("synthetic csv")

        store.requestMaintenance()
        drain()

        assertFalse(expired.exists())

        assertEquals(
            "synthetic unrelated data",
            unrelated.readText()
        )

        assertEquals(
            "synthetic csv",
            unrelatedCsv.readText()
        )
    }


    private fun nullableField(name: String): Any? {
        return store.javaClass.getDeclaredField(name).apply {
            isAccessible = true
        }.get(store)
    }

    private fun indexedArchiveBytes(): Long? {
        return nullableField("archiveBytes") as Long?
    }

    private fun indexedBucket(): Long? {
        return nullableField("preparedBucket") as Long?
    }

    private fun assertArchiveIndexConsistent() {
        assertNotNull(indexedArchiveBytes())
        assertEquals(archiveSize(), indexedArchiveBytes())
        assertNotNull(indexedBucket())
    }

    @Test(timeout = 10000)
    fun archiveIndexMatchesSuccessiveWrites() {
        repeat(64) { index ->
            append(index.toLong())
            drain()

            assertArchiveIndexConsistent()
        }

        assertEquals(
            (0L until 64L).toList(),
            records()
        )

        assertTrue(
            archiveSize() <= 5L * 1024L * 1024L
        )
    }

    @Test(timeout = 10000)
    fun resetInvalidatesArchiveIndex() {
        append(1)
        drain()

        assertArchiveIndexConsistent()

        store.resetArchive()
        drain()

        assertNull(indexedArchiveBytes())
        assertNull(indexedBucket())
        assertEquals(0L, archiveSize())
        assertTrue(records().isEmpty())
    }

    @Test(timeout = 10000)
    fun archiveIndexRebuildsAfterReset() {
        append(1)
        drain()

        store.resetArchive()
        drain()

        append(2)
        drain()

        assertArchiveIndexConsistent()
        assertEquals(listOf(2L), records())
    }

    @Test(timeout = 10000)
    fun maintenanceInvalidatesArchiveIndex() {
        append(1)
        drain()

        assertArchiveIndexConsistent()

        store.requestMaintenance()
        drain()

        assertNull(indexedArchiveBytes())
        assertNull(indexedBucket())
        assertEquals(listOf(1L), records())
    }

    @Test(timeout = 10000)
    fun archiveIndexRebuildsAfterMaintenance() {
        append(1)
        drain()

        val expired = syntheticArchive(
            syntheticRow(99),
            3000L
        )

        store.requestMaintenance()
        drain()

        assertFalse(expired.exists())
        assertNull(indexedArchiveBytes())

        append(2)
        drain()

        assertArchiveIndexConsistent()
        assertEquals(listOf(1L, 2L), records())
    }

    @Test(timeout = 10000)
    fun archiveIndexRebuildsAfterFilesystemFailure() {
        append(1)
        drain()

        assertArchiveIndexConsistent()

        val directory = File(root, "memory_diagnostics")
        val displaced = File(root, "synthetic-displaced")

        assertTrue(directory.renameTo(displaced))

        directory.writeText("synthetic obstruction")

        val before = store.droppedRecords()

        append(2)
        drain()

        assertTrue(store.droppedRecords() > before)
        assertNull(indexedArchiveBytes())
        assertNull(indexedBucket())

        assertTrue(directory.delete())
        assertTrue(displaced.renameTo(directory))

        append(3)
        drain()

        assertArchiveIndexConsistent()
        assertEquals(listOf(1L, 3L), records())
    }

    private fun appendAtSyntheticWall(
        wall: Long,
        id: Long
    ) {
        val method = store.javaClass.getDeclaredMethod(
            "appendRecord",
            Long::class.javaPrimitiveType!!,
            String::class.java
        )

        method.isAccessible = true

        val row =
            "$wall,$id,SAMPLE,1024,2048,4096,512,,,,,,synthetic-session,\n"

        synchronized(diskLock) {
            method.invoke(store, wall, row)
        }
    }

    @Test(timeout = 10000)
    fun archiveIndexRebuildsOnBucketRotation() {
        val currentBucket =
            System.currentTimeMillis() / 60000L

        val firstWall =
            (currentBucket - 2L) * 60000L + 1000L

        val secondWall =
            firstWall + 60000L

        appendAtSyntheticWall(firstWall, 1L)

        assertArchiveIndexConsistent()
        assertEquals(firstWall / 60000L, indexedBucket())

        appendAtSyntheticWall(secondWall, 2L)

        assertArchiveIndexConsistent()
        assertEquals(secondWall / 60000L, indexedBucket())

        assertEquals(listOf(1L, 2L), records())

        val directory = File(root, "memory_diagnostics")

        assertTrue(
            File(
                directory,
                "memory-v3-${firstWall / 60000L}.csv"
            ).exists()
        )

        assertTrue(
            File(
                directory,
                "memory-v3-${secondWall / 60000L}.csv"
            ).exists()
        )
    }


    private fun runConcurrent(
        vararg actions: () -> Unit
    ) {
        val executor =
            java.util.concurrent.Executors.newFixedThreadPool(
                actions.size
            )

        val ready =
            java.util.concurrent.CountDownLatch(actions.size)

        val start =
            java.util.concurrent.CountDownLatch(1)

        try {
            val futures = actions.map { action ->
                executor.submit {
                    ready.countDown()

                    check(start.await(5, TimeUnit.SECONDS))

                    action()
                }
            }

            assertTrue(
                ready.await(5, TimeUnit.SECONDS)
            )

            start.countDown()

            for (future in futures) {
                future.get(5, TimeUnit.SECONDS)
            }
        } finally {
            start.countDown()
            executor.shutdownNow()

            assertTrue(
                executor.awaitTermination(5, TimeUnit.SECONDS)
            )
        }
    }

    @Test(timeout = 20000)
    fun concurrentProducersPreserveRecords() {
        val before = store.droppedRecords()

        val producers = Array<() -> Unit>(4) { producer ->
            {
                repeat(20) { index ->
                    append(producer * 20L + index)
                }
            }
        }

        runConcurrent(*producers)
        drain()

        val actual = records()

        assertEquals(80, actual.size)

        assertEquals(
            (0L until 80L).toSet(),
            actual.toSet()
        )

        assertEquals(
            before,
            store.droppedRecords()
        )

        assertArchiveIndexConsistent()
    }

    @Test(timeout = 20000)
    fun concurrentExportPreservesValidRecords() {
        append(1000L)
        drain()

        runConcurrent(
            {
                repeat(64) {
                    append(it.toLong())
                }
            },
            {
                repeat(20) {
                    val exported = records()

                    assertTrue(
                        exported.contains(1000L)
                    )

                    assertEquals(
                        exported.size,
                        exported.toSet().size
                    )

                    assertTrue(
                        exported.all {
                            it == 1000L || it in 0L..63L
                        }
                    )
                }
            }
        )

        drain()

        val actual = records()

        assertEquals(65, actual.size)

        assertEquals(
            (0L until 64L).toSet() + 1000L,
            actual.toSet()
        )

        assertArchiveIndexConsistent()
    }

    @Test(timeout = 20000)
    fun concurrentResetPreservesNewGeneration() {
        append(1000L)
        drain()

        runConcurrent(
            {
                repeat(40) {
                    append(it.toLong())
                }
            },
            {
                repeat(40) {
                    append(100L + it)
                }
            },
            {
                store.resetArchive()
            }
        )

        drain()

        assertEquals(
            MemoryDiagnosticsStore.ResetState.SUCCESS,
            store.getResetState()
        )

        append(9999L)
        drain()

        val actual = records()

        assertFalse(actual.contains(1000L))
        assertTrue(actual.contains(9999L))

        assertEquals(
            actual.size,
            actual.toSet().size
        )

        assertTrue(
            actual.all {
                it == 9999L ||
                it in 0L..39L ||
                it in 100L..139L
            }
        )

        assertArchiveIndexConsistent()
    }

    @Test(timeout = 20000)
    fun concurrentMaintenancePreservesRecentWrites() {
        val expired = syntheticArchive(
            syntheticRow(9999L),
            3000L
        )

        val before = store.droppedRecords()

        runConcurrent(
            {
                repeat(64) {
                    append(it.toLong())
                }
            },
            {
                repeat(10) {
                    store.requestMaintenance()
                }
            }
        )

        drain()

        assertFalse(expired.exists())

        val actual = records()

        assertEquals(64, actual.size)

        assertEquals(
            (0L until 64L).toSet(),
            actual.toSet()
        )

        assertEquals(
            before,
            store.droppedRecords()
        )

        // Maintenance may invalidate the index.
        // A subsequent write must rebuild it.
        append(1000L)
        drain()

        assertArchiveIndexConsistent()
    }

    @Test(timeout = 20000)
    fun concurrentOperationsKeepArchiveIndexConsistent() {
        repeat(5) { round ->
            store.resetArchive()
            drain()

            val base = round * 1000L

            runConcurrent(
                {
                    repeat(24) {
                        append(base + it)
                    }
                },
                {
                    repeat(24) {
                        append(base + 100L + it)
                    }
                },
                {
                    repeat(5) {
                        records()
                    }
                },
                {
                    repeat(5) {
                        store.requestMaintenance()
                    }
                }
            )

            drain()

            append(base + 999L)
            drain()

            assertArchiveIndexConsistent()

            val expected =
                (0L until 24L).map { base + it }.toSet() +
                (0L until 24L).map { base + 100L + it } +
                (base + 999L)

            val actual = records()

            assertEquals(49, actual.size)
            assertEquals(expected, actual.toSet())

            assertTrue(
                archiveSize() <= 5L * 1024L * 1024L
            )
        }
    }


    @Test(timeout = 10000)
    fun exportIsRejectedWhileResetIsPending() {
        append(1L)
        drain()

        synchronized(diskLock) {
            store.resetArchive()

            assertEquals(
                MemoryDiagnosticsStore.ResetState.PENDING,
                store.getResetState()
            )

            val output = ByteArrayOutputStream()

            assertThrows(java.io.IOException::class.java) {
                store.exportTo(output)
            }

            assertEquals(0, output.size())
        }

        drain()

        assertEquals(
            MemoryDiagnosticsStore.ResetState.SUCCESS,
            store.getResetState()
        )
    }

    @Test(timeout = 10000)
    fun exportIsAvailableAfterSuccessfulReset() {
        append(1L)
        drain()

        store.resetArchive()
        drain()

        assertEquals(
            MemoryDiagnosticsStore.ResetState.SUCCESS,
            store.getResetState()
        )

        assertTrue(records().isEmpty())

        append(2L)
        drain()

        assertEquals(listOf(2L), records())
    }

    @Test(timeout = 10000)
    fun exportIsRejectedAfterFailedReset() {
        append(1L)
        drain()

        val directory = File(root, "memory_diagnostics")
        val displaced = File(root, "synthetic-displaced")

        assertTrue(directory.renameTo(displaced))

        directory.writeText("synthetic obstruction")

        try {
            store.resetArchive()
            drain()

            assertEquals(
                MemoryDiagnosticsStore.ResetState.FAILED,
                store.getResetState()
            )

            val output = ByteArrayOutputStream()

            assertThrows(java.io.IOException::class.java) {
                store.exportTo(output)
            }

            assertEquals(0, output.size())
        } finally {
            assertTrue(directory.delete())
            assertTrue(displaced.renameTo(directory))
        }

        store.resetArchive()
        drain()

        assertEquals(
            MemoryDiagnosticsStore.ResetState.SUCCESS,
            store.getResetState()
        )

        assertTrue(records().isEmpty())
    }

    @Test(timeout = 10000)
    fun exportAfterResetContainsOnlyNewGeneration() {
        append(1L)
        append(2L)
        drain()

        synchronized(diskLock) {
            store.resetArchive()

            append(3L)
            append(4L)

            val output = ByteArrayOutputStream()

            assertThrows(java.io.IOException::class.java) {
                store.exportTo(output)
            }

            assertEquals(0, output.size())
        }

        drain()

        assertEquals(
            MemoryDiagnosticsStore.ResetState.SUCCESS,
            store.getResetState()
        )

        assertEquals(listOf(3L, 4L), records())
        assertArchiveIndexConsistent()
    }


    @Test(timeout = 10000)
    fun maintenanceEnforcesSizeLimitWithoutNewSamples() {
        val mib = 1024L * 1024L

        val oldest = sizedArchive(120L, 3L * mib)
        val newest = sizedArchive(60L, 3L * mib)

        assertEquals(6L * mib, archiveSize())

        store.requestMaintenance()
        drain()

        assertFalse(oldest.exists())
        assertTrue(newest.exists())
        assertEquals(3L * mib, newest.length())
        assertTrue(archiveSize() <= 5L * mib)
        assertEquals(0L, store.droppedRecords())
    }

    @Test(timeout = 10000)
    fun maintenanceSizeLimitPreservesUnrelatedFiles() {
        val mib = 1024L * 1024L

        val oldest = sizedArchive(120L, 4L * mib)
        val newest = sizedArchive(60L, 4L * mib)

        val unrelated = File(
            newest.parentFile,
            "unrelated.csv"
        )

        unrelated.writeText("synthetic unrelated content")

        store.requestMaintenance()
        drain()

        assertFalse(oldest.exists())
        assertTrue(newest.exists())
        assertTrue(unrelated.exists())

        assertEquals(
            "synthetic unrelated content",
            unrelated.readText()
        )

        assertTrue(archiveSize() <= 5L * mib)
    }

    @Test(timeout = 10000)
    fun maintenanceReportsDirectoryFailureAndRecovers() {
        val directory = File(root, "memory_diagnostics")

        assertFalse(directory.exists())

        directory.writeText("synthetic obstruction")

        val before = store.droppedRecords()

        store.requestMaintenance()
        drain()

        assertTrue(store.droppedRecords() > before)
        assertNull(indexedArchiveBytes())

        assertTrue(directory.delete())

        append(999L)
        drain()

        assertEquals(listOf(999L), records())
        assertArchiveIndexConsistent()
    }

    @Test(timeout = 10000)
    fun exportsDeviceMemoryAndLegacyRecordsTogether() {
        val now = System.currentTimeMillis()
        val directory = File(root, "memory_diagnostics")
        assertTrue(directory.mkdirs() || directory.isDirectory)
        val legacy = File(directory, "memory-v3-${now / 60000L}.csv")
        legacy.writeText("$now,1,SAMPLE,1024,2048,4096,512,,,,,,synthetic,\n")
        store.append(MemoryDiagnosticsStore.Kind.SAMPLE,
            2L, 1024L, 2048L, 4096L, 512L, 100,
            deviceTotal = 4096L, deviceAvailable = 1024L,
            deviceThreshold = 512L, deviceLowMemory = false)
        append(3L)
        drain()
        val output = ByteArrayOutputStream()
        store.exportTo(output)
        val rows = output.toString("UTF-8").lineSequence()
            .filter { it.isNotBlank() }.map { it.split(',') }.toList()
        assertEquals(4, rows.size)
        assertTrue(rows.all { it.size == 18 })
        assertEquals(listOf("", "", "", ""), rows[1].takeLast(4))
        assertEquals(listOf("4096", "1024", "512", "false"), rows[2].takeLast(4))
        assertEquals(listOf("", "", "", ""), rows[3].takeLast(4))
    }
}
