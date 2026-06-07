package com.example.tandemapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun PumpSettingsScreen(
	modifier: Modifier = Modifier,
	exportMessage: String? = null,
	onDownloadPumpSettingsBin: () -> Unit = {}
) {
	Column(
		modifier = modifier
			.fillMaxSize()
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
					text = "Pump settings",
					style = MaterialTheme.typography.titleMedium,
					fontWeight = FontWeight.SemiBold
				)

				Text(
					text = "Scarica il blob raw dei settings/profili pompa nella cartella Download/TandemSourceRT.",
					style = MaterialTheme.typography.bodySmall
				)

				OutlinedButton(
					onClick = onDownloadPumpSettingsBin,
					modifier = Modifier.fillMaxWidth()
				) {
					Text("Download .bin settings")
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
