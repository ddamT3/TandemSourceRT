package com.example.tandemapp.data

import com.example.tandemapp.model.DayDataset
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

class PumpEventsAdapterParityTest {
	@OptIn(ExperimentalSerializationApi::class)
	private val canonicalJson = Json { encodeDefaults = true; explicitNulls = true }

	@Test
	fun `Kotlin adapter matches Python adapter on local pump event fixture`() {
		val workingDirectory = System.getProperty("user.dir") ?: return
		val repository = generateSequence(File(workingDirectory)) { it.parentFile }
			.firstOrNull { File(it, "LocalAssets/pump_event").isDirectory }
		assumeTrue("LocalAssets/pump_event non disponibile", repository != null)
		val repo = repository ?: return
		val fixture = File(repo, "LocalAssets/pump_event").listFiles()
			?.filter { it.extension.equals("json", ignoreCase = true) }
			?.maxByOrNull { it.length() }
		assumeTrue("Nessun fixture pump-event disponibile", fixture != null)

		val actual = PumpEventsAdapter.decode(fixture!!.readText())
		val expected = canonicalJson.parseToJsonElement(pythonReference(repo, fixture)).let { it as JsonObject }
		val actualJson = canonicalJson.parseToJsonElement(canonicalJson.encodeToString<DayDataset>(actual)).let { it as JsonObject }
		expected.keys.forEach { section ->
			val expectedRows = expected[section] as JsonArray
			val actualRows = actualJson[section] as JsonArray
			assertEquals("$section count", expectedRows.size, actualRows.size)
			expectedRows.indices.forEach { index -> assertEquals("$section[$index]", expectedRows[index], actualRows[index]) }
		}
	}

	private fun pythonReference(repository: File, fixture: File): String {
		val pythonDir = File(repository, "app/src/test/python")
		val script = """
import json,sys
sys.path.insert(0,sys.argv[2])
from bff_adapter_reference import decode_bff_payload
with open(sys.argv[1],encoding='utf-8') as f: d=decode_bff_payload(json.load(f))
print(json.dumps({'cgm':d['cgm'],'bolus':d['bolus'],'carbs':d['cho'],'iob':d['iob'],'basal':d['basal'],'deviceStates':d['deviceState'],'supplementalEvents':d['supplementalEvents']},separators=(',',':')))
		""".trimIndent()
		val process = ProcessBuilder("python", "-c", script, fixture.absolutePath, pythonDir.absolutePath)
			.redirectErrorStream(true).start()
		val output = process.inputStream.bufferedReader().readText()
		check(process.waitFor() == 0) { "Adapter Python fallito: $output" }
		return output
	}
}
