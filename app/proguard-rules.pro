# Add project specific ProGuard rules here.
# Optimized for Tempo - Music Listening Stats App

# =====================================================
# TEMPO APP-SPECIFIC RULES
# =====================================================

# Tempo service - keep everything in service package
-keep class me.avinas.tempo.service.** { *; }
-keep class me.avinas.tempo.receiver.** { *; }
-keep class me.avinas.tempo.worker.** { *; }

# Room entities and DAOs
-keep class me.avinas.tempo.data.local.entities.** { *; }
-keep class me.avinas.tempo.data.local.dao.** { *; }
-keep class me.avinas.tempo.data.stats.** { *; }

# Keep data classes used for JSON serialization
-keepclassmembers class me.avinas.tempo.data.** {
    <fields>;
}

# =====================================================
# HILT / DAGGER
# =====================================================
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }
-keep @dagger.hilt.InstallIn class * { *; }
-keep @dagger.Module class * { *; }
-keep @dagger.Provides class * { *; }

# =====================================================
# MOSHI
# =====================================================
-keep class com.squareup.moshi.** { *; }
-keepclassmembers class * {
    @com.squareup.moshi.FromJson *;
    @com.squareup.moshi.ToJson *;
}
-keepclasseswithmembers class * {
    @com.squareup.moshi.Json <fields>;
}
-keep @com.squareup.moshi.JsonClass class * { *; }

# =====================================================
# RETROFIT & OKHTTP
# =====================================================
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

# =====================================================
# COROUTINES
# =====================================================
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# =====================================================
# ROOM
# =====================================================
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# =====================================================
# COIL
# =====================================================
-dontwarn coil.**

# =====================================================
# JETPACK COMPOSE
# =====================================================
-dontwarn androidx.compose.**
-keep class androidx.compose.** { *; }

# =====================================================
# PERFORMANCE OPTIMIZATIONS
# =====================================================
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
