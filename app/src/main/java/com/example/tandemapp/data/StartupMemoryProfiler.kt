package com.example.tandemapp.data

import android.content.Context
import android.app.ActivityManager
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal object StartupMemoryProfiler {
    data class DeviceMemory(val totalBytes: Long, val availableBytes: Long,
                            val thresholdBytes: Long, val lowMemory: Boolean)

    data class Sample(
        val elapsedMs: Long,
        val usedBytes: Long,
        val committedBytes: Long,
        val maxBytes: Long,
        val nativeBytes: Long,
        val pssKiB: Int?,
        val deviceMemory: DeviceMemory? = null
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

    // Private injection points for deterministic JVM tests.
    // Production always uses Android measurements and automatic sampling.
    private var sampleOverride: ((Boolean) -> Sample)? = null
    private var automaticSampling = true
    private var store: MemoryDiagnosticsStore? = null
    private var activityManager: ActivityManager? = null
    private var preferences: android.content.SharedPreferences? = null

    internal inline fun <T> diagnosticOr(
        fallback: T,
        block: () -> T
    ): T {
        return try {
            block()
        } catch (_: Exception) {
            fallback
        }
    }

    fun initialize(context: Context) = diagnosticOr(Unit) {
        initializeInternal(context)
    }

    private fun initializeInternal(context: Context) = synchronized(lock) {
        if (store == null) {
            val app = context.applicationContext
            activityManager = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            store = MemoryDiagnosticsStore(app.filesDir)
            preferences = app.getSharedPreferences("memory_diagnostics", Context.MODE_PRIVATE)
            setEnabled(preferences?.getBoolean("enabled", false) ?: false)
        }
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
        preferences?.edit()?.putBoolean("enabled", value)?.apply()
        if (value) {
            lastSampleAt = -5000L
            lastPssAt = -2000L
            if (!automaticSampling) return@synchronized

            scheduled = executor.scheduleWithFixedDelay({
                diagnosticOr(Unit) {
                    synchronized(lock) {
                        if (enabled) {
                            val now = SystemClock.elapsedRealtime()
                            val interval = if (active.isEmpty()) 5000L else 250L
                            if (now - lastSampleAt >= interval) {
                                record(now - lastPssAt >= 2000L)
                            }
                        }
                    }
                    Unit
                }
            }, 0, 250, TimeUnit.MILLISECONDS)
        } else {
            scheduled?.cancel(false)
            scheduled = null
            active.clear()
        }
    }

    fun sample(stage: String) = diagnosticOr(Unit) {
        sampleInternal(stage)
    }

    private fun sampleInternal(stage: String) = synchronized(lock) {
        if (!enabled) return@synchronized
        val s = record(false)
        Log.i("P0B2A_MEMORY", "stage=$stage javaUsedMiB=${s.usedBytes / 1048576L} javaMaxMiB=${s.maxBytes / 1048576L}")
    }

    inline fun <T> measure(name: String, block: () -> T): T {
        val id = beginPhase(name)
        try { return block() } finally { endPhase(id) }
    }

    fun beginPhase(name: String): Long? = diagnosticOr<Long?>(null) {
        CrashDiagnostics.markPhase(name)
        beginPhaseInternal(name)
    }

    private fun beginPhaseInternal(name: String): Long? = synchronized(lock) {
        if (!enabled || active.size >= ACTIVE_LIMIT) return@synchronized null
        val s = record(false)
        val id = ++nextId
        active[id] = ActivePhase(name, s, s.usedBytes)
        try {
            persist(s, MemoryDiagnosticsStore.Kind.PHASE_START, id, name = name)
            id
        } catch (e: Exception) {
            active.remove(id)
            throw e
        }
    }

    fun endPhase(id: Long?) = diagnosticOr(Unit) {
        endPhaseInternal(id)
    }

    private fun endPhaseInternal(id: Long?) = synchronized(lock) {
        if (!enabled || id == null) return@synchronized
        val phase = active[id] ?: return@synchronized
        val s = try {
            record(false)
        } finally {
            active.remove(id)
        }

        persist(s, MemoryDiagnosticsStore.Kind.PHASE_END, id,
            s.elapsedMs - phase.start.elapsedMs, phase.start.usedBytes, phase.peak, phase.name)
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
        store?.resetArchive()
        history.clear()
        phases.clear()
        active.clear()
        peak = 0L
        current = null
    }

    private fun collectAndroidSample(includePss: Boolean): Sample {
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

        return Sample(
            now,
            used,
            committed,
            runtime.maxMemory(),
            Debug.getNativeHeapAllocatedSize(),
            pss,
            if (includePss) diagnosticOr<DeviceMemory?>(null) {
                activityManager?.let { manager ->
                    val info = ActivityManager.MemoryInfo()
                    manager.getMemoryInfo(info)
                    DeviceMemory(info.totalMem, info.availMem, info.threshold, info.lowMemory)
                }
            } else null
        )
    }

    private fun record(includePss: Boolean): Sample {
        val s = sampleOverride?.invoke(includePss)
            ?: collectAndroidSample(includePss)

        if (includePss) {
            lastPssAt = s.elapsedMs
        }

        lastSampleAt = s.elapsedMs
        current = s
        peak = maxOf(peak, s.usedBytes)

        for (phase in active.values) {
            phase.peak = maxOf(phase.peak, s.usedBytes)
        }

        if (history.size >= HISTORY_LIMIT) {
            history.removeFirst()
        }

        history.addLast(s)
        persist(s, MemoryDiagnosticsStore.Kind.SAMPLE)

        return s
    }

    private fun persist(s: Sample, kind: MemoryDiagnosticsStore.Kind,
                        id: Long? = null, duration: Long? = null,
                        start: Long? = null, peak: Long? = null, name: String? = null) {
        store?.append(kind, s.elapsedMs, s.usedBytes, s.committedBytes,
            s.maxBytes, s.nativeBytes, s.pssKiB, id, duration, start, peak, name,
            s.deviceMemory?.totalBytes, s.deviceMemory?.availableBytes,
            s.deviceMemory?.thresholdBytes, s.deviceMemory?.lowMemory)
    }
}
