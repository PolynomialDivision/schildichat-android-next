/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.api.notifications

import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId

/**
 * Manages the optional, per-room Android `NotificationChannel` a user can opt a specific
 * conversation into (custom sound / priority), on top of the app's two shared message channels.
 * Most rooms have no entry here and keep using the shared channel via [NotificationChannels].
 */
interface RoomNotificationChannelManager {
    /**
     * Returns the channel id to notify on for [roomId]: its own channel if the room has custom
     * settings, otherwise the app's shared channel (same as [NotificationChannels.getChannelIdForMessage]).
     */
    suspend fun getChannelIdForRoom(sessionId: SessionId, roomId: RoomId, roomDisplayName: String, noisy: Boolean): String

    /** Whether notification bodies for [roomId] should show the message text, or a placeholder. */
    suspend fun shouldShowMessagePreview(sessionId: SessionId, roomId: RoomId): Boolean

    /** Deletes [roomId]'s channel (if any) and its persisted settings. Idempotent. */
    suspend fun clearRoomChannel(sessionId: SessionId, roomId: RoomId)

    /**
     * Call after the user changes [roomId]'s custom sound/priority/preview settings, so its
     * channel is (re)created under the new persisted version and any stale prior-version channel
     * is removed. No-op if the room has no custom settings (e.g. they were just cleared).
     */
    suspend fun onRoomNotificationSettingsChanged(sessionId: SessionId, roomId: RoomId, roomDisplayName: String)

    /** Deletes channels for rooms no longer in [roomIds] (left via another device/client, etc). */
    suspend fun pruneChannelsForSession(sessionId: SessionId, roomIds: Set<RoomId>)

    /** Deletes every per-room channel for [sessionId]. Call on logout. */
    suspend fun clearAllChannelsForSession(sessionId: SessionId)
}
