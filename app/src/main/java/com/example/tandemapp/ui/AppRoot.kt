package com.example.tandemapp.ui

import android.util.Log

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.tandemapp.data.EmbeddedTandemRepository
import com.example.tandemapp.data.LiveHistoryResult
import com.example.tandemapp.viewmodel.HomeViewModel
import kotlinx.coroutines.launch
import android.content.Context
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.height
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: HomeViewModel) {
	var currentScreen by rememberSaveable { mutableStateOf(AppScreen.Login) }
	var loginError by remember { mutableStateOf<String?>(null) }
	var calendarExportMessage by remember { mutableStateOf<String?>(null) }
	var pumpSettingsExportMessage by remember { mutableStateOf<String?>(null) }

	val context = LocalContext.current
	val configuration = LocalConfiguration.current
	val adaptiveTopBarHeight =
		(configuration.screenHeightDp * GlucoseChartLayout.HomeUi.topBarHeightRatio).dp
			.coerceIn(
				GlucoseChartLayout.HomeUi.topBarMinHeight,
				GlucoseChartLayout.HomeUi.topBarMaxHeight
			)

	val apiRepo = remember(context) { EmbeddedTandemRepository(context.applicationContext) }
	val scope = rememberCoroutineScope()

	val prefs = remember {
		context.getSharedPreferences("login_prefs", Context.MODE_PRIVATE)
	}

	var loginEmail by rememberSaveable {
			mutableStateOf(prefs.getString("email", "") ?: "")
	}

	var loginPassword by rememberSaveable {
			mutableStateOf(prefs.getString("password", "") ?: "")
	}

	var loginRememberMe by rememberSaveable {
			mutableStateOf(prefs.getBoolean("remember_me", false))
	}

	LaunchedEffect(Unit) {
		if (loginRememberMe && loginEmail.isNotBlank() && loginPassword.isNotBlank()) {
			try {
				val today = LocalDate.now()
				val result = apiRepo.loadLiveHistory(
					username = loginEmail,
					password = loginPassword,
					selectedDate = today
				)

				if (result is LiveHistoryResult.Success) {
					vm.selectAnchorDate(today)
					vm.setLiveData(result.dataset, today)
					vm.jumpToLatest()
					currentScreen = AppScreen.Home
				} else {
					loginError = when (result) {
						is LiveHistoryResult.AuthenticationFailure -> "Autenticazione Tandem non riuscita"
						is LiveHistoryResult.DataFailure -> "Autenticazione riuscita, caricamento dati non riuscito"
						is LiveHistoryResult.Success -> null
					}
					currentScreen = AppScreen.Login
				}
			} catch (e: Exception) {
				currentScreen = AppScreen.Login
			}
		}
	}

	Scaffold(
		topBar = {
			Box(
				modifier = Modifier
					.fillMaxWidth()
					.height(adaptiveTopBarHeight)
					.padding(horizontal = GlucoseChartLayout.HomeUi.adaptiveHorizontalPadding(configuration.screenWidthDp.dp)),
				contentAlignment = Alignment.BottomStart
			) {
				Text(
					text = currentScreen.title,
					style = MaterialTheme.typography.titleLarge,
					fontWeight = FontWeight.Bold,
					fontSize = GlucoseChartLayout.HomeUi.topBarTitleFontSize,
					modifier = Modifier.padding(
						bottom = GlucoseChartLayout.HomeUi.topBarTitleBottomPadding
					)
				)
			}
		},
		bottomBar = {
			NavigationBar(
				modifier = Modifier.height(56.dp)
			) {
				NavigationBarItem(
					selected = currentScreen == AppScreen.Home,
					onClick = { currentScreen = AppScreen.Home },
					icon = { DashboardChartIcon() },
					label = { Text("Dashboard", fontSize = 10.sp) }
				)

				NavigationBarItem(
					selected = currentScreen == AppScreen.Calendar,
					onClick = { currentScreen = AppScreen.Calendar },
					icon = { CalendarMonthGridIcon() },
					label = { Text("Calendar", fontSize = 10.sp) }
				)

				NavigationBarItem(
					selected = currentScreen == AppScreen.PumpSettings,
					onClick = { currentScreen = AppScreen.PumpSettings },
					icon = { PumpIcon() },
					label = { Text("Pump", fontSize = 10.sp) }
				)

				NavigationBarItem(
					selected = currentScreen == AppScreen.AppSettings,
					onClick = { currentScreen = AppScreen.AppSettings },
					icon = {
						Icon(
							imageVector = Icons.Outlined.Settings,
							contentDescription = "Settings",
							modifier = Modifier.size(21.dp)
						)
					},
					label = { Text("Settings", fontSize = 10.sp) }
				)

				NavigationBarItem(
					selected = currentScreen == AppScreen.Login,
					onClick = { currentScreen = AppScreen.Login },
					icon = {
						Icon(
							imageVector = Icons.Outlined.Lock,
							contentDescription = "Login",
							modifier = Modifier.size(21.dp)
						)
					},
					label = { Text("Login", fontSize = 10.sp) }
				)
			}
		}
	) { innerPadding ->
		when (currentScreen) {
			AppScreen.Home -> HomeScreen(
				vm = vm,
				modifier = Modifier.padding(innerPadding),
				onUpdateClick = {
					scope.launch {
						val email = prefs.getString("email", "") ?: ""
						val password = prefs.getString("password", "") ?: ""

						if (email.isBlank() || password.isBlank()) {
							loginError = "Credenziali mancanti"
							currentScreen = AppScreen.Login
							return@launch
						}

						Log.d("CalendarDebug", "home update live dataset for ${vm.state.value.anchorDate}")

				val result = apiRepo.loadLiveHistory(
							username = email,
							password = password,
							selectedDate = vm.state.value.anchorDate
						)

				if (result is LiveHistoryResult.Success) {
					vm.setLiveData(result.dataset, vm.state.value.anchorDate)
				} else {
					loginError = when (result) {
						is LiveHistoryResult.AuthenticationFailure -> "Autenticazione Tandem non riuscita"
						is LiveHistoryResult.DataFailure -> "Autenticazione riuscita, aggiornamento dati fallito"
						is LiveHistoryResult.Success -> null
					}
						}
					}
				}
			)

			AppScreen.Calendar -> CalendarScreen(
				vm = vm,
				onApply = { selectedDate ->
					scope.launch {
						val email = prefs.getString("email", "") ?: ""
						val password = prefs.getString("password", "") ?: ""

						vm.selectAnchorDate(selectedDate)

						if (email.isBlank() || password.isBlank()) {
							loginError = "Credenziali mancanti"
							currentScreen = AppScreen.Login
							return@launch
						}

						Log.d("CalendarDebug", "loading live dataset for $selectedDate")

						val result = apiRepo.loadLiveHistory(
							username = email,
							password = password,
							selectedDate = selectedDate
						)

						if (result is LiveHistoryResult.Success) {
							vm.setLiveData(result.dataset, selectedDate)
							currentScreen = AppScreen.Home
						} else {
							loginError = when (result) {
								is LiveHistoryResult.AuthenticationFailure -> "Autenticazione Tandem non riuscita"
								is LiveHistoryResult.DataFailure -> "Autenticazione riuscita, caricamento dati per $selectedDate fallito"
								is LiveHistoryResult.Success -> null
							}
							currentScreen = AppScreen.Home
						}
					}
				},
				onCancel = { currentScreen = AppScreen.Home },
				modifier = Modifier.padding(innerPadding),
				exportMessage = calendarExportMessage,
				onExportPumpEventsBin = {
					scope.launch {
						val email = prefs.getString("email", "") ?: ""
						val password = prefs.getString("password", "") ?: ""

						if (email.isBlank() || password.isBlank()) {
							calendarExportMessage = "Credenziali mancanti"
							currentScreen = AppScreen.Login
							return@launch
						}

						val selectedDate = vm.state.value.anchorDate
						calendarExportMessage = "Download .json event in corso..."
						calendarExportMessage = apiRepo.exportPumpEventsBin(email, password, selectedDate)
					}
				}
			)

			AppScreen.PumpSettings -> PumpSettingsScreen(
				modifier = Modifier.padding(innerPadding),
				exportMessage = pumpSettingsExportMessage,
				onDownloadPumpSettingsBin = {
					scope.launch {
						val email = prefs.getString("email", "") ?: ""
						val password = prefs.getString("password", "") ?: ""

						if (email.isBlank() || password.isBlank()) {
							pumpSettingsExportMessage = "Credenziali mancanti"
							currentScreen = AppScreen.Login
							return@launch
						}

						pumpSettingsExportMessage = "Download .bin settings in corso..."
						pumpSettingsExportMessage = apiRepo.exportPumpSettingsBin(email, password)
					}
				}
			)

			AppScreen.AppSettings -> PlaceholderScreen(
				modifier = Modifier.padding(innerPadding)
			)

			AppScreen.Login -> LoginScreen(
				modifier = Modifier.padding(innerPadding),
				email = loginEmail,
				password = loginPassword,
				rememberMe = loginRememberMe,
				errorMessage = loginError,
				onEmailChange = { newEmail ->
					loginEmail = newEmail
					if (loginRememberMe) {
						prefs.edit().putString("email", newEmail).apply()
					}
				},
				onPasswordChange = { newPassword ->
					loginPassword = newPassword
					if (loginRememberMe) {
						prefs.edit().putString("password", newPassword).apply()
					}
				},
				onRememberMeChange = { checked ->
					loginRememberMe = checked
					if (checked) {
						prefs.edit()
							.putBoolean("remember_me", true)
							.putString("email", loginEmail)
							.putString("password", loginPassword)
							.apply()
					} else {
						prefs.edit()
							.putBoolean("remember_me", false)
							.remove("email")
							.remove("password")
							.apply()
					}
				},
				onSignInClick = { email, password, rememberMe ->
					loginError = null

					if (rememberMe) {
						prefs.edit()
							.putBoolean("remember_me", true)
							.putString("email", email)
							.putString("password", password)
							.apply()
					} else {
						prefs.edit()
							.putBoolean("remember_me", false)
							.remove("email")
							.remove("password")
							.apply()
					}

					scope.launch {
						val result = apiRepo.loadLiveHistory(
							username = email,
							password = password,
							selectedDate = vm.state.value.anchorDate
						)

						if (result is LiveHistoryResult.Success) {
							vm.setLiveData(result.dataset, vm.state.value.anchorDate)
							currentScreen = AppScreen.Home
						} else {
							loginError = when (result) {
								is LiveHistoryResult.AuthenticationFailure -> "Autenticazione Tandem non riuscita"
								is LiveHistoryResult.DataFailure -> "Autenticazione riuscita, caricamento dati non riuscito"
								is LiveHistoryResult.Success -> null
							}
						}
					}
				},
				onTestClick = {
					vm.loadTestData(apiRepo)
					currentScreen = AppScreen.Home
				}
			)
		}
	}
}

@Composable
private fun DashboardChartIcon(
	modifier: Modifier = Modifier,
	tint: Color = Color(0xFF37474F)
) {
	Canvas(modifier = modifier.size(22.dp)) {
		val w = size.width
		val h = size.height

		val stroke = w * 0.075f
		val left = w * 0.14f
		val bottom = h * 0.78f
		val top = h * 0.18f

		// assi mini dashboard
		drawLine(
			color = tint,
			start = Offset(left, top),
			end = Offset(left, bottom),
			strokeWidth = stroke
		)
		drawLine(
			color = tint,
			start = Offset(left, bottom),
			end = Offset(w * 0.86f, bottom),
			strokeWidth = stroke
		)

		// linea glicemia / trend
		val p1 = Offset(w * 0.22f, h * 0.62f)
		val p2 = Offset(w * 0.38f, h * 0.46f)
		val p3 = Offset(w * 0.55f, h * 0.56f)
		val p4 = Offset(w * 0.76f, h * 0.30f)
		drawLine(tint, p1, p2, strokeWidth = stroke)
		drawLine(tint, p2, p3, strokeWidth = stroke)
		drawLine(tint, p3, p4, strokeWidth = stroke)

		// marker evento
		drawCircle(
			color = tint,
			radius = w * 0.07f,
			center = p4
		)
	}
}

@Composable
private fun CalendarMonthGridIcon(
	modifier: Modifier = Modifier,
	tint: Color = Color(0xFF37474F)
) {
	Canvas(modifier = modifier.size(22.dp)) {
		val w = size.width
		val h = size.height

		val left = w * 0.14f
		val top = h * 0.16f
		val width = w * 0.72f
		val height = h * 0.70f

		drawRoundRect(
			color = tint,
			topLeft = Offset(left, top),
			size = Size(width, height),
			cornerRadius = CornerRadius(w * 0.08f, w * 0.08f)
		)

		drawRoundRect(
			color = Color.White,
			topLeft = Offset(left + w * 0.06f, top + h * 0.18f),
			size = Size(width - w * 0.12f, height - h * 0.26f),
			cornerRadius = CornerRadius(w * 0.04f, w * 0.04f)
		)

		drawRect(
			color = tint,
			topLeft = Offset(left + w * 0.06f, top + h * 0.22f),
			size = Size(width - w * 0.12f, h * 0.06f)
		)

		val cell = w * 0.10f
		val startX = left + w * 0.12f
		val startY = top + h * 0.36f
		val gap = w * 0.05f

		for (row in 0..1) {
			for (col in 0..2) {
				drawRoundRect(
					color = tint,
					topLeft = Offset(
						startX + col * (cell + gap),
						startY + row * (cell + gap)
					),
					size = Size(cell, cell),
					cornerRadius = CornerRadius(w * 0.02f, w * 0.02f)
				)
			}
		}

		drawRoundRect(
			color = Color.White,
			topLeft = Offset(left + w * 0.14f, top - h * 0.01f),
			size = Size(w * 0.08f, h * 0.10f),
			cornerRadius = CornerRadius(w * 0.03f, w * 0.03f)
		)
		drawRoundRect(
			color = Color.White,
			topLeft = Offset(left + width - w * 0.22f, top - h * 0.01f),
			size = Size(w * 0.08f, h * 0.10f),
			cornerRadius = CornerRadius(w * 0.03f, w * 0.03f)
		)
	}
}
