import com.android.build.gradle.internal.api.BaseVariantOutputImpl

plugins {
	id("com.android.application")
	id("org.jetbrains.kotlin.android")
	id("com.chaquo.python") version "17.0.0"
	kotlin("plugin.serialization") version "1.9.24"
}

// Versione release letta da buildAPK.bat. I nomi delle due variabili sono
// intenzionalmente compatibili con le espressioni regolari dello script.
val release_versionName = "01.02"
val release_versionCode = 2

val releaseVersionParts = release_versionName.split(".")
require(releaseVersionParts.size == 2) { "versionName deve usare il formato MM.mm" }

val releaseMajor = releaseVersionParts[0].toInt()
val releaseMinor = releaseVersionParts[1].toInt()
require(releaseMajor in 0..21474) { "Major version fuori intervallo" }
require(releaseMinor in 0..99) { "Minor version fuori intervallo 00..99" }
require(release_versionCode in 0..999) { "Revisione fuori intervallo 000..999" }

val androidVersionCode =
	releaseMajor * 100_000 + releaseMinor * 1_000 + release_versionCode
require(androidVersionCode > 0) { "Android versionCode deve essere positivo" }

android {
	namespace = "com.example.tandemapp.st"
	compileSdk = 35

	defaultConfig {
		applicationId = "com.example.tandemapp.st"
		minSdk = 29
		targetSdk = 35
		versionCode = androidVersionCode
		versionName = release_versionName

		ndk {
			abiFilters += listOf("arm64-v8a", "x86_64")
		}
	}

	buildFeatures {
		compose = true
		buildConfig = true
	}

	composeOptions {
		kotlinCompilerExtensionVersion = "1.5.14"
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}

	kotlinOptions {
		jvmTarget = "17"
	}

	applicationVariants.all {
		outputs.all {
			(this as BaseVariantOutputImpl).outputFileName = "TandemSourceRT.apk"
		}
	}
}

chaquopy {
	defaultConfig {
		version = "3.11"
		pip {
			install("requests==2.32.3")
		}
	}
	sourceSets {
		getByName("main") {
			srcDir("../../backend")
			srcDir("../../tandem_decoder/src")
		}
	}
}

dependencies {
	implementation("androidx.core:core-ktx:1.13.1")
	implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
	implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
	implementation("androidx.activity:activity-compose:1.9.1")
	implementation("com.google.code.gson:gson:2.10.1")
	implementation(platform("androidx.compose:compose-bom:2024.06.00"))
	implementation("androidx.compose.ui:ui")
	implementation("androidx.compose.material3:material3")
	implementation("androidx.compose.ui:ui-tooling-preview")

	implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
}










