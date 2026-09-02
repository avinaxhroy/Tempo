import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
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

// Local development can keep using local.properties. CI/release builders can
// inject the public Google OAuth client ID without creating a repository file.
val googleWebClientId = localProperties.getProperty("GOOGLE_WEB_CLIENT_ID")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?: System.getenv("TEMPO_GOOGLE_WEB_CLIENT_ID")?.trim().orEmpty()

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
        versionCode = 486
        versionName = "4.8.6"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Unit tests execute real Android-library code paths (e.g. Room Migration
        // objects that log via android.util.Log); return defaults instead of
        // throwing "not mocked" for framework calls that don't affect test logic.
        testOptions {
            unitTests.isReturnDefaultValues = true
        }

        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"${localProperties.getProperty("SPOTIFY_CLIENT_ID", "")}\"")
        buildConfigField("String", "SPOTIFY_REDIRECT_URI", "\"tempo://spotify-callback\"")
        buildConfigField("String", "MUSICBRAINZ_USER_AGENT", "\"Tempo/${versionName} (https://github.com/avinaxhroy/Tempo; avinashroy.bh@gmail.com)\"")
        buildConfigField("Long", "MUSICBRAINZ_RATE_LIMIT_MS", "1000L")
        buildConfigField("String", "LASTFM_API_KEY", "\"${localProperties.getProperty("LASTFM_API_KEY", "")}\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$googleWebClientId\"")
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
    
    bundle {
        language {
            enableSplit = true
        }
        density {
            enableSplit = true
        }
        abi {
            enableSplit = true
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

    androidResources {
        localeFilters += setOf("en", "fr", "de", "hu", "pt")
    }
}

ksp {
    arg("dagger.formatGeneratedSource", "disabled")
    arg("dagger.fastInit", "enabled")
    arg("correctErrorTypes", "true")
    // Export Room schema to schemas/ so Room validates entity definitions against migrations at
    // compile time. Commit these JSON files — a schema mismatch will fail the build before
    // it can ever reach a user and trigger a crash.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.media3.exoplayer)
    implementation(libs.androidx.core.ktx)

    implementation("com.google.android.material:material:1.12.0") 
    implementation("androidx.appcompat:appcompat:1.7.0")

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling)
    implementation(libs.compose.activity)
    
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.moshi)
    implementation(libs.okhttp.logging)

    implementation(libs.coil)
    implementation(libs.coil.core)
    implementation(libs.coil.network.okhttp)

    implementation(libs.work.runtime.ktx)

    implementation(libs.mpandroidchart)
    implementation(libs.vico.compose)

    implementation(libs.palette.ktx)

    implementation(libs.hilt.work)
    ksp("androidx.hilt:hilt-compiler:1.2.0") 

    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)

    implementation(libs.navigation.compose)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.datastore.preferences)

    implementation(libs.security.crypto)

    implementation(libs.moshi.kotlin)
    ksp(libs.moshi.codegen)

    implementation(libs.credentials)
    implementation(libs.credentials.play.services.auth)
    implementation(libs.googleid)

    implementation(libs.google.api.client) {
        exclude(group = "org.apache.httpcomponents")
    }
    implementation(libs.google.api.drive) {
        exclude(group = "org.apache.httpcomponents")
    }
    implementation(libs.google.http.client)

    implementation(libs.play.services.auth)
    implementation(libs.play.services.coroutines)

    implementation(libs.play.review)
    implementation(libs.play.review.ktx)

    // Desktop Satellite: NanoHTTPD (local HTTP server) + ZXing QR decoding + CameraX scanning
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.zxing:core:3.5.3")
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.sqlite.jdbc)
    // Android's local-unit-test stub does not provide a functional org.json.
    // Use the reference JVM implementation so Drive protocol encode/decode tests
    // execute the same JSON semantics instead of returning stub nulls.
    testImplementation("org.json:json:20260814")
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    
    implementation(libs.profileinstaller)
}

configurations.all {
    if (name.contains("hiltAnnotationProcessor")) {
        exclude(group = "com.squareup.moshi", module = "moshi-kotlin-codegen")
    }
}
