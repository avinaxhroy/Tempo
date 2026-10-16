package me.avinas.tempo.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import me.avinas.tempo.data.drive.BackupSettingsManager
import me.avinas.tempo.data.drive.DriveHistorySyncSettingsManager
import me.avinas.tempo.data.drive.GoogleAuthManager
import me.avinas.tempo.data.drive.GoogleDriveService
import me.avinas.tempo.data.drive.GoogleDriveTokenStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * Hilt module for Google Drive backup dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
object DriveModule {
    
    @Provides
    @Singleton
    fun provideGoogleDriveTokenStorage(
        @ApplicationContext context: Context
    ): GoogleDriveTokenStorage = GoogleDriveTokenStorage(context)
    
    @Provides
    @Singleton
    fun provideGoogleAuthManager(
        @ApplicationContext context: Context,
        tokenStorage: GoogleDriveTokenStorage,
        historySyncSettings: DriveHistorySyncSettingsManager
    ): GoogleAuthManager = GoogleAuthManager(context, tokenStorage, historySyncSettings)
    
    @Provides
    @Singleton
    fun provideGoogleDriveService(
        @ApplicationContext context: Context,
        authManager: GoogleAuthManager
    ): GoogleDriveService = GoogleDriveService(context, authManager)
    
    @Provides
    @Singleton
    fun provideBackupSettingsManager(
        @ApplicationContext context: Context
    ): BackupSettingsManager = BackupSettingsManager(context)

    /**
     * Application-lifetime scope for long-running backup/restore work that must
     * survive ViewModel destruction (e.g. the user navigating away mid-restore).
     * Never cancelled; operations observe their own errors and clean up in
     * finally blocks.
     */
    @Provides
    @Singleton
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
