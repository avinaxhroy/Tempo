# Contributing to Tempo

Tempo is built with its community — contributions of all sizes are welcome and genuinely valued, from bug fixes and translations to new features, documentation, and tests. If you use Tempo, you are already part of what makes it better.

This guide covers how to set up your development environment, build and test the app, follow codebase conventions, and submit pull requests.

## Project principles

Tempo is a local-first music companion and scrobbler for Android. Every change must adhere to these core constraints:

- **Local storage first:** Listening history, statistics, and metadata stay on device in local SQLite databases via Room. The app never transmits listening content, search queries, notification text, or device identifiers to any server.
- **Anonymous app-health only:** The one thing that leaves the device is anonymous crash, error and feature-usage statistics, and they are constrained by a **closed schema**. Every event and property is defined in `data/analytics/AnalyticsEvent.kt`; there are no free-form string properties, so a caller cannot pass a track title, artist name or file path. Adding a property that could carry listening content or an identifier is prohibited, and `AnalyticsSchemaTest` fails the build if one appears. Reporting is on by default with a one-tap opt-out in Settings, requires an in-app notice before the first event is sent, and is inert in any build without an `APTABASE_APP_KEY`. See [docs/ANALYTICS.md](docs/ANALYTICS.md).
- **Use the diagnostics report instead of expanding telemetry:** when you need more detail to debug something, add it to `DiagnosticsInput` (rendered at **Settings → Your Data → Data & diagnostics**), not to the analytics schema. The report is user-initiated and reviewable, so it may carry detail the automatic events never should.
- **Offline operation:** Core tracking, stats generation, and library browsing must work without network access. External API calls (Last.fm, Spotify, MusicBrainz, Deezer) are strictly opt-in for metadata enrichment and imports.
- **Free core forever, premium is strictly additive:** The core app — local tracking, stats, library tools, importers, local and Drive backups, LAN desktop sync, the browser extension — stays free forever and is never degraded to make premium look better. Tempo will never exploit the app for cash: no ads, no selling listening data, no paywalling merged community work, no intentionally worsened free tier. Optional premium exists only to cover real server costs for things a phone cannot do alone: cloud sync and one history across every device, AI reports, public profiles, web access, and offloaded metadata enrichment. The AGPLv3 plus "no commercial use / no rebranding" addendum in `LICENSE` binds forks and redistributors — it is what stops clones from paywalling your work. As copyright holder, the maintainer may offer first-party paid hosting for those server-cost features; that does not relicense, close, or paywall any core code.

## Development setup

### Requirements

- Android Studio Ladybug (2024.2.1) or newer
- JDK 17
- Android SDK 36 (Minimum SDK: 26, Target SDK: 36, Compile SDK: 36)
- Node.js 18+ (required only for the browser extension)

### Optional API configuration

The app builds and runs without external API keys. To test features that require third-party services, add the relevant keys to `local.properties` in the project root:

```properties
SPOTIFY_CLIENT_ID=your_spotify_client_id
LASTFM_API_KEY=your_lastfm_api_key
GOOGLE_WEB_CLIENT_ID=your_google_client_id
```

**Leave `APTABASE_APP_KEY` unset unless you are specifically testing analytics.** With no key the app reports nothing at all and no analytics UI is shown, which is the intended state for a development build — and for anyone building Tempo from source. Debug builds never report even when a key is present.

## Building and testing

### Android application

Clone the repository and build the debug APK:

```bash
git clone https://github.com/avinaxhroy/Tempo.git
cd Tempo
./gradlew assembleDebug
```

Run unit tests:

```bash
./gradlew testDebugUnitTest
```

Run lint checks:

```bash
./gradlew lintDebug
```

### Room database schemas

Room entity definitions export database schemas to `app/schemas/`. When adding or modifying entities and database migrations:
1. Ensure the schema export succeeds during compilation.
2. Add migration test cases in `app/src/test/java/me/avinas/tempo/data/local/` to verify forward compatibility.
3. Commit the updated JSON schema files alongside the migration code.

### Release builds and crash symbolication

Release builds are obfuscated by R8. Crash reports produced by the app deliberately contain only an obfuscated class name and one frame with a line number, so they can only be read with the `mapping.txt` for that exact version:

```bash
./gradlew assembleRelease archiveReleaseMapping
```

This writes `mappings/mapping-<version>.txt`. That directory is gitignored — copy the file somewhere durable, or the crash reports become unreadable and you would have to rely on Play Console instead.

### Browser companion extension

The browser companion lives in `browser-extension/`:

```bash
cd browser-extension
npm install
npm run build
npm run typecheck
```

Load the unpacked extension directory (`dist/chrome` or `dist/firefox`) into your browser:
- Chrome: Open `chrome://extensions`, enable **Developer mode**, and select **Load unpacked**.
- Firefox: Open `about:debugging#/runtime/this-firefox` and select **Load Temporary Add-on**.

## Contribution workflow

### When to open an issue first

Open an issue to discuss your proposal before writing code for:
- New major features or background services
- Database schema changes or structural refactoring
- UI navigation changes or visual redesigns
- Adding new third-party dependencies
- **Adding an analytics event or property** — the schema is a privacy commitment that is published to users, so it changes deliberately

You do not need to open an issue before submitting pull requests for:
- Bug fixes and crash resolutions
- Parser fixes or support for new media players in `DefaultMusicApps.kt`
- Translation updates in `app/src/main/res/values-*/strings.xml`
- Documentation fixes and test additions

### Pull request checklist

1. Fork the repository and create a branch from `main`.
2. Keep each pull request focused on a single change or bug fix.
3. Run `./gradlew testDebugUnitTest` and `./gradlew lintDebug` to verify tests pass and no lint regressions were introduced.
4. Open the pull request against `main` and include:
   - A summary of the problem and the implemented fix.
   - Reproduction steps or test coverage for bug fixes.
   - Before and after screenshots or screen recordings for UI changes.
   - The issue number if one exists (for example, `Fixes #42`).

### Premium roadmap overlap — please check before building

To save your time and avoid duplicated effort, please open an issue first if your idea touches an area on the premium roadmap, including but not limited to:

- cloud backup / cloud sync, multi-device history, accounts or device pairing
- AI summaries, insights, or narrated reports
- public or shareable web profiles
- web app access
- server-side metadata enrichment
- any future hosted / server-cost capability the maintainer marks as premium — the roadmap evolves, so when in doubt, just ask

These are server-cost features that sustain development, so they are designed and hosted as premium. If you open an issue early, the maintainer will help scope your idea toward the free core (for example, a local-first version of the feature) or point to where a clean premium hook belongs. The maintainer may also build adjacent premium capabilities later; merged core contributions always stay free and are never retroactively gated (see below).

Pull requests that duplicate a planned or future premium capability, or implement a server-cost feature as free-only in a way that conflicts with the roadmap, may be redirected, asked to be rescoped to the free core, or respectfully declined — not because the work is not appreciated, but to keep the free/premium boundary clean and the project sustainable. Early discussion almost always finds a version that can be merged.

## Code and architecture standards

- **Architecture:** MVVM with Clean Architecture. Keep business logic in `domain/`, data persistence and network calls in `data/`, and Jetpack Compose screens and ViewModels in `ui/`.
- **Dependency injection:** Use Hilt for dependency injection across ViewModels, repositories, workers, and background services.
- **UI:** Write UI entirely in Jetpack Compose using Material 3 components. Support both light and dark themes. Ensure interactive touch targets meet the 48dp minimum size requirement.
- **Background work:** Use `WorkManager` for periodic or deferrable background tasks (enrichment, daily stats). Use foreground services only where continuous playback listening requires Android service lifecycle management.
- **Memory and performance:** Downsample large cover art bitmaps before writing to SQLite to prevent `TransactionTooLargeException` and memory pressure. Release broadcast receivers, database cursors, and coroutine scopes on component teardown.
- **Dependencies:** Avoid adding new dependencies unless the functionality cannot be reasonably implemented with existing libraries or platform APIs.
- **Analytics:** Never call an analytics SDK directly. Inject `AnalyticsTracker` and pass one of the events from `data/analytics/AnalyticsEvent.kt`. Never add a free-form `String` property, and bucket any count rather than sending an exact number.

## Localization

Tempo supports multiple languages. String resources live in `app/src/main/res/`:

- Default English: `values/strings.xml`
- German: `values-de/strings.xml`
- French: `values-fr/strings.xml`
- Hungarian: `values-hu/strings.xml`
- Portuguese: `values-pt/strings.xml`
- Russian: `values-ru/strings.xml`

When adding user-facing UI text:
1. Add the string to `values/strings.xml` using a descriptive key.
2. Reference the string via `stringResource(R.string.your_key)` in Compose instead of hardcoding text.
3. If providing translations for existing keys, update the corresponding `values-*/strings.xml` file.

**Privacy-related copy must be accurate in every language.** If you change a string that makes a privacy claim, remove the now-stale translations for that key in `values-*/` rather than leaving a claim that is no longer true — falling back to English is better than telling users something false.

## License terms for contributions

Tempo is licensed under the [GNU Affero General Public License v3 (AGPLv3) with Custom Limitations](LICENSE).

By submitting a pull request, you agree that your contributions are licensed under these same terms. Contributions are not accepted if they require commercial relicensing, closed-source distribution, or removal of project attribution.

What this means in practice:

- **Your contribution stays free:** anything merged into the core app remains free and will never be paywalled or moved behind premium later.
- **Premium funds development, it does not take from the core:** premium is limited to additive, server-cost features (cloud sync, AI, public profiles, web access). No merged core feature is ever removed, gated, or degraded for premium.
- **Only the maintainer may offer premium:** the no-commercial-use addendum applies to forks and redistributors. The copyright holder may offer first-party paid hosting for the premium features above without changing the license of any core code.
