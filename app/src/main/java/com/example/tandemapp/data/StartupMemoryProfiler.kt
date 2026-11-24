package com.example.tandemapp.data

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal object StartupMemoryProfiler {
    data class Sample(
        val elapsedMs: Long,
        val usedBytes: Long,
        val committedBytes: Long,
        val maxBytes: Long,
        val nativeBytes: Long,
        val pssKiB: Int?
    )

    data class PhaseResult(
        val name: String,
        val durationMs: Long,
        val startBytes: Long,
        val endBytes: Long,
        val peakBytes: Long
    ) {
        val deltaBytes: Long get() = endBytes - startBytes
    }

    data class Snapshot(
        val enabled: Boolean,
        val current: Sample?,
        val peakBytes: Long,
        val history: List<Sample>,
        val phases: List<PhaseResult>
    )

    private data class ActivePhase(
        val name: String,
        val start: Sample,
        var peak: Long
    )

    private const val HISTORY_LIMIT = 512
    private const val PHASE_LIMIT = 128
    private const val ACTIVE_LIMIT = 64
    private val lock = Any()
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "memory-profiler").apply { isDaemon = true }
    }
    private var scheduled: ScheduledFuture<*>? = null
    private var enabled = false
    private var store: MemoryDiagnosticsStore? = null

    fun initialize(context: Context) = synchronized(lock) {
        if (store == null) store = MemoryDiagnosticsStore(context.applicationContext.filesDir)
    }

    fun diagnosticsStore(): MemoryDiagnosticsStore? = synchronized(lock) { store }
    private var nextId = 0L
    private var lastPssAt = -2000L
    private var lastSampleAt = -5000L
    private var peak = 0L
    private var current: Sample? = null
    private val active = mutableMapOf<Long, ActivePhase>()
    private val history = ArrayDeque<Sample>()
    private val phases = ArrayDeque<PhaseResult>()

    fun setEnabled(value: Boolean) = synchronized(lock) {
        if (enabled == value) return@synchronized
        enabled = value
        if (value) {
            lastSampleAt = -5000L
            lastPssAt = -2000L
            scheduled = executor.scheduleWithFixedDelay({
                synchronized(lock) {
                    if (enabled) {
                        val now = SystemClock.elapsedRealtime()
                        val interval = if (active.isEmpty()) 5000L else 250L
                        if (now - lastSampleAt >= interval) {
                            record(now - lastPssAt >= 2000L)
                        }
                    }
                }
            }, 0, 250, TimeUnit.MILLISECONDS)
        } else {
            scheduled?.cancel(false)
            scheduled = null
            active.clear()
        }
    }

    fun sample(stage: String) = synchronized(lock) {
        if (!enabled) return@synchronized
        val s = record(false)
        Log.i("P0B2A_MEMORY", "stage=$stage javaUsedMiB=${s.usedBytes / 1048576L} javaMaxMiB=${s.maxBytes / 1048576L}")
    }

    fun beginPhase(name: String): Long? = synchronized(lock) {
        if (!enabled || active.size >= ACTIVE_LIMIT) return@synchronized null
        val s = record(false)
        val id = ++nextId
        active[id] = ActivePhase(name, s, s.usedBytes)
        persist(s, MemoryDiagnosticsStore.Kind.PHASE_START, id)
        id
    }

    fun endPhase(id: Long?) = synchronized(lock) {
        if (!enabled || id == null) return@synchronized
        val phase = active[id] ?: return@synchronized
        val s = record(false)
        active.remove(id)
        persist(s, MemoryDiagnosticsStore.Kind.PHASE_END, id,
            s.elapsedMs - phase.start.elapsedMs, phase.start.usedBytes, phase.peak)
        if (phases.size >= PHASE_LIMIT) phases.removeFirst()
        phases.addLast(PhaseResult(
            phase.name, s.elapsedMs - phase.start.elapsedMs,
            phase.start.usedBytes, s.usedBytes, phase.peak
        ))
    }

    fun getSnapshot(): Snapshot = synchronized(lock) {
        Snapshot(enabled, current, peak, history.toList(), phases.toList())
    }

    fun resetStatistics() = synchronized(lock) {
        history.clear()
        phases.clear()
        active.clear()
        peak = 0L
        current = null
    }

    private fun record(includePss: Boolean): Sample {
        val runtime = Runtime.getRuntime()
        val now = SystemClock.elapsedRealtime()
        val committed = runtime.totalMemory()
        val used = (committed - runtime.freeMemory()).coerceAtLeast(0L)
        val pss = if (includePss) {
            lastPssAt = now
            try {
                val info = Debug.MemoryInfo()
                Debug.getMemoryInfo(info)
                info.totalPss
            } catch (_: RuntimeException) {
                null
            }
        } else null
        val s = Sample(now, used, committed, runtime.maxMemory(),
            Debug.getNativeHeapAllocatedSize(), pss)
        lastSampleAt = now
        current = s
        peak = maxOf(peak, used)
        for (phase in active.values) phase.peak = maxOf(phase.peak, used)
        if (history.size >= HISTORY_LIMIT) history.removeFirst()
        history.addLast(s)
        persist(s, MemoryDiagnosticsStore.Kind.SAMPLE)
        return s
    }

    private fun persist(s: Sample, kind: MemoryDiagnosticsStore.Kind,
                        id: Long? = null, duration: Long? = null,
                        start: Long? = null, peak: Long? = null) {
        store?.append(kind, s.elapsedMs, s.usedBytes, s.committedBytes,
            s.maxBytes, s.nativeBytes, s.pssKiB, id, duration, start, peak)
    }
}
