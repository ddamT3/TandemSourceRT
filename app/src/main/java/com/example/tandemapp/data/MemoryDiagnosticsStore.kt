package com.example.tandemapp.data

import java.io.File
import java.io.OutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Private diagnostic records only. All disk operations are serialized. */
internal class MemoryDiagnosticsStore(filesDir: File) {
    private val directory = File(filesDir, "memory_diagnostics")
    private val dropped = AtomicLong()
    private val session = UUID.randomUUID().toString()
    private val diskLock = Any()
    private val submissionLock = Any()
    private val generation = AtomicLong()

    // Accessed exclusively under diskLock.
    private var archiveBytes: Long? = null
    private var preparedBucket: Long? = null

    private fun invalidateArchiveIndex() {
        archiveBytes = null
        preparedBucket = null
    }

    enum class ResetState { IDLE, PENDING, SUCCESS, FAILED }

    @Volatile
    private var resetState = ResetState.IDLE

    fun getResetState(): ResetState = resetState
    private val worker = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(128),
        { task -> Thread(task, "memory-diagnostics-io").apply { isDaemon = true } },
        { _, _ -> dropped.incrementAndGet() }
    )

    private val maintenance = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "memory-diagnostics-maintenance").apply {
            isDaemon = true
        }
    }

    init {
        maintenance.scheduleWithFixedDelay(
            { requestMaintenance() },
            0L,
            MAINTENANCE_INTERVAL_MINUTES,
            TimeUnit.MINUTES
        )
    }

    internal fun requestMaintenance() {
        try {
            synchronized(submissionLock) {
                if (!worker.isShutdown &&
                    resetState != ResetState.PENDING &&
                    worker.queue.remainingCapacity() > 0) {

                    val submittedGeneration = generation.get()

                    worker.execute {
                        synchronized(diskLock) {
                            if (submittedGeneration != generation.get()) {
                                return@execute
                            }

                            try {
                                invalidateArchiveIndex()
                                prune(System.currentTimeMillis())
                                enforceMaintenanceLimit()
                            } catch (_: Exception) {
                                dropped.incrementAndGet()
                            }
                        }
                    }
                }

                Unit
            }
        } catch (_: Exception) {
            dropped.incrementAndGet()
        }
    }

    fun droppedRecords(): Long = dropped.get()

    fun append(kind: Kind, elapsedMs: Long, used: Long, committed: Long,
               maximum: Long, native: Long, pss: Int?, phaseId: Long? = null, durationMs: Long? = null,
               startBytes: Long? = null, peakBytes: Long? = null, phaseName: String? = null,
               deviceTotal: Long? = null, deviceAvailable: Long? = null,
               deviceThreshold: Long? = null, deviceLowMemory: Boolean? = null) {
        val wall = System.currentTimeMillis()
        val safeName = phaseName?.take(64)?.replace(Regex("[^A-Za-z0-9_.-]"), "_") ?: ""
        val row = "$wall,$elapsedMs,${kind.name},$used,$committed,$maximum,$native,${pss ?: ""},${phaseId ?: ""},${durationMs ?: ""},${startBytes ?: ""},${peakBytes ?: ""},$session,$safeName,${deviceTotal ?: ""},${deviceAvailable ?: ""},${deviceThreshold ?: ""},${deviceLowMemory ?: ""}\n"
        synchronized(submissionLock) {
            val submittedGeneration = generation.get()
            worker.execute {
                synchronized(diskLock) {
                    if (submittedGeneration != generation.get()) {
                        return@execute
                    }
                    try {
                        appendRecord(wall, row)
                    } catch (_: IOException) {
                        invalidateArchiveIndex()
                        dropped.incrementAndGet()
                    } catch (_: SecurityException) {
                        invalidateArchiveIndex()
                        dropped.incrementAndGet()
                    }
                }
            }
        }
    }

    // Called exclusively by the serialized I/O worker under diskLock.
    private fun appendRecord(wall: Long, row: String) {
        val bucket = wall / BUCKET_MS

        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("mkdir")
        }

        val file = File(directory, "memory-v3-$bucket.csv")

        if (preparedBucket != bucket || archiveBytes == null) {
            invalidateArchiveIndex()
            prune(System.currentTimeMillis())
            repairIncompleteTail(file)

            archiveBytes = files().sumOf { it.length() }
            preparedBucket = bucket
        }

        val bytes = row.toByteArray(Charsets.UTF_8)
        var total = archiveBytes ?: throw IOException("archive index")

        if (total + bytes.size + HEADER.length > LIMIT_BYTES) {
            for (old in files()) {
                if (total + bytes.size + HEADER.length <= LIMIT_BYTES) {
                    break
                }

                val size = old.length()

                if (old.delete()) {
                    total -= size
                }
            }

            archiveBytes = total
        }

        if (total + bytes.size + HEADER.length > LIMIT_BYTES) {
            dropped.incrementAndGet()
            return
        }

        file.appendBytes(bytes)
        archiveBytes = total + bytes.size
    }

    fun resetArchive(): Unit {
        synchronized(submissionLock) {
            val resetGeneration = generation.incrementAndGet()
            resetState = ResetState.PENDING

            // Discard pending diagnostic writes from older generations.
            // All producers use submissionLock, so the reset has queue space.
            worker.queue.clear()

            try {
                worker.execute {
                    var success = true

                    synchronized(diskLock) {
                        try {
                            invalidateArchiveIndex()

                            if (directory.exists()) {
                                val entries = directory.listFiles()
                                    ?: throw IOException("list diagnostics")

                                for (file in entries) {
                                    if (file.isFile &&
                                        file.name.matches(Regex("memory-v3-[0-9]+\\.csv"))) {
                                        if (!file.delete() && file.exists()) {
                                            success = false
                                            dropped.incrementAndGet()
                                        }
                                    }
                                }
                            }

                            Unit
                        } catch (_: Exception) {
                            success = false
                            dropped.incrementAndGet()
                            Unit
                        }
                    }

                    synchronized(submissionLock) {
                        if (generation.get() == resetGeneration) {
                            resetState = if (success) {
                                ResetState.SUCCESS
                            } else {
                                ResetState.FAILED
                            }
                        }

                        Unit
                    }
                }

                if (worker.isShutdown) {
                    resetState = ResetState.FAILED
                }

                Unit
            } catch (_: Exception) {
                resetState = ResetState.FAILED
                dropped.incrementAndGet()
                Unit
            }
        }
    }

    enum class Kind { SAMPLE, PHASE_START, PHASE_END }

    // Call off the UI thread. Export only complete, non-expired records.
    private fun requireExportAvailable() {
        when (resetState) {
            ResetState.PENDING ->
                throw IOException("Diagnostic reset in progress")

            ResetState.FAILED ->
                throw IOException("Diagnostic reset failed")

            ResetState.IDLE, ResetState.SUCCESS -> Unit
        }
    }

    fun exportTo(output: OutputStream) = synchronized(diskLock) {
        requireExportAvailable()
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        output.write(HEADER.toByteArray(Charsets.UTF_8))
        for (file in files()) {
            forEachCompleteLine(file) { line ->
                val fields = line.split(',')
                if ((fields.size == 14 || (fields.size == 18 &&
                    fields.subList(14, 17).all { it.isEmpty() || it.toLongOrNull()?.let { n -> n >= 0 } == true } &&
                    fields[17] in listOf("", "true", "false"))) &&
                    fields[0].toLongOrNull()?.let { it >= cutoff } == true &&
                    Kind.values().any { it.name == fields[2] } &&
                    fields[1].toLongOrNull() != null &&
                    fields.subList(3, 7).all { it.toLongOrNull() != null } &&
                    (fields[7].isEmpty() || fields[7].toIntOrNull() != null) &&
                    fields.subList(8, 12).all { it.isEmpty() || it.toLongOrNull() != null }) {
                    val normalized = if (fields.size == 14) line + ",,,," else line
                    output.write((normalized + "\n").toByteArray(Charsets.UTF_8))
                }
            }
        }
    }

    // Read complete LF-terminated records without modifying the archive.
    private fun forEachCompleteLine(file: File, consume: (String) -> Unit) {
        file.inputStream().buffered(4096).use { input ->
            val line = ByteArray(1024)
            var size = 0
            var oversized = false

            while (true) {
                val value = input.read()
                if (value == -1) break

                if (value == 10) {
                    if (!oversized && size > 0) {
                        consume(String(line, 0, size, Charsets.UTF_8))
                    }

                    size = 0
                    oversized = false
                } else if (!oversized) {
                    if (size < line.size) {
                        line[size++] = value.toByte()
                    } else {
                        oversized = true
                    }
                }
            }

            // Any remaining bytes lack LF and are not exported.
        }
    }

    // Called under diskLock before appending a diagnostic record.
    private fun repairIncompleteTail(file: File) {
        if (!file.exists() || file.length() == 0L) return

        RandomAccessFile(file, "rw").use { access ->
            val length = access.length()
            if (length == 0L) return@use

            access.seek(length - 1)

            if (access.read() == 10) return@use

            val buffer = ByteArray(4096)
            var end = length

            while (end > 0L) {
                val start = (end - buffer.size).coerceAtLeast(0L)
                val count = (end - start).toInt()

                access.seek(start)
                access.readFully(buffer, 0, count)

                for (i in count - 1 downTo 0) {
                    if (buffer[i] == 10.toByte()) {
                        access.setLength(start + i + 1L)
                        return@use
                    }
                }

                end = start
            }

            // No complete record exists in this file.
            access.setLength(0L)
        }
    }

    private fun files(): List<File> {
        if (!directory.exists()) return emptyList()

        val entries = directory.listFiles()
            ?: throw IOException("list diagnostics")

        return entries.filter {
            it.isFile &&
                it.name.matches(Regex("memory-v3-[0-9]+\\.csv"))
        }.sortedBy {
            it.name.removePrefix("memory-v3-")
                .removeSuffix(".csv")
                .toLongOrNull() ?: Long.MAX_VALUE
        }
    }

    // Called under diskLock by the maintenance worker.
    private fun enforceMaintenanceLimit() {
        val entries = files()
        var total = entries.sumOf { it.length() }

        for (file in entries) {
            if (total <= LIMIT_BYTES) break

            val size = file.length()

            if (!file.delete() && file.exists()) {
                throw IOException("delete diagnostic archive")
            }

            total -= size
        }

        if (total > LIMIT_BYTES) {
            throw IOException("diagnostic archive limit")
        }
    }

    private fun prune(now: Long) {
        val cutoff = now - RETENTION_MS
        for (file in files()) {
            val bucket = file.name.removePrefix("memory-v3-").removeSuffix(".csv").toLongOrNull() ?: continue
            if ((bucket + 1) * BUCKET_MS <= cutoff) {
                if (!file.delete() && file.exists()) {
                    throw IOException("delete expired diagnostics")
                }
            }
        }
    }

    companion object {
        private const val MAINTENANCE_INTERVAL_MINUTES = 15L
        private const val BUCKET_MS = 60_000L
        private const val RETENTION_MS = 48L * 60 * 60 * 1000
        private const val LIMIT_BYTES = 5L * 1024 * 1024
        private const val HEADER = "wall_ms,elapsed_ms,kind,used_bytes,committed_bytes,max_bytes,native_bytes,pss_kib,phase_id,duration_ms,start_bytes,peak_bytes,session,phase_name,device_total_bytes,device_available_bytes,device_threshold_bytes,device_low_memory\n"
    }
}
