package com.example.tandemapp.ui

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.tandemapp.data.AppUpdateRepository
import com.example.tandemapp.data.GitHubRelease
import com.example.tandemapp.st.BuildConfig
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class UpdateUiState(
	val busy: Boolean = false, val progress: Int? = null, val message: String? = null,
	val release: GitHubRelease? = null, val ready: File? = null, val showRelease: Boolean = false
)

/** Only the current operation may publish progress, errors, results or completion. */
internal class UpdateOperationState {
	private var generation = 0L
	var value by mutableStateOf(UpdateUiState())
		private set

	fun begin(next: UpdateUiState): Long {
		generation++
		value = next
		return generation
	}
	fun isCurrent(operation: Long) = operation == generation
	fun update(operation: Long, transform: (UpdateUiState) -> UpdateUiState): Boolean {
		if (!isCurrent(operation)) return false
		value = transform(value)
		return true
	}
	fun finish(operation: Long) = update(operation) { it.copy(busy = false, progress = null) }
	fun cancelDownload() {
		generation++ // Invalidate before cancelling: old callbacks and finally blocks are now harmless.
		value = value.copy(busy = false, progress = null, ready = null, message = "Download cancelled.")
	}
	fun editIdle(transform: (UpdateUiState) -> UpdateUiState) {
		if (!value.busy) value = transform(value)
	}
}

internal class AppUpdateViewModel(application: Application) : AndroidViewModel(application) {
	private val repository = AppUpdateRepository(application)
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
	private val operations = UpdateOperationState()
	private var task: Job? = null
	val state: UpdateUiState get() = operations.value

	fun check() {
		if (state.busy) return
		state.ready?.delete()
		val operation = operations.begin(UpdateUiState(busy = true, message = "Checking GitHub releases..."))
		runTask(operation) {
			val release = repository.latest()
			operations.update(operation) {
				if (release != null && release.version.versionCode > BuildConfig.VERSION_CODE.toLong())
					it.copy(release = release, showRelease = true, message = "Update available: ${release.version.tag}")
				else it.copy(message = "No newer stable release is available.")
			}
		}
	}

	fun download() {
		val release = state.release ?: return
		if (state.busy) return
		state.ready?.delete()
		val operation = operations.begin(state.copy(busy = true, ready = null, progress = 0,
			message = "Downloading and verifying APK..."))
		runTask(operation) {
			val file = repository.download(release) { percent ->
				withContext(Dispatchers.Main.immediate) {
					operations.update(operation) { it.copy(progress = percent) }
				}
			}
			val accepted = operations.update(operation) {
				it.copy(ready = file, message = if (release.sha256 == null)
					"APK package, version and current signing certificates verified. GitHub did not publish a SHA-256 checksum."
					else "APK verified. Ready to open the Android installer.")
			}
			if (!accepted) file.delete()
		}
	}

	private fun runTask(operation: Long, block: suspend () -> Unit) {
		task = scope.launch {
			try { block() }
			catch (cancelled: CancellationException) { throw cancelled }
			catch (error: Exception) {
				operations.update(operation) { it.copy(message = error.message ?: "Update request failed", ready = null) }
			} finally {
				if (operations.finish(operation)) task = null
			}
		}
	}

	fun cancelDownload() {
		if (!state.busy || state.progress == null) return
		val cancelled = task
		operations.cancelDownload()
		task = null
		cancelled?.cancel()
	}
	fun dismiss() { operations.editIdle { it.copy(showRelease = false) } }
	fun message(value: String) { operations.editIdle { it.copy(message = value) } }

	suspend fun prepareInstaller(): Intent? {
		val release = state.release ?: return null
		val file = state.ready ?: return null
		if (state.busy) return null
		val operation = operations.begin(state.copy(busy = true, message = "Verifying APK before installation..."))
		return try {
			repository.verify(file, release)
			if (operations.isCurrent(operation)) repository.installer(file, release) else null
		} catch (cancelled: CancellationException) {
			operations.update(operation) { it.copy(message = "Installation preparation cancelled.") }
			throw cancelled
		} catch (error: Exception) {
			file.delete()
			operations.update(operation) {
				it.copy(ready = null, message = error.message ?: "APK verification failed. Installation blocked.")
			}
			null
		} finally { operations.finish(operation) }
	}

	fun installerReturned(result: Int) {
		message(if (result == Activity.RESULT_OK) "Android reported installation completed."
			else "Installation cancelled or not completed. You can retry.")
	}

	override fun onCleared() { scope.cancel(); super.onCleared() }
}

@Composable
internal fun AppUpdateControls(modifier: Modifier = Modifier) {
	val model: AppUpdateViewModel = viewModel()
	val state = model.state
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
		model.installerReturned(it.resultCode)
	}
	val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
		model.message(if (context.packageManager.canRequestPackageInstalls())
			"Permission granted. Tap Install APK to continue."
			else "Permission not granted. Installation cancelled.")
	}
	Column(modifier) {
		TextButton(onClick = model::check, enabled = !state.busy) { Text("Check for updates") }
		state.message?.let { Text(it) }
	}
	val release = state.release
	if (state.showRelease && release != null) {
		AlertDialog(
			onDismissRequest = model::dismiss,
			title = { Text("Update ${release.version.tag}") },
			text = {
				Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
					Text(release.notes.ifBlank { "No release notes published." })
					if (release.sha256 == null) Text(
						"GitHub has not published SHA-256: file integrity cannot be compared with a published checksum. " +
						"Package, version and current signing certificate checks remain mandatory."
					)
					state.message?.let { Text(it) }
					state.progress?.let { Text("Download: $it%") }
				}
			},
			confirmButton = {
				if (state.ready == null) {
					Button(onClick = model::download, enabled = !state.busy) { Text("Download APK") }
				} else {
					Button(enabled = !state.busy, onClick = {
						try {
							if (!context.packageManager.canRequestPackageInstalls()) {
								permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
									Uri.parse("package:${context.packageName}")))
							} else scope.launch {
								model.prepareInstaller()?.let { intent ->
									try { installer.launch(intent) }
									catch (error: Exception) { model.message("Cannot open Android installer: ${error.message}") }
								}
							}
						} catch (error: Exception) { model.message("Cannot open installation settings: ${error.message}") }
					}) { Text("Install APK") }
				}
			},
			dismissButton = {
				if (state.progress != null) TextButton(onClick = model::cancelDownload) { Text("Cancel download") }
				else TextButton(onClick = model::dismiss, enabled = !state.busy) { Text("Close") }
			}
		)
	}
}
