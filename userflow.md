

## **Tempo - Complete User Flow Documentation**

### **Overview**
Tempo is a music statistics app that tracks listening habits automatically and presents beautiful insights. The user flow prioritizes **instant value delivery** while gradually introducing features.[1][2]

***

## **1. First Launch Experience (New User)**

### **Entry Point:** User opens Tempo for the first time

**Flow:**

**A. Welcome Screen (Optional - Skip Available)**
- Hero text: "Know Your Music, Love Your Stats 🎵"
- Subtitle: "Tempo automatically tracks what you listen to and shows you beautiful insights"
- CTA Button: "Get Started" (primary red)
- "Skip" link at top right

**B. Permission Request Screen (Critical)**
- Large icon showing notification bell
- Headline: "Enable Music Tracking"
- Explanation: "Tempo needs access to your notifications to track what you're listening to. This runs in the background and doesn't drain your battery."
- Privacy note: "All data stays on your device 🔒"
- CTA: "Enable Tracking" → Opens Android Settings > Notification Access
- "I'll do this later" link (app still works but shows empty state)

**User Decision Point:**
- **If GRANTED** → Continue to next screen
- **If DENIED/LATER** → Show home with empty state message: "Enable tracking in Settings to start collecting stats"

**C. Battery Optimization Screen (Optional)**
- Headline: "One more thing for better accuracy"
- Explanation: "Let Tempo run in the background for continuous tracking"
- CTA: "Optimize" → Request battery exemption
- "Skip for now" link

**D. Welcome to Dashboard**
- Show home screen with **sample data** or empty state
- Tooltip: "Start listening to music and come back to see your stats!"
- Bottom sheet: "Want advanced stats? Connect Spotify (Optional)" with "Maybe Later" and "Connect" options

**Design Philosophy:** Get users to the main app ASAP. Permissions are explained clearly with benefits, not forced.[2][1]

***

## **2. Core User Journey (Active User with Data)**

### **Entry Point:** User opens Tempo (has listening history)

**Default Landing:** Home Dashboard

**User sees:**
- Hero card showing listening time this week/month with trend
- Quick stat cards (top artist, top song, total hours, discoveries)
- Spotlight story card (if available)
- Week in review grid
- Personalized insights based on habits

**User Actions Available:**

**A. View More Stats** → Tap bottom nav "Stats"
- Lands on Top Songs by default
- Can switch tabs: Top Songs / Top Artists / Top Albums
- Can change time period: Week / Month / Year
- Can tap any item → Navigate to Detail Screen

**B. Check History** → Tap bottom nav "History"
- See chronological listening timeline
- Grouped by date (Today, Yesterday, specific dates)
- Can search history using search icon
- Can tap any song → Navigate to Song Details

**C. Explore Spotlight Stories** → Tap "Spotlight Story" card on Home
- Navigate to full-screen swipeable cards
- Swipe through different insights (Time Devotion, Early Adopter, etc.)
- Can share any card to social media
- Can change time period (This Year / Last Month / All Time)

**D. Dive into Details** → Tap any song/artist/album anywhere
- Navigate to detailed screen showing comprehensive stats
- Can view listening trends chart
- Can see metadata and audio features (if Spotify connected)
- Can share the stats as image

***

## **3. Settings & Configuration Flow**

### **Entry Point:** User taps Settings icon (top right on Home)

**Settings Screen Shows:**
- Theme selection (System / Dark / Light)
- Notification preferences toggle
- Spotify connection status
- Data management options
- Privacy info
- About section

**User Actions:**

**A. Connect Spotify**
- Tap "Connect Spotify for Advanced Stats"
- See benefits modal: "Unlock mood analysis, energy tracking, and audio features"
- CTA: "Connect Now" → OAuth login flow
- After successful auth → Return to settings with "Connected as [username]" shown
- New badge appears on Home: "New stats unlocked! 🎉"

**B. Manage Data**
- Export History → Choose location → Save JSON file
- Import History → Select file → Confirm merge/replace → Import complete
- Clear All Data → Confirmation dialog → Wipe database

**C. Tracking Status**
- "Music Tracking: Active ✓" (green)
- If disabled: "Music Tracking: Disabled ❌" with "Enable Now" button → Opens permission settings

***

## **4. Detail Screen Flows**

### **Song Detail Flow**

**Entry Point:** User taps song from Stats/History/Home

**User sees:**
- Large album art with gradient background
- Song title, artist, album
- Achievement badges (if applicable): "All-Time Favorite", "Most Played This Month"
- Big stat: Times Played count
- Stat pills: Total listening time, Peak position, Release date, Genre
- Listening trends chart
- Audio features (if Spotify connected)

**Actions:**
- Back button → Return to previous screen
- Share button → Generate shareable card → Save/share
- "Play on YouTube Music" → Deep link to YouTube Music app (if installed)

### **Artist Detail Flow**

**Entry Point:** User taps artist from Stats/Home/Song Details

**User sees:**
- Artist image/placeholder
- Artist name and genre tags
- Two stat pills: Global listeners vs Your listening time
- Discovery insight card with story
- Top songs you played from this artist
- Globally popular songs by artist (optional, from MusicBrainz)

**Actions:**
- Back, Share (same as song details)
- Tap any song in list → Navigate to that Song Detail

### **Album Detail Flow**

**Entry Point:** User taps album from Song/Artist details

**User sees:**
- Album artwork and metadata
- Track list with individual play counts
- Total album listening time
- Completion rate (how many tracks played)
- Most played track highlighted

**Actions:**
- Back, Share
- Tap any track → Navigate to Song Detail

***

## **5. Spotify Connection Flow**

### **Entry Point:** Multiple entry points

**Trigger Points:**
1. First launch bottom sheet: "Connect Spotify for advanced stats"
2. Settings → Spotify section
3. Song Details → Tap locked "Audio Features" section
4. Home insight card: "Unlock mood tracking with Spotify"

**Flow:**
1. User taps "Connect Spotify"
2. Modal appears: "Unlock Advanced Stats"
   - Lists benefits: Mood analysis, Energy tracking, Audio features, Genre insights
   - Privacy note: "We only read your music data, never modify playlists"
   - CTA: "Connect with Spotify"
3. Opens Spotify OAuth in browser/WebView
4. User logs into Spotify → Authorizes Tempo
5. Redirect back to app
6. Success screen: "Spotify Connected! ✅"
   - "Enriching your stats now... This may take a few minutes"
   - Background WorkManager job starts enriching tracks with Spotify data
7. Return to previous screen
8. Home shows new insights unlocked: "Your music was 65% energetic this week! ⚡"

**Disconnect Flow:**
- Settings → "Connected as [username]" → "Disconnect Spotify"
- Confirmation: "Audio features will no longer update. Your existing data stays."
- Confirm → Token deleted → Reverted to basic stats

***

## **6. Sharing Flow**

### **Entry Point:** User taps Share button on any card/detail screen

**Flow:**
1. Generate image from current view (convert Composable to bitmap)
2. Add Tempo branding at bottom
3. Android share sheet appears: "Share via..."
4. User picks app (Instagram, Twitter, WhatsApp, etc.)
5. Image attached to chosen app
6. Optional: "Saved to gallery" toast notification

**Shareable Content:**
- Spotlight story cards (all types)
- Song detail stats card
- Artist detail stats card
- Home hero card (weekly/monthly summary)

***

## **7. Edge Cases & Error Flows**

### **No Data Yet (Empty State)**

**Trigger:** New user or tracking disabled

**Home Screen Shows:**
- Illustration of headphones
- Message: "Start listening to music to see your stats!"
- Sub-message: "Tempo tracks what you listen to on YouTube Music, Spotify, and other music apps"
- CTA: "Enable Tracking" (if not enabled)

**Stats/History Shows:**
- Empty state illustration
- "No data yet. Keep listening! 🎧"

### **Permission Denied**

**Trigger:** User denies notification access

**Behavior:**
- App still opens and works
- All screens show empty states with: "Enable tracking in Settings → Privacy → Notifications"
- Settings screen shows red warning: "Tracking Disabled ❌ Enable Now"

### **No Internet (Offline Mode)**

**Trigger:** Device offline when opening app

**Behavior:**
- App loads cached stats data (works fully offline for stats/history)
- Spotify enrichment queued for later (WorkManager retries when online)
- Toast: "Offline mode - Showing cached data"
- No MusicBrainz/Spotify API calls attempted

### **API Failures**

**Trigger:** MusicBrainz/Spotify API returns error

**Behavior:**
- Background enrichment fails silently
- Shows basic notification data (song title, artist)
- Retry scheduled for later via WorkManager
- User sees: "Some metadata unavailable" in song details

### **Spotify Token Expired**

**Trigger:** Spotify access token expired (1 hour)

**Behavior:**
- Automatic token refresh using refresh token
- If refresh fails → Settings shows "Spotify Disconnected - Reconnect"
- User taps → Re-auth flow

***

## **8. Navigation Structure**

### **Bottom Navigation (Always Visible)**
- **Dashboard** (Home icon) - Default landing
- **Stats** (Bar chart icon) - Rankings
- **History** (Clock icon) - Timeline

### **Top Level Screens**
- Dashboard → Settings (gear icon top right)
- Stats → Search icon (future feature)
- History → Search icon

### **Detail Screens (Full Screen, Back Navigation)**
- Song Details
- Artist Details
- Album Details
- Spotlight Stories (full screen swipeable)
- Settings

### **Modals/Bottom Sheets**
- Spotify connection benefits
- Time period selector (Week/Month/Year picker)
- Share options
- Confirmation dialogs (Clear data, Disconnect Spotify)

***

## **9. Background Behavior (User Not in App)**

### **Continuous Tracking**

**While user listens to music:**
- NotificationListenerService captures playback notifications
- Stores listening events in database
- No user interaction required

**WorkManager Background Jobs:**
- Enrich unenriched tracks with MusicBrainz (runs hourly)
- Enrich with Spotify if connected (runs daily)
- Generate daily summary notification (optional, 8 PM)
- Clean up old cached data (runs weekly)

**User Returns to App:**
- Stats automatically updated with new listening events
- Pull-to-refresh available for manual refresh
- New insights appear if milestones reached

***

## **10. Notification Strategy**

### **Persistent Service Notification (Always On)**
- Title: "Tempo is tracking your music"
- Icon: Tempo app icon
- Action: Tap to open app
- Dismissible: No (required for foreground service)

### **Optional Milestone Notifications**
- Daily Summary: "You listened for 3h 42m today! 🎵" (8 PM)
- Weekly Recap: "Your top artist this week: [Artist]" (Sunday evening)
- Achievements: "🔥 7 day listening streak!"
- New discoveries: "You found 5 new artists this week!"

**User Control:** Toggle in Settings → Notifications

***

## **Key UX Principles Applied**

1. **Instant Value:** Show stats immediately, don't block with onboarding[1][2]
2. **Progressive Disclosure:** Advanced features (Spotify) introduced gradually, not upfront
3. **Privacy First:** Emphasize on-device processing, no cloud sync required
4. **Graceful Degradation:** App works without permissions/Spotify, just with limited features
5. **Clear CTAs:** Every screen has obvious next action
6. **Feedback Loops:** Loading states, success messages, error explanations
7. **Minimal Friction:** Skip options, delayed account creation, optional features

***
