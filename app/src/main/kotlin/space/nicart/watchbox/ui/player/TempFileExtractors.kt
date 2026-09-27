package space.nicart.watchbox.ui.player

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.MatroskaExtractor

/**
 * Extractors for a "no seek" stream played from its growing temp file.
 *
 * Matroska keeps its seek index (Cues) at the end of most releases. Jumping there before the
 * first frame would wait for the whole download, so that jump is disabled - and without the
 * index the extractor declares the file unseekable, which sends every seek back to zero.
 *
 * This wraps the extractor so an unseekable map is replaced by one that estimates the byte
 * offset from time, assuming an even bitrate across the file (as a constant-bitrate MP3 would).
 * The Matroska reader resynchronises on the next cluster after such a jump, so a seek lands a
 * little either side of the target - fine for a player whose seeks are limited to what has
 * downloaded anyway.
 */
@UnstableApi
internal class TempFileExtractorsFactory(
    /** The file's full size in bytes, or a non-positive value while unknown. */
    private val totalBytes: () -> Long,
) : ExtractorsFactory {

    private val delegate = DefaultExtractorsFactory()
        .setMatroskaExtractorFlags(MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES)

    override fun createExtractors(): Array<Extractor> =
        delegate.createExtractors().map { EstimatedSeekExtractor(it, totalBytes) }.toTypedArray()
}

@UnstableApi
private class EstimatedSeekExtractor(
    private val inner: Extractor,
    private val totalBytes: () -> Long,
) : Extractor {

    /**
     * Set after a jump to an estimated offset, which lands mid-cluster. Matroska would read
     * whatever follows as an element header and blow up on a garbage size, so the input is
     * first advanced to the next Cluster id and parsing resumes from there.
     */
    private var resyncPending = false

    override fun sniff(input: ExtractorInput): Boolean = inner.sniff(input)

    override fun init(output: ExtractorOutput) {
        inner.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = output.track(id, type)
            override fun endTracks() = output.endTracks()
            override fun seekMap(seekMap: SeekMap) {
                val size = totalBytes()
                val durationUs = seekMap.durationUs
                output.seekMap(
                    if (!seekMap.isSeekable && size > 0 && durationUs != C.TIME_UNSET && durationUs > 0) {
                        EvenBitrateSeekMap(durationUs, size)
                    } else {
                        seekMap
                    },
                )
            }
        })
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (resyncPending) {
            if (!skipToNextCluster(input)) return Extractor.RESULT_END_OF_INPUT
            resyncPending = false
        }
        return inner.read(input, seekPosition)
    }

    override fun seek(position: Long, timeUs: Long) {
        // A seek to 0 is a genuine element boundary; anything else is our estimate.
        resyncPending = position > 0
        inner.seek(position, timeUs)
    }

    /**
     * Skips input up to (not past) the next Matroska Cluster id `1F 43 B6 75`, scanning in
     * blocks so a long gap is not walked one byte at a time. Clusters are a few MB apart.
     */
    private fun skipToNextCluster(input: ExtractorInput): Boolean {
        val block = ByteArray(SCAN_BLOCK)
        while (true) {
            input.resetPeekPosition()
            val got = input.peek(block, 0, block.size)
            if (got <= 0) return false
            for (i in 0..got - 4) {
                if (block[i] == 0x1F.toByte() && block[i + 1] == 0x43.toByte() &&
                    block[i + 2] == 0xB6.toByte() && block[i + 3] == 0x75.toByte()
                ) {
                    input.resetPeekPosition()
                    if (i > 0) input.skipFully(i)
                    return true
                }
            }
            input.resetPeekPosition()
            // Keep the last three bytes, in case the id straddles two blocks.
            input.skipFully((got - 3).coerceAtLeast(1))
        }
    }

    override fun release() = inner.release()

    override fun getUnderlyingImplementation(): Extractor = inner.getUnderlyingImplementation()
}

private const val SCAN_BLOCK = 64 * 1024

/** Maps time to byte offset linearly across the file. */
@UnstableApi
private class EvenBitrateSeekMap(
    private val durationUs: Long,
    private val sizeBytes: Long,
) : SeekMap {
    override fun isSeekable(): Boolean = true

    override fun getDurationUs(): Long = durationUs

    override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
        val clamped = timeUs.coerceIn(0L, durationUs)
        val position = if (clamped == 0L) 0L else (sizeBytes.toDouble() * clamped / durationUs).toLong()
        return SeekMap.SeekPoints(SeekPoint(clamped, position))
    }
}
