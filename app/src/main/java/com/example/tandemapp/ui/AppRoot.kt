package com.example.tandemapp.ui

import android.util.Log

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.example.tandemapp.data.EmbeddedTandemRepository
import com.example.tandemapp.data.ChartDataCacheRepository
import com.example.tandemapp.data.LiveHistoryResult
import com.example.tandemapp.data.PumpSettingsRepository
import com.example.tandemapp.data.PumpSettingsResult
import com.example.tandemapp.data.SensorSetRepository
import com.example.tandemapp.model.PumpSettingsUiState
import com.example.tandemapp.model.SensorSetUiState
import com.example.tandemapp.viewmodel.HomeViewModel
import kotlinx.coroutines.launch
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
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
	var pumpSettingsState by remember { mutableStateOf<PumpSettingsUiState>(PumpSettingsUiState.Idle) }
	var sensorSetState by remember { mutableStateOf<SensorSetUiState>(SensorSetUiState.Idle) }
	var homeDataStatus by remember { mutableStateOf<PageDataStatus?>(null) }
	var pumpSettingsRequestSerial by remember { mutableStateOf(0L) }

	val context = LocalContext.current
	val configuration = LocalConfiguration.current
	val adaptiveTopBarHeight =
		(configuration.screenHeightDp * GlucoseChartLayout.HomeUi.topBarHeightRatio).dp
			.coerceIn(
				GlucoseChartLayout.HomeUi.topBarMinHeight,
				GlucoseChartLayout.HomeUi.topBarMaxHeight
			)

	val apiRepo = remember(context) { EmbeddedTandemRepository(context.applicationContext) }
	val pumpSettingsRepo = remember(context, apiRepo) {
		PumpSettingsRepository(context.applicationContext, apiRepo::getAuthenticatedContext)
	}
	val sensorSetRepo = remember(context) { SensorSetRepository(context.applicationContext) }
	val chartCacheRepo = remember(context) { ChartDataCacheRepository(context.applicationContext) }
	val scope = rememberCoroutineScope()
	val pageDataStatus = when (currentScreen) {
		AppScreen.Home -> homeDataStatus
		AppScreen.PumpSettings -> (pumpSettingsState as? PumpSettingsUiState.Ready)?.data?.let {
			if (it.isFromCache) PageDataStatus.Cached else PageDataStatus.Updated
		}
		AppScreen.SensorSet -> (sensorSetState as? SensorSetUiState.Ready)?.data?.let {
			if (it.isFromCache) PageDataStatus.Cached else PageDataStatus.Updated
		}
		else -> null
	}

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

	suspend fun loadPumpSettings() {
		val requestSerial = ++pumpSettingsRequestSerial
		if (loginEmail.isBlank() || loginPassword.isBlank()) {
			if (requestSerial == pumpSettingsRequestSerial) {
				pumpSettingsState = PumpSettingsUiState.Error("Missing credentials")
			}
			return
		}
		pumpSettingsState = PumpSettingsUiState.Loading
		val loadedState = when (val result = pumpSettingsRepo.loadCurrent(loginEmail, loginPassword)) {
			is PumpSettingsResult.Success -> PumpSettingsUiState.Ready(result.data)
			is PumpSettingsResult.Failure -> PumpSettingsUiState.Error(result.message)
		}
		if (requestSerial == pumpSettingsRequestSerial) pumpSettingsState = loadedState
	}

	suspend fun loadSensorSet(loadSettings: Boolean = true) {
		val cached = sensorSetRepo.readCached()
		if (cached != null) sensorSetState = SensorSetUiState.Ready(cached)
		else sensorSetState = SensorSetUiState.Loading
		if (loginEmail.isBlank() || loginPassword.isBlank()) {
			if (cached == null) sensorSetState = SensorSetUiState.Error("Missing credentials")
			return
		}

		val eventsResult = apiRepo.loadLiveHistory(loginEmail, loginPassword, LocalDate.now())
		val settingsData = if (loadSettings) {
			when (val settingsResult = pumpSettingsRepo.loadCurrent(loginEmail, loginPassword)) {
				is PumpSettingsResult.Success -> {
					pumpSettingsState = PumpSettingsUiState.Ready(settingsResult.data)
					settingsResult.data
				}
				is PumpSettingsResult.Failure -> null
			}
		} else {
			(pumpSettingsState as? PumpSettingsUiState.Ready)?.data
		}
		if (eventsResult is LiveHistoryResult.Success) {
			val updated = sensorSetRepo.update(eventsResult.dataset, settingsData)
			sensorSetState = updated?.let { SensorSetUiState.Ready(it) }
				?: cached?.let { SensorSetUiState.Ready(it) }
				?: SensorSetUiState.Error("No sensor or infusion set data available")
		} else if (cached == null) {
			sensorSetState = SensorSetUiState.Error("Unable to load sensor and infusion set data")
		}
	}

	LaunchedEffect(Unit) {
		if (loginRememberMe && loginEmail.isNotBlank() && loginPassword.isNotBlank()) {
			val cachedChart = chartCacheRepo.read()
			if (cachedChart != null) {
				val today = LocalDate.now()
				vm.setLiveData(cachedChart, today)
				homeDataStatus = PageDataStatus.Cached
				vm.jumpToLatest()
				currentScreen = AppScreen.Home
			}
			try {
				val today = LocalDate.now()
				val result = apiRepo.loadLiveHistory(
					username = loginEmail,
					password = loginPassword,
					selectedDate = today
				)

				if (result is LiveHistoryResult.Success) {
					loginError = AUTH_SUCCESS_MESSAGE
					val cachedResult = chartCacheRepo.update(result.dataset)
					vm.selectAnchorDate(today)
					vm.setLiveData(cachedResult.dataset, today)
					homeDataStatus = PageDataStatus.Updated
					sensorSetRepo.update(result.dataset, null)?.let {
						sensorSetState = SensorSetUiState.Ready(it)
					}
					vm.jumpToLatest()
					currentScreen = AppScreen.Home
				} else {
					loginError = when (result) {
						is LiveHistoryResult.AuthenticationFailure -> if (isNetworkAvailable(context)) {
							"Tandem authentication failed."
						} else "No connection. Cached data is available."
						is LiveHistoryResult.DataFailure -> if (isNetworkAvailable(context)) {
							"Authentication succeeded, data loading failed."
						} else "No connection. Cached data is available."
						is LiveHistoryResult.Success -> null
					}
					if (cachedChart == null) currentScreen = AppScreen.Login
				}
			} catch (e: Exception) {
				loginError = if (isNetworkAvailable(context)) {
					"Unable to update data."
				} else "No connection. Cached data is available."
				if (cachedChart == null) currentScreen = AppScreen.Login
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
				Row(
					modifier = Modifier.fillMaxWidth().padding(
						bottom = GlucoseChartLayout.HomeUi.topBarTitleBottomPadding
					),
					horizontalArrangement = Arrangement.SpaceBetween,
					verticalAlignment = Alignment.CenterVertically
				) {
					Text(
						text = currentScreen.title,
						style = MaterialTheme.typography.titleLarge,
						fontWeight = FontWeight.Bold,
						fontSize = GlucoseChartLayout.HomeUi.topBarTitleFontSize
					)
					pageDataStatus?.let { status ->
						Text(
							text = status.label,
							color = status.color,
							fontSize = 16.sp,
							fontWeight = FontWeight.Normal
						)
					}
				}
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
					onClick = {
						currentScreen = AppScreen.PumpSettings
						scope.launch {
							loadPumpSettings()
							loadSensorSet(loadSettings = false)
						}
					},
					icon = { PumpIcon() },
					label = { Text("Pump", fontSize = 10.sp) }
				)

				NavigationBarItem(
					selected = currentScreen == AppScreen.SensorSet,
					onClick = {
						currentScreen = AppScreen.SensorSet
						scope.launch { loadSensorSet() }
					},
					icon = { SensorSetIcon() },
					label = { Text("Sensor Set", fontSize = 10.sp) }
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
					loginError = AUTH_SUCCESS_MESSAGE
					val anchorDate = vm.state.value.anchorDate
					if (isCurrentRequestWindow(anchorDate)) {
						val cachedResult = chartCacheRepo.update(result.dataset)
						vm.setLiveData(cachedResult.dataset, anchorDate)
						homeDataStatus = PageDataStatus.Updated
					} else {
						vm.setLiveData(result.dataset, anchorDate)
						homeDataStatus = PageDataStatus.Historical
					}
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
				availableDateColor = homeDataStatus?.color ?: PageDataStatus.Updated.color,
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
							loginError = AUTH_SUCCESS_MESSAGE
							if (isCurrentRequestWindow(selectedDate)) {
								val cachedResult = chartCacheRepo.update(result.dataset)
								vm.setLiveData(cachedResult.dataset, selectedDate)
								homeDataStatus = PageDataStatus.Updated
							} else {
								vm.setLiveData(result.dataset, selectedDate)
								homeDataStatus = PageDataStatus.Historical
							}
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
				modifier = Modifier.padding(innerPadding),
				exportMessage = calendarExportMessage,
				onExportPumpEventsJson = {
					scope.launch {
						val email = prefs.getString("email", "") ?: ""
						val password = prefs.getString("password", "") ?: ""

						if (email.isBlank() || password.isBlank()) {
							calendarExportMessage = "Missing credentials"
							currentScreen = AppScreen.Login
							return@launch
						}

						val selectedDate = vm.state.value.anchorDate
						calendarExportMessage = "Downloading events JSON..."
						calendarExportMessage = apiRepo.exportPumpEventsJson(email, password, selectedDate)
					}
				}
			)

			AppScreen.PumpSettings -> PumpSettingsScreen(
				state = pumpSettingsState,
				modifier = Modifier.padding(innerPadding),
				batteryPercent = (sensorSetState as? SensorSetUiState.Ready)?.data?.batteryPercent,
				batteryTimestamp = (sensorSetState as? SensorSetUiState.Ready)?.data?.batteryTimestamp,
				onRetry = {
					scope.launch {
						loadPumpSettings()
						loadSensorSet(loadSettings = false)
					}
				},
				pumpSettingsExportMessage = pumpSettingsExportMessage,
				onDownloadPumpSettingsJson = {
					scope.launch {
						if (loginEmail.isBlank() || loginPassword.isBlank()) {
							pumpSettingsExportMessage = "Missing credentials"
							currentScreen = AppScreen.Login
							return@launch
						}
						pumpSettingsExportMessage = "Downloading settings JSON..."
						pumpSettingsExportMessage = apiRepo.exportPumpSettingsJson(loginEmail, loginPassword)
					}
				}
			)

			AppScreen.SensorSet -> SensorSetScreen(
				state = sensorSetState,
				modifier = Modifier.padding(innerPadding)
			)

			AppScreen.Login -> LoginScreen(
				modifier = Modifier.padding(innerPadding),
				email = loginEmail,
				password = loginPassword,
				rememberMe = loginRememberMe,
				errorMessage = loginError,
				messageIsSuccess = loginError == AUTH_SUCCESS_MESSAGE,
				onEmailChange = { newEmail ->
					loginEmail = newEmail
					pumpSettingsState = PumpSettingsUiState.Idle
					if (loginRememberMe) {
						prefs.edit().putString("email", newEmail).apply()
					}
				},
				onPasswordChange = { newPassword ->
					loginPassword = newPassword
					pumpSettingsState = PumpSettingsUiState.Idle
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
							loginError = AUTH_SUCCESS_MESSAGE
							val anchorDate = vm.state.value.anchorDate
							if (isCurrentRequestWindow(anchorDate)) {
								val cachedResult = chartCacheRepo.update(result.dataset)
								vm.setLiveData(cachedResult.dataset, anchorDate)
								homeDataStatus = PageDataStatus.Updated
							} else {
								vm.setLiveData(result.dataset, anchorDate)
								homeDataStatus = PageDataStatus.Historical
							}
							currentScreen = AppScreen.Home
						} else {
							loginError = when (result) {
								is LiveHistoryResult.AuthenticationFailure -> "Autenticazione Tandem non riuscita"
								is LiveHistoryResult.DataFailure -> "Autenticazione riuscita, caricamento dati non riuscito"
								is LiveHistoryResult.Success -> null
							}
						}
					}
				}
			)
		}
	}
}

private enum class PageDataStatus(val label: String, val color: Color) {
	Cached("Data Cached", Color(0xFFC62828)),
	Updated("Data Updated", Color(0xFF2E7D32)),
	Historical("Historical", Color(0xFFEF6C00))
}

private const val AUTH_SUCCESS_MESSAGE = "Authentication succeeded, data available"

private fun isCurrentRequestWindow(selectedDate: LocalDate): Boolean =
	!selectedDate.plusDays(7).isBefore(LocalDate.now())

private fun isNetworkAvailable(context: Context): Boolean {
	val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
		?: return false
	val network = manager.activeNetwork ?: return false
	val capabilities = manager.getNetworkCapabilities(network) ?: return false
	return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
		capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
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
private fun SensorSetIcon(modifier: Modifier = Modifier) {
	val tint = LocalContentColor.current
	Canvas(modifier = modifier.size(24.dp)) {
		val stroke = size.minDimension * 0.075f

		// Rounded CGM sensor.
		drawOval(
			color = tint,
			topLeft = Offset(size.width * 0.03f, size.height * 0.27f),
			size = Size(size.width * 0.38f, size.height * 0.46f),
			style = Stroke(width = stroke)
		)
		drawCircle(
			color = tint,
			radius = size.minDimension * 0.045f,
			center = Offset(size.width * 0.22f, size.height * 0.55f)
		)

		// Infusion-set symbol from the chart legend.
		val setCenter = Offset(size.width * 0.76f, size.height * 0.55f)
		val setRadius = size.minDimension * 0.21f
		drawCircle(color = tint, radius = setRadius, center = setCenter, style = Stroke(width = stroke))
		drawLine(
			color = tint,
			start = Offset(setCenter.x - setRadius, setCenter.y),
			end = Offset(setCenter.x, setCenter.y),
			strokeWidth = stroke
		)
		val sector = Path().apply {
			moveTo(setCenter.x, setCenter.y - setRadius * 0.66f)
			quadraticBezierTo(
				setCenter.x + setRadius * 0.92f,
				setCenter.y,
				setCenter.x,
				setCenter.y + setRadius * 0.66f
			)
			quadraticBezierTo(
				setCenter.x + setRadius * 0.30f,
				setCenter.y,
				setCenter.x,
				setCenter.y - setRadius * 0.66f
			)
			close()
		}
		drawPath(sector, color = tint)
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
