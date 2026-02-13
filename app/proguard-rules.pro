

# TEMPO APP-SPECIFIC RULES

# Tempo service - keep everything in service package
-keep class me.avinas.tempo.service.** { *; }
-keep class me.avinas.tempo.receiver.** { *; }
-keep class me.avinas.tempo.worker.** { *; }
-keep class me.avinas.tempo.widget.** { *; }

# Keep widget-related resources
-keep class androidx.glance.appwidget.GlanceAppWidget { *; }
-keep class androidx.glance.appwidget.GlanceAppWidgetReceiver { *; }
-keepclassmembers class * extends androidx.glance.appwidget.GlanceAppWidget {
    <init>();
    <methods>;
}
-keepclassmembers class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver {
    <init>();
    <methods>;
}

# Room entities and DAOs
-keep class me.avinas.tempo.data.local.entities.** { *; }
-keep class me.avinas.tempo.data.local.dao.** { *; }
-keep class me.avinas.tempo.data.stats.** { *; }

# Keep data classes used for JSON serialization
-keepclassmembers class me.avinas.tempo.data.** {
    <fields>;
}


# HILT / DAGGER (Updated for R8 Full Mode)

-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }
-keep @dagger.hilt.InstallIn class * { *; }
-keep @dagger.Module class * { *; }
-keep @dagger.Provides class * { *; }
-keep @dagger.Binds class * { *; }

# Keep all Hilt generated classes
-keep class **_HiltModules { *; }
-keep class **_HiltModules$* { *; }
-keep class **_GeneratedInjector { *; }
-keep interface **_GeneratedInjector { *; }
-keep class **Hilt_* { *; }
-keep class * extends dagger.hilt.internal.GeneratedComponent { *; }
-keep class * extends dagger.hilt.internal.GeneratedComponentManager { *; }
-keep @dagger.hilt.codegen.OriginatingElement class * { *; }
-keep @dagger.hilt.GeneratedEntryPoint class * { *; }
-keep @dagger.hilt.internal.GeneratedEntryPoint class * { *; }

# Keep Hilt components and their members
-keep,allowobfuscation,allowshrinking class dagger.hilt.** { *; }
-keep,allowobfuscation,allowshrinking class javax.inject.** { *; }

# Keep Hilt worker factory
-keep class androidx.hilt.work.** { *; }
-keep class * extends androidx.work.ListenableWorker { *; }

# Specifically keep TempoApplication's Hilt-generated classes
-keep class me.avinas.tempo.TempoApplication_GeneratedInjector { *; }
-keep interface me.avinas.tempo.TempoApplication_GeneratedInjector { *; }
-keep class me.avinas.tempo.Hilt_TempoApplication { *; }
-keep class me.avinas.tempo.TempoApplication_HiltComponents** { *; }
-keep class me.avinas.tempo.DaggerTempoApplication_HiltComponents** { *; }
-dontwarn me.avinas.tempo.TempoApplication_GeneratedInjector

# Keep ViewModels for Hilt injection
-keep class * extends androidx.lifecycle.ViewModel { *; }
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# Keep Hilt @Inject constructors
-keepclasseswithmembernames class * {
    @javax.inject.Inject <init>(...);
}

# Keep KSP generated classes
-keep class **_Factory { *; }
-keep class **_MembersInjector { *; }

# R8 full mode compatibility
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeInvisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations
-keepattributes RuntimeInvisibleParameterAnnotations
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# MOSHI (Updated for R8 Full Mode)

-keep class com.squareup.moshi.** { *; }
-keepclassmembers class * {
    @com.squareup.moshi.FromJson *;
    @com.squareup.moshi.ToJson *;
}
-keepclasseswithmembers class * {
    @com.squareup.moshi.Json <fields>;
}
-keep @com.squareup.moshi.JsonClass class * { *; }
-keepclassmembers @com.squareup.moshi.JsonClass class * {
    <init>();
    <fields>;
}
-if @com.squareup.moshi.JsonClass class *
-keep class <1>JsonAdapter {
    <init>(...);
    <fields>;
}
-if @com.squareup.moshi.JsonClass class **$*
-keep class <1>_<2>JsonAdapter {
    <init>(...);
    <fields>;
}
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}


# RETROFIT & OKHTTP

-keepattributes Signature
-keepattributes *Annotation*
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# COROUTINES

-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# ROOM
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# COIL
-dontwarn coil.**

# JETPACK COMPOSE (Updated for R8 Full Mode)
-dontwarn androidx.compose.**
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }
-keep class androidx.compose.foundation.** { *; }
-keep class androidx.compose.material.** { *; }
-keep class androidx.compose.material3.** { *; }

# Keep Composable functions
-keep @androidx.compose.runtime.Composable class * { *; }
-keepclassmembers class * {
    @androidx.compose.runtime.Composable *;
}

# Keep Compose ViewModel
-keep class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# Keep Stable and Immutable annotations for Compose
-keep @androidx.compose.runtime.Stable class * { *; }
-keep @androidx.compose.runtime.Immutable class * { *; }

# PERFORMANCE OPTIMIZATIONS
# Remove debug logging in release builds
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Optimization flags
-optimizationpasses 5
-allowaccessmodification
-dontpreverify

# GOOGLE DRIVE API & G_CLIENT
-keep class com.google.api.client.** { *; }
-keep class com.google.api.services.drive.** { *; }
-keep class com.google.gson.** { *; }
-keep class com.google.android.gms.** { *; }
-keep class com.google.android.libraries.identity.** { *; }

# GenericJson models used by Google API Client
-keepclassmembers class * extends com.google.api.client.json.GenericJson {
    *;
}
-keep class com.google.api.client.json.GenericJson { *; }
-keep class com.google.api.client.util.** { *; }

# Prevent R8 from stripping the default constructor of model classes
-keepclassmembers class com.google.api.services.drive.model.** {
    <init>();
}

# Suppress warnings for missing Java and Apache classes (likely unused or optional)
-dontwarn javax.naming.**
-dontwarn org.ietf.jgss.**
-dontwarn org.apache.http.**

# CREDENTIAL MANAGER & GOOGLE AUTH
-keep class androidx.credentials.** { *; }
-keepattributes *Annotation*

# Google Sign-In and Credential Manager
-keep class com.google.android.libraries.identity.googleid.** { *; }

# SERIALIZATION & PARCELIZE
-keepattributes *Annotation*
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Keep Kotlin serialization
-keepattributes InnerClasses
-keepattributes Signature
-keepattributes Exceptions

# APP BUNDLE OPTIMIZATION
# Enable aggressive code shrinking for smaller APK
-repackageclasses ''
-allowaccessmodification
-optimizationpasses 5

# Keep line numbers for crash reports (minimal overhead)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Remove verbose logging and unused code
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
}
