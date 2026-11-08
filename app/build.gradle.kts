plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/*
 * Release settings come from the environment, so no secret or deployment address is written into the repository.
 * Without a keystore, the release build is produced unsigned. See docs/release.md.
 */
val releaseKeystorePath: String? = System.getenv("RP_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
val releaseSyncUrl: String =
    (findProperty("routinepilot.syncBaseUrl") as String?)
        ?: System.getenv("ROUTINEPILOT_SYNC_URL").orEmpty()

android {
    namespace = "com.routinepilot"
    compileSdk = 35

    signingConfigs {
        create("release") {
            if (releaseKeystorePath != null) {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("RP_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RP_KEY_ALIAS")
                keyPassword = System.getenv("RP_KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "com.routinepilot"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            buildConfigField("String", "SYNC_BASE_URL", "\"http://10.0.2.2:8080\"")
        }
        release {
            // Minification stays off until a release build has run on a device. The keep rules for Room, Hilt, Glance,
            // and kotlinx-serialization were not verified when this was written. See docs/release.md.
            isMinifyEnabled = false
            require(releaseSyncUrl.isEmpty() || releaseSyncUrl.startsWith("https://")) {
                "ROUTINEPILOT_SYNC_URL must be an https:// address, or empty to turn sync off"
            }
            if (releaseKeystorePath != null) signingConfig = signingConfigs.getByName("release")
            buildConfigField("String", "SYNC_BASE_URL", "\"$releaseSyncUrl\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.work.testing)
}
