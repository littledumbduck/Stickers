plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val appId = "com.littledumbduck.stickers"
val providerAuthority = "$appId.stickercontentprovider"

android {
    namespace = "com.littledumbduck.stickers"
    compileSdk = 35

    defaultConfig {
        applicationId = appId
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.5"

        // Solo ARM (móviles reales). x86/x86_64 son para emuladores y duplican ~40 MB de ML Kit.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        manifestPlaceholders["contentProviderAuthority"] = providerAuthority
        buildConfigField("String", "CONTENT_PROVIDER_AUTHORITY", "\"$providerAuthority\"")
    }

    // Misma firma en cualquier máquina, para que cada APK nuevo se instale como actualización
    // sin borrar los paquetes. Es una clave de depuración (no sirve para publicar en Play Store).
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            test.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
            // Prueba real contra TikTok solo si se pasa un enlace: TIKTOK_LIVE_URL=https://...
            System.getenv("TIKTOK_LIVE_URL")?.let { test.systemProperty("tiktokLiveUrl", it) }
            System.getenv("SNAPSHOT_DIR")?.let { test.systemProperty("snapshotDir", it) }
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0") // reproducir stickers animados en las listas

    // Recorte inteligente (segmentación de sujeto en el dispositivo)
    implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1")
    implementation("com.google.android.gms:play-services-base:18.5.0")
    // Alternativa sin servicios de Google Play: modelo de personas incluido en la app
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
