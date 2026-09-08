# Changelog

All notable changes to Riplox IG are documented here.
Versioning: **MAJOR.MINOR.PATCH** — new features bump MINOR, fixes bump PATCH.

## [1.2.0] — 2026-09-08

A reason to come back, two hours without ads, an update notice, and the full-screen ad that never showed at all.

### Added
- **Daily check-in streak.** Open Riplox IG, tap **Check in**, and your streak grows. Accent colours unlock as it does — **Ocean** at 3 days, **Neon** at 7, **Ember** at 14, **Aurora** at 30 — and a 7-day streak pays a **whole ad-free day**, with nothing to watch for it.
- **Two hours with no ads, whenever you want them.** Watch one short ad and the app goes quiet — banner and full-screen both — for two hours, and more time is added on top if you watch again, up to a day. Nothing that used to be free has been put behind it.
- **You will be told when a new version is out.** The update downloads quietly in the background and a card offers to restart and finish it. Riplox IG had no update notice at all before this.

### Fixed
- **The full-screen ad never appeared.** It was fetched on app open and on every share — moments when it could not be shown — and then expired. Over one measured week the app fetched 18 and showed **none**. It is now fetched one download before it is due, and dropped if it goes stale.

## [1.1.0] — 2026-09-03

Photos, a second way to download everything, and a share sheet you can actually see.

### Added

- **Photo posts download.** The long-standing gap is closed: single photos, and the picture
  slides inside a mixed carousel, now save to `Pictures/RiploxIG` alongside the videos.
- **A second, independent route.** Until now every download went through one engine, so the day
  that engine fell behind Instagram, *everything* failed at once and there was nothing to do but
  wait. Riplox IG now asks Instagram directly first, using your own login, and only falls back
  to the engine if that does not work out. Two doors instead of one.
- **Downloads are noticeably faster.** The direct route needs one request rather than starting
  a whole extraction engine, and the post's details are now fetched once and shared instead of
  being looked up a second time just to draw the notification.
- **Interrupted downloads resume.** Losing signal partway through a large reel used to mean
  starting again from zero; it now picks up where it stopped, and retries on its own.
- **A short or truncated file is now caught** instead of being saved and reported as done —
  that was the cause of the occasional file that saved "successfully" but would not play.

### Fixed

- **The bottom banner no longer disappears for the rest of the session.** When there was no ad
  available at that moment, the app asked once and never asked again, so the strip stayed empty
  until the app was reopened. It now asks again on its own, backing off so it never nags, and
  the banner comes back by itself. It also sizes itself to the screen properly and stops
  refreshing while the app is in the background.

- **Instant download sheet.** Sharing a reel from Instagram used to fire a toast and vanish. It now opens a small sheet with the post's thumbnail, a live progress bar and a **Cancel** button — so a carousel that expands into six videos finally shows what it is doing and when it has finished. Dismissing it (Back, or a tap outside) leaves the download running in the background exactly as before.
- **A real error, with a way out of it.** A failed share now shows the actual reason plus **Retry** and **Update & retry** — the first one-tap engine refresh this app has ever had. A share-only user previously had no route to fix anything.
- **Open / Share straight from the sheet** once the file has landed.
- **Engine status in About** — which yt-dlp build you are on, when it was last checked, and whether that check actually worked. Plus **Update engine now**.
- **A privacy form for users in the EEA, the UK and Switzerland**, via Google's User Messaging Platform.

### Fixed

- **The engine never updated for share-sheet users.** The once-a-day refresh only ran on the home screen, so anyone who always downloaded from Instagram's share menu kept a months-old engine. On Instagram that is fatal rather than cosmetic — every download fails with "no video formats". It now runs from both entry points.
- **A failed engine update looked identical to a successful one.** The result was swallowed by an empty catch block; it is now recorded and shown.
- **Self-heal could be switched off by a reworded error.** The list of "this looks like a stale engine" strings existed in two places and only one of them was maintained. There is now one list.

### Changed

- Ad unit IDs and the AdMob app ID come from the build type. A `qa` build carries Google's test IDs and installs alongside the Play copy, so on-device testing can never touch the real ad units.
- A hard 60-second floor between full-screen ads, on top of the existing every-second-download rule. One carousel link finishing six downloads at once could otherwise have fired two interstitials back to back.

## [1.0.1] — 2026-07-25

First public release. A focused, Instagram-only companion to [Riplox](https://github.com/xniperbuilds/riplox) and [Riplox TT](https://github.com/xniperbuilds/riplox-tt).

### Highlights

- **Reels, video posts and IGTV** — paste an Instagram link or share one from the Instagram app
- **Carousels** — a multi-item post downloads every video it contains in one go
- **Stories** — from an account you can already see
- **Quality selector** — Best / 1080p / 720p / 480p, or **MP3** audio extraction (your choice is remembered)
- **Connect Instagram (optional)** — sign in once for private posts and stories. You sign in on Instagram's own page; the app never sees or stores your password, and the session stays in the app's private storage, excluded from cloud backup and device transfer
- **"Use my login" switch** — turns the login off for downloads without logging you out
- **Zero-tap share target** — share from Instagram and the download starts on its own
- **Rock-solid background downloads** — live progress notification (cancel anytime), survives app-close and reboot, auto-retry with backoff, and a stall-watchdog that recovers stuck downloads by itself
- **One-time "Fix background downloads" guide** — stops aggressive phones (XOS/HiOS/MIUI and similar) from freezing downloads in the background
- **Clear finishing states** — "Finishing — merging & saving…" and a live "Saving to gallery… %" so large downloads never look stuck
- **Failed downloads show Copy link + Retry** — inline on the home screen and as a notification action
- **Recent list** — tap to play, copy link, download again, or delete
- Saves to your gallery: `Movies/RiploxIG` (video), `Music/RiploxIG` (audio), `Pictures/RiploxIG` (images)
- Self-updating engine on the nightly channel, so links keep working as Instagram changes
- AMOLED-black Material 3 UI

### Fixed since the first internal build

- **Downloads that failed for every link while signed in.** Instagram sometimes answers a logged-in request with an empty body (a stale or soft-limited session), which made the extractor fail on posts that worked fine signed out. The app now detects that specific failure and retries the same link once as a guest, so a bad session no longer kills every download.
- **Downloads that hung at "finishing".** Thumbnail and metadata embedding was removed from the pipeline — that post-processing step could hang on Android, leaving a completed download stuck at 100%. Files save exactly as before, just without cover art and file tags.
- **Links that failed on a fresh install.** A stale bundled engine returned "no video formats" on a first run. The first such failure now updates the engine on the nightly channel and retries automatically, so a day-one install heals itself.

### Known limitation

- **Photo-only posts do not download yet.** The engine does not expose Instagram's still images as a downloadable format, so a post containing no video ends with a clear "photo post — videos and reels for now" message instead of a raw error. Mixed carousels still save the videos they contain. A dedicated image extractor is planned.

### Note on this build

The released APK includes the AdMob SDK (a banner, plus an interstitial after every second completed download). There is no analytics and no tracking of what you download.
