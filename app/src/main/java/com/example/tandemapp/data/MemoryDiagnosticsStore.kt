package com.example.tandemapp.data

import java.io.File
import java.io.OutputStream
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Private diagnostic records only. All disk operations are serialized. */
internal class MemoryDiagnosticsStore(filesDir: File) {
    private val directory = File(filesDir, "memory_diagnostics")
    private val dropped = AtomicLong()
    private val worker = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(128),
        { task -> Thread(task, "memory-diagnostics-io").apply { isDaemon = true } },
        { _, _ -> dropped.incrementAndGet() }
    )

    fun droppedRecords(): Long = dropped.get()

    fun append(kind: Kind, elapsedMs: Long, used: Long, committed: Long,
               maximum: Long, native: Long, pss: Int?) {
        val wall = System.currentTimeMillis()
        val row = "$wall,$elapsedMs,${kind.name},$used,$committed,$maximum,$native,${pss ?: ""}\n"
        worker.execute {
            try {
                prune(wall)
                if (!directory.exists() && !directory.mkdirs()) throw IOException("mkdir")
                val file = File(directory, "memory-${wall / BUCKET_MS}.csv")
                val bytes = row.toByteArray(Charsets.UTF_8)
                val total = files().sumOf { it.length() }
                if (total + bytes.size + HEADER.length > LIMIT_BYTES) {
                    dropped.incrementAndGet()
                } else {
                    file.appendBytes(bytes)
                }
            } catch (_: IOException) {
                dropped.incrementAndGet()
            } catch (_: SecurityException) {
                dropped.incrementAndGet()
            }
        }
    }

    enum class Kind { SAMPLE, PHASE_START, PHASE_END }

    // Call off the UI thread. Export only complete, non-expired records.
    @Synchronized
    fun exportTo(output: OutputStream) {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        output.write(HEADER.toByteArray(Charsets.UTF_8))
        for (file in files()) {
            file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    val fields = line.split(',')
                    if (fields.size == 8 &&
                        fields[0].toLongOrNull()?.let { it >= cutoff } == true &&
                        fields[2] in Kind.entries.map { it.name } &&
                        fields[1].toLongOrNull() != null &&
                        fields.subList(3, 7).all { it.toLongOrNull() != null } &&
                        (fields[7].isEmpty() || fields[7].toIntOrNull() != null)) {
                        output.write((line + "\n").toByteArray(Charsets.UTF_8))
                    }
                }
            }
        }
    }

    private fun files(): List<File> = directory.listFiles()?.filter {
        it.isFile && it.name.matches(Regex("memory-[0-9]+\\.csv"))
    }?.sortedBy { it.name } ?: emptyList()

    private fun prune(now: Long) {
        val cutoff = now - RETENTION_MS
        for (file in files()) {
            val bucket = file.name.removePrefix("memory-").removeSuffix(".csv").toLongOrNull() ?: continue
            if ((bucket + 1) * BUCKET_MS <= cutoff) file.delete()
        }
    }

    companion object {
        private const val BUCKET_MS = 60_000L
        private const val RETENTION_MS = 48L * 60 * 60 * 1000
        private const val LIMIT_BYTES = 5L * 1024 * 1024
        private const val HEADER = "wall_ms,elapsed_ms,kind,used_bytes,committed_bytes,max_bytes,native_bytes,pss_kib\n"
    }
}
