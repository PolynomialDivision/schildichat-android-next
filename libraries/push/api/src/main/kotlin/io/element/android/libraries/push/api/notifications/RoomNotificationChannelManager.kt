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
 * Manages per-room Android `NotificationChannel`s, on top of the app's two shared message
 * channels: an explicitly *customized* channel (custom sound / priority, set by the user via
 * SchildiChat's own room notification settings), and an automatically-created *ordinary* channel
 * for any other room that has produced at least one noisy notification. Both kinds are linked to
 * the shared "noisy" channel via `setConversationId`, making the room eligible for Android's
 * Conversations UI (Settings, Priority Conversations) - without one, a room's notification is
 * never listed there, no matter how it's built otherwise (MessagingStyle, shortcut, etc.).
 *
 * Ordinary channels are deliberately only created for *noisy* notifications: some Matrix push
 * rule modes (e.g. "mentions only") vary noisy per-event within the same room, and a channel's
 * importance can never change after creation, so creating one from a silent event would
 * permanently silence a room's future mentions. A silent notification for an otherwise-uncustomized
 * room keeps using the app's plain shared silent channel exactly as before this existed.
 */
interface RoomNotificationChannelManager {
    /**
     * Returns the channel id to notify on for [roomId]: its customized channel if the room has
     * one, otherwise its ordinary channel if [noisy] (creating it on first use), otherwise the
     * app's shared channel (same as [NotificationChannels.getChannelIdForMessage]). Any channel
     * created here is filed under the "Private chats" or "Rooms" system channel group depending
     * on [isDm], so it doesn't fall into Android's generic "Other" bucket for ungrouped channels.
     */
    suspend fun getChannelIdForRoom(sessionId: SessionId, roomId: RoomId, roomDisplayName: String, isDm: Boolean, noisy: Boolean): String

    /** Whether notification bodies for [roomId] should show the message text, or a placeholder. */
    suspend fun shouldShowMessagePreview(sessionId: SessionId, roomId: RoomId): Boolean

    /** Deletes [roomId]'s channel (if any) and its persisted settings. Idempotent. */
    suspend fun clearRoomChannel(sessionId: SessionId, roomId: RoomId)

    /**
     * Call after the user changes [roomId]'s custom sound/priority/preview settings, so its
     * channel is (re)created under the new persisted version and any stale prior-version channel
     * is removed. No-op if the room has no custom settings (e.g. they were just cleared).
     */
    suspend fun onRoomNotificationSettingsChanged(sessionId: SessionId, roomId: RoomId, roomDisplayName: String, isDm: Boolean)

    /** Deletes channels for rooms no longer in [roomIds] (left via another device/client, etc). */
    suspend fun pruneChannelsForSession(sessionId: SessionId, roomIds: Set<RoomId>)

    /** Deletes every per-room channel for [sessionId]. Call on logout. */
    suspend fun clearAllChannelsForSession(sessionId: SessionId)

    /**
     * Retires long-inactive, unmodified *ordinary* (auto-created, uncustomized) channels for
     * [sessionId], keeping the total bounded. Never touches a room's explicitly customized channel.
     *
     * A channel is skipped (never deleted) if either:
     * - the user has marked it a Priority Conversation ([android.app.NotificationChannel.isImportantConversation]), or
     * - its live importance/sound/vibration/lights no longer match what this manager would create for
     *   an ordinary channel, meaning the user (or something else) changed it directly via system
     *   Settings.
     *
     * Among the remaining, unprotected candidates, a channel is deleted if it hasn't been used in
     * over 30 days, or - if the session still has more than 50 remaining after that - the oldest
     * ones are removed down to that limit.
     */
    suspend fun pruneInactiveOrdinaryChannels(sessionId: SessionId)
}
