package space.nicart.watchbox.ui.download

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import space.nicart.watchbox.R
import space.nicart.watchbox.core.ui.adaptiveFocus
import space.nicart.watchbox.core.ui.rememberFocusInteraction
import space.nicart.watchbox.core.ui.wb
import space.nicart.watchbox.data.remote.SubtitleResult
import space.nicart.watchbox.domain.StreamOption
import space.nicart.watchbox.domain.SubtitleOption

/** What the picker is showing. */
sealed interface DownloadPickerState {
    data object Hidden : DownloadPickerState

    /** Streams are being resolved, which takes as long as opening the player does. */
    data class Resolving(val episodeUrl: String) : DownloadPickerState

    data class Ready(
        val episodeUrl: String,
        val episodeLabel: String,
        val streams: List<StreamOption>,
        /**
         * Whether the show is absent from the viewer's list.
         *
         * Drives an opt-out inside this step rather than a prompt after it: downloading
         * something is a stronger signal of intent than adding it to a list, so the
         * question is worth asking, but not worth a second dialog.
         */
        val offerWatchlist: Boolean = false,
        /** Whether it will be added, pre-set when offered at all. */
        val addToWatchlist: Boolean = false,
    ) : DownloadPickerState

    /**
     * Looking for subtitles to offer alongside the stream's own.
     *
     * Its own step rather than something done in the background, because it is a decision: the
     * files on offer are matched by title and episode, not cut for this release, so which one -
     * or whether to bother - is the viewer's call.
     */
    data class FindingSubtitles(
        val episodeUrl: String,
        val stream: StreamOption,
        val addToWatchlist: Boolean = false,
    ) : DownloadPickerState

    /**
     * What can be saved alongside the video.
     *
     * Carries the stream's own tracks as well as anything found online. Those are listed
     * rather than assumed: a stream supplying one subtitle in a language the viewer does not
     * read used to skip this step entirely and download without asking, which looked like
     * the picker was broken.
     */
    data class SubtitleChoice(
        val episodeUrl: String,
        val stream: StreamOption,
        val results: List<SubtitleResult>,
        val addToWatchlist: Boolean = false,
    ) : DownloadPickerState {
        /** The stream's own subtitles, saved whatever else is chosen here. */
        val ownSubtitles: List<SubtitleOption> get() = stream.subtitles
    }

    data class Failed(val message: String) : DownloadPickerState
}

/**
 * Asks which stream to download.
 *
 * Deliberately a prompt rather than a silent preference. Sizes here span three orders of
 * magnitude - one real source label advertises a 66 GB file next to an 858 MB one - so
 * choosing on the user's behalf risks spending most of a phone's free space on a single
 * episode. The label the source supplied is shown verbatim because it carries the size,
 * codec and release that make the choice meaningful.
 *
 * Rows are not deduplicated by resolution. A server routinely lists the same height several
 * times as different releases or mirrors, and collapsing them would hide real alternatives -
 * the same reason the player's own quality panel keeps them apart.
 */
@Composable
fun DownloadQualityDialog(
    state: DownloadPickerState,
    onPick: (StreamOption) -> Unit,
    /** Downloads the video with the chosen subtitle alongside it. */
    onPickSubtitle: (SubtitleResult) -> Unit = {},
    /** Downloads the video on its own, leaving it without subtitles. */
    onSkipSubtitle: () -> Unit = {},
    /** Toggles whether the show is added to the viewer's list when the download starts. */
    onToggleWatchlist: (Boolean) -> Unit = {},
    onDismiss: () -> Unit,
) {
    if (state is DownloadPickerState.Hidden) return

    val tokens = MaterialTheme.wb

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(max = DIALOG_MAX_WIDTH)
                .clip(RoundedCornerShape(20.dp))
                .background(tokens.colors.surfaceDialog)
                .padding(20.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Download,
                    contentDescription = null,
                    tint = tokens.colors.accent,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = stringResource(
                        when (state) {
                            is DownloadPickerState.FindingSubtitles,
                            is DownloadPickerState.SubtitleChoice,
                            -> R.string.download_pick_subtitle

                            else -> R.string.download_pick_quality
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.colors.textPrimary,
                )
            }

            when (state) {
                is DownloadPickerState.Resolving -> {
                    Spacer(Modifier.height(20.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            color = tokens.colors.accent,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = stringResource(R.string.download_resolving),
                            style = MaterialTheme.typography.bodyMedium,
                            color = tokens.colors.textSecondary,
                        )
                    }
                }

                is DownloadPickerState.Failed -> {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.textSecondary,
                    )
                }

                is DownloadPickerState.Ready -> {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = state.episodeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = tokens.colors.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.download_pick_quality_summary),
                        style = MaterialTheme.typography.labelSmall,
                        color = tokens.colors.textMuted,
                    )
                    Spacer(Modifier.height(12.dp))

                    // Scrolls rather than expanding: a source can return dozens of streams,
                    // and a dialog taller than the screen cannot be dismissed on a remote.
                    LazyColumn(
                        modifier = Modifier.heightIn(max = LIST_MAX_HEIGHT),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Keyed by position, not by URL.
                        //
                        // A URL is not unique here: sources routinely list the same file
                        // several times over as different releases or as mirrors, and Compose
                        // throws outright on a repeated key - which crashed the app the moment
                        // this dialog opened on such a source. The list is fixed for as long as
                        // the dialog is up, so the index is both stable and unique.
                        itemsIndexed(
                            items = state.streams,
                            key = { index, _ -> index },
                        ) { _, stream ->
                            StreamRow(stream = stream, onClick = { onPick(stream) })
                        }
                    }

                    // Below the list, so it reads as a note on the download about to start
                    // rather than as one of the things being chosen between.
                    if (state.offerWatchlist) {
                        Spacer(Modifier.height(14.dp))
                        WatchlistCheckRow(
                            checked = state.addToWatchlist,
                            onCheckedChange = onToggleWatchlist,
                        )
                    }
                }

                is DownloadPickerState.FindingSubtitles -> {
                    Spacer(Modifier.height(20.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            color = tokens.colors.accent,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = stringResource(R.string.download_finding_subtitles),
                            style = MaterialTheme.typography.bodyMedium,
                            color = tokens.colors.textSecondary,
                        )
                    }
                }

                is DownloadPickerState.SubtitleChoice -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = when {
                            state.ownSubtitles.isNotEmpty() ->
                                stringResource(R.string.download_subtitle_included_summary)
                            state.results.isEmpty() ->
                                stringResource(R.string.download_no_subtitles_found)
                            else -> stringResource(R.string.download_pick_subtitle_summary)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = tokens.colors.textMuted,
                    )

                    if (state.ownSubtitles.isNotEmpty() || state.results.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        LazyColumn(
                            modifier = Modifier.heightIn(max = LIST_MAX_HEIGHT),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // The stream's own first, and not selectable: they are saved
                            // regardless, so a tappable row would imply a choice that is not
                            // being offered. Shown at all because their absence from this list
                            // made a download that did include them look as though it had not.
                            itemsIndexed(
                                items = state.ownSubtitles,
                                key = { index, _ -> "own-$index" },
                            ) { _, subtitle ->
                                IncludedSubtitleRow(subtitle = subtitle)
                            }

                            // Indexed, not keyed by id: release names repeat across mirrors and
                            // Compose throws outright on a duplicate key.
                            itemsIndexed(
                                items = state.results,
                                key = { index, _ -> "found-$index" },
                            ) { _, result ->
                                SubtitleResultRow(
                                    result = result,
                                    onClick = { onPickSubtitle(result) },
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                    ) {
                        // Always offered. The video is what was asked for; a subtitle is an
                        // addition, and not finding one must not block the download.
                        SkipButton(
                            label = stringResource(
                                when {
                                    // Not "without subtitles" when the stream brought its own:
                                    // that would state the opposite of what happens.
                                    state.ownSubtitles.isNotEmpty() ->
                                        R.string.download_continue
                                    state.results.isEmpty() ->
                                        R.string.download_without_subtitles
                                    else -> R.string.download_skip_subtitles
                                },
                            ),
                            onClick = onSkipSubtitle,
                        )
                    }
                }

                DownloadPickerState.Hidden -> Unit
            }
        }
    }
}

@Composable
private fun StreamRow(stream: StreamOption, onClick: () -> Unit) {
    val tokens = MaterialTheme.wb
    val interaction = rememberFocusInteraction()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .adaptiveFocus(interaction, RoundedCornerShape(12.dp), scale = false)
            .clip(RoundedCornerShape(12.dp))
            .background(tokens.colors.surfaceCard)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            // Verbatim, because the source's own label is where the size and codec live and
            // reformatting it would drop exactly what makes one row differ from the next.
            text = stream.label,
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val DIALOG_MAX_WIDTH = 420.dp
private val LIST_MAX_HEIGHT = 320.dp

/**
 * One subtitle on offer.
 *
 * Shows the release name and the download count, which are the only two signals worth acting
 * on: the name says which cut it was timed against, and a heavily-downloaded file is the one
 * most people found usable.
 */
@Composable
private fun SubtitleResultRow(result: SubtitleResult, onClick: () -> Unit) {
    val tokens = MaterialTheme.wb
    val interaction = rememberFocusInteraction()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .adaptiveFocus(interaction, RoundedCornerShape(12.dp), scale = false)
            .clip(RoundedCornerShape(12.dp))
            .background(tokens.colors.surfaceCard)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = result.name,
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = listOfNotNull(
                result.languageName.takeIf { it.isNotBlank() } ?: result.language,
                result.downloads.takeIf { it > 0 }?.let { "$it downloads" },
                "(HI)".takeIf { result.hearingImpaired },
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = tokens.colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A subtitle the stream already carries, shown as a statement rather than a choice.
 *
 * Deliberately not clickable: these are saved whichever button ends the step, so a tappable
 * row would offer a decision that is not being made. They are listed because leaving them out
 * made a download that did include subtitles look as though it had none.
 */
@Composable
private fun IncludedSubtitleRow(subtitle: SubtitleOption) {
    val tokens = MaterialTheme.wb

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(tokens.colors.surfaceCard)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Check,
            contentDescription = null,
            tint = tokens.colors.accent,
            modifier = Modifier.size(16.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = subtitle.label,
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.download_subtitle_from_source),
                style = MaterialTheme.typography.labelSmall,
                color = tokens.colors.textMuted,
                maxLines = 1,
            )
        }
    }
}

/** Opt-out for adding the show to the viewer's list as the download starts. */
@Composable
private fun WatchlistCheckRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val tokens = MaterialTheme.wb
    val interaction = rememberFocusInteraction()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .adaptiveFocus(interaction, RoundedCornerShape(12.dp), scale = false)
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = { onCheckedChange(!checked) },
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Checkbox(
            checked = checked,
            // Null so the whole row is the target: a checkbox alone is a small hit area on a
            // phone and barely reachable with a remote.
            onCheckedChange = null,
            colors = CheckboxDefaults.colors(
                checkedColor = tokens.colors.accent,
                uncheckedColor = tokens.colors.textMuted,
            ),
        )
        Text(
            text = stringResource(R.string.download_add_to_list),
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textSecondary,
        )
    }
}

/** Proceeds without a subtitle. */
@Composable
private fun SkipButton(label: String, onClick: () -> Unit) {    val tokens = MaterialTheme.wb
    val interaction = rememberFocusInteraction()

    Row(
        modifier = Modifier
            .adaptiveFocus(interaction, RoundedCornerShape(12.dp), scale = false)
            .clip(RoundedCornerShape(12.dp))
            .background(tokens.colors.textPrimary)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = tokens.colors.background,
        )
    }
}
