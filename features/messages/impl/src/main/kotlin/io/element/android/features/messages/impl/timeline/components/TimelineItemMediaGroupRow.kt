/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import chat.schildi.theme.ScTheme
import chat.schildi.theme.extensions.scOrElse
import coil3.compose.AsyncImage
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.features.messages.impl.R
import io.element.android.features.messages.impl.timeline.TimelineEvent
import io.element.android.features.messages.impl.timeline.TimelineRoomInfo
import io.element.android.features.messages.impl.timeline.aTimelineItemEvent
import io.element.android.features.messages.impl.timeline.aTimelineRoomInfo
import io.element.android.features.messages.impl.timeline.components.receipt.ReadReceiptViewState
import io.element.android.features.messages.impl.timeline.components.receipt.TimelineItemReadReceiptView
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.model.TimelineItemGroupPosition
import io.element.android.features.messages.impl.timeline.model.bubble.BubbleState
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemImageContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemVideoContent
import io.element.android.features.messages.impl.timeline.model.event.aTimelineItemImageContent
import io.element.android.features.messages.impl.timeline.model.event.aTimelineItemVideoContent
import io.element.android.libraries.designsystem.components.blurhash.blurHashBackground
import io.element.android.libraries.designsystem.modifiers.roundedBackground
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.api.user.MatrixUser
import io.element.android.libraries.matrix.ui.media.MAX_THUMBNAIL_HEIGHT
import io.element.android.libraries.matrix.ui.media.MAX_THUMBNAIL_WIDTH
import io.element.android.libraries.matrix.ui.media.MediaRequestData
import kotlinx.collections.immutable.toImmutableList

private val MEDIA_GROUP_HEIGHT = 220.dp
private val TILE_GAP = 2.dp

/**
 * Renders a [TimelineItem.MediaGroup] as a single Telegram-style album/collage: one shared
 * message bubble, one sender header, one timestamp/read-receipt/reactions row (anchored to the
 * *last* event in the group, matching Telegram's convention), and a 2/3/4/5+ tile grid inside.
 *
 * Every tile still maps to one specific, individually addressable [TimelineItem.Event]: tapping
 * opens the media viewer at that event, long-pressing opens the action list for that event only.
 */
@Composable
fun TimelineItemMediaGroupRow(
    timelineItem: TimelineItem.MediaGroup,
    timelineRoomInfo: TimelineRoomInfo,
    isLastOutgoingMessage: Boolean,
    onUserDataClick: (MatrixUser) -> Unit,
    onTileClick: (TimelineItem.Event) -> Unit,
    onTileLongClick: (TimelineItem.Event) -> Unit,
    onReadReceiptClick: (TimelineItem.Event) -> Unit,
    eventSink: (TimelineEvent.TimelineItemEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstEvent = timelineItem.events.first()
    val lastEvent = timelineItem.events.last()
    val interactionSource = remember { MutableInteractionSource() }

    Column(modifier = modifier.fillMaxWidth()) {
        if (firstEvent.groupPosition.isNew()) {
            Spacer(modifier = Modifier.height(16.dp))
        } else {
            Spacer(modifier = Modifier.height(2.dp))
        }

        if (firstEvent.showSenderInformation && !timelineRoomInfo.isDm) {
            MessageSenderInformation(
                firstEvent.senderId,
                firstEvent.senderProfile,
                firstEvent.senderAvatar,
                { onUserDataClick(MatrixUser(firstEvent.senderId, firstEvent.safeSenderName, firstEvent.senderAvatar.url)) },
                Modifier.padding(horizontal = 16.dp),
            )
        }

        // The avatar-overlap ("cutTopStart") bubble corner trick used for single-event bubbles is
        // intentionally not replicated here (this row doesn't overlap the sender avatar with the
        // bubble) - force isDm=true for this computation only, which is exactly the condition
        // under which single-event bubbles also skip that cutout.
        val bubbleState = BubbleState(
            groupPosition = timelineItem.effectiveGroupPosition(),
            isMine = firstEvent.isMine,
            timelineRoomInfo = timelineRoomInfo.copy(isDm = true),
        )
        // Incoming bubbles in non-DM rooms get an extra BUBBLE_INCOMING_OFFSET on the start edge so
        // they align under the sender's name rather than under their avatar, matching the single-
        // event bubble in TimelineItemEventRow.
        val startPadding = if (!firstEvent.isMine && !timelineRoomInfo.isDm) 16.dp + BUBBLE_INCOMING_OFFSET else 16.dp
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = startPadding, end = 16.dp),
            contentAlignment = if (firstEvent.isMine) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            MessageEventBubble(
                state = bubbleState,
                interactionSource = interactionSource,
                onClick = { onTileClick(firstEvent) },
                onLongClick = { onTileLongClick(firstEvent) },
            ) {
                MediaGroupCollage(
                    events = timelineItem.events,
                    onTileClick = onTileClick,
                    onTileLongClick = onTileLongClick,
                    eventSink = eventSink,
                )
            }
        }

        TimelineItemReadReceiptView(
            state = ReadReceiptViewState(
                sendState = lastEvent.localSendState,
                isLastOutgoingMessage = isLastOutgoingMessage,
                receipts = lastEvent.readReceiptState.receipts,
            ),
            onReadReceiptsClick = { onReadReceiptClick(lastEvent) },
            modifier = Modifier.padding(top = 4.dp, start = 16.dp, end = 16.dp),
        )
    }
}

/**
 * The bubble corner-rounding position for the group as a whole: the leading edge follows the
 * first event's own position (was it preceded by a different sender?) and the trailing edge
 * follows the last event's (is it followed by a different sender?). Both were already computed
 * per-event by [io.element.android.features.messages.impl.timeline.factories.event.TimelineItemEventFactory]
 * before the media grouping pass ran.
 */
private fun TimelineItem.MediaGroup.effectiveGroupPosition(): TimelineItemGroupPosition {
    val startsNewRun = events.first().groupPosition.isNew()
    val endsRun = events.last().groupPosition.let { it == TimelineItemGroupPosition.Last || it == TimelineItemGroupPosition.None }
    return when {
        startsNewRun && endsRun -> TimelineItemGroupPosition.None
        startsNewRun -> TimelineItemGroupPosition.First
        endsRun -> TimelineItemGroupPosition.Last
        else -> TimelineItemGroupPosition.Middle
    }
}

@Composable
private fun MediaGroupCollage(
    events: List<TimelineItem.Event>,
    onTileClick: (TimelineItem.Event) -> Unit,
    onTileLongClick: (TimelineItem.Event) -> Unit,
    eventSink: (TimelineEvent.TimelineItemEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val total = events.size
    // In every layout below (2, 3, or a 2x2+overlay grid), the last event is always the tile in the
    // bottom-right corner, so the whole collage's bottom-end corner is also that tile's bottom-end
    // corner - the timestamp overlay can simply be anchored to the outer Box, matching the Telegram
    // convention of showing the timestamp on the album's last/bottom-right item.
    Box(
        modifier = modifier
            .height(MEDIA_GROUP_HEIGHT)
            .fillMaxWidth()
    ) {
        when (total) {
            2 -> Row(horizontalArrangement = Arrangement.spacedBy(TILE_GAP), modifier = Modifier.fillMaxSize()) {
                events.forEachIndexed { index, event ->
                    MediaGroupTile(
                        event = event,
                        index = index,
                        total = total,
                        overlayCount = null,
                        onClick = { onTileClick(event) },
                        onLongClick = { onTileLongClick(event) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
            3 -> Row(horizontalArrangement = Arrangement.spacedBy(TILE_GAP), modifier = Modifier.fillMaxSize()) {
                MediaGroupTile(
                    event = events[0],
                    index = 0,
                    total = total,
                    overlayCount = null,
                    onClick = { onTileClick(events[0]) },
                    onLongClick = { onTileLongClick(events[0]) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                Column(verticalArrangement = Arrangement.spacedBy(TILE_GAP), modifier = Modifier.weight(1f).fillMaxHeight()) {
                    for (index in 1..2) {
                        MediaGroupTile(
                            event = events[index],
                            index = index,
                            total = total,
                            overlayCount = null,
                            onClick = { onTileClick(events[index]) },
                            onLongClick = { onTileLongClick(events[index]) },
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                    }
                }
            }
            else -> Column(verticalArrangement = Arrangement.spacedBy(TILE_GAP), modifier = Modifier.fillMaxSize()) {
                Row(horizontalArrangement = Arrangement.spacedBy(TILE_GAP), modifier = Modifier.weight(1f).fillMaxWidth()) {
                    for (index in 0..1) {
                        MediaGroupTile(
                            event = events[index],
                            index = index,
                            total = total,
                            overlayCount = null,
                            onClick = { onTileClick(events[index]) },
                            onLongClick = { onTileLongClick(events[index]) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(TILE_GAP), modifier = Modifier.weight(1f).fillMaxWidth()) {
                    for (index in 2..3) {
                        val overlayCount = (total - 4).takeIf { index == 3 && it > 0 }
                        MediaGroupTile(
                            event = events[index],
                            index = index,
                            total = total,
                            overlayCount = overlayCount,
                            onClick = { onTileClick(events[index]) },
                            onLongClick = { onTileLongClick(events[index]) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
        }
        TimelineEventTimestampView(
            event = events.last(),
            eventSink = eventSink,
            modifier = Modifier
                .scOrElse(
                    forSc = Modifier
                        .background(
                            ScTheme.exposures.timestampOverlayBg,
                            RoundedCornerShape(ScTheme.exposures.timestampRadius, 0.dp, ScTheme.exposures.bubbleRadius, 0.dp)
                        )
                ) {
                    this
                        // Outer padding
                        .padding(horizontal = 4.dp, vertical = 4.dp)
                        .background(ElementTheme.colors.bgSubtleSecondary, RoundedCornerShape(10.0.dp))
                }
                .align(Alignment.BottomEnd)
                // Inner padding
                .padding(horizontal = 4.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun MediaGroupTile(
    event: TimelineItem.Event,
    index: Int,
    total: Int,
    overlayCount: Int?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = event.content
    val (requestData, blurhash, isVideo) = when (content) {
        is TimelineItemImageContent -> Triple(content.thumbnailMediaRequestData, content.blurhash, false)
        is TimelineItemVideoContent -> Triple(
            MediaRequestData(
                source = content.thumbnailSource,
                kind = MediaRequestData.Kind.Thumbnail(
                    width = content.thumbnailWidth?.toLong() ?: MAX_THUMBNAIL_WIDTH,
                    height = content.thumbnailHeight?.toLong() ?: MAX_THUMBNAIL_HEIGHT,
                ),
            ),
            content.blurHash,
            true,
        )
        // The media grouper only ever admits image/video content into a MediaGroup.
        else -> return
    }
    val a11yLabel = if (isVideo) {
        stringResource(R.string.a11y_media_group_video, index + 1, total)
    } else {
        stringResource(R.string.a11y_media_group_image, index + 1, total)
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(2.dp))
            .blurHashBackground(blurhash, alpha = 0.9f)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics { contentDescription = a11yLabel },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            modifier = Modifier.fillMaxSize(),
            model = requestData,
            contentScale = ContentScale.Crop,
            alignment = Alignment.Center,
            contentDescription = null,
        )
        if (isVideo) {
            Box(modifier = Modifier.roundedBackground(), contentAlignment = Alignment.Center) {
                Image(
                    imageVector = CompoundIcons.PlaySolid(),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(Color.White),
                )
            }
        }
        if (overlayCount != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.screen_room_timeline_media_group_more_overlay, overlayCount),
                    style = ElementTheme.typography.fontHeadingMdBold,
                    color = Color.White,
                )
            }
        }
    }
}

private fun aMediaGroup(count: Int, isMine: Boolean = false): TimelineItem.MediaGroup {
    val events = (0 until count).map { index ->
        aTimelineItemEvent(
            eventId = EventId("\$media_group_item_$index"),
            isMine = isMine,
            content = if (index % 2 == 0) aTimelineItemImageContent() else aTimelineItemVideoContent(),
            groupPosition = TimelineItemGroupPosition.None,
        )
    }
    return TimelineItem.MediaGroup(id = UniqueId("media_group"), events = events.toImmutableList())
}

@PreviewsDayNight
@Composable
internal fun TimelineItemMediaGroupRowTwoItemsPreview() = ElementPreview {
    TimelineItemMediaGroupRow(
        timelineItem = aMediaGroup(2),
        timelineRoomInfo = aTimelineRoomInfo(),
        isLastOutgoingMessage = false,
        onUserDataClick = {},
        onTileClick = {},
        onTileLongClick = {},
        onReadReceiptClick = {},
        eventSink = {},
    )
}

@PreviewsDayNight
@Composable
internal fun TimelineItemMediaGroupRowThreeItemsPreview() = ElementPreview {
    TimelineItemMediaGroupRow(
        timelineItem = aMediaGroup(3),
        timelineRoomInfo = aTimelineRoomInfo(),
        isLastOutgoingMessage = false,
        onUserDataClick = {},
        onTileClick = {},
        onTileLongClick = {},
        onReadReceiptClick = {},
        eventSink = {},
    )
}

@PreviewsDayNight
@Composable
internal fun TimelineItemMediaGroupRowFourItemsPreview() = ElementPreview {
    TimelineItemMediaGroupRow(
        timelineItem = aMediaGroup(4, isMine = true),
        timelineRoomInfo = aTimelineRoomInfo(),
        isLastOutgoingMessage = false,
        onUserDataClick = {},
        onTileClick = {},
        onTileLongClick = {},
        onReadReceiptClick = {},
        eventSink = {},
    )
}

@PreviewsDayNight
@Composable
internal fun TimelineItemMediaGroupRowMoreOverlayPreview() = ElementPreview {
    TimelineItemMediaGroupRow(
        timelineItem = aMediaGroup(6),
        timelineRoomInfo = aTimelineRoomInfo(),
        isLastOutgoingMessage = false,
        onUserDataClick = {},
        onTileClick = {},
        onTileLongClick = {},
        onReadReceiptClick = {},
        eventSink = {},
    )
}
