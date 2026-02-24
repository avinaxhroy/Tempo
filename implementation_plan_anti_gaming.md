# Anti-Gaming Implementation Plan

## Problem Analysis
The feedback highlights two specific exploits used to "game" the system (inflate stats/XP) without genuine engagement:
1.  **"Song on Repeat" Exploit**: Looping a single short song indefinitely to rack up play counts.
2.  **"Zero Volume" Exploit**: Playing music with the volume set to 0 to farm duration/XP while not actually listening.

## Existing State
- **File**: `MusicTrackingService.kt`
- **Volume Detection**: Currently **non-existent**. The service does not check device volume.
- **Repeat Detection**: Exists (`REPLAY_THRESHOLD_MS`), but only flags events as `isReplay`. It **does not stop** them from being saved or counted, meaning users can still loop tracks for infinite credit.

## Proposed Implementation

### 1. Stop "Zero Volume" Farming
**Strategy**: Instead of awarding 0 XP at the end (which requires complex post-processing), we will **pause time tracking** whenever the device is muted. This ensures "Muted Time" = "0 Play Time".

**Steps**:
1.  **Inject AudioManager**: Initialize `android.media.AudioManager` in `MusicTrackingService.onCreate`.
2.  **Track Muted State**: Add a transient `isMuted: Boolean` field to the `PlaybackSession` data class.
3.  **Real-time Check**: 
    - In `pollMediaSessionPositions` (and `processMediaControllerState`), check `audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)`.
    - If volume is `0`, set `isMuted = true`.
4.  **Halt Accumulation**: 
    - Modify `PlaybackSession.updatePosition` to accept the `isMuted` status.
    - **Logic**: If `isMuted` is true, **do not increment** `accumulatedPositionMs`.
    - Effect: A user playing 10 minutes of silent music will have `0ms` recorded duration. The event will effectively be discarded as "too short".
5.  **User Warning (UI)**:
    - Update `buildTrackingNotification` to check `session.isMuted`.
    - If muted, append `" (Muted)"` to the notification title (e.g., *"🎵 Song Title (Muted)"*). 
    - This satisfies the "it warns you first" observation from the feedback.

### 2. Stop "Song on Repeat" Spam
**Strategy**: Limit the number of times a single track can be credited consecutively. "Technically listening" is allowed, but "Spam" is not.

**Steps**:
1.  **Enhanced Cache**:
    - Change `recentPlaysCache` from `Map<Long, Long>` (Time Only) to `Map<Long, Pair<Long, Int>>` (Time + Count).
2.  **Enforce Limit**:
    - In `saveListeningEvent`, retrieve the previous play info for the current `trackId`.
    - **Logic**:
        - If `(Now - LastTime) < REPLAY_THRESHOLD_MS` (It's a replay):
            - Increment `RepeatCount`.
            - **Limit Check**: If `RepeatCount > 5` (Configurable Limit):
                - Log warning: "Spam replay detected (Count: 6), discarding event".
                - **Return early** (Do NOT save to DB).
        - Else (Time > Threshold OR Difference Track):
            - Reset `RepeatCount` to 1.
3.  **Result**: Users can loop a song ~5 times for legitimate listening, but infinite looping will stop awarding stats after the 5th play.

## Verification Check
- [ ] **Mute Test**: Play music -> Set Vol 0 -> Verify Notification updates to "(Muted)" -> Verify logs show no duration accumulation.
- [ ] **Spam Test**: Loop a 30s song 10 times -> Verify only 5 events are saved in the database.
