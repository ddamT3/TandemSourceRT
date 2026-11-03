package com.example.tandemapp.data

import com.example.tandemapp.model.IobPoint
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChartDataCacheRepositoryTest {
	@get:Rule val temporary = TemporaryFolder()
	private fun cache() = ChartDataCacheRepository(File(temporary.root, "chart.json"), today = { java.time.LocalDate.of(2026, 10, 25) })

	@Test
	fun `new readings survive a rollback even if maximum timestamp decreases`() = runBlocking<Unit> {
		val cache = cache()
		val old = cgmDataset(reading(1, "02:00:00", value = 100), reading(2, "02:05:00", value = 110))
		cache.update(old)
		val incoming = cgmDataset(reading(3, "02:00:00", value = 180))
		val result = cache.update(incoming)
		assertFalse(result.usedCachedData)
		assertEquals(3, result.dataset.cgm.size)
		assertEquals(listOf(100, 180, 110), result.dataset.cgm.map { it.value })
		assertEquals(listOf(0, 1, 0), result.dataset.cgm.map { it.source!!.payloadIndex })
		assertEquals(listOf(old.pumpEventsSources.single(), incoming.pumpEventsSources.single()), result.dataset.pumpEventsSources)
		assertEquals(listOf(180), CgmTimelineResolver.resolve(result.dataset).points.map { it.point.value })
		assertEquals(result.dataset, ChartDataCacheRepository(File(temporary.root, "chart.json"), today = { java.time.LocalDate.of(2026, 10, 25) }).read())
	}

	@Test
	fun `same timestamp corrections and metadata are accepted but repeated polling does not grow history`() = runBlocking<Unit> {
		val cache = cache()
		val first = cgmDataset(reading(1, "02:00:00", value = 100))
		val correction = cgmDataset(reading(2, "02:00:00", value = 150))
		cache.update(first)
		val updated = cache.update(correction)
		assertFalse(updated.usedCachedData)
		assertEquals(listOf(100, 150), updated.dataset.cgm.map { it.value })
		val repeated = cache.update(correction)
		assertTrue(repeated.usedCachedData)
		assertEquals(updated.dataset, repeated.dataset)
	}

	@Test
	fun `original duplicate occurrences remain and empty response preserves originals`() = runBlocking<Unit> {
		val cache = cache()
		val point = reading(1, "02:00:00")
		val original = cgmDataset(point, point)
		cache.update(original)
		val repeated = cache.update(original)
		assertEquals(2, repeated.dataset.cgm.size)
		assertTrue(repeated.usedCachedData)
		val empty = original.copy(cgm = emptyList(), pumpEventsSources = emptyList())
		assertEquals(original, cache.update(empty).dataset)
	}

	@Test
	fun `cache preserves existing selection of other series when maximum does not increase`() = runBlocking<Unit> {
		val cache = cache()
		val old = cgmDataset(reading(1, "02:05:00")).copy(iob = listOf(IobPoint("2026-10-25T02:05:00Z", 3.0)))
		cache.update(old)
		val incoming = cgmDataset(reading(2, "02:00:00")).copy(iob = listOf(IobPoint("2026-10-25T02:00:00Z", 4.0)))
		val result = cache.update(incoming)
		assertEquals(old.iob, result.dataset.iob)
		assertEquals(2, result.dataset.cgm.size)
	}

	@Test
	fun `previous cache envelope can be read and upgraded without losing originals`() = runBlocking<Unit> {
		val file = File(temporary.root, "chart.json")
		                File(temporary.root, "chart.json.version").writeText(
                    "p0c2b-v3-days-${com.example.tandemapp.config.AppConfig.HISTORY_DAYS}"
                )
		file.writeText("""{"latestTimestamp":"2026-10-25T02:05:00Z","dataset":{
			"cgm":[{"time":"2026-10-25T02:05:00Z","value":110}],
			"bolus":[],"carbs":[],"iob":[],"basal":[],"deviceStates":[]}}""")
		val cache = ChartDataCacheRepository(file, today = { java.time.LocalDate.of(2026, 10, 25) })
		assertNotNull(ChartDataCacheRepository(File(temporary.root, "chart.json"), today = { java.time.LocalDate.of(2026, 10, 25) }).read())
		val result = cache.update(cgmDataset(reading(2, "02:00:00", value = 180)))
		assertFalse(result.usedCachedData)
		assertEquals(listOf(180, 110), result.dataset.cgm.map { it.value })
		assertEquals(result.dataset, ChartDataCacheRepository(File(temporary.root, "chart.json"), today = { java.time.LocalDate.of(2026, 10, 25) }).read())
		assertEquals(result.dataset, Json.decodeFromString<com.example.tandemapp.model.DayDataset>(Json.encodeToString(result.dataset)))
	}

	@Test
	fun `failed atomic replacement keeps old cache and merged originals for retry`() = runBlocking<Unit> {
		val file = File(temporary.root, "chart.json")
		var failWrite = false
		val cache = ChartDataCacheRepository(file, today = { java.time.LocalDate.of(2026, 10, 25) }) { source, destination ->
			if (failWrite) throw IOException("simulated replacement failure")
			java.nio.file.Files.move(source.toPath(), destination.toPath(),
				java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
		}
		val original = cgmDataset(reading(1, "02:05:00", value = 110))
		cache.update(original)
		val originalBytes = file.readBytes()
		failWrite = true
		val merged = cache.mergeForDisplay(cgmDataset(reading(2, "02:00:00", value = 180)))
		try {
			cache.save()
			fail("Expected write failure")
		} catch (_: IOException) { }
		assertArrayEquals(originalBytes, file.readBytes())
		assertEquals(original, ChartDataCacheRepository(file, today = { java.time.LocalDate.of(2026, 10, 25) }).read())
		assertEquals(merged.dataset, cache.read())
		assertEquals(2, merged.dataset.cgm.size)
		assertEquals(2, merged.dataset.pumpEventsSources.size)
		assertEquals(listOf(180), CgmTimelineResolver.resolve(merged.dataset).points.map { it.point.value })
		assertEquals(listOf("chart.json", "chart.json.version"), temporary.root.listFiles()!!.map { it.name }.sorted())
		failWrite = false
		cache.save()
		assertEquals(merged.dataset, ChartDataCacheRepository(file, today = { java.time.LocalDate.of(2026, 10, 25) }).read())
	}

	@Test
	fun `merge does not wait for disk writer and queued save retains newest snapshot`() = runBlocking<Unit> {
		val entered = CountDownLatch(1)
		val release = CountDownLatch(1)
		val file = File(temporary.root, "chart.json")
		val cache = ChartDataCacheRepository(file, today = { java.time.LocalDate.of(2026, 10, 25) }) { source, destination ->
			entered.countDown()
			check(release.await(5, TimeUnit.SECONDS))
			java.nio.file.Files.move(source.toPath(), destination.toPath(),
				java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
		}
		cache.mergeForDisplay(cgmDataset(reading(1, "02:05:00")))
		val writer = async(Dispatchers.IO) { cache.save() }
		try {
			assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
			val merged = cache.mergeForDisplay(cgmDataset(reading(2, "02:00:00")))
			assertEquals(2, merged.dataset.cgm.size)
			assertEquals(merged.dataset, cache.read())
			assertFalse(file.exists())
		} finally { release.countDown() }
		writer.await()
		cache.save()
		assertEquals(cache.read(), ChartDataCacheRepository(file, today = { java.time.LocalDate.of(2026, 10, 25) }).read())
		assertEquals(2, ChartDataCacheRepository(file, today = { java.time.LocalDate.of(2026, 10, 25) }).read()!!.cgm.size)
	}

	@Test
	fun `stream encoding failure preserves previous file and removes partial temporary`() = runBlocking<Unit> {
		val file = File(temporary.root, "chart.json")
		val cache = ChartDataCacheRepository(file, today = { java.time.LocalDate.of(2026, 10, 25) })
		val original = cgmDataset(reading(1, "02:00:00"))
		cache.update(original)
		val originalBytes = file.readBytes()
		val incoming = cgmDataset(reading(2, "02:05:00")).copy(
			iob = listOf(IobPoint("2026-10-25T02:05:00Z", Double.NaN)))
		val merged = cache.mergeForDisplay(incoming)
		try {
			cache.save()
			fail("Expected stream encoding failure")
		} catch (_: kotlinx.serialization.SerializationException) { }
		assertArrayEquals(originalBytes, file.readBytes())
		assertEquals(original, ChartDataCacheRepository(file, today = { java.time.LocalDate.of(2026, 10, 25) }).read())
		assertEquals(merged.dataset, cache.read())
		assertEquals(listOf("chart.json", "chart.json.version"), temporary.root.listFiles()!!.map { it.name }.sorted())
	}

    @Test
    fun `compact removes unreferenced payloads without clock changes`() = runBlocking<Unit> {
        val original = cgmDataset(reading(1, "02:00:00"))
        val referenced = original.pumpEventsSources.single()

        val unused = referenced.copy(
            requestedAssignmentId = "unused",
            rawPayload = kotlinx.serialization.json.JsonObject(
                mapOf(
                    "events" to kotlinx.serialization.json.JsonArray(emptyList())
                )
            )
        )

        val input = original.copy(
            pumpEventsSources = listOf(unused, referenced),
            cgm = original.cgm.map {
                it.copy(
                    source = it.source!!.copy(payloadIndex = 1)
                )
            }
        )

        val result = cache().update(input).dataset

        assertEquals(listOf(referenced), result.pumpEventsSources)
        assertEquals(0, result.cgm.single().source!!.payloadIndex)
        assertEquals(
            original.cgm.single().source!!.eventIndex,
            result.cgm.single().source!!.eventIndex
        )
        assertEquals(
            referenced.rawPayload,
            result.pumpEventsSources.single().rawPayload
        )
    }

    @Test
    fun `compact preserves unreferenced clock change payloads`() = runBlocking<Unit> {
        val original = cgmDataset(reading(1, "02:00:00"))
        val referenced = original.pumpEventsSources.single()

        val clockPayload = kotlinx.serialization.json.JsonObject(
            mapOf(
                "events" to kotlinx.serialization.json.JsonArray(emptyList()),
                "clockChanges" to kotlinx.serialization.json.JsonArray(
                    listOf(
                        kotlinx.serialization.json.JsonObject(
                            mapOf(
                                "opaque" to kotlinx.serialization.json.JsonPrimitive(true)
                            )
                        )
                    )
                )
            )
        )

        val clockSource = referenced.copy(
            requestedAssignmentId = "clock-source",
            rawPayload = clockPayload
        )

        val unused = referenced.copy(
            requestedAssignmentId = "unused",
            rawPayload = kotlinx.serialization.json.JsonObject(
                mapOf(
                    "events" to kotlinx.serialization.json.JsonArray(emptyList())
                )
            )
        )

        val result = cache().update(
            original.copy(
                pumpEventsSources = listOf(referenced, unused, clockSource)
            )
        ).dataset

        assertEquals(
            listOf(referenced, clockSource),
            result.pumpEventsSources
        )
        assertEquals(0, result.cgm.single().source!!.payloadIndex)
        assertEquals(
            clockPayload["clockChanges"],
            result.pumpEventsSources[1].clockChanges
        )
        assertEquals(
            clockPayload,
            result.pumpEventsSources[1].rawPayload
        )
    }

    @Test
    fun `compact rebases multiple payload indices and keeps event indices`() = runBlocking<Unit> {
        val a = cgmDataset(reading(1, "02:00:00"))
        val b = cgmDataset(reading(2, "02:05:00"))

        val sourceA = a.pumpEventsSources.single()
        val sourceB = b.pumpEventsSources.single().copy(
            requestedAssignmentId = "source-B"
        )

        val unused = sourceA.copy(
            requestedAssignmentId = "unused",
            rawPayload = kotlinx.serialization.json.JsonObject(
                mapOf(
                    "events" to kotlinx.serialization.json.JsonArray(emptyList())
                )
            )
        )

        val second = b.cgm.single().copy(
            source = b.cgm.single().source!!.copy(
                payloadIndex = 2
            )
        )

        val input = a.copy(
            cgm = a.cgm + second,
            pumpEventsSources = listOf(sourceA, unused, sourceB)
        )

        val result = cache().update(input).dataset

        assertEquals(
            listOf(sourceA, sourceB),
            result.pumpEventsSources
        )
        assertEquals(
            listOf(0, 1),
            result.cgm.map { it.source!!.payloadIndex }
        )
        assertEquals(
            input.cgm.map { it.source!!.eventIndex },
            result.cgm.map { it.source!!.eventIndex }
        )
    }

    @Test
    fun `compact does not discard payloads when source index is invalid`() = runBlocking<Unit> {
        val original = cgmDataset(reading(1, "02:00:00"))

        val invalid = original.copy(
            cgm = original.cgm.map {
                it.copy(
                    source = it.source!!.copy(payloadIndex = 99)
                )
            }
        )

        val result = cache().update(invalid).dataset

        assertEquals(
            invalid.pumpEventsSources,
            result.pumpEventsSources
        )
        assertEquals(
            99,
            result.cgm.single().source!!.payloadIndex
        )
    }
}
