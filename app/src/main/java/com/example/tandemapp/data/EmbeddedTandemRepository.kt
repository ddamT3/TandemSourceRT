package com.example.tandemapp.data

import android.content.Context
import android.os.Environment
import android.util.Log
import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.example.tandemapp.model.DayDataset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

	private val json = Json {
		ignoreUnknownKeys = true
	}


	private fun ensurePythonStarted() {
		if (!Python.isStarted()) {
			Python.start(AndroidPlatform(context.applicationContext))
		}
	}

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

	private fun parseExportResponse(responseText: String): String {
		val root = json.parseToJsonElement(responseText).jsonObject
		val status = root["status"]?.jsonPrimitive?.contentOrNull
		val type = root["type"]?.jsonPrimitive?.contentOrNull ?: "export"

		if (status == "ok") {
			val path = root["path"]?.jsonPrimitive?.contentOrNull ?: "saved file"
			return "$type saved: $path"
		}

		val detail = root["detail"]?.jsonObject
		val message = detail?.get("message")?.jsonPrimitive?.contentOrNull ?: responseText
		return "$type failed: $message"
	}

	suspend fun getAuthenticatedContext(username: String, password: String): TandemAuthContextResult =
		withContext(Dispatchers.IO) {
			try {
				ensurePythonStarted()
				val responseText = Python.getInstance()
					.getModule("tandem_embedded")
					.callAttr("get_authenticated_context_json", username, password)
					.toJava(String::class.java)
				val root = json.parseToJsonElement(responseText).jsonObject
				if (root["status"]?.jsonPrimitive?.contentOrNull != "ok") {
					val message = root["detail"]?.jsonObject
						?.get("message")?.jsonPrimitive?.contentOrNull
					return@withContext TandemAuthContextResult.Failure(
						message ?: "Autenticazione Tandem non riuscita"
					)
				}

				val accessToken = root["accessToken"]?.jsonPrimitive?.contentOrNull
				val pumperId = root["pumperId"]?.jsonPrimitive?.contentOrNull
				if (accessToken.isNullOrBlank() || pumperId.isNullOrBlank()) {
					return@withContext TandemAuthContextResult.Failure("Contesto autenticato incompleto")
				}
				TandemAuthContextResult.Success(TandemAuthContext(accessToken, pumperId))
			} catch (e: Exception) {
				Log.e(tag, "Errore recupero contesto autenticato", e)
				TandemAuthContextResult.Failure(e.message ?: "Autenticazione Tandem non riuscita")
			}
		}


	suspend fun exportPumpEventsJson(username: String, password: String, selectedDate: LocalDate? = null): String = withContext(Dispatchers.IO) {
		try {
			ensurePythonStarted()
			val py = Python.getInstance()
			val module = py.getModule("tandem_embedded")
			Log.d(tag, "export_pump_logs_json selectedDate=$selectedDate")

			val responseText = module
				.callAttr("export_pump_logs_json", username, password, exportDirectory().absolutePath, selectedDate?.toString())
				.toJava(String::class.java)

			return@withContext parseExportResponse(responseText)
		} catch (e: PyException) {
			Log.e(tag, "Errore Python export pump logs JSON", e)
			return@withContext "Events JSON failed: ${e.message}"
		} catch (e: Exception) {
			Log.e(tag, "Errore export pump logs JSON", e)
			return@withContext "Events JSON failed: ${e.message}"
		}
	}

	suspend fun exportPumpSettingsJson(username: String, password: String): String = withContext(Dispatchers.IO) {
		try {
			ensurePythonStarted()
			val py = Python.getInstance()
			val module = py.getModule("tandem_embedded")
			val responseText = module
				.callAttr("export_pump_settings_json", username, password, exportDirectory().absolutePath)
				.toJava(String::class.java)

			return@withContext parseExportResponse(responseText)
		} catch (e: PyException) {
			Log.e(tag, "Errore Python export pump settings JSON", e)
			return@withContext "Settings JSON failed: ${e.message}"
		} catch (e: Exception) {
			Log.e(tag, "Errore export pump settings JSON", e)
			return@withContext "Settings JSON failed: ${e.message}"
		}
	}

}
