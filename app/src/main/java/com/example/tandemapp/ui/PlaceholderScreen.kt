package com.example.tandemapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.tandemapp.st.BuildConfig
import java.util.Locale

@Composable
fun PlaceholderScreen(
	modifier: Modifier = Modifier
) {
	Column(
		modifier = modifier
			.fillMaxSize()
			.padding(16.dp),
		verticalArrangement = Arrangement.Center,
		horizontalAlignment = Alignment.CenterHorizontally
	) {
		Card {
			Column(modifier = Modifier.padding(24.dp)) {
				Text(
					text = String.format(
						Locale.US,
						"v%s.%03d",
						BuildConfig.VERSION_NAME,
						BuildConfig.VERSION_CODE
					),
					style = MaterialTheme.typography.titleMedium,
					fontWeight = FontWeight.Medium
				)
			}
		}
	}
}
