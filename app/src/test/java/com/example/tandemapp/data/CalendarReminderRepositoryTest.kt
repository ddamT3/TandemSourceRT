package com.example.tandemapp.data

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalendarReminderRepositoryTest {
	private val rome = ZoneId.of("Europe/Rome")

	@Test
	fun `pump offset is treated as pump local wall time`() {
		val expected = LocalDateTime.of(2026, 8, 20, 19, 30)
			.atZone(rome).toInstant().toEpochMilli()

		assertEquals(expected, CalendarReminderTime.toEpochMillis("2026-08-20T19:30:00+02:00", rome))
	}

	@Test
	fun `utc suffix is treated as pump local wall time`() {
		val expected = LocalDateTime.of(2026, 8, 26, 22, 27)
			.atZone(rome).toInstant().toEpochMilli()

		assertEquals(expected, CalendarReminderTime.toEpochMillis("2026-08-26T22:27:00Z", rome))
	}

	@Test
	fun `invalid time is rejected`() {
		assertNull(CalendarReminderTime.toEpochMillis("invalid", rome))
	}

	@Test
	fun `sensor expiration before 10 moves to 20 on previous day`() {
		assertSensorEventTime("2026-10-04T03:15:00+02:00", 2026, 10, 3, 20, 0)
		assertSensorEventTime("2026-10-04T09:59:00+02:00", 2026, 10, 3, 20, 0)
	}

	@Test
	fun `sensor expiration inside daytime window keeps exact time`() {
		assertSensorEventTime("2026-10-04T15:30:00+02:00", 2026, 10, 4, 15, 30)
	}

	@Test
	fun `sensor expiration after 20 moves to 20 on same day`() {
		assertSensorEventTime("2026-10-04T22:30:00+02:00", 2026, 10, 4, 20, 0)
	}

	@Test
	fun `sensor notification keeps original estimated time`() {
		assertEquals(
			"4 Oct 2026, 03:15",
			CalendarReminderTime.displayTime("2026-10-04T03:15:00+02:00")
		)
	}

	@Test
	fun `infusion notification reports original due time`() {
		assertEquals(
			"20 Aug 2026, 19:30",
			CalendarReminderTime.displayTime("2026-08-20T19:30:00+02:00")
		)
	}

	@Test
	fun `sensor expiration at 10 keeps boundary time`() {
		assertSensorEventTime("2026-10-04T10:00:00+02:00", 2026, 10, 4, 10, 0)
	}

	@Test
	fun `sensor expiration at 20 keeps boundary time`() {
		assertSensorEventTime("2026-10-04T20:00:00+02:00", 2026, 10, 4, 20, 0)
	}

	private fun assertSensorEventTime(
		value: String,
		year: Int,
		month: Int,
		day: Int,
		hour: Int,
		minute: Int
	) {
		val expected = LocalDateTime.of(year, month, day, hour, minute)
			.atZone(rome).toInstant().toEpochMilli()
		assertEquals(expected, CalendarReminderTime.sensorEventEpochMillis(value, rome))
	}
}
