package com.example.tandemapp.st

import android.os.Bundle
import com.example.tandemapp.data.StartupMemoryProfiler
import com.example.tandemapp.data.CrashDiagnostics
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.example.tandemapp.ui.AppRoot
import com.example.tandemapp.viewmodel.HomeViewModel

class MainActivity : ComponentActivity() {

	override fun onCreate(savedInstanceState: Bundle?) {
		CrashDiagnostics.initialize(applicationContext)
		CrashDiagnostics.markPhase("activity_start")
		super.onCreate(savedInstanceState)
		StartupMemoryProfiler.initialize(applicationContext)
		StartupMemoryProfiler.sample("activity_start")

		val vm = HomeViewModel()

		setContent {
			MaterialTheme {
				Surface {
					AppRoot(vm = vm)
				}
			}
		}
	}
}
