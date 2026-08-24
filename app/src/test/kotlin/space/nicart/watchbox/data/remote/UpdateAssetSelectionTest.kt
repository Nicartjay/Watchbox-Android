package space.nicart.watchbox.data.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for choosing the right APK out of a release.
 *
 * A release carries four APKs - two form factors times two architectures - and the updater
 * previously took the first `.apk` it found. On a television that means downloading the touch
 * build: the app installs, launches, and cannot be navigated with a remote to undo it. That
 * makes the wrong choice materially worse than no update at all, which is why the no-match case
 * returns null instead of falling back to something plausible.
 *
 * The architecture split exists because FFmpeg ships native libraries per ABI; installing the
 * wrong one fails at load rather than at install, so it has to be matched too.
 */
class UpdateAssetSelectionTest {

    private fun asset(name: String) = GithubAsset(
        name = name,
        downloadUrl = "https://example.invalid/$name",
        size = 1_000L,
    )

    // Pre-split naming, still published by older releases.
    private val mobile = asset("watchbox-3.0.0.apk")
    private val tv = asset("watchbox-3.0.0-tv.apk")

    // Current naming, one per form factor per architecture.
    private val mobileArm64 = asset("watchbox-4.0.0-arm64-v8a.apk")
    private val mobileArm32 = asset("watchbox-4.0.0-armeabi-v7a.apk")
    private val tvArm64 = asset("watchbox-4.0.0-tv-arm64-v8a.apk")
    private val tvArm32 = asset("watchbox-4.0.0-tv-armeabi-v7a.apk")

    private val arm64Device = listOf("arm64-v8a", "armeabi-v7a")
    private val arm32Device = listOf("armeabi-v7a")

    private fun pick(
        assets: List<GithubAsset>,
        formFactor: String,
        abis: List<String> = arm64Device,
    ) = UpdateChecker.selectApkAsset(assets, formFactor, abis)

    // ------------------------------------------------------- both present

    @Test
    fun `the tv build picks the tv apk`() {
        assertEquals(tv.name, pick(listOf(mobile, tv), "tv")?.name)
    }

    @Test
    fun `the mobile build picks the mobile apk`() {
        assertEquals(mobile.name, pick(listOf(mobile, tv), "mobile")?.name)
    }

    @Test
    fun `asset order does not decide the outcome`() {
        // The whole defect was order-dependence, so both orderings are pinned.
        assertEquals(tv.name, pick(listOf(tv, mobile), "tv")?.name)
        assertEquals(mobile.name, pick(listOf(tv, mobile), "mobile")?.name)
    }

    @Test
    fun `matching ignores case`() {
        val upper = asset("WatchBox-3.0.0-TV.APK")
        assertEquals(upper.name, pick(listOf(mobile, upper), "TV")?.name)
    }

    // ------------------------------------------------------ single apk

    @Test
    fun `a release with one apk is accepted by either build`() {
        // Releases published before the split carry a single APK; refusing it would
        // strand those installs on an older version with no way forward.
        assertEquals(mobile.name, pick(listOf(mobile), "tv")?.name)
        assertEquals(mobile.name, pick(listOf(mobile), "mobile")?.name)
    }

    // --------------------------------------------------------- no match

    @Test
    fun `several apks with no match yields nothing`() {
        // Better to report no update than to install a build for the wrong device.
        val other = asset("watchbox-3.0.0-wear.apk")
        assertNull(pick(listOf(tv, other), "mobile"))
    }

    @Test
    fun `an empty asset list yields nothing`() {
        assertNull(pick(emptyList(), "tv"))
    }

    @Test
    fun `non-apk assets are ignored`() {
        val notes = asset("release-notes.txt")
        val mapping = asset("mapping.txt")
        assertEquals(tv.name, pick(listOf(notes, mapping, tv), "tv")?.name)
    }

    @Test
    fun `a lone non-apk asset yields nothing`() {
        assertNull(pick(listOf(asset("mapping.txt")), "tv"))
    }

    // ------------------------------------------------------ name safety

    // ------------------------------------------------- architecture split

    private val allFour = listOf(mobileArm64, mobileArm32, tvArm64, tvArm32)

    @Test
    fun `each form factor and architecture picks its own apk`() {
        assertEquals(tvArm64.name, pick(allFour, "tv", arm64Device)?.name)
        assertEquals(tvArm32.name, pick(allFour, "tv", arm32Device)?.name)
        assertEquals(mobileArm64.name, pick(allFour, "mobile", arm64Device)?.name)
        assertEquals(mobileArm32.name, pick(allFour, "mobile", arm32Device)?.name)
    }

    /**
     * A 64-bit device runs the 32-bit build, so a release carrying only that is still offered.
     *
     * SUPPORTED_ABIS is ordered best-first, so this falls back rather than refusing.
     */
    @Test
    fun `a 64-bit device falls back to the 32-bit build`() {
        assertEquals(tvArm32.name, pick(listOf(tvArm32), "tv", arm64Device)?.name)
    }

    /** A 32-bit device must never be given the 64-bit build: it would fail to load. */
    @Test
    fun `a 32-bit device is not given the 64-bit build`() {
        assertNull(pick(listOf(tvArm64, mobileArm64), "tv", arm32Device))
    }

    /** Releases from before the split have no architecture in the name, and still install. */
    @Test
    fun `an unqualified release is still offered`() {
        assertEquals(tv.name, pick(listOf(mobile, tv), "tv", arm64Device)?.name)
    }

    /** With both namings present the architecture-specific one wins, being the exact match. */
    @Test
    fun `a qualified apk is preferred over an unqualified one`() {
        assertEquals(tvArm64.name, pick(listOf(tv, tvArm64), "tv", arm64Device)?.name)
    }

    // ------------------------------------------------------ name safety

    @Test
    fun `an apk with an unrecognised qualifier is refused`() {
        val tricky = asset("watchbox-3.0.0-tvshows.apk")

        // Behaviour change, and a deliberate one. This previously matched as the TV build,
        // because the check was a bare "contains -tv" - which the old test documented as a
        // consequence rather than defended as correct. It is now matched against the exact
        // naming, so an artifact nobody recognises is refused instead of installed.
        //
        // That is what the rest of this file already argues for: reporting no update beats
        // installing a build for the wrong device.
        assertNull(pick(listOf(mobile, tricky), "tv"))

        // The genuine phone APK is still found alongside it.
        assertEquals(mobile.name, pick(listOf(mobile, tricky), "mobile")?.name)
    }
}
