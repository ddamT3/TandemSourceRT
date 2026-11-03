package com.example.tandemapp.data

import android.os.Debug
import android.util.Log

internal object StartupMemoryProfiler {
    fun sample(stage: String) {
        val runtime = Runtime.getRuntime()
        val mib = 1024L * 1024L

        val used = runtime.totalMemory() - runtime.freeMemory()

        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)

        Log.i(
            "P0B2A_MEMORY",
            "stage=$stage " +
                "javaUsedMiB=${used / mib} " +
                "javaCommittedMiB=${runtime.totalMemory() / mib} " +
                "javaMaxMiB=${runtime.maxMemory() / mib} " +
                "nativeAllocatedMiB=${Debug.getNativeHeapAllocatedSize() / mib} " +
                "totalPssKiB=${info.totalPss} " +
                "dalvikPssKiB=${info.dalvikPss} " +
                "nativePssKiB=${info.nativePss} " +
                "otherPssKiB=${info.otherPss}"
        )
    }
}