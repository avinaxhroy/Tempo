import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
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
    compileSdk = 36

    signingConfigs {
        create("release") {
            val keystoreFile = localProperties.getProperty("STORE_FILE")
            if (keystoreFile != null) {
                storeFile = file(keystoreFile)
                storePassword = localProperties.getProperty("STORE_PASSWORD")
                keyAlias = localProperties.getProperty("KEY_ALIAS")
                keyPassword = localProperties.getProperty("KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "me.avinas.tempo"
        minSdk = 26
        targetSdk = 36
        versionCode = 411
        versionName = "4.1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // API Configuration via BuildConfig
        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"${localProperties.getProperty("SPOTIFY_CLIENT_ID", "")}\"")
        buildConfigField("String", "SPOTIFY_REDIRECT_URI", "\"tempo://spotify-callback\"")
        buildConfigField("String", "MUSICBRAINZ_USER_AGENT", "\"Tempo/${versionName} (https://github.com/avinaxhroy/Tempo; avinashroy.bh@gmail.com)\"")
        buildConfigField("Long", "MUSICBRAINZ_RATE_LIMIT_MS", "1000L")
        buildConfigField("String", "LASTFM_API_KEY", "\"${localProperties.getProperty("LASTFM_API_KEY", "")}\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${localProperties.getProperty("GOOGLE_WEB_CLIENT_ID", "")}\"")
    }
    
    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
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

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    packaging {
        resources {
            excludes += "META-INF/*"
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }
    
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

// Configure Hilt to use KSP
ksp {
    arg("dagger.formatGeneratedSource", "disabled")
    arg("dagger.fastInit", "enabled")
    arg("correctErrorTypes", "true")
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.media3.exoplayer)
    implementation(libs.androidx.core.ktx)

    // Material Components
    implementation("com.google.android.material:material:1.12.0") 

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material) // Material 2
    implementation(libs.compose.material.icons)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling)
    implementation(libs.compose.activity)
    
    // Glance (Widgets)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    // Typography
    implementation("androidx.compose.ui:ui-text-google-fonts:1.7.6")

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // Retrofit & OkHttp
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.moshi)
    implementation(libs.okhttp.logging)

    // Coil
    implementation(libs.coil)
    implementation(libs.coil.core)
    implementation(libs.coil.network.okhttp)

    // WorkManager
    implementation(libs.work.runtime.ktx)

    // Charts (MPAndroidChart) and Vico
    implementation(libs.mpandroidchart)
    implementation(libs.vico.compose)

    // Palette
    implementation(libs.palette.ktx)

    // Hilt WorkManager integration
    implementation(libs.hilt.work)
    ksp("androidx.hilt:hilt-compiler:1.2.0") 

    // Lifecycle
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)

    // Navigation
    implementation(libs.navigation.compose)
    implementation(libs.hilt.navigation.compose)

    // DataStore Preferences
    implementation(libs.datastore.preferences)

    // Security - EncryptedSharedPreferences
    implementation(libs.security.crypto)

    // Moshi Kotlin adapter
    implementation(libs.moshi.kotlin)
    ksp(libs.moshi.codegen)

    // Credential Manager for Google Sign-In
    implementation(libs.credentials)
    implementation(libs.credentials.play.services.auth)
    implementation(libs.googleid)

    // Google Drive REST API
    implementation(libs.google.api.client) {
        exclude(group = "org.apache.httpcomponents")
    }
    implementation(libs.google.api.drive) {
        exclude(group = "org.apache.httpcomponents")
    }
    implementation(libs.google.http.client)

    // Play Services Auth
    implementation(libs.play.services.auth)

    // Coroutines extension for Play Services
    implementation(libs.play.services.coroutines)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    
    // Baseline Profiles
    implementation(libs.profileinstaller)
}

configurations.all {
    if (name.contains("hiltAnnotationProcessor")) {
        exclude(group = "com.squareup.moshi", module = "moshi-kotlin-codegen")
    }
}
