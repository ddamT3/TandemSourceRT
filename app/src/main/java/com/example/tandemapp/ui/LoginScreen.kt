package com.example.tandemapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun LoginScreen(
	modifier: Modifier = Modifier,
	email: String,
	password: String,
	rememberMe: Boolean,
	errorMessage: String? = null,
	onEmailChange: (String) -> Unit,
	onPasswordChange: (String) -> Unit,
	onRememberMeChange: (Boolean) -> Unit,
	onSignInClick: (email: String, password: String, rememberMe: Boolean) -> Unit = { _, _, _ -> }
) {
	var passwordVisible by remember { mutableStateOf(false) }

	Column(
		modifier = modifier
			.fillMaxSize()
			.padding(16.dp),
		verticalArrangement = Arrangement.Center
	) {
		Card(
			modifier = Modifier.fillMaxWidth()
		) {
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.padding(20.dp),
				verticalArrangement = Arrangement.spacedBy(12.dp)
			) {
				Text(
					text = "Login",
					style = MaterialTheme.typography.headlineSmall
				)

				OutlinedTextField(
					value = email,
					onValueChange = onEmailChange,
					label = { Text("Email") },
					modifier = Modifier.fillMaxWidth(),
					singleLine = true
				)

				OutlinedTextField(
					value = password,
					onValueChange = onPasswordChange,
					label = { Text("Password") },
					visualTransformation = if (passwordVisible) {
						VisualTransformation.None
					} else {
						PasswordVisualTransformation()
					},
					trailingIcon = {
						TextButton(onClick = { passwordVisible = !passwordVisible }) {
							Text(if (passwordVisible) "🙈" else "👁")
						}
					},
					modifier = Modifier.fillMaxWidth(),
					singleLine = true
				)

				Row(
					verticalAlignment = Alignment.CenterVertically
				) {
					Checkbox(
						checked = rememberMe,
						onCheckedChange = onRememberMeChange
					)
					Text("Remember me")
				}

				if (!errorMessage.isNullOrBlank()) {
					Text(
						text = errorMessage,
						color = Color(0xFFB3261E),
						style = MaterialTheme.typography.bodyMedium
					)
				}

				Button(
					onClick = {
						onSignInClick(email, password, rememberMe)
					},
					modifier = Modifier.fillMaxWidth()
				) {
					Text("Sign in")
				}
			}
		}
	}
}
