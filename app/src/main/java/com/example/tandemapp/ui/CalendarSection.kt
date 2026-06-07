package com.example.tandemapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun CalendarSection(
	selected: LocalDate,
	available: List<LocalDate>,
	onSelect: (LocalDate) -> Unit
) {
	val month = YearMonth.of(selected.year, selected.month)
	val firstDayOfMonth = month.atDay(1)

	val startOffset = when (firstDayOfMonth.dayOfWeek) {
		DayOfWeek.MONDAY -> 0
		DayOfWeek.TUESDAY -> 1
		DayOfWeek.WEDNESDAY -> 2
		DayOfWeek.THURSDAY -> 3
		DayOfWeek.FRIDAY -> 4
		DayOfWeek.SATURDAY -> 5
		DayOfWeek.SUNDAY -> 6
	}

	val monthDays = (1..month.lengthOfMonth()).map { month.atDay(it) }
	val paddedDays: List<LocalDate?> = List(startOffset) { null } + monthDays
	val weeks = paddedDays.chunked(7)

	Column(
		modifier = Modifier.padding(16.dp),
		verticalArrangement = Arrangement.spacedBy(8.dp)
	) {
		Text(
			text = "${selected.month.name.lowercase().replaceFirstChar { it.uppercase() }} ${selected.year}"
		)

		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.spacedBy(4.dp)
		) {
			listOf(
				DayOfWeek.MONDAY,
				DayOfWeek.TUESDAY,
				DayOfWeek.WEDNESDAY,
				DayOfWeek.THURSDAY,
				DayOfWeek.FRIDAY,
				DayOfWeek.SATURDAY,
				DayOfWeek.SUNDAY
			).forEach { day ->
				Box(
					modifier = Modifier.weight(1f),
					contentAlignment = Alignment.Center
				) {
					Text(
						text = day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).uppercase()
					)
				}
			}
		}

		weeks.forEach { week ->
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.spacedBy(4.dp)
			) {
				repeat(7) { index ->
					val date = week.getOrNull(index)

					val backgroundColor = when {
						date == null -> Color.Transparent
						date == selected -> Color.Blue
						available.contains(date) -> Color.Green
						else -> Color.LightGray
					}

					Box(
						modifier = Modifier
							.weight(1f)
							.aspectRatio(1f)
							.background(backgroundColor)
							.clickable(enabled = date != null) {
								date?.let(onSelect)
							},
						contentAlignment = Alignment.Center
					) {
						Text(text = date?.dayOfMonth?.toString() ?: "")
					}
				}
			}
		}
	}
}