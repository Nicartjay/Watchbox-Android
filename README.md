# WatchBox for Android

A native Android anime client built with Kotlin and Jetpack Compose. Content comes
entirely from **user-installed Aniyomi-compatible extensions** — the app ships no
sources and hosts no media of its own.

The interface is a deliberate port of
[NuvioMobile](https://github.com/NuvioMedia/NuvioMobile)'s design system — same
tokens, typography, spacing, poster metrics, floating pill navigation and player
chrome.

---

## Features

- **Home** — hero pager with auto-advance and parallax, Continue Watching, My
  List, and one rail per installed source
- **Browse** — installed sources, each with paged Popular/Latest grids
- **Extensions** — install and remove extensions from a repository index, with
  load failures surfaced rather than hidden
- **Detail** — parallax hero with a multi-stop scrim, collapsing floating header,
  expanding action row, and the episode list
- **Player** — Media3/ExoPlayer with HLS + MP4, quality/subtitle/speed pickers,
  episode switcher, aspect-ratio cycling, tap and drag gestures, and a lock mode
- **Search** — debounced search across every installed source at once, grouped
  per source
- **Library** — My List, in-progress, and full history
- **Settings** — seven accent themes, AMOLED black, auto-play-next, repository
  URL, and an 18+ toggle

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

Everything has a working default, so no configuration is needed to build.

| Key | Default | Purpose |
|---|---|---|
| `WATCHBOX_REPO_URL` | yuzono/anime-repo | Default extension repository |
| `WATCHBOX_VERSION_NAME` | `1.0.0` | Version name |
| `WATCHBOX_VERSION_CODE` | `1` | Version code |

The repository URL is also editable at runtime in **Settings → Extension
repository**, so a shipped APK can be repointed without a rebuild.

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
- **No cast, no downloads, no tracker sync.**

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

Single-module Android app, no Kotlin Multiplatform. Plain layering with a
hand-rolled service locator (`AppContainer`) instead of Hilt or Koin.

```
app/src/main/kotlin/
├── eu/kanade/tachiyomi/          THE EXTENSION ABI — see above, do not rename
│   ├── animesource/              AnimeSource, AnimeHttpSource, models
│   └── network/                  NetworkHelper, Requests, interceptors
└── space/nicart/watchbox/
    ├── core/ui/                  Design tokens, theme, type scale
    ├── data/local/               DataStore: history, watchlist, settings
    ├── domain/                   UI models + AnimeRepository
    ├── extension/                Loader, classloader, repo index, installer
    └── ui/
        ├── components/           Poster cards, shelves, skeletons
        ├── navigation/           Routes + floating pill nav bar
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

## Tech stack

Kotlin 2.1 · Compose BOM 2025.05 · Material 3 · Navigation-Compose (typed
routes) · Media3 1.6 · Ktor 3.1 · Coil 2.7 · DataStore · kotlinx.serialization

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
