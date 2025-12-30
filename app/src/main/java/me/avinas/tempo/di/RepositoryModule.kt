package me.avinas.tempo.di

import me.avinas.tempo.data.local.dao.*
import me.avinas.tempo.data.repository.*
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    abstract fun bindTrackRepository(impl: RoomTrackRepository): TrackRepository

    @Binds
    abstract fun bindListeningRepository(impl: RoomListeningRepository): ListeningRepository

    @Binds
    abstract fun bindArtistRepository(impl: RoomArtistRepository): ArtistRepository

    @Binds
    abstract fun bindAlbumRepository(impl: RoomAlbumRepository): AlbumRepository

    @Binds
    abstract fun bindEnrichedRepository(impl: RoomEnrichedMetadataRepository): EnrichedMetadataRepository

    @Binds
    abstract fun bindPreferencesRepository(impl: RoomPreferencesRepository): PreferencesRepository

    @Binds
    abstract fun bindSpotifyRepository(impl: RoomSpotifyRepository): SpotifyRepository

    @Binds
    @Singleton
    abstract fun bindStatsRepository(impl: RoomStatsRepository): StatsRepository
}
