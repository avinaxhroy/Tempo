package me.avinas.tempo.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import me.avinas.tempo.data.drive.BackupSettingsManager
import me.avinas.tempo.data.drive.GoogleAuthManager
import me.avinas.tempo.data.drive.GoogleDriveService
import me.avinas.tempo.data.drive.GoogleDriveTokenStorage
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
        tokenStorage: GoogleDriveTokenStorage
    ): GoogleAuthManager = GoogleAuthManager(context, tokenStorage)
    
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
}
