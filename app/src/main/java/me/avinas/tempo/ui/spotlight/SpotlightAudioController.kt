package me.avinas.tempo.ui.spotlight

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * Controller for handling audio playback in Spotlight Stories.
 * Manages 30-second song previews with specific volume ramps and global mute state.
 */
class SpotlightAudioController(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private var player: ExoPlayer? = null
    private var volumeRampJob: Job? = null
    
    // Global mute state
    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()
    
    // Current track being played
    private var currentUrl: String? = null
    
    // State to track if we should be playing (independent of mute)
    private var isPlayingState = false

    init {
        initializePlayer()
    }

    private fun initializePlayer() {
        if (player == null) {
            player = ExoPlayer.Builder(context).build().apply {
                repeatMode = Player.REPEAT_MODE_OFF
                volume = if (_isMuted.value) 0f else 0.3f // Start low for fade-in
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_BUFFERING -> android.util.Log.d("SpotlightAudio", "Player: BUFFERING")
                            Player.STATE_READY -> android.util.Log.d("SpotlightAudio", "Player: READY (PlayWhenReady=${player?.playWhenReady})")
                            Player.STATE_ENDED -> {
                                android.util.Log.d("SpotlightAudio", "Player: ENDED")
                                isPlayingState = false
                            }
                            Player.STATE_IDLE -> android.util.Log.d("SpotlightAudio", "Player: IDLE")
                        }
                    }
                    
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        android.util.Log.e("SpotlightAudio", "Player Error: ${error.message}", error)
                    }
                })
            }
        }
    }

    /**
     * Start playing a preview URL with a gentle fade-in (Slide 1 behavior).
     * Used for the "Setup" slide.
     */
    /**
     * Start playing a preview URL with a gentle fade-in (Slide 1 behavior).
     * Used for the "Setup" slide.
     */
    fun playSetup(url: String) {
        if (currentUrl == url && isPlayingState) return // Already playing
        
        // Reverted to 0.3f per user request (was temporarily 0.6f)
        playUrl(url, startVolume = 0.1f, targetVolume = 0.3f, fadeDurationMs = 500)
    }

    /**
     * Ramp up volume for the "Highlight" slide (Slide 2 behavior).
     * Continues playing existing track but increases volume.
     */
    fun playHighlight(url: String) {
        if (currentUrl != url || !isPlayingState) {
            // New track OR preloaded but not playing -> Start from scratch
            // Using 500ms fade for snappy start (consistent with playSetup)
            playUrl(url, startVolume = 0.1f, targetVolume = 0.7f, fadeDurationMs = 500)
        } else {
            // Check if user is muted
            if (_isMuted.value) return 
            
            // Already playing, just ramp up volume
            rampVolume(currentVolume = player?.volume ?: 0f, targetVolume = 0.7f, durationMs = 1000)
        }
    }

    /**
     * Stop playback immediately (on slide exit).
     */
    fun stop() {
        isPlayingState = false
        volumeRampJob?.cancel()
        player?.stop()
        currentUrl = null
    }

    /**
     * Fade out and stop (gentler exit).
     */
    fun fadeOutAndStop() {
        isPlayingState = false
        volumeRampJob?.cancel()
        
        if (_isMuted.value || player == null) {
            stop()
            return
        }
        
        volumeRampJob = scope.launch {
            val startVol = player?.volume ?: 0f
            val steps = 10
            for (i in 1..steps) {
                val progress = i.toFloat() / steps
                player?.volume = max(0f, startVol * (1 - progress))
                delay(50)
            }
            stop()
        }
    }

    /**
     * Toggle mute state.
     */
    fun toggleMute() {
        _isMuted.value = !_isMuted.value
        if (_isMuted.value) {
            // Mute immediately
            volumeRampJob?.cancel()
            player?.volume = 0f
        } else {
            // Unmute - restore volume
            // If we are in "highlight" mode (high volume) vs "setup" mode (low volume)
            // Ideally we'd know which target to restore to. For now, default to 0.7f if playing.
            if (isPlayingState) {
                player?.volume = 0.5f // Safe middle ground, or ramp up
                rampVolume(0f, 0.7f, 500)
            }
        }
    }

    /**
     * Preload a track so it's ready to play immediately.
     */
    fun prepare(url: String) {
        if (currentUrl == url) return // Already prepared/playing this one
        
        currentUrl = url
        val mediaItem = MediaItem.fromUri(url)
        player?.setMediaItem(mediaItem)
        player?.prepare() // Starts buffering
        // playWhenReady is false by default, so it won't start playing yet
    }

    private fun playUrl(url: String, startVolume: Float, targetVolume: Float, fadeDurationMs: Long) {
        // If we are already prepared for this URL, don't reset the player
        val needsPrepare = currentUrl != url
        
        currentUrl = url
        isPlayingState = true
        
        if (needsPrepare) {
             val mediaItem = MediaItem.fromUri(url)
             player?.setMediaItem(mediaItem)
             player?.prepare()
        }
        
        if (_isMuted.value) {
            player?.volume = 0f
            android.util.Log.d("SpotlightAudio", "Play requested but MUTED")
        } else {
            player?.volume = startVolume
            android.util.Log.d("SpotlightAudio", "Starting playback: $url (Vol: $startVolume -> $targetVolume)")
            rampVolume(startVolume, targetVolume, fadeDurationMs)
        }
        
        player?.play()
    }

    private fun rampVolume(currentVolume: Float, targetVolume: Float, durationMs: Long) {
        if (_isMuted.value) return
        volumeRampJob?.cancel()
        
        volumeRampJob = scope.launch {
            try {
                val steps = 20
                val delayStep = max(10L, durationMs / steps)
                val volStep = (targetVolume - currentVolume) / steps
                
                android.util.Log.d("SpotlightAudio", "Ramping volume: $currentVolume -> $targetVolume in ${steps} steps")
                
                var vol = currentVolume
                for (i in 1..steps) {
                    if (_isMuted.value) break // Stop ramping if muted mid-way
                    
                    vol += volStep
                    val clampedVol = min(1f, max(0f, vol))
                    player?.volume = clampedVol
                    delay(delayStep)
                }
                // Ensure final target is exact
                if (!_isMuted.value) {
                    player?.volume = targetVolume
                    android.util.Log.d("SpotlightAudio", "Volume ramp complete: $targetVolume")
                }
            } catch (e: Exception) {
                 android.util.Log.e("SpotlightAudio", "Volume ramp failed", e)
                 // Safety fallback
                 if (!_isMuted.value) player?.volume = targetVolume
            }
        }
    }

    fun release() {
        volumeRampJob?.cancel()
        player?.release()
        player = null
    }
}
