// Load local.properties for API keys
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
}

// Load local.properties for API keys
val localProperties =
    Properties().apply {
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            load(localPropertiesFile.inputStream())
        }
    }

// Declared once so archiveReleaseMapping below names the archived mapping file after the
// exact version it deobfuscates. Without the matching mapping.txt a release stack trace
// cannot be read back, so these two must never drift apart.
val appVersionCode = 4810
val appVersionName = "4.8.10"

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
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Unit tests execute real Android-library code paths (e.g. Room Migration
        // objects that log via android.util.Log); return defaults instead of
        // throwing "not mocked" for framework calls that don't affect test logic.
        testOptions {
            unitTests.isReturnDefaultValues = true
        }

        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"${localProperties.getProperty("SPOTIFY_CLIENT_ID", "")}\"")
        buildConfigField("String", "SPOTIFY_REDIRECT_URI", "\"tempo://spotify-callback\"")
        buildConfigField(
            "String",
            "MUSICBRAINZ_USER_AGENT",
            "\"Tempo/$versionName (https://github.com/avinaxhroy/Tempo; avinashroy.bh@gmail.com)\"",
        )
        buildConfigField("Long", "MUSICBRAINZ_RATE_LIMIT_MS", "1000L")
        buildConfigField("String", "LASTFM_API_KEY", "\"${localProperties.getProperty("LASTFM_API_KEY", "")}\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${localProperties.getProperty("GOOGLE_WEB_CLIENT_ID", "")}\"")

        // Anonymous app-health analytics. The key is deliberately NOT committed: a blank
        // key makes AnalyticsGate report the build as unconfigured, so anyone building
        // Tempo from source gets a tracker-free app with no extra flags. Debug builds
        // never report either, unless APTABASE_DEBUG_PREVIEW=true is set locally to
        // preview the Home notice + Settings toggle in a debug build.
        buildConfigField("String", "APTABASE_APP_KEY", "\"${localProperties.getProperty("APTABASE_APP_KEY", "")}\"")
        buildConfigField("String", "APTABASE_HOST", "\"${localProperties.getProperty("APTABASE_HOST", "https://eu.aptabase.com")}\"")
        buildConfigField("boolean", "ANALYTICS_DEBUG_PREVIEW", "${localProperties.getProperty("APTABASE_DEBUG_PREVIEW", "false")}")
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
                "proguard-rules.pro",
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
            freeCompilerArgs.add("-Xannotation-default-target=param-property")
        }
    }

    packaging {
        resources {
            excludes += "META-INF/*"
        }
        jniLibs {
            useLegacyPackaging = false
            keepDebugSymbols += listOf("libandroidx.graphics.path.so", "libimage_processing_util_jni.so")
        }
    }

    lint {
        // Tempo has a large historical lint backlog. Keep generating the full
        // report, while CI below fails on lint errors introduced by changed files.
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

// R8 obfuscates release stack traces. The anonymous crash signature Tempo reports contains
// only an obfuscated class name plus one frame with a line number, so it is readable only
// with the mapping.txt for that exact version.
//
// Run this after assembleRelease / bundleRelease and before shipping, then copy the result
// somewhere durable — `mappings/` is gitignored, and without the file the crash reports are
// unreadable and you would have to fall back to Play Console Android vitals.
val releaseMappingArchiveName = "mapping-$appVersionName-$appVersionCode.txt"

tasks.register<Copy>("archiveReleaseMapping") {
    group = "release"
    description = "Archives the release R8 mapping file as mappings/mapping-<version>.txt for deobfuscating crash reports."
    // Lazy match rather than a hardcoded name: resolves to nothing if the release variant
    // is ever removed, instead of failing configuration.
    dependsOn(tasks.matching { it.name == "minifyReleaseWithR8" })
    from(layout.buildDirectory.file("outputs/mapping/release/mapping.txt"))
    into(rootProject.layout.projectDirectory.dir("mappings"))
    // Passed as arguments rather than a rename lambda: a closure here would capture the
    // build script instance and break the configuration cache.
    rename("mapping.txt", releaseMappingArchiveName)
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
    implementation(libs.okhttp)
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
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)

    implementation(libs.profileinstaller)
}

configurations.all {
    if (name.contains("hiltAnnotationProcessor")) {
        exclude(group = "com.squareup.moshi", module = "moshi-kotlin-codegen")
    }
}
