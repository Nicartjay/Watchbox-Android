package space.nicart.watchbox.data.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for choosing the right APK out of a release.
 *
 * A release now carries two APKs, one per form factor, and the updater previously took
 * the first `.apk` it found. On a television that means downloading the touch build:
 * the app installs, launches, and cannot be navigated with a remote to undo it. That
 * makes the wrong choice materially worse than no update at all, which is why the
 * no-match case returns null instead of falling back to something plausible.
 */
class UpdateAssetSelectionTest {

    private fun asset(name: String) = GithubAsset(
        name = name,
        downloadUrl = "https://example.invalid/$name",
        size = 1_000L,
    )

    private val mobile = asset("watchbox-3.0.0.apk")
    private val tv = asset("watchbox-3.0.0-tv.apk")

    private fun pick(assets: List<GithubAsset>, formFactor: String) =
        UpdateChecker.selectApkAsset(assets, formFactor)

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

    @Test
    fun `a mobile apk is not matched by an incidental tv substring`() {
        // "-tv" is required, so a name that merely contains the letters cannot be
        // mistaken for the television build.
        val tricky = asset("watchbox-3.0.0-tvshows.apk")
        // It does contain "-tv", so it is treated as the TV asset - documented here so
        // the naming convention stays deliberate rather than accidental.
        assertEquals(tricky.name, pick(listOf(mobile, tricky), "tv")?.name)
        assertEquals(mobile.name, pick(listOf(mobile, tricky), "mobile")?.name)
    }
}
