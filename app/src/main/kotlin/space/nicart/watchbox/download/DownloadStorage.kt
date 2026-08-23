package space.nicart.watchbox.download

import android.content.Context
import android.os.StatFs
import java.io.File

/**
 * Where downloaded media lives, and how much room is left there.
 *
 * App-private external storage rather than [Context.getFilesDir]: a television box
 * routinely ships with 8-16 GB of internal storage against episode files that run to
 * gigabytes each, and `getExternalFilesDirs` is the only API that offers a second volume
 * without asking for a storage permission. It is still app-private - removed on uninstall,
 * unreadable by other apps - so nothing here needs `READ_MEDIA_VIDEO` or the document
 * picker.
 *
 * Not the cache directory, which is where subtitles go. The reasoning that puts a subtitle
 * in cache inverts here: a cached file is one the system may delete because it can be
 * re-derived, and the entire point of a download is that it cannot be. `SubtitleRepository`
 * also empties its own directory wholesale on every episode change, so anything kept under
 * that root would not survive the next episode.
 */
class DownloadStorage(private val context: Context) {

    /**
     * The volumes downloads may be written to, in the order Android reports them.
     *
     * The first entry is always the internal one and is always present. Later entries are
     * physically removable - an SD card or a USB drive on a television box - so a volume
     * chosen once may be gone by the next launch, which is why [resolveRoot] falls back
     * rather than failing.
     */
    fun volumes(): List<DownloadVolume> {
        // The nulls are real: a slot that is present but unmounted reports null rather
        // than being omitted, so the list has to be filtered rather than indexed into.
        val dirs = context.getExternalFilesDirs(null).filterNotNull()

        return dirs.mapIndexed { index, dir ->
            val stat = runCatching { StatFs(dir.absolutePath) }.getOrNull()
            DownloadVolume(
                id = if (index == 0) VOLUME_INTERNAL else "external-$index",
                isRemovable = index > 0,
                path = dir.absolutePath,
                freeBytes = stat?.let { it.availableBlocksLong * it.blockSizeLong } ?: 0L,
                totalBytes = stat?.let { it.blockCountLong * it.blockSizeLong } ?: 0L,
            )
        }
    }

    /**
     * The directory downloads are written into, for the volume identified by [volumeId].
     *
     * Falls back to the internal volume when the requested one is absent. A removable card
     * can be pulled between sessions, and refusing to download at all in that case would
     * be a worse answer than quietly using the volume that is always there - the files on
     * the missing card are still listed, and reconciliation marks them unavailable rather
     * than deleting the record.
     */
    fun resolveRoot(volumeId: String?): File {
        val available = volumes()
        val chosen = available.firstOrNull { it.id == volumeId }
            ?: available.firstOrNull()
            // Only when even the internal volume is unmounted, which is possible while
            // the device is sharing storage over USB.
            ?: return File(context.filesDir, DIR).apply { mkdirs() }

        return File(chosen.path, DIR).apply { mkdirs() }
    }

    /** Whether the volume a download was written to is currently mounted. */
    fun isVolumeAvailable(volumeId: String?): Boolean =
        volumeId == null || volumes().any { it.id == volumeId }

    /**
     * Total bytes occupied by downloaded media across every mounted volume.
     *
     * Walked rather than summed from the registry, because the filesystem is the authority:
     * a partial download, an orphan left by a crash and a file the user deleted by hand all
     * make the registry's own figures a claim rather than a measurement.
     */
    fun usedBytes(): Long = volumes()
        .map { File(it.path, DIR) }
        .filter { it.isDirectory }
        .sumOf { it.walkBottomUp().filter { file -> file.isFile }.sumOf { file -> file.length() } }

    /** Free space on the volume [volumeId] names, or on the default one. */
    fun freeBytes(volumeId: String?): Long {
        val available = volumes()
        return (available.firstOrNull { it.id == volumeId } ?: available.firstOrNull())
            ?.freeBytes
            ?: 0L
    }

    private companion object {
        /**
         * Sits beside the Media3 download cache rather than containing it.
         *
         * The cache directory is Media3's to own - it keeps its own index there and will
         * delete anything it does not recognise - so subtitles and any future sidecar file
         * are kept outside it.
         */
        const val DIR = "downloads"
    }
}

/** One volume downloads can be written to. */
data class DownloadVolume(
    val id: String,
    /** True for an SD card or USB drive, which may be absent on a later launch. */
    val isRemovable: Boolean,
    val path: String,
    val freeBytes: Long,
    val totalBytes: Long,
)

/** The volume that is always present, and the default when nothing has been chosen. */
const val VOLUME_INTERNAL = "internal"
