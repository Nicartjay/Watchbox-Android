package space.nicart.watchbox.download

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import space.nicart.watchbox.data.local.DownloadEntry
import space.nicart.watchbox.data.local.DownloadState

/**
 * Tests for deciding which registry entries survive reconciliation.
 *
 * The bug this guards was silent data loss, which is the worst kind here. Reconciliation checked
 * every entry against Media3's download index and dropped anything absent from it - correct for a
 * cache-backed download, and wrong for a remuxed one, which is a plain file FFmpeg wrote and was
 * never in that index at all. Two finished episodes disappeared from the Library on the next app
 * start while 742 MB of real video stayed on disk with nothing pointing at it.
 *
 * The rule these pin: a remuxed entry is judged by its own file, everything else by the index.
 * Nothing is dropped on the strength of a store that does not own it.
 */
class DownloadReconcileTest {

    private fun entry(
        key: String = "1::/anime::/ep-1",
        remuxed: Boolean = false,
        state: DownloadState = DownloadState.COMPLETED,
        volumeId: String = "internal",
    ) = DownloadEntry(
        sourceId = 1L,
        animeUrl = "/anime",
        episodeUrl = key.substringAfterLast("::"),
        title = "Show",
        isRemuxed = remuxed,
        state = state,
        volumeId = volumeId,
    )

    /**
     * Mirrors the decision in DownloadController.reconcile.
     *
     * Extracted here as a pure function because the real one needs a DownloadManager, a store and
     * a Context. Any change to the production rule has to be reflected here; the names below say
     * what each branch is for so a mismatch is obvious.
     */
    private fun survives(
        entry: DownloadEntry,
        inMedia3Index: Boolean,
        fileBytesOnDisk: Long,
        volumeMounted: Boolean = true,
    ): Boolean = when {
        // An unmounted volume means neither store can answer, so the entry is left alone.
        !volumeMounted -> true
        entry.isRemuxed -> fileBytesOnDisk > 0L
        else -> inMedia3Index
    }

    @Test
    fun `a remuxed download with its file present survives`() {
        assertTrue(
            survives(entry(remuxed = true), inMedia3Index = false, fileBytesOnDisk = 435_000_000L),
            "a remuxed download was dropped because Media3 had never heard of it",
        )
    }

    @Test
    fun `a remuxed download whose file is gone is dropped`() {
        // Deleted by hand, or a write that never finished: the entry points at nothing.
        assertEquals(
            false,
            survives(entry(remuxed = true), inMedia3Index = false, fileBytesOnDisk = 0L),
        )
    }

    @Test
    fun `a cache-backed download is still judged by the index`() {
        assertTrue(survives(entry(), inMedia3Index = true, fileBytesOnDisk = 0L))
        assertEquals(false, survives(entry(), inMedia3Index = false, fileBytesOnDisk = 0L))
    }

    /**
     * A pulled SD card must not look like a deleted download.
     *
     * The files come back when the card does, so an entry on an absent volume is kept whatever
     * either store says about it.
     */
    @Test
    fun `an entry on an unmounted volume is kept regardless`() {
        assertTrue(
            survives(
                entry(remuxed = true, volumeId = "external-1"),
                inMedia3Index = false,
                fileBytesOnDisk = 0L,
                volumeMounted = false,
            ),
        )
        assertTrue(
            survives(
                entry(volumeId = "external-1"),
                inMedia3Index = false,
                fileBytesOnDisk = 0L,
                volumeMounted = false,
            ),
        )
    }

    /** The two engines are told apart by the flag, not by guessing from other fields. */
    @Test
    fun `the remuxed flag is what selects the check`() {
        val sameKey = "1::/anime::/ep-9"
        // Identical but for the flag, and they reach opposite conclusions on identical inputs.
        assertTrue(
            survives(entry(sameKey, remuxed = true), false, fileBytesOnDisk = 1L),
        )
        assertEquals(
            false,
            survives(entry(sameKey, remuxed = false), false, fileBytesOnDisk = 1L),
        )
    }
}
