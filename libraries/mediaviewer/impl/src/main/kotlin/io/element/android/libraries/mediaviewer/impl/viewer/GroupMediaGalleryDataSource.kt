/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaviewer.impl.viewer

import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.core.mimetype.MimeTypes.isMimeTypeVideo
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.UniqueId
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.mediaviewer.api.GroupMediaItem
import io.element.android.libraries.mediaviewer.api.MediaViewerEntryPoint
import io.element.android.libraries.mediaviewer.impl.datasource.MediaGalleryDataSource
import io.element.android.libraries.mediaviewer.impl.model.GroupedMediaItems
import io.element.android.libraries.mediaviewer.impl.model.MediaItem
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flowOf

/**
 * A [MediaGalleryDataSource] scoped to a single visually-grouped media album from the timeline
 * (see [MediaViewerEntryPoint.MediaViewerMode.TimelineMediaGroup]). Backed entirely by the fixed
 * item list the timeline already has in memory - no SDK querying or pagination, same approach as
 * [SingleMediaGalleryDataSource].
 */
class GroupMediaGalleryDataSource(
    private val data: GroupedMediaItems,
) : MediaGalleryDataSource {
    override fun start(coroutineScope: CoroutineScope) = Unit
    override fun groupedMediaItemsFlow() = flowOf(AsyncData.Success(data))
    override fun getLastData(): AsyncData<GroupedMediaItems> = AsyncData.Success(data)

    override val isReady: Boolean = true

    override suspend fun loadMore(direction: Timeline.PaginationDirection) = Unit
    override suspend fun deleteItem(eventId: EventId) = Unit

    companion object {
        fun createFrom(mode: MediaViewerEntryPoint.MediaViewerMode.TimelineMediaGroup) = GroupMediaGalleryDataSource(
            data = GroupedMediaItems(
                imageAndVideoItems = mode.items.map { it.toMediaItem() }.toPersistentList(),
                fileItems = persistentListOf(),
            )
        )
    }
}

private fun GroupMediaItem.toMediaItem(): MediaItem = if (mediaInfo.mimeType.isMimeTypeVideo()) {
    MediaItem.Video(
        id = UniqueId(eventId?.value ?: mediaSource.safeUrl),
        eventId = eventId,
        mediaInfo = mediaInfo,
        mediaSource = mediaSource,
        thumbnailSource = thumbnailSource,
    )
} else {
    MediaItem.Image(
        id = UniqueId(eventId?.value ?: mediaSource.safeUrl),
        eventId = eventId,
        mediaInfo = mediaInfo,
        mediaSource = mediaSource,
        thumbnailSource = thumbnailSource,
    )
}
