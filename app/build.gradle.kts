import com.android.build.gradle.internal.api.BaseVariantOutputImpl

plugins {
	id("com.android.application")
	id("org.jetbrains.kotlin.android")
	kotlin("plugin.serialization") version "1.9.24"
}

// Versione canonica: YY.MM.NNN. Incremento esclusivamente esplicito.
val release_versionName = "26.11.002"

require(Regex("""\d{2}\.(0[1-9]|1[0-2])\.\d{3}""").matches(release_versionName)) {
    "Formato richiesto: YY.MM.NNN"
}
val versionParts = release_versionName.split(".")
val releaseYear = versionParts[0].toInt()
val releaseMonth = versionParts[1].toInt()
val releaseRevision = versionParts[2].toInt()
require(releaseRevision in 1..999) { "Progressivo richiesto: 001..999" }

val androidVersionCode =
    releaseYear * 100_000 + releaseMonth * 1_000 + releaseRevision
require(androidVersionCode > 0) { "Android versionCode non valido" }

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

buildTypes {
getByName("debug") {
applicationIdSuffix = ".dev"

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
	testImplementation("junit:junit:4.13.2")
}






