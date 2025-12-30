import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    // kotlin("kapt") // Removed Kapt
    id("com.google.devtools.ksp")
    kotlin("plugin.compose")
    id("dagger.hilt.android.plugin")

}

// Load local.properties for API keys
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        load(localPropertiesFile.inputStream())
    }
}

android {
    namespace = "me.avinas.tempo"
    compileSdk = 35

    defaultConfig {
        applicationId = "me.avinas.tempo"
        minSdk = 26
        targetSdk = 35
        versionCode = 310
        versionName = "3.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // API Configuration via BuildConfig
        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"${localProperties.getProperty("SPOTIFY_CLIENT_ID", "")}\"")
        buildConfigField("String", "SPOTIFY_REDIRECT_URI", "\"tempo://spotify-callback\"")
        buildConfigField("String", "MUSICBRAINZ_USER_AGENT", "\"Tempo/${versionName} (https://github.com/avinaxhroy/Tempo; avinashroy.bh@gmail.com)\"")
        buildConfigField("Long", "MUSICBRAINZ_RATE_LIMIT_MS", "1000L")
        buildConfigField("String", "LASTFM_API_KEY", "\"${localProperties.getProperty("LASTFM_API_KEY", "")}\"")
    }
    
    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

    packaging {
        resources {
            excludes += "META-INF/*"
        }
    }
    
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.0")
    implementation("androidx.media3:media3-exoplayer:1.2.0")
    implementation("androidx.core:core-ktx:1.15.0")

    // Material Components
    implementation("com.google.android.material:material:1.11.0")

    // Compose
    implementation("androidx.compose.ui:ui:1.6.2")
    implementation("androidx.compose.material:material:1.6.2")
    implementation("androidx.compose.material:material-icons-extended:1.6.2")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.ui:ui-tooling:1.6.2")
    implementation("androidx.activity:activity-compose:1.8.1")
    
    // Glance (Widgets)
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")

    // Typography
    implementation("androidx.compose.ui:ui-text-google-fonts:1.6.2")

    // Room
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.57.2")
    ksp("com.google.dagger:hilt-compiler:2.57.2")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // Retrofit & OkHttp
    implementation("com.squareup.retrofit2:retrofit:2.10.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.10.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Coil
    implementation("io.coil-kt:coil-compose:2.6.0")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Charts (MPAndroidChart) and Vico optional
    implementation("com.github.PhilJay:MPAndroidChart:3.1.0")
    implementation("com.patrykandpatrick.vico:compose:2.0.0")

    // Palette
    implementation("androidx.palette:palette-ktx:1.0.0")

    // Hilt WorkManager integration
    implementation("androidx.hilt:hilt-work:1.3.0")
    ksp("androidx.hilt:hilt-compiler:1.3.0")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.hilt:hilt-navigation-compose:1.0.0")

    // DataStore Preferences
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Security - EncryptedSharedPreferences for Spotify token storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Moshi Kotlin adapter
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")
    ksp("com.squareup.moshi:moshi-kotlin-codegen:1.15.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

configurations.all {
    if (name.contains("hiltAnnotationProcessor")) {
        exclude(group = "com.squareup.moshi", module = "moshi-kotlin-codegen")
    }
}
