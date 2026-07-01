/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.groups

import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemImageContent
import io.element.android.features.messages.impl.timeline.model.event.TimelineItemVideoContent
import kotlin.math.abs

/**
 * Maximum gap, in milliseconds, between two consecutive image/video events for them to still be
 * considered part of the same visual media album.
 */
internal const val MEDIA_GROUP_TIME_WINDOW_MILLIS = 60_000L

/**
 * Returns true if this event's content is a type that can ever be part of a visual media album
 * (i.e. an image or a video), and it's not a reply or a thread event, both of which must stay
 * visually separate per the media grouping design.
 */
internal fun TimelineItem.Event.isMediaGroupCandidate(): Boolean {
    return (content is TimelineItemImageContent || content is TimelineItemVideoContent) &&
        inReplyTo == null &&
        threadInfo == null
}

/**
 * Returns true if [a] and [b] are eligible to sit in the same visual media album, i.e. they were
 * sent by the same person, close together in time. Both events are assumed to have already passed
 * [isMediaGroupCandidate]; this only checks the *pairwise* relationship between them.
 */
internal fun canGroupMediaEvents(a: TimelineItem.Event, b: TimelineItem.Event): Boolean {
    if (a.senderId != b.senderId) return false
    return abs(a.sentTimeMillis - b.sentTimeMillis) <= MEDIA_GROUP_TIME_WINDOW_MILLIS
}
