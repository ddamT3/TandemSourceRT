package com.example.tandemapp.data

import android.content.Context
import android.os.Environment
import android.util.Log
import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.example.tandemapp.model.DayDataset
import com.example.tandemapp.model.HistoryLiveResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.LocalDate

class EmbeddedTandemRepository(
	private val context: Context
) {

	private val tag = "EmbeddedTandemRepo"

	private val json = Json {
		ignoreUnknownKeys = true
	}


	private fun parseDatasetResponse(responseText: String): DayDataset? {
		val root = json.parseToJsonElement(responseText).jsonObject
		val status = root["status"]?.jsonPrimitive?.contentOrNull
		if (status != "ok") {
			Log.e(tag, "Embedded decode failed: $responseText")
			return null
		}

		val response = json.decodeFromString(HistoryLiveResponse.serializer(), responseText)
		return response.data.toDayDataset()
	}

	private fun ensurePythonStarted() {
		if (!Python.isStarted()) {
			Python.start(AndroidPlatform(context.applicationContext))
		}
	}

	suspend fun loadLiveHistory(username: String, password: String, selectedDate: LocalDate? = null): DayDataset? = withContext(Dispatchers.IO) {
		try {
			ensurePythonStarted()
			val py = Python.getInstance()
			val module = py.getModule("tandem_embedded")
			Log.d(tag, "fetch_live_dataset selectedDate=$selectedDate")

			val responseText = module
				.callAttr("fetch_live_dataset", username, password, selectedDate?.toString())
				.toJava(String::class.java)

			return@withContext parseDatasetResponse(responseText)
		} catch (e: PyException) {
			Log.e(tag, "Errore Python embedded", e)
			return@withContext null
		} catch (e: Exception) {
			Log.e(tag, "Errore repository standalone", e)
			return@withContext null
		}
	}
	

	suspend fun loadBundledBlobHistory(assetName: String): DayDataset? = withContext(Dispatchers.IO) {
		try {
			ensurePythonStarted()

			val outFile = java.io.File(context.cacheDir, assetName)
			context.assets.open(assetName).use { input ->
				outFile.outputStream().use { output ->
					input.copyTo(output)
				}
			}

			val py = Python.getInstance()
			val module = py.getModule("tandem_embedded")
			val responseText = module.callAttr("decode_test_blob", outFile.absolutePath)
				.toJava(String::class.java)

			return@withContext parseDatasetResponse(responseText)
		} catch (e: PyException) {
			Log.e(tag, "Errore Python embedded decode blob", e)
			return@withContext null
		} catch (e: Exception) {
			Log.e(tag, "Errore repository standalone decode blob", e)
			return@withContext null
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
			val path = root["path"]?.jsonPrimitive?.contentOrNull ?: "file salvato"
			return "$type salvato: $path"
		}

		val detail = root["detail"]?.jsonObject
		val message = detail?.get("message")?.jsonPrimitive?.contentOrNull ?: responseText
		return "$type fallito: $message"
	}


	private fun exportBaseDir(): String {
		return context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.absolutePath
			?: context.filesDir.absolutePath
	}

	suspend fun exportPumpEventsBin(username: String, password: String, selectedDate: LocalDate? = null): String = withContext(Dispatchers.IO) {
		try {
			ensurePythonStarted()
			val py = Python.getInstance()
			val module = py.getModule("tandem_embedded")
			Log.d(tag, "export_full_pump_events_bin selectedDate=$selectedDate")

			val responseText = module
				.callAttr("export_full_pump_events_bin", username, password, exportDirectory().absolutePath, selectedDate?.toString())
				.toJava(String::class.java)

			return@withContext parseExportResponse(responseText)
		} catch (e: PyException) {
			Log.e(tag, "Errore Python export .bin", e)
			return@withContext "bin fallito: ${e.message}"
		} catch (e: Exception) {
			Log.e(tag, "Errore export .bin", e)
			return@withContext "bin fallito: ${e.message}"
		}
	}

	suspend fun exportDatasetJson(username: String, password: String): String = withContext(Dispatchers.IO) {
		try {
			ensurePythonStarted()
			val py = Python.getInstance()
			val module = py.getModule("tandem_embedded")
			val responseText = module
				.callAttr("export_dataset_json", username, password, exportDirectory().absolutePath)
				.toJava(String::class.java)

			return@withContext parseExportResponse(responseText)
		} catch (e: PyException) {
			Log.e(tag, "Errore Python export dataset .json", e)
			return@withContext "json fallito: ${e.message}"
		} catch (e: Exception) {
			Log.e(tag, "Errore export dataset .json", e)
			return@withContext "json fallito: ${e.message}"
		}
	}

	suspend fun exportPumpSettingsBin(username: String, password: String): String = withContext(Dispatchers.IO) {
		try {
			ensurePythonStarted()
			val py = Python.getInstance()
			val module = py.getModule("tandem_embedded")
			val responseText = module
				.callAttr("export_pump_settings_bin", username, password, exportDirectory().absolutePath)
				.toJava(String::class.java)

			return@withContext parseExportResponse(responseText)
		} catch (e: PyException) {
			Log.e(tag, "Errore Python export settings .bin", e)
			return@withContext "settings bin fallito: ${e.message}"
		} catch (e: Exception) {
			Log.e(tag, "Errore export settings .bin", e)
			return@withContext "settings bin fallito: ${e.message}"
		}
	}

}
