package com.example.tandemapp.data

import android.content.Context
import android.os.Environment
import android.util.Log
import com.example.tandemapp.model.DayDataset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

sealed interface LiveHistoryResult {
	data class Success(val dataset: DayDataset) : LiveHistoryResult
	data class AuthenticationFailure(val message: String?) : LiveHistoryResult
	data class DataFailure(val message: String?) : LiveHistoryResult
}

class EmbeddedTandemRepository(
	private val context: Context
) {

	private val tag = "EmbeddedTandemRepo"
	private val authProvider: TandemAuthProvider = KotlinTandemAuthProvider()

	suspend fun loadLiveHistory(username: String, password: String, selectedDate: LocalDate? = null): LiveHistoryResult = withContext(Dispatchers.IO) {
		val auth = when (val result = getAuthenticatedContext(username, password)) {
			is TandemAuthContextResult.Success -> result.context
			is TandemAuthContextResult.Failure ->
				return@withContext LiveHistoryResult.AuthenticationFailure(result.message)
		}
		return@withContext try {
			Log.d(tag, "Kotlin pump-event pipeline selectedDate=$selectedDate")
			LiveHistoryResult.Success(PumpEventsRepository().load(auth, selectedDate))
		} catch (e: Exception) {
			Log.e(tag, "Errore caricamento pump-event Kotlin", e)
			LiveHistoryResult.DataFailure(e.message)
		}
	}


	private fun exportDirectory(): File {
		val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
			?: context.filesDir

		val exportDir = File(baseDir, "TandemSourceRT")
		if (!exportDir.exists()) {
			exportDir.mkdirs()
		}
		return exportDir
	}

	suspend fun getAuthenticatedContext(username: String, password: String): TandemAuthContextResult =
		authProvider.authenticate(username, password)


	suspend fun exportPumpEventsJson(username: String, password: String, selectedDate: LocalDate? = null): String = withContext(Dispatchers.IO) {
		val auth = when (val result = getAuthenticatedContext(username, password)) {
			is TandemAuthContextResult.Success -> result.context
			is TandemAuthContextResult.Failure -> return@withContext "Events JSON failed: ${result.message}"
		}
		return@withContext try {
			val result = PumpEventsRepository().exportRaw(auth, selectedDate, exportDirectory())
			if (result.files.isEmpty()) {
				"No events returned for the selected period; no file was created."
			} else {
				"Events JSON saved: ${result.files.size} file(s) in ${exportDirectory().absolutePath}"
			}
		} catch (e: Exception) {
			Log.e(tag, "Kotlin pump-event JSON export failed", e)
			"Events JSON failed: ${e.message}"
		}
	}

	suspend fun exportPumpSettingsJson(username: String, password: String): String = withContext(Dispatchers.IO) {
		val auth = when (val result = getAuthenticatedContext(username, password)) {
			is TandemAuthContextResult.Success -> result.context
			is TandemAuthContextResult.Failure -> return@withContext "Settings JSON failed: ${result.message}"
		}
		return@withContext try {
			val repository = PumpSettingsRepository(context.applicationContext, ::getAuthenticatedContext)
			repository.exportRaw(auth, exportDirectory())
			"Settings JSON saved: 1 file in ${exportDirectory().absolutePath}"
		} catch (e: Exception) {
			Log.e(tag, "Kotlin pump-settings JSON export failed", e)
			"Settings JSON failed: ${e.message}"
		}
	}

}
