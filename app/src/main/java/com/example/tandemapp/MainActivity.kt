package com.example.tandemapp.st

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.example.tandemapp.data.JsonTandemRepository
import com.example.tandemapp.ui.AppRoot
import com.example.tandemapp.viewmodel.HomeViewModel

class MainActivity : ComponentActivity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		val repo = JsonTandemRepository(assets)
		val vm = HomeViewModel(repo)

		setContent {
			MaterialTheme {
				Surface {
					AppRoot(vm = vm)
				}
			}
		}
	}
}