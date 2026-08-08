#!/usr/bin/env python3
"""
Verifies the extension ABI survived R8 in a *release* APK.

The compile-time check (tools/verify-extension-abi.py) is not sufficient on its
own. Nothing in this app statically references the `eu.kanade.tachiyomi` tree --
extension APKs link against it reflectively at runtime -- so R8 correctly
concludes it is unused and deletes it unless keep rules say otherwise.

That is not hypothetical: the first release build of this app shipped 4,861
classes and zero `eu.kanade.tachiyomi` ones. The debug build worked, so the
failure would only have appeared in the released APK, at the moment a user tapped
a source.

Renaming is equally fatal. Kotlin file-facade class names (RequestsKt,
OkHttpExtensionsKt, SpecificHostRateLimitInterceptorKt, JsoupExtensionsKt) and
member names are all part of the ABI.

Usage:  python3 tools/verify-release-abi.py <path-to-release.apk>
"""

import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

# Classes that must exist, with names unchanged, for extensions to link.
REQUIRED_CLASSES = [
    "animesource/AnimeSource",
    "animesource/AnimeCatalogueSource",
    "animesource/AnimeSourceFactory",
    "animesource/ConfigurableAnimeSource",
    "animesource/model/SAnime",
    "animesource/model/SAnimeImpl",
    "animesource/model/SEpisode",
    "animesource/model/SEpisodeImpl",
    "animesource/model/Video",
    "animesource/model/Track",
    "animesource/model/AnimesPage",
    "animesource/model/AnimeFilter",
    "animesource/model/AnimeFilterList",
    "animesource/online/AnimeHttpSource",
    "animesource/online/ParsedAnimeHttpSource",
    "network/NetworkHelper",
    # Kotlin file facades: the name comes from the filename, so moving a
    # top-level function to another file silently breaks callers.
    "network/RequestsKt",
    "network/OkHttpExtensionsKt",
    "network/interceptor/SpecificHostRateLimitInterceptorKt",
    "network/interceptor/RateLimitInterceptorKt",
    "util/JsoupExtensionsKt",
]

# Classes outside our own tree that extensions still resolve from the host by
# name. Both shipped regressions were here, not in eu.kanade.tachiyomi:
#   * Injekt's FullTypeReference needs its generic signature intact, or
#     Application.onCreate throws and the app dies on launch.
#   * kotlin.jvm.internal.MutablePropertyReference1Impl and friends are looked up
#     by name when an extension uses a property reference.
# Counts from dexdump across real extension APKs: kotlinx.serialization 2823
# references, kotlin.jvm 942, kotlin.coroutines 842, jsoup 356,
# androidx.preference 333, okhttp3 ~1500.
REQUIRED_EXTERNAL = [
    "kotlin/jvm/internal/Intrinsics",
    "kotlin/jvm/internal/DefaultConstructorMarker",
    "kotlin/jvm/internal/PropertyReference1Impl",
    "kotlin/jvm/internal/MutablePropertyReference1Impl",
    "kotlin/Pair",
    "kotlin/Lazy",
    "kotlinx/serialization/KSerializer",
    "kotlinx/serialization/json/Json",
    "okhttp3/OkHttpClient",
    "okhttp3/Request",
    "okhttp3/Response",
    "okhttp3/Headers",
    "okhttp3/HttpUrl",
    "okhttp3/CacheControl",
    "org/jsoup/Jsoup",
    "org/jsoup/nodes/Document",
    "org/jsoup/nodes/Element",
    "rx/Observable",
    "uy/kohesive/injekt/api/FullTypeReference",
    "androidx/preference/PreferenceScreen",
]

# Members extensions call or override, which must not be obfuscated.
REQUIRED_MEMBERS = {
    "animesource/online/AnimeHttpSource": [
        "popularAnimeRequest", "popularAnimeParse",
        "latestUpdatesRequest", "latestUpdatesParse",
        "searchAnimeRequest", "searchAnimeParse",
        "animeDetailsRequest", "animeDetailsParse",
        "episodeListRequest", "episodeListParse",
        "videoListRequest", "videoListParse",
        "setUrlWithoutDomain", "headersBuilder", "generateId",
    ],
}


def find_dexdump():
    """Locate dexdump from the newest installed build-tools.

    Versions sort lexically here, which is fine for the numeric directory names
    build-tools uses, but each candidate is verified because a partial SDK
    install can leave a version directory without the binary.
    """
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") \
        or os.path.expanduser("~/Library/Android/sdk")
    build_tools = os.path.join(sdk, "build-tools")
    if not os.path.isdir(build_tools):
        sys.exit(
            f"Android SDK build-tools not found under {sdk}. "
            "Set ANDROID_HOME."
        )

    for version in sorted(os.listdir(build_tools), reverse=True):
        candidate = os.path.join(build_tools, version, "dexdump")
        if os.path.exists(candidate) and os.access(candidate, os.X_OK):
            return candidate

    sys.exit(f"No dexdump binary found under {build_tools}")


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__.strip().splitlines()[-1])

    apk = sys.argv[1]
    if not os.path.exists(apk):
        sys.exit(f"APK not found: {apk}")

    dexdump = find_dexdump()
    workdir = tempfile.mkdtemp(prefix="relabi_")

    try:
        with zipfile.ZipFile(apk) as zf:
            dexes = [n for n in zf.namelist() if n.endswith(".dex")]
            if not dexes:
                sys.exit("APK contains no dex files")
            for name in dexes:
                zf.extract(name, workdir)

        dump = ""
        for name in dexes:
            dump += subprocess.run(
                [dexdump, "-f", os.path.join(workdir, name)],
                capture_output=True, text=True,
            ).stdout

        total = len(re.findall(r"Class descriptor", dump))
        kanade = len(re.findall(r"Leu/kanade/tachiyomi/", dump))
        print(f"release dex: {total} classes, {kanade} eu.kanade.tachiyomi references")

        failures = []

        for cls in REQUIRED_CLASSES:
            if f"Leu/kanade/tachiyomi/{cls};" not in dump:
                failures.append(f"STRIPPED OR RENAMED  eu.kanade.tachiyomi.{cls}")

        for cls in REQUIRED_EXTERNAL:
            if f"L{cls};" not in dump:
                failures.append(
                    f"STRIPPED OR RENAMED  {cls.replace('/', '.')}"
                )

        for cls, members in REQUIRED_MEMBERS.items():
            block = re.search(
                r"Class descriptor\s*:\s*'Leu/kanade/tachiyomi/" + re.escape(cls)
                + r";'(.*?)source_file",
                dump, re.S,
            )
            if not block:
                continue  # already reported as a missing class
            body = block.group(1)
            for member in members:
                if f"'{member}'" not in body:
                    failures.append(f"OBFUSCATED MEMBER    {cls}.{member}")

        if failures:
            print()
            print("\n".join(failures))
            print()
            print("Check the extension-ABI keep rules in app/proguard-rules.pro.")
            sys.exit(1)

        print(f"verified {len(REQUIRED_CLASSES)} ABI classes, "
              f"{len(REQUIRED_EXTERNAL)} host-provided library classes and "
              f"{sum(len(m) for m in REQUIRED_MEMBERS.values())} members")
        print("ABI survived minification")

    finally:
        shutil.rmtree(workdir, ignore_errors=True)


if __name__ == "__main__":
    main()
