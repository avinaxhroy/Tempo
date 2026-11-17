# What Tempo collects

Tempo sends anonymous app-health statistics: crashes, errors, and which features get used. It never sends what you listened to. This page lists every event it can send.

Source of truth is `app/src/main/java/me/avinas/tempo/data/analytics/AnalyticsEvent.kt`. If that file and this page disagree, the file is right and this page is a bug.

To turn reporting off, open Settings → Your Data → Anonymous app-health stats. Turning it off stops all reporting at once and deletes anything buffered but not yet sent. To see the same list in the app, open Settings → Your Data → What we collect.

## How anonymity works

Tempo has no accounts and no login, and your listening history never leaves the device.

The only identifier involved is a **random install id**: a value the app generates once, keeps
on your device, and sends only as the routing key for its own statistics. It is not your
advertising ID, not `ANDROID_ID`, and not derived from anything about your device or you, so it
cannot be matched against another app — and there is nothing to match it to, because Tempo has
no accounts. Its one job is making "how many people used Tempo today, and did they come back"
answerable at all. Without a value that survives across days, one person opening the app thirty
times and thirty people opening it once are the same number.

You can replace it at any time in Settings → Your Data → **Reset analytics ID**. Turning
reporting off replaces it too, so everything recorded before becomes unlinkable to anything
after.

Tempo collects no advertising ID, no `ANDROID_ID`, and does no fingerprinting. Events are sent
as anonymous: the analytics service is told not to create a user profile, so no user record is
stored for you. The client IP address is never stored, and every event tells the analytics
service not to derive a location from it either, so nothing about where you are is kept. See
[Where it goes](#where-it-goes).

Reporting works over any connection. Each event is a few hundred bytes, about 16 KB on a busy
day. Waiting for Wi-Fi would silence everyone on mobile data only, so Tempo does not wait for it.

Full policy: [PRIVACY.md](../PRIVACY.md).

## What is sent

Numbers go out as ranges, not exact figures. Counts use `0`, `1-5`, `6-25`, `26-100`, `100+`. Durations use `<1s`, `1-5s`, `5-30s`, `30s+`. Sizes use `0`, `<1MB`, `1-10MB`, `10-100MB`, `100MB+`. Ranges avoid fingerprinting and keep dashboards stable as libraries grow.

Everything else is a fixed name from a closed list. There is no free-text field a track title could pass through. The exceptions are small `0`/`1` flags, the app version string, and the coarse Android-version band on `install_once`.

| Event | What it tells us |
|---|---|
| `startup_slow` | That Tempo took an unusually long time to start. Sent only when a start is slow — a normal launch reports nothing |
| `feature_first_used` | The first time each feature is used — once ever, never again. Answers which features are unused without counting every tap |
| `onboarding_step` | Where setup stalls: step name, next/skip/back, time range on step |
| `onboarding_completed` | Setup completion: skipped-step range, total time range |
| `install_once` | That Tempo was installed, once ever: the version it arrived on and a coarse Android-version band. The only event that separates "people left" from "nobody new arrived" |
| `daily_activity` | That Tempo was used at all today, and whether music detection was already down when it was. Once a day, and the only thing "daily active users" is counted from |
| `tracking_health` | That music detection stopped and Tempo brought it back: how long it had been down, what triggered the check, and how forceful the recovery was. Once a day at most, however many times it happened |
| `listening_activity` | Daily totals only, never per track: listen-count range, app-count range, and whether the app's own screens were opened that day |
| `import_run` | Import result for Last.fm, Spotify, YouTube Music, Deezer, Musicolet, or the browser-extension export: phase, record range, failure category, time range |
| `enrichment_failure` | That a metadata enrichment sweep failed, once a day at most and never for a sweep that worked: what started it, how much backlog was waiting, the failure category, time range |
| `backup_run` | Backup result for local or Drive: success flag, size and time ranges |
| `crash` | Fatal crash signature: obfuscated class name, one obfuscated line, app version |

Failure reasons use a closed list: `IO`, `PARSE`, `NETWORK`, `DATABASE`, `PERMISSION`, `OUT_OF_MEMORY`, `CANCELLED`, `UNKNOWN`. Never an error message or stack trace.

### How often an event may be sent

> An event is sent at most once per install per day, or on a rare terminal outcome. Never once per run of a background job.

The bound is not about tidiness. A per-invocation event multiplies by the number of times Android wakes the job, which the developer does not control: `app_started` was sent on every process start and became 28% of all traffic for a value that never varied; `tracking_gap` was sent on every health check and produced 192 events per device per day; `enrichment_run` was sent on every sweep — every app start, every hourly tick, every per-track refresh — and became 94% of all traffic, two thirds of it reporting that a run cleared nothing.

That volume is not merely wasteful. It exhausted the free monthly quota, and events past the cap are dropped permanently at the server. The noise crowds out the signal: the last two months of data end mid-day because the quota ran out, and what was lost included the crashes and activity numbers worth keeping.

So an event is justified by a decision it changes, and it is sized to the rate that decision needs. Daily is enough for everything here. Anything a developer would want per-run belongs in logs and the in-app diagnostics screen, not in an event.

## Crash reports

Error messages often echo the notification text Tempo just parsed. That text can contain listening data, so Tempo leaves messages out entirely. A crash sends three fields and nothing else:

```
crash_class   obfuscated class name, e.g. "a.b.c"
top_frame     obfuscated method + line, e.g. "d(SourceFile:412)"
app_version   e.g. "4.8.8"
```

The format is checked before the event can exist. Text that does not match the pattern is rejected, so a song title cannot fit through this path.

## What is never sent

- Track, artist, album, or playlist names, not even hashed.
- Search queries, notification content, file paths, listening timestamps.
- Account details, advertising ID, `ANDROID_ID`, or any hardware identifier.
- Your IP address, and anything that could be derived from it. Tempo stores no address, and every event carries PostHog's `$geoip_disable` flag, so the backend does not turn the connection into a country, region or city either. No location of any kind is kept — see below.
- No advertising or attribution SDK, no Google Analytics, no Firebase.
- No session replay, no screen recording, no tap maps.

## Diagnostics report

Separate from automatic reporting, Tempo can build a diagnostics report at Settings → Your Data → Data & diagnostics.

It covers build and database versions, library counts, metadata-enrichment status, music detection health, and background work states. It appears on screen so you can read it first. It leaves your device only if you share it, for example by attaching it to a bug report.

Because you start it and review it, it can carry more detail than the automatic events. It still has no track, artist, or album names, no file paths, and no account or device identifiers.

## Where it goes

[PostHog](https://posthog.com), on EU infrastructure in Frankfurt. PostHog is open source and does not sell data. Events go straight from the device to PostHog's batch endpoint — there is no intermediary service, so the guarantees below are enforced by the app itself and are auditable in `PostHogPayload.kt`. Three things matter here and all are deliberate:

- **Person profiles are off.** Every event is sent with `$process_person_profile = false`, so no user record is created and there is nothing to look a person up by.
- **Location enrichment is off.** Every event is sent with `$geoip_disable = true`, so PostHog does not turn the connection into a country, region or city. PostHog receives the address as any server does; this flag is what stops it becoming stored location data.
- **No address is stored by Tempo.** It is never written to a database, a log, or an event property.

**No location data is collected** — not a country, not a city, not coordinates. That is a deliberate change from an earlier arrangement where a relay attached a coarse country code; the flag above is what keeps the stronger promise true.

The PostHog project token ships inside the app. That is how PostHog client tokens are designed to be used, and it replaces an earlier arrangement where a server held the token. The trade is that anyone holding the app can post events to that project, so the closed schema and the published list below are what keep the data honest.

## Builds from source

Builds compiled from source send nothing. The analytics endpoint is not committed to the repository, so a self-built copy has reporting inert. Debug builds never report either.

To point a build at your own analytics, set `ANALYTICS_ENDPOINT` and `ANALYTICS_TOKEN` in
`local.properties`. `ANALYTICS_ENDPOINT` is the full URL — for PostHog,
`https://eu.i.posthog.com/batch/` — and `ANALYTICS_TOKEN` is that project's token. Nothing else
is needed: the app speaks PostHog's batch API directly.

## Play Store Data Safety

The declaration in Play Console has to match this page, and it is easy for the two to drift because one lives in a web form. Suggested answers, with the reasoning that matters more than the exact labels:

| Category | Declare | Why |
|---|---|---|
| Device or other IDs | **Collected** | The pseudonymous install id. **Not** an advertising ID — that is never read. |
| App activity → Other actions | **Collected** | Feature first use, onboarding steps, import and backup outcomes. |
| App info and performance → Crash logs | **Collected** | Obfuscated class and frame only; never the message or a stack. |
| Location | **Not collected** | Every event carries `$geoip_disable`, so no country, city or coordinates are derived or stored. |
| Advertising or marketing | **No** | |
| Encrypted in transit | **Yes** | |
| Users can delete their data | **Yes** | Reset analytics ID, or turn reporting off — both leave the old id unlinkable and nothing further is sent under it. |

On "shared with third parties": events reach PostHog, which acts as a **processor under contract**, not as a purchaser using the data for its own purposes. Play's "sharing" definition normally covers transfer for the recipient's own use, so collection-only is the usual reading — but confirm it against PostHog's DPA rather than taking it from here.

This table, the list of events above, and `AnalyticsCatalog` must stay in step when the schema changes. `AnalyticsCatalogTest` and `AnalyticsEnumUsageTest` guard the code side; nothing guards the console form, so it is checked by hand at release.

## Questions

- Full policy: [PRIVACY.md](../PRIVACY.md)
- Contact: hi@avinash.im
- In the app: Settings → Your Data for the toggle, the event list, and the diagnostics report

> Contributing code that touches analytics? See [CONTRIBUTION.md](../CONTRIBUTION.md). Events use a closed schema. Put anything you need for debugging in the user-initiated diagnostics report, not in the automatic events.
