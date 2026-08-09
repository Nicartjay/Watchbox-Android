# WatchBox for Android

A native Android anime client built with Kotlin and Jetpack Compose. Content comes
entirely from **user-installed Aniyomi-compatible extensions** — the app ships no
sources, no extension repository, and hosts no media of its own.

The interface is a deliberate port of
[NuvioMobile](https://github.com/NuvioMedia/NuvioMobile)'s design system — same
tokens, typography, spacing, poster metrics, floating pill navigation and player
chrome.

---

## Features

- **Home** — hero pager with auto-advance and parallax, Continue Watching, My
  List, and one rail per installed source
- **Browse** — a grid of installed sources led by their extension icons, each
  opening paged Popular/Latest grids with per-source search and the source's own
  filters
- **Extensions** — multiple repositories, each independently switchable; search
  and filter by language, adult content and repository; per-extension settings for
  extensions that expose them; load failures surfaced rather than hidden
- **Detail** — parallax hero with a multi-stop scrim, collapsing floating header,
  expanding action row, and the episode list
- **Player** — Media3/ExoPlayer with HLS + MP4, quality/subtitle/speed pickers,
  episode switcher, aspect-ratio cycling, tap and drag gestures, brightness and
  volume swipes, and a lock mode
- **Casting** — Chromecast and DLNA in one device list, with a local
  header-injecting proxy for streams whose CDN requires a `Referer`, plus a
  hand-off to Web Video Caster for receivers this app does not speak
- **Subtitles** — size, background style (none, outline, drop shadow, box,
  full-width band), outline/shadow width, colour and opacity, adjustable from
  Settings or from inside the player
- **Search** — debounced search across every installed source at once, grouped
  per source, or narrowed to a single source
- **Library** — My List, in-progress, and full history
- **Settings** — seven accent themes, AMOLED black, auto-play-next, repository
  management, subtitle appearance, and an 18+ toggle

### Android TV

A separate build with its own UI, not the phone layout stretched:

- **Leanback launcher entry** with a banner, so it appears on the TV home screen
- **Left navigation rail** that expands on focus, replacing the bottom pill — which
  sat inside the overscan region a television can physically crop
- **Backdrop that follows focus**, using TMDB backdrops and title logos; landscape
  16:9 cards rather than portrait posters
- **Full D-pad navigation**, with visible focus at three-metre viewing distance
- **Remote playback control** — directional seek, media transport keys, and Back
  that hides the controls before leaving
- **Voice search**, because typing a title with a remote is nobody's preference

### Tablet

- A **navigation rail** above 1000dp instead of the bottom bar, since the bottom
  edge is the hardest place to reach two-handed in landscape
- Column counts and padding scale with width from one shared definition

## Install

Two APKs per release, one per form factor:

| File | For |
|---|---|
| `watchbox-<version>.apk` | Phones and tablets |
| `watchbox-<version>-tv.apk` | Android TV, Google TV, TV boxes |

Grab them from [Releases](../../releases/latest), or download the debug artifacts
from any [CI run](../../actions/workflows/ci.yml).

The two use different package names, so both can be installed on one device - useful
for testing the TV UI on a tablet. The in-app updater picks the matching APK by
filename, so renaming release assets will send devices the wrong build.

`minSdk` is 24 (Android 7.0); `targetSdk` is 36.

### First run

**The app ships with no extension repository.** Bundling one would decide on your
behalf which third-party index the app fetches from, so you add your own:

- Open an `aniyomi://add-repo?url=...` link — repositories advertise themselves
  this way, and the app adds them automatically, or
- paste the URL under **Settings → Extension repositories**.

Both the repository root and a direct link to its `index.min.json` are accepted;
they normalise to the same entry.

## Build

Requires **JDK 17** and the Android SDK (platform 36, build-tools 36.0.0).

```bash
git clone https://github.com/Nicartjay/Watchbox-Android.git
cd Watchbox-Android

# Point Gradle at your SDK
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties

# Phone/tablet, or :app:assembleTvDebug for Android TV
./gradlew :app:assembleMobileDebug
```

The APKs land in `app/build/outputs/apk/mobile/debug/` and
`app/build/outputs/apk/tv/debug/`.

### Configuration

Everything has a working default, so no configuration is needed to build.

| Key | Default | Purpose |
|---|---|---|
| `WATCHBOX_REPO_URL` | yuzono/anime-repo | Seed value for `BuildConfig.DEFAULT_REPO_URL` |
| `WATCHBOX_VERSION_NAME` | current version in `app/build.gradle.kts` | Version name |
| `WATCHBOX_VERSION_CODE` | `1` locally; CI run number in releases | Version code |
| `TMDB_API_KEY` | a working shared key | Artwork and metadata enrichment |

`WATCHBOX_REPO_URL` no longer pre-configures a repository — nothing reads it at
runtime any more, since repositories are added by the user. It survives as a build
constant for forks that want to hardcode one.

## How extensions work

This is the part worth understanding before changing anything.

Aniyomi-family extension APKs are compiled `compileOnly` against the Aniyomi
source API and **bundle none of it**. Disassembling one shows a single class:

```
AnimePahe  extends    eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
           implements eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
```

Neither of those is in the APK. They are resolved at runtime from the host
application — so **this app is the extension runtime library**. That has three
consequences:

1. **The `eu.kanade.tachiyomi.*` tree under `app/src/main/kotlin/` is a fixed
   ABI.** Class names, member names, signatures and even Kotlin file-facade names
   (`RequestsKt`, `OkHttpExtensionsKt`) are load-bearing. Renaming any of them
   still compiles and then fails at runtime with `NoSuchMethodError`.

2. **Dependency versions are constraints, not preferences.** rxjava 1.3.8,
   okhttp 5.3.2, jsoup 1.22.1 and `androidx.preference` are what the extensions
   were compiled against.

3. **R8 must be told to keep all of it.** Nothing in this app references the tree
   statically, so R8 deletes it by default. That is not theoretical — the first
   release build shipped 4,861 classes and zero `eu.kanade.tachiyomi` ones while
   the debug build worked fine.

Two checks guard this, both wired into CI:

```bash
python3 tools/verify-extension-abi.py                    # compiled classes
python3 tools/verify-release-abi.py <release.apk>        # after minification
```

The published `aniyomi-extensions-lib` artifact cannot be used here: every method
body in it is `throw Exception("Stub!")`, in the same way `android.jar` is a
compile-only facade. The ABI in this repo is an independent implementation written
against signatures extracted from real extension APKs with `dexdump`.

### Supported extension API

Library versions **12–15**, which covers every extension in the default
repository (all report 14). Lib 16 is deliberately rejected: it made
`getSeasonList` abstract and replaced the video contract with `Hoster`, so a 16
extension would call members this app does not implement.

### Known limitations

- **Cloudflare-protected sources will not work.** Solving those needs a WebView to
  run the JS challenge. `cloudflareClient` exists for ABI compatibility but is
  not a solver, so affected sources fail rather than hang.
- **Extensions are private to this app.** They are stored in internal storage
  rather than installed system-wide, which avoids needing
  `REQUEST_INSTALL_PACKAGES` and `QUERY_ALL_PACKAGES` but means they are not
  shared with other Aniyomi clients.
- **No downloads and no tracker sync.**

## Casting

Two protocols, listed together in one picker:

- **Chromecast**, via the Cast SDK and `MediaRouter`, using Google's Default Media
  Receiver (`CC1AD845`).
- **DLNA/UPnP**, via SSDP discovery and SOAP AVTransport.

Casting is **pull-based**: you hand the receiver a URL and it opens its own
connection. Neither Cast's `LOAD` nor DLNA's `SetAVTransportURI` carries request
headers, so a receiver cannot send the `Referer` that extension CDNs require. A
local proxy therefore relays those streams — the TV fetches from your phone, and
your phone fetches upstream with the headers. Streams needing no headers skip the
proxy entirely, keeping the phone out of the data path.

For HLS the manifest is rewritten as it passes through, because a receiver fetches
segments, variant playlists and encryption keys itself; proxying only the manifest
fixes nothing.

Three details are easy to get wrong and each one silently returns zero devices:

- **`NEARBY_WIFI_DEVICES` is required on Android 13+.** Without it the router
  reports no routes at all. It is requested when the cast panel opens.
- **A multicast lock is required for SSDP.** Android's Wi-Fi driver filters
  multicast in hardware without one, and SSDP is entirely multicast.
- **The SSDP socket must join the multicast group.** Several Samsung and LG models
  reply to the group rather than to the requester, so a plain `DatagramSocket`
  never sees them.

Two caveats worth knowing before reporting a bug:

- **Most DLNA TVs cannot play HLS at all.** That is a receiver limitation — those
  sources generally need Chromecast, or the Web Video Caster hand-off.
- **Seeking is limited on proxied HLS,** because the proxy deliberately does not
  advertise byte-range support for rewritten manifests.

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
always increases, which Android requires for in-place upgrades — releases so far
run 16, 17, 18 against version names 2.7.0, 2.8.0, 2.9.0. A local build defaults
to `1`, so a locally built APK will not install over a released one.

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

Single-module Android app, no Kotlin Multiplatform. Plain layering with a
hand-rolled service locator (`AppContainer`) instead of Hilt or Koin.

```
app/src/
├── main/kotlin/                  Shared by both builds
├── mobile/kotlin/                Phone/tablet entry point
└── tv/kotlin/                    TV screens + leanback manifest and banner

app/src/main/kotlin/
├── eu/kanade/tachiyomi/          THE EXTENSION ABI — see above, do not rename
│   ├── animesource/              AnimeSource, AnimeHttpSource, models
│   └── network/                  NetworkHelper, Requests, interceptors
└── space/nicart/watchbox/
    ├── cast/                     Chromecast + DLNA transports, discovery, proxy
    ├── core/ui/                  Design tokens, theme, type scale
    ├── data/local/               DataStore: history, watchlist, settings, repos
    ├── domain/                   UI models + AnimeRepository
    ├── extension/                Loader, classloader, repo index, installer
    └── ui/
        ├── components/           Poster cards, shelves, skeletons, search field
        ├── navigation/           Routes + floating pill nav bar
        ├── source/               Per-extension settings bridge
        └── home/ browse/ detail/ player/ search/ library/ settings/ extensions/
```

### Notable choices

- **Parent-last classloading.** Extensions bundle their own copies of common
  libraries, so their dex is searched before the host's — with a parent-first
  fallback on `LinkageError`, since a few only link that way.
- **Every extension call is guarded.** Third-party code runs in-process, and it
  is linked at runtime, so failures arrive as `NoSuchMethodError` rather than
  `Exception`. One bad source degrades to an empty rail instead of taking down
  the feed.
- **Per-source search results.** Relevance is not comparable across sources, so
  merging would bury good matches.
- **Identity is `sourceId` + source-relative `url`.** There is no global id in
  this ecosystem, and titles change between fetches.
- **Repository fetches report per-repository failures.** With several configured,
  one unreachable repo must not hide the extensions the others listed — and
  "not in any repo" is only concluded when every repository actually answered.
- **Subtitles are drawn in Compose, not by Media3's `SubtitleView`.**
  `SubtitlePainter` hardcodes the outline to 2dp and `CaptionStyleCompat` exposes
  no width, so an outline-width setting is impossible without rendering the cues.
- **Two flavors rather than one combined APK.** A single build carrying both
  `LAUNCHER` and `LEANBACK_LAUNCHER` works, but it ships the TV UI to every phone and
  makes the two impossible to install side by side for testing.
- **Form factor is tracked separately from width.** A 1080p television and a 1080p
  tablet report near-identical dp widths yet need opposite treatments: the TV is read
  at three metres with a D-pad and needs *fewer, larger* targets. Sizing off width
  alone gets the TV wrong every time.
- **Focus affordance lives in the shared components,** gated on form factor. On a
  touchscreen the finger is the cursor, so an outline left after a tap reads as a
  rendering fault; on a TV, invisible focus is unusable.

### Testing

143 unit tests, run in CI. They deliberately cover only pure logic whose failures
are *silent* on a device — HLS URI rewriting, DLNA SOAP envelopes, gesture maths,
filter application, deep-link parsing, subtitle style values — because those break
in ways that look like missing data rather than errors. Anything better checked by
looking at the screen is not unit-tested.

```bash
./gradlew :app:testMobileDebugUnitTest
```

Flavor-aware task names. `assembleRelease` no longer exists; use
`assembleMobileRelease` and `assembleTvRelease`, or `assembleMobileDebug` /
`assembleTvDebug`.

## Tech stack

Kotlin 2.1 · Compose BOM 2025.05 · Material 3 · Navigation-Compose (typed
routes) · Media3 1.6 · Ktor 3.1 · Coil 2.7 · DataStore · kotlinx.serialization ·
play-services-cast 22 · androidx.mediarouter 1.7

Extension runtime: okhttp 5.3.2 · rxjava 1.3.8 · jsoup 1.22.1 · Injekt ·
androidx.preference

## Legal

WatchBox is a client interface. It ships no sources and does not host, store or
distribute any content. All content is provided by extensions the user chooses to
install, and the app is not affiliated with those extensions or the sites they
read from.

Installing an extension runs third-party code inside this app's process, with its
network access. Only install extensions from repositories you trust.

## Credits

The design system — colour tokens, spacing scale, typography, poster metrics,
navigation pill and player chrome — is ported from
[NuvioMobile](https://github.com/NuvioMedia/NuvioMobile) (GPL-3.0). Typeface is
JetBrains Sans (Apache-2.0).

The extension ABI reproduces the interface defined by
[Aniyomi](https://github.com/aniyomiorg/aniyomi) (Apache-2.0) so that existing
extensions can link against it. The implementation is this project's own, written
against signatures observed in published extension APKs; no Aniyomi source was
copied.
