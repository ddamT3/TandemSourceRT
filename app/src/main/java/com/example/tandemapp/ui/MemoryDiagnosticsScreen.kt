package com.example.tandemapp.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.tandemapp.data.StartupMemoryProfiler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
internal fun MemoryDiagnosticsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf(StartupMemoryProfiler.getSnapshot()) }
    var message by remember { mutableStateOf("") }
    var confirmReset by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        StartupMemoryProfiler.initialize(context)
        while (true) {
            snapshot = StartupMemoryProfiler.getSnapshot()
            delay(1000)
        }
    }
    BackHandler(onBack = onBack)
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            message = withContext(Dispatchers.IO) {
                try {
                    val store = StartupMemoryProfiler.diagnosticsStore() ?: error("Unavailable")
                    val output = context.contentResolver.openOutputStream(uri) ?: error("Unavailable")
                    output.use { store.exportTo(it) }
                    "Export completed"
                } catch (_: Exception) {
                    "Export failed"
                }
            }
        }
    }
    val sample = snapshot.current
    val limit = sample?.maxBytes ?: Runtime.getRuntime().maxMemory()
    val graphColor = MaterialTheme.colorScheme.primary
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text("Back") }
        Text("Memory Diagnostics", style = MaterialTheme.typography.headlineSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Enable memory profiler", Modifier.weight(1f))
            Switch(checked = snapshot.enabled, onCheckedChange = {
                StartupMemoryProfiler.setEnabled(it)
                snapshot = StartupMemoryProfiler.getSnapshot()
            })
        }
        Text("Java heap: ${sample?.usedBytes?.let(::mib) ?: "--"} / ${mib(limit)}")
        Text("Sampled peak: ${mib(snapshot.peakBytes)}")
        Text("Java committed: ${sample?.committedBytes?.let(::mib) ?: "--"}")
        Text("Native allocated: ${sample?.nativeBytes?.let(::mib) ?: "--"}")
        val pss = snapshot.history.lastOrNull { it.pssKiB != null }?.pssKiB
        Text("Last PSS: ${pss?.let { mib(it.toLong() * 1024) } ?: "--"}")
        Text("Recent heap samples")
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val points = snapshot.history
            if (points.size > 1 && limit > 0) {
                val first = points.first().elapsedMs
                val span = (points.last().elapsedMs - first).coerceAtLeast(1)
                fun point(i: Int): Offset {
                    val s = points[i]
                    return Offset(size.width * (s.elapsedMs - first).toFloat() / span,
                        size.height * (1f - (s.usedBytes.toFloat() / limit).coerceIn(0f, 1f)))
                }
                for (i in 1 until points.size) drawLine(graphColor, point(i - 1), point(i), 2.dp.toPx())
            }
        }
        Text("Completed phases", style = MaterialTheme.typography.titleMedium)
        for (phase in snapshot.phases.asReversed()) {
            Text("${phase.name}: ${phase.durationMs} ms")
            Text("Start ${mib(phase.startBytes)} | End ${mib(phase.endBytes)} | Peak ${mib(phase.peakBytes)} | Delta ${mib(phase.deltaBytes)}",
                style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = { export.launch("memory_diagnostics.csv") }) { Text("Export CSV") }
        OutlinedButton(onClick = { confirmReset = true }) { Text("Reset statistics") }
        if (message.isNotEmpty()) Text(message)
    }
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("Reset memory diagnostics?") },
        text = { Text("Only diagnostic statistics and CSV history will be cleared.") },
        confirmButton = { TextButton(onClick = {
            StartupMemoryProfiler.resetStatistics()
            snapshot = StartupMemoryProfiler.getSnapshot()
            confirmReset = false
        }) { Text("Reset") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } }
    )
}

private fun mib(bytes: Long): String = String.format(Locale.US, "%.1f MiB", bytes / 1048576.0)
