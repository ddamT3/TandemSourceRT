package com.example.tandemapp.data

import com.example.tandemapp.model.IobPoint
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChartDataCacheRepositoryTest {
	@get:Rule val temporary = TemporaryFolder()
	private fun cache() = ChartDataCacheRepository(File(temporary.root, "chart.json"))

	@Test
	fun `new readings survive a rollback even if maximum timestamp decreases`() {
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
		assertEquals(result.dataset, cache.read())
	}

	@Test
	fun `same timestamp corrections and metadata are accepted but repeated polling does not grow history`() {
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
	fun `original duplicate occurrences remain and empty response preserves originals`() {
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
	fun `cache preserves existing selection of other series when maximum does not increase`() {
		val cache = cache()
		val old = cgmDataset(reading(1, "02:05:00")).copy(iob = listOf(IobPoint("2026-10-25T02:05:00Z", 3.0)))
		cache.update(old)
		val incoming = cgmDataset(reading(2, "02:00:00")).copy(iob = listOf(IobPoint("2026-10-25T02:00:00Z", 4.0)))
		val result = cache.update(incoming)
		assertEquals(old.iob, result.dataset.iob)
		assertEquals(2, result.dataset.cgm.size)
	}

	@Test
	fun `previous cache envelope can be read and upgraded without losing originals`() {
		val file = File(temporary.root, "chart.json")
		file.writeText("""{"latestTimestamp":"2026-10-25T02:05:00Z","dataset":{
			"cgm":[{"time":"2026-10-25T02:05:00Z","value":110}],
			"bolus":[],"carbs":[],"iob":[],"basal":[],"deviceStates":[]}}""")
		val cache = ChartDataCacheRepository(file)
		assertNotNull(cache.read())
		val result = cache.update(cgmDataset(reading(2, "02:00:00", value = 180)))
		assertFalse(result.usedCachedData)
		assertEquals(listOf(180, 110), result.dataset.cgm.map { it.value })
		assertEquals(result.dataset, cache.read())
		assertEquals(result.dataset, Json.decodeFromString<com.example.tandemapp.model.DayDataset>(Json.encodeToString(result.dataset)))
	}
}
