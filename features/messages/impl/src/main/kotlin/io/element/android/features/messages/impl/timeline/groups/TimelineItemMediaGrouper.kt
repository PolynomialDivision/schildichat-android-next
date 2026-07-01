/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.timeline.groups

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.features.messages.impl.timeline.model.TimelineItem
import io.element.android.libraries.di.RoomScope
import io.element.android.libraries.featureflag.api.FeatureFlagService
import io.element.android.libraries.featureflag.api.FeatureFlags
import io.element.android.libraries.matrix.api.core.UniqueId
import kotlinx.collections.immutable.toImmutableList

/**
 * Groups consecutive image/video [TimelineItem.Event]s from the same sender, sent close together
 * in time, into a single [TimelineItem.MediaGroup] for visual (album/collage) rendering.
 *
 * This is purely a presentation-layer concern: every underlying event keeps its own identity,
 * reactions, read receipts, etc. Nothing about how the events are stored or addressed changes.
 *
 * Deliberately separate from [TimelineItemGrouper], which bundles consecutive *state* events
 * (joins, profile changes) under entirely different eligibility rules ([canBeGrouped]).
 *
 * No-ops (returns [from] unchanged) unless [FeatureFlags.MediaGrouping] is enabled, so the feature
 * can be fully disabled by flipping the flag, with no other code path affected.
 */
@SingleIn(RoomScope::class)
@Inject
class TimelineItemMediaGrouper(
    private val featureFlagService: FeatureFlagService,
) {
    suspend fun group(from: List<TimelineItem>): List<TimelineItem> {
        if (!featureFlagService.isFeatureEnabled(FeatureFlags.MediaGrouping)) return from
        val result = mutableListOf<TimelineItem>()
        val currentGroup = mutableListOf<TimelineItem.Event>()
        from.forEach { timelineItem ->
            val canJoin = timelineItem is TimelineItem.Event &&
                timelineItem.isMediaGroupCandidate() &&
                (currentGroup.isEmpty() || canGroupMediaEvents(timelineItem, currentGroup.first()))
            if (canJoin) {
                currentGroup.add(0, timelineItem as TimelineItem.Event)
            } else {
                if (currentGroup.isNotEmpty()) {
                    result.addMediaGroup(currentGroup)
                    currentGroup.clear()
                }
                result.add(timelineItem)
            }
        }
        if (currentGroup.isNotEmpty()) {
            result.addMediaGroup(currentGroup)
        }
        return result
    }
}

/**
 * Adds a [TimelineItem.MediaGroup] if there are 2 or more items, else adds the single item as-is
 * (mirrors [TimelineItemGrouper]'s `addGroup`: a "group" of one is not a group).
 */
private fun MutableList<TimelineItem>.addMediaGroup(groupOfItems: List<TimelineItem.Event>) {
    if (groupOfItems.size == 1) {
        add(groupOfItems.first())
    } else {
        add(
            TimelineItem.MediaGroup(
                id = UniqueId("${groupOfItems.first().identifier()}_mediaGroup"),
                events = groupOfItems.toImmutableList(),
            )
        )
    }
}
