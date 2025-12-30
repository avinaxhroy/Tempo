package me.avinas.tempo.ui.navigation

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import me.avinas.tempo.data.repository.StatsRepository
import javax.inject.Inject

@HiltViewModel
class NavigationViewModel @Inject constructor(
    private val statsRepository: StatsRepository
) : ViewModel() {

    suspend fun getArtistIdByName(artistName: String): Long? {
        return statsRepository.getArtistIdByName(artistName)
    }

    suspend fun getAlbumIdByTitleAndArtist(albumTitle: String, artistName: String): Long? {
        return statsRepository.getAlbumIdByTitleAndArtist(albumTitle, artistName)
    }
}
