# Changelog

All notable changes to Riplox IG are documented here.
Versioning: **MAJOR.MINOR.PATCH** — new features bump MINOR, fixes bump PATCH.

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
