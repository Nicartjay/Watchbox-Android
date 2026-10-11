#!/usr/bin/env python3
"""
Verifies that the app still exposes the ABI Aniyomi anime extensions link against.

Extension APKs are compiled `compileOnly` against the Aniyomi source API and
bundle none of it, so this app *is* their runtime library. Every class name,
member name and signature below was extracted from real extension APKs in
github.com/yuzono/anime-repo with `dexdump`, which ships lib 14 and lib 16. Renaming or moving any of them
still compiles cleanly but fails at runtime with NoSuchMethodError the moment a
source is used, which is why this check exists as a build step rather than as a
comment.

Two traps this guards against, both of which were hit while writing the ABI:

  * Kotlin names the facade class for top-level functions after the file, so
    moving `rateLimitHost` out of SpecificHostRateLimitInterceptor.kt silently
    breaks it.
  * javap only lists *declared* members, so inherited ones must be resolved by
    walking the hierarchy in CHAIN below.

Usage:  python3 tools/verify-extension-abi.py     (after :app:compileMobileDebugKotlin)
"""
import os
import shutil
import subprocess
import sys

P = "eu.kanade.tachiyomi."

# The extension ABI is flavor-independent, so any debug variant will do. Checked in
# order rather than hardcoded: the mobile/tv split renamed the output directory from
# "debug" to "<flavor>Debug", and the old name survives locally as a stale leftover
# while a clean CI checkout only ever has the flavored ones - so hardcoding either
# name passes on one machine and fails on the other.
CP_CANDIDATES = (
    "app/build/tmp/kotlin-classes/mobileDebug",
    "app/build/tmp/kotlin-classes/tvDebug",
    "app/build/tmp/kotlin-classes/debug",
)


def find_classes():
    """First existing debug class directory, or None when nothing is compiled."""
    return next((path for path in CP_CANDIDATES if os.path.isdir(path)), None)


def works(candidate):
    """True when the binary is a real javap.

    macOS ships a /usr/bin/javap shim that exits non-zero with "Unable to locate
    a Java Runtime" when no JDK is installed. Taking the first match on PATH
    without checking made this script report every class as missing rather than
    failing loudly.
    """
    if not candidate or not os.path.exists(candidate):
        return False
    result = subprocess.run(
        [candidate, "-version"], capture_output=True, text=True,
    )
    return result.returncode == 0


def find_javap():
    """Locate a working javap: JAVA_HOME, then PATH, then a local toolchain.

    CI sets JAVA_HOME; a dev machine may only have an unpacked JDK. Hardcoding
    one path broke the first CI run, so all three are tried and each is verified.
    """
    candidates = []

    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidates.append(os.path.join(java_home, "bin", "javap"))

    candidates.append(shutil.which("javap"))

    local = os.path.expanduser("~/.local/jdk")
    if os.path.isdir(local):
        for entry in sorted(os.listdir(local), reverse=True):
            candidates.append(os.path.join(local, entry, "Contents/Home/bin/javap"))
            candidates.append(os.path.join(local, entry, "bin/javap"))

    for candidate in candidates:
        if works(candidate):
            return candidate

    sys.exit("No working javap found. Set JAVA_HOME to a JDK.")


JAVAP = find_javap()

CP = find_classes()

if CP is None:
    sys.exit(
        "No compiled debug classes found. Tried:\n  "
        + "\n  ".join(CP_CANDIDATES)
        + "\nRun :app:compileMobileDebugKotlin first."
    )

# Explicit chains: javap prints generic wildcards as "<? extends X>", so parsing
# the text for superclasses is unreliable. These are the real hierarchies.
CHAIN = {
 "animesource.online.ParsedAnimeHttpSource": ["animesource.online.AnimeHttpSource",
   "animesource.AnimeCatalogueSource","animesource.AnimeSource"],
 "animesource.online.AnimeHttpSource": ["animesource.AnimeCatalogueSource","animesource.AnimeSource"],
 "animesource.AnimeCatalogueSource": ["animesource.AnimeSource"],
 "animesource.ConfigurableAnimeSource": ["animesource.AnimeSource"],
}

def decl(cls):
    r=subprocess.run([JAVAP,"-p","-classpath",CP,P+cls],capture_output=True,text=True)
    return r.stdout if r.returncode==0 else None

def blob(cls):
    parts=[decl(cls)]
    if parts[0] is None: return None
    for sup in CHAIN.get(cls,[]):
        parts.append(decl(sup) or "")
    return "\n".join(parts)

CHECKS = [
 ("animesource.model.AnimeFilterList", ["isEmpty"]),
 ("animesource.model.AnimesPage", ["<init>"]),
 ("animesource.model.SAnimeImpl", ["getUrl","setUrl","setTitle","setThumbnail_url",
   "setDescription","setGenre","setStatus","setAuthor","setArtist","setInitialized",
   "setBackground_url","setUpdate_strategy","setFetch_type","getFetch_type",
   "setSeason_number","getSeason_number"]),
 ("animesource.model.SEpisodeImpl", ["getUrl","setUrl","getName","setName",
   "setDate_upload","setEpisode_number","getEpisode_number","setScanlator",
   "setFillermark","setSummary","setPreview_url"]),
 ("animesource.model.Track", ["<init>","getUrl","getLang"]),
 ("animesource.model.Video", ["getQuality","getUrl","getVideoUrl","getHeaders",
   "getSubtitleTracks","getAudioTracks","<init>",
   # Lib 16 made Video a data class; its extensions call copy$default and these getters.
   "getVideoTitle","getResolution","getBitrate","getPreferred","getTimestamps",
   "getMpvArgs","getFfmpegStreamArgs","getFfmpegVideoArgs","getInternalData",
   "getInitialized","copy$default"]),
 # Lib 16 models.
 ("animesource.model.Hoster", ["<init>","getHosterUrl","getHosterName","getVideoList",
   "getInternalData","getLazy","Companion"]),
 # Also called by every lib 14 extension, from a bundled getHosterList shim.
 ("animesource.model.Hoster$Companion", ["toHosterList"]),
 ("animesource.model.TimeStamp", ["<init>"]),
 ("animesource.model.ChapterType", ["Opening","Ending"]),
 ("animesource.model.FetchType", ["Seasons","Episodes"]),
 ("animesource.model.AnimeUpdateStrategy", ["ALWAYS_UPDATE","ONLY_FETCH_ONCE"]),
 ("animesource.online.AnimeHttpSource", ["getHeaders","getId","getNetwork",
   "headersBuilder","setUrlWithoutDomain","getBaseUrl","getClient","generateId",
   # Related-anime suggestions. Extensions were already overriding these while
   # AnimeHttpSource did not declare them, so the overrides were dead code and
   # suggestions silently never appeared.
   "relatedAnimeListRequest","relatedAnimeListParse","fetchRelatedAnimeList",
   "getSupportsRelatedAnimes","getDisableRelatedAnimes",
   "getDisableRelatedAnimesBySearch",
   # Lib 16 seasons and hosters. Overridden rather than called, so a missing one does not
   # fail to link - the override is simply never dispatched to.
   "getSeasonList","seasonListRequest","seasonListParse",
   "getHosterList","hosterListRequest","hosterListParse",
   "resolveVideo","sortHosters","sortVideos","getVideoUrl","videoUrlRequest"]),
 ("animesource.online.ParsedAnimeHttpSource", ["getHeaders","getNetwork",
   "headersBuilder","setUrlWithoutDomain","seasonListSelector","seasonFromElement"]),
 ("animesource.AnimeSource", ["getId","getName","getLang","getAnimeDetails",
   "getEpisodeList","getVideoList","fetchVideoList"]),
 ("animesource.AnimeCatalogueSource", ["getPopularAnime","getLatestUpdates",
   "getSearchAnime","getFilterList","getSupportsLatest"]),
 ("animesource.ConfigurableAnimeSource", ["setupPreferenceScreen","getSourcePreferences"]),
 ("animesource.AnimeSourceFactory", ["createSources"]),
 ("network.NetworkHelper", ["getClient","defaultUserAgentProvider","getCloudflareClient"]),
 ("network.OkHttpExtensionsKt", ["await","awaitSuccess","asObservable","asObservableSuccess"]),
 # get/post are the lib 16 suspend helpers, called through get$default.
 ("network.RequestsKt", ["GET","POST","get$default","post$default"]),
 ("network.HttpException", ["getCode"]),
 ("network.interceptor.SpecificHostRateLimitInterceptorKt", ["rateLimitHost"]),
 ("network.interceptor.RateLimitInterceptorKt", ["rateLimit"]),
 ("util.JsoupExtensionsKt", ["asJsoup"]),
]

fails=[]; total=0
for cls, ms in CHECKS:
    b=blob(cls)
    if b is None: fails.append(f"MISSING CLASS  {cls}"); continue
    for m in ms:
        total+=1
        needle = cls.split('.')[-1]+"(" if m=="<init>" else m
        if needle not in b: fails.append(f"MISSING MEMBER {cls}.{m}")

print(f"verified {total} ABI members across {len(CHECKS)} classes")
if fails:
    print("\n".join(fails)); sys.exit(1)
print("ALL PRESENT - ABI satisfies the surveyed lib-14 and lib-16 extensions")
