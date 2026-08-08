# WatchBox for Android

A native Android client for [WatchBox](https://watchbox.nicart.space), built with
Kotlin and Jetpack Compose. Browse movies and TV series, track progress, and
stream with a full-featured player.

The interface is a deliberate port of
[NuvioMobile](https://github.com/NuvioMedia/NuvioMobile)'s design system — same
tokens, typography, spacing, poster metrics, floating pill navigation and player
chrome.

---

## Features

- **Home** — cinematic hero pager with auto-advance and parallax, Continue
  Watching, My List, and the API's own content rows
- **Detail** — full-bleed backdrop with a multi-stop scrim, collapsing floating
  header, expanding action row, season selector, and horizontal episode cards
- **Player** — Media3/ExoPlayer with HLS + MP4, quality/subtitle/audio/speed
  pickers, episode switcher, skip intro/outro, aspect-ratio cycling, tap and
  drag gestures, and a lock mode
- **Search** — debounced live search with type filters and recent terms
- **Library** — My List, in-progress, and full history in one grid
- **Settings** — seven accent themes, AMOLED black, auto-play-next, and an
  editable backend URL

Content metadata comes from the ONEROOM API; backdrops, logos, cast and episode
stills are enriched from TMDB. Streams resolve through the WatchBox Cloudflare
Worker, which injects the correct upstream `Referer` server-side.

## Install

Grab the APK from [Releases](../../releases/latest), or download the debug build
artifact from any [CI run](../../actions/workflows/ci.yml).

`minSdk` is 24 (Android 7.0); `targetSdk` is 36.

## Build

Requires **JDK 17** and the Android SDK (platform 36, build-tools 36.0.0).

```bash
git clone https://github.com/Nicartjay/Watchbox-Android.git
cd Watchbox-Android

# Point Gradle at your SDK
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties

./gradlew :app:assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`.

### Configuration

Both values have working defaults, so no configuration is needed to build. Set
either in `local.properties` or as an environment variable to override:

| Key | Default | Purpose |
|---|---|---|
| `WATCHBOX_API_BASE_URL` | `https://watchbox.nicart.space` | Backend Worker base URL |
| `TMDB_API_KEY` | bundled public key | TMDB v3 API key |
| `WATCHBOX_VERSION_NAME` | `1.0.0` | Version name |
| `WATCHBOX_VERSION_CODE` | `1` | Version code |

The backend URL is also editable at runtime in **Settings → Server URL**, so a
shipped APK can be repointed without a rebuild.
`https://watchbox.nicartjay.workers.dev` works as an alternative.

## Releasing

`.github/workflows/release.yml` builds a minified release APK and publishes it.
Run it from the **Actions** tab with one of three modes:

| Mode | Effect |
|---|---|
| `dry-run` | Build only; APK uploaded as a workflow artifact |
| `draft` | Build and create a **draft** GitHub Release |
| `publish` | Build and publish the Release |

Pushing a `v*` tag (e.g. `v1.1.0`) publishes automatically and takes the version
from the tag name. `versionCode` is derived from the workflow run number so it
always increases, which Android requires for in-place upgrades.

### Signing

The release keystore (`release.jks`) is **committed to this repo**, and its
passwords are below:

| Field | Value |
|---|---|
| Store / key password | `watchbox` |
| Alias | `watchbox` |
| Key | RSA 4096, valid until 2056 |

That is deliberate, and the reason is upgrade compatibility rather than secrecy.
Android refuses to install an update whose signature differs from the installed
copy, and a debug-key fallback cannot work in CI because every runner is a fresh
VM that generates its own debug key — two consecutive release builds would be
signed with different keys and neither could update the other.

The trade-off is that anyone can build an APK that Android treats as an update to
this one. That is fine for a personal build. If you ever distribute this more
widely, move the key into CI secrets:

```bash
keytool -genkeypair -v -keystore release.jks -storetype PKCS12 \
  -keyalg RSA -keysize 4096 -validity 10950 -alias watchbox

base64 -i release.jks | pbcopy   # paste into WATCHBOX_KEYSTORE_BASE64
```

Then set `WATCHBOX_KEYSTORE_BASE64`, `WATCHBOX_KEYSTORE_PASSWORD`,
`WATCHBOX_KEY_ALIAS` and `WATCHBOX_KEY_PASSWORD` as repository secrets — when all
four are present they override the committed keystore, no code change needed.
Be aware that changing keys breaks in-place upgrades for existing installs.

The workflow fails the build if an APK ends up debug-signed, so a dead-end
release cannot be published by accident.

## Architecture

Single-module Android app, no Kotlin Multiplatform. Plain ES-style layering with
a hand-rolled service locator (`AppContainer`) instead of Hilt or Koin, since the
graph is small.

```
app/src/main/kotlin/space/nicart/watchbox/
├── core/
│   ├── network/     Ktor clients, AOneRoom auth (MD5 token -> Bearer JWT)
│   └── ui/          Design tokens, theme, type scale
├── data/
│   ├── local/       DataStore: history, watchlist, settings, token
│   ├── model/       API DTOs
│   ├── remote/      ONEROOM, TMDB, WatchBox Worker
│   └── source/      Native stream resolvers (6 providers, one implementation)
├── domain/          UI models + MediaRepository
└── ui/
    ├── components/  Poster cards, shelves, skeletons, shared widgets
    ├── navigation/  Routes + floating pill nav bar
    ├── home/ detail/ player/ search/ library/ settings/
```

### Notable choices

- **Stream resolution has real failure detection.** The six native providers are
  tried in an explicit order and each failure advances the chain. The web client
  cannot do this for its iframe servers, so a dead embed there stays broken.
- **One watch-history definition.** Storage key, schema and read/write path exist
  once. Progress is always a `0f..1f` fraction with `positionMs` authoritative.
- **One resolver implementation** replaces six near-identical adapters.
- **Concurrent TMDB lookups are de-duplicated** by the request cache.

## Tech stack

Kotlin 2.1 · Compose BOM 2025.05 · Material 3 · Navigation-Compose (typed
routes) · Media3 1.6 · Ktor 3.1 · Coil 2.7 · DataStore · kotlinx.serialization

## Legal

WatchBox is a client interface for browsing metadata and playing media from
user-configured sources. It does not host, store or distribute any content, and
is not affiliated with any third-party provider.

## Credits

The design system — colour tokens, spacing scale, typography, poster metrics,
navigation pill and player chrome — is ported from
[NuvioMobile](https://github.com/NuvioMedia/NuvioMobile) (GPL-3.0). Typeface is
JetBrains Sans (Apache-2.0).
