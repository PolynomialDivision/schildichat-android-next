/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.groups

import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.fixtures.aMessageEvent
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemRedactedContent
import io.element.android.features.messages.impl.timeline.model.event.aTimelineItemImageContent
import io.element.android.features.messages.impl.timeline.model.event.aTimelineItemVideoContent
import io.element.android.features.messages.impl.timeline.model.virtual.aTimelineItemDaySeparatorModel
import io.element.android.libraries.featureflag.api.FeatureFlags
import io.element.android.libraries.featureflag.test.FakeFeatureFlagService
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.test.A_USER_ID
import io.element.android.libraries.matrix.test.A_USER_ID_2
import io.element.android.libraries.matrix.ui.messages.reply.InReplyToDetails
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TimelineItemMediaGrouperTest {
    private fun sut(enabled: Boolean = true) = TimelineItemMediaGrouper(
        featureFlagService = FakeFeatureFlagService(
            initialState = mapOf(FeatureFlags.MediaGrouping.key to enabled),
        ),
    )

    private fun anImageEvent(
        eventId: EventId = EventId("\$image${System.nanoTime()}"),
        senderId: io.element.android.libraries.matrix.api.core.UserId = A_USER_ID,
        sentTimeMillis: Long = 0L,
        inReplyTo: InReplyToDetails? = null,
    ) = aMessageEvent(eventId = eventId, content = aTimelineItemImageContent(), inReplyTo = inReplyTo)
        .copy(id = UniqueId(eventId.value), senderId = senderId, sentTimeMillis = sentTimeMillis)

    private fun aVideoEvent(
        eventId: EventId = EventId("\$video${System.nanoTime()}"),
        senderId: io.element.android.libraries.matrix.api.core.UserId = A_USER_ID,
        sentTimeMillis: Long = 0L,
    ) = aMessageEvent(eventId = eventId, content = aTimelineItemVideoContent())
        .copy(id = UniqueId(eventId.value), senderId = senderId, sentTimeMillis = sentTimeMillis)

    private fun aTextEvent(
        eventId: EventId = EventId("\$text${System.nanoTime()}"),
        sentTimeMillis: Long = 0L,
    ) = aMessageEvent(eventId = eventId).copy(id = UniqueId(eventId.value), sentTimeMillis = sentTimeMillis)

    private fun aVirtualDaySeparator() = TimelineItem.Virtual(UniqueId("day"), aTimelineItemDaySeparatorModel("Today"))

    @Test
    fun `two images from same sender within threshold are grouped`() = runTest {
        val a = anImageEvent(EventId("\$a"), sentTimeMillis = 0L)
        val b = anImageEvent(EventId("\$b"), sentTimeMillis = 10_000L)
        val result = sut().group(listOf(b, a)) // newest-first, as produced by the timeline pipeline
        assertThat(result).hasSize(1)
        val group = result.first() as TimelineItem.MediaGroup
        assertThat(group.events.map { it.eventId }).containsExactly(EventId("\$a"), EventId("\$b")).inOrder()
    }

    @Test
    fun `images from different senders are not grouped`() = runTest {
        val a = anImageEvent(EventId("\$a"), senderId = A_USER_ID, sentTimeMillis = 0L)
        val b = anImageEvent(EventId("\$b"), senderId = A_USER_ID_2, sentTimeMillis = 1_000L)
        val result = sut().group(listOf(b, a))
        assertThat(result).containsExactly(b, a).inOrder()
    }

    @Test
    fun `image then text then image are not grouped together`() = runTest {
        val a = anImageEvent(EventId("\$a"), sentTimeMillis = 0L)
        val text = aTextEvent(EventId("\$t"), sentTimeMillis = 1_000L)
        val b = anImageEvent(EventId("\$b"), sentTimeMillis = 2_000L)
        val result = sut().group(listOf(b, text, a))
        assertThat(result).containsExactly(b, text, a).inOrder()
    }

    @Test
    fun `images with a large time gap are not grouped`() = runTest {
        val a = anImageEvent(EventId("\$a"), sentTimeMillis = 0L)
        val b = anImageEvent(EventId("\$b"), sentTimeMillis = MEDIA_GROUP_TIME_WINDOW_MILLIS + 1_000L)
        val result = sut().group(listOf(b, a))
        assertThat(result).containsExactly(b, a).inOrder()
    }

    @Test
    fun `image and video from same sender within threshold are grouped`() = runTest {
        val image = anImageEvent(EventId("\$a"), sentTimeMillis = 0L)
        val video = aVideoEvent(EventId("\$b"), sentTimeMillis = 5_000L)
        val result = sut().group(listOf(video, image))
        assertThat(result).hasSize(1)
        val group = result.first() as TimelineItem.MediaGroup
        assertThat(group.events).containsExactly(image, video).inOrder()
    }

    @Test
    fun `an event with inReplyTo set is excluded from the group`() = runTest {
        val a = anImageEvent(EventId("\$a"), sentTimeMillis = 0L)
        val reply = anImageEvent(EventId("\$b"), sentTimeMillis = 1_000L, inReplyTo = InReplyToDetails.Loading(EventId("\$original")))
        val result = sut().group(listOf(reply, a))
        assertThat(result).containsExactly(reply, a).inOrder()
    }

    @Test
    fun `a run cannot bundle across a virtual item such as a day separator`() = runTest {
        val a = anImageEvent(EventId("\$a"), sentTimeMillis = 0L)
        val separator = aVirtualDaySeparator()
        val b = anImageEvent(EventId("\$b"), sentTimeMillis = 1_000L)
        val result = sut().group(listOf(b, separator, a))
        assertThat(result).containsExactly(b, separator, a).inOrder()
    }

    @Test
    fun `a redacted event in the middle of a run breaks the run`() = runTest {
        val a = anImageEvent(EventId("\$a"), sentTimeMillis = 0L)
        val redacted = aMessageEvent(eventId = EventId("\$r"), content = TimelineItemRedactedContent)
            .copy(id = UniqueId("r"), sentTimeMillis = 1_000L)
        val b = anImageEvent(EventId("\$b"), sentTimeMillis = 2_000L)
        val result = sut().group(listOf(b, redacted, a))
        assertThat(result).containsExactly(b, redacted, a).inOrder()
    }

    @Test
    fun `an edited image does not break the run`() = runTest {
        val a = anImageEvent(EventId("\$a"), sentTimeMillis = 0L)
        val editedImage = aMessageEvent(eventId = EventId("\$b"), content = aTimelineItemImageContent().copy(isEdited = true))
            .copy(id = UniqueId("b"), sentTimeMillis = 1_000L)
        val result = sut().group(listOf(editedImage, a))
        assertThat(result).hasSize(1)
        assertThat((result.first() as TimelineItem.MediaGroup).events).hasSize(2)
    }

    @Test
    fun `more than four eligible events are all bundled into a single group`() = runTest {
        val events = (0 until 6).map { i -> anImageEvent(EventId("\$$i"), sentTimeMillis = i * 1_000L) }
        // newest-first, as produced by the timeline pipeline
        val result = sut().group(events.reversed())
        assertThat(result).hasSize(1)
        val group = result.first() as TimelineItem.MediaGroup
        assertThat(group.events).hasSize(6)
        assertThat(group.events.map { it.eventId }).isEqualTo(events.map { it.eventId })
    }

    @Test
    fun `a single eligible image with no eligible neighbour stays ungrouped`() = runTest {
        val a = anImageEvent(EventId("\$a"), sentTimeMillis = 0L)
        val result = sut().group(listOf(a))
        assertThat(result).containsExactly(a)
    }

    @Test
    fun `grouping is a no-op when the feature flag is disabled`() = runTest {
        val a = anImageEvent(EventId("\$a"), sentTimeMillis = 0L)
        val b = anImageEvent(EventId("\$b"), sentTimeMillis = 1_000L)
        val result = sut(enabled = false).group(listOf(b, a))
        assertThat(result).containsExactly(b, a).inOrder()
    }

    @Test
    fun `empty list stays empty`() = runTest {
        assertThat(sut().group(emptyList())).isEmpty()
    }
}
