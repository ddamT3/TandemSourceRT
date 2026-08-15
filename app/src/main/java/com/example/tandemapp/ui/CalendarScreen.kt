package com.example.tandemapp.ui

import android.util.Log

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.tandemapp.viewmodel.HomeViewModel
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private data class CalendarCell(
	val date: LocalDate?,
	val inCurrentMonth: Boolean
)

@Composable
fun CalendarScreen(
	vm: HomeViewModel,
	onApply: (LocalDate) -> Unit,
	onCancel: () -> Unit,
	modifier: Modifier = Modifier,
	onExportPumpEventsBin: () -> Unit = {},
	exportMessage: String? = null
) {
	val state by vm.state
	val scrollState = rememberScrollState()

	val selectedDate = remember(state.anchorDate) {
		mutableStateOf(state.anchorDate)
	}
	val visibleMonth = remember(state.anchorDate) {
		mutableStateOf(YearMonth.from(state.anchorDate))
	}

	val availableDates = remember(state.available) {
		state.available.toSet()
	}

	val monthFormatter = remember {
		DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
	}
	val dateFormatter = remember {
		DateTimeFormatter.ofPattern("dd/MM/yyyy")
	}

	val cells = remember(visibleMonth.value) {
		buildCalendarCells(visibleMonth.value)
	}

	Column(
		modifier = modifier
			.fillMaxSize()
			.verticalScroll(scrollState)
			.padding(12.dp),
		verticalArrangement = Arrangement.spacedBy(12.dp)
	) {
		Card(
			modifier = Modifier.fillMaxWidth(),
			colors = CardDefaults.cardColors()
		) {
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.padding(12.dp),
				verticalArrangement = Arrangement.spacedBy(10.dp)
			) {
				Text(
					text = "Seleziona l'inizio vista",
					style = MaterialTheme.typography.titleMedium
				)

				OutlinedButton(
					onClick = {
						visibleMonth.value = YearMonth.from(selectedDate.value)
					},
					modifier = Modifier.fillMaxWidth()
				) {
					Text("Start: ${dateFormatter.format(selectedDate.value)}")
				}

				Row(
					modifier = Modifier.fillMaxWidth(),
					verticalAlignment = Alignment.CenterVertically,
					horizontalArrangement = Arrangement.SpaceBetween
				) {
					IconButton(
						onClick = {
							visibleMonth.value = visibleMonth.value.minusMonths(1)
						}
					) {
						Icon(
							imageVector = Icons.Outlined.ArrowBack,
							contentDescription = "Mese precedente"
						)
					}

					Text(
						text = visibleMonth.value.format(monthFormatter)
							.replaceFirstChar { ch ->
								if (ch.isLowerCase()) ch.titlecase(Locale.getDefault()) else ch.toString()
							},
						style = MaterialTheme.typography.titleMedium,
						fontWeight = FontWeight.SemiBold
					)

					IconButton(
						onClick = {
							visibleMonth.value = visibleMonth.value.plusMonths(1)
						}
					) {
						Icon(
							imageVector = Icons.Outlined.ArrowForward,
							contentDescription = "Mese successivo"
						)
					}
				}

				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.spacedBy(4.dp)
				) {
					weekdayHeaders().forEach { dayLabel ->
						Box(
							modifier = Modifier.weight(1f),
							contentAlignment = Alignment.Center
						) {
							Text(
								text = dayLabel,
								style = MaterialTheme.typography.labelSmall,
								fontWeight = FontWeight.SemiBold
							)
						}
					}
				}

				LazyVerticalGrid(
					columns = GridCells.Fixed(7),
					modifier = Modifier
						.fillMaxWidth()
						.heightIn(max = 300.dp),
					horizontalArrangement = Arrangement.spacedBy(4.dp),
					verticalArrangement = Arrangement.spacedBy(4.dp),
					userScrollEnabled = false
				) {
					items(cells) { cell ->
						val date = cell.date
						val isSelected = date != null && date == selectedDate.value
						val isToday = date != null && date == LocalDate.now()
						val isAvailable = date != null && availableDates.contains(date)

						val backgroundColor = when {
							isSelected -> Color(0xFFD9CFF4)
							isAvailable -> Color(0xFFEAF4E7)
							else -> Color.Transparent
						}

						val borderColor = when {
							isSelected -> Color(0xFF9C8FD6)
							isToday -> Color(0xFF90A4AE)
							isAvailable -> Color(0xFF9CCC65)
							else -> Color(0xFFD0D0D0)
						}

						val textColor = when {
							date == null -> Color.Transparent
							cell.inCurrentMonth -> Color(0xFF222222)
							else -> Color(0xFFAAAAAA)
						}

						Box(
							modifier = Modifier
								.widthIn(min = 34.dp)
								.heightIn(min = 34.dp)
								.border(
									width = 1.dp,
									color = borderColor,
									shape = RoundedCornerShape(8.dp)
								)
								.background(
									color = backgroundColor,
									shape = RoundedCornerShape(8.dp)
								)
								.clickable(enabled = date != null) {
									if (date != null) {
										Log.d("CalendarDebug", "selected date = $date")
										selectedDate.value = date
										visibleMonth.value = YearMonth.from(date)
										onApply(date)
									}
								},
							contentAlignment = Alignment.Center
						) {
							if (date != null) {
								Column(
									horizontalAlignment = Alignment.CenterHorizontally,
									verticalArrangement = Arrangement.Center
								) {
									Text(
										text = date.dayOfMonth.toString(),
										color = textColor,
										style = MaterialTheme.typography.labelMedium,
										fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
									)

									if (isAvailable) {
										Box(
											modifier = Modifier
												.padding(top = 2.dp)
												.size(4.dp)
												.background(
													color = Color(0xFF66BB6A),
													shape = RoundedCornerShape(99.dp)
												)
										)
									}
								}
							}
						}
					}
				}

				Text(
					text = "Data selezionata: ${dateFormatter.format(selectedDate.value)}",
					style = MaterialTheme.typography.bodyMedium,
					fontWeight = FontWeight.Medium
				)

				Text(
					text = "Tocca un giorno per applicarlo subito.",
					style = MaterialTheme.typography.bodySmall
				)
			}

		Card(
			modifier = Modifier.fillMaxWidth(),
			colors = CardDefaults.cardColors()
		) {
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.padding(12.dp),
				verticalArrangement = Arrangement.spacedBy(10.dp)
			) {
				Text(
					text = "Export",
					style = MaterialTheme.typography.titleMedium,
					fontWeight = FontWeight.SemiBold
				)

				Text(
					text = "Scarica gli eventi completi in formato JSON nella cartella Download/TandemSourceRT.",
					style = MaterialTheme.typography.bodySmall
				)

				OutlinedButton(
					onClick = onExportPumpEventsBin,
					modifier = Modifier.fillMaxWidth()
				) {
					Text("Download .json event")
				}

				if (!exportMessage.isNullOrBlank()) {
					Text(
						text = exportMessage,
						style = MaterialTheme.typography.bodySmall
					)
				}
			}
		}
		}
	}
}

private fun buildCalendarCells(month: YearMonth): List<CalendarCell> {
	val firstDay = month.atDay(1)
	val daysInMonth = month.lengthOfMonth()

	val startOffset = ((firstDay.dayOfWeek.value + 6) % 7)
	val prevMonth = month.minusMonths(1)
	val daysInPrevMonth = prevMonth.lengthOfMonth()

	val cells = mutableListOf<CalendarCell>()

	for (i in 0 until startOffset) {
		val day = daysInPrevMonth - startOffset + i + 1
		cells += CalendarCell(
			date = prevMonth.atDay(day),
			inCurrentMonth = false
		)
	}

	for (day in 1..daysInMonth) {
		cells += CalendarCell(
			date = month.atDay(day),
			inCurrentMonth = true
		)
	}

	while (cells.size < 42) {
		val nextMonthDay = cells.size - (startOffset + daysInMonth) + 1
		cells += CalendarCell(
			date = month.plusMonths(1).atDay(nextMonthDay),
			inCurrentMonth = false
		)
	}

	return cells
}

private fun weekdayHeaders(): List<String> {
	val locale = Locale.getDefault()
	return listOf(
		DayOfWeek.MONDAY,
		DayOfWeek.TUESDAY,
		DayOfWeek.WEDNESDAY,
		DayOfWeek.THURSDAY,
		DayOfWeek.FRIDAY,
		DayOfWeek.SATURDAY,
		DayOfWeek.SUNDAY
	).map { day ->
		day.getDisplayName(TextStyle.SHORT, locale)
			.replace(".", "")
			.replaceFirstChar { ch ->
				if (ch.isLowerCase()) ch.titlecase(locale) else ch.toString()
			}
	}
}
