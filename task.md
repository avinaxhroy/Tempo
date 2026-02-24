# Anti-Gaming Implementation Checklist

- [x] **Zero Volume Detection** <!-- id: 0 -->
    - [x] Integrate `AudioManager` to monitor device volume
    - [x] Pause playback accumulation when volume is 0
    - [x] Update notification to show "(Muted)" status immediately on volume change
- [x] **Repeat Spam Protection** <!-- id: 1 -->
    - [x] Implement consecutive play counter for tracks
    - [x] Limit credit to 3 consecutive plays (prevents infinite looping)
    - [x] Use `ConcurrentHashMap` for thread-safe replay tracking
