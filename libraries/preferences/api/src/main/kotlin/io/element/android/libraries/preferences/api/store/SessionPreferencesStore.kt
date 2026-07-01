/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.preferences.api.store

import io.element.android.libraries.matrix.api.core.RoomId
import kotlinx.coroutines.flow.Flow

interface SessionPreferencesStore {
    suspend fun setSharePresence(enabled: Boolean)
    fun isSharePresenceEnabled(): Flow<Boolean>

    suspend fun setSendPublicReadReceipts(enabled: Boolean)
    fun isSendPublicReadReceiptsEnabled(): Flow<Boolean>

    suspend fun setRenderReadReceipts(enabled: Boolean)
    fun isRenderReadReceiptsEnabled(): Flow<Boolean>

    suspend fun setSendTypingNotifications(enabled: Boolean)
    fun isSendTypingNotificationsEnabled(): Flow<Boolean>

    suspend fun setRenderTypingNotifications(enabled: Boolean)
    fun isRenderTypingNotificationsEnabled(): Flow<Boolean>

    suspend fun setSkipSessionVerification(skip: Boolean)
    fun isSessionVerificationSkipped(): Flow<Boolean>

    suspend fun setOptimizeImages(compress: Boolean)
    fun doesOptimizeImages(): Flow<Boolean>

    suspend fun setVideoCompressionPreset(preset: VideoCompressionPreset)
    fun getVideoCompressionPreset(): Flow<VideoCompressionPreset>

    /** Null when [roomId] has no client-local Android channel customization. */
    fun getRoomNotificationChannelSettingsFlow(roomId: RoomId): Flow<RoomNotificationChannelSettings?>

    /** Single-shot read of [getRoomNotificationChannelSettingsFlow], for non-Composable callers. */
    suspend fun getRoomNotificationChannelSettings(roomId: RoomId): RoomNotificationChannelSettings?

    /**
     * Persists [sound] for [roomId] and bumps its channel version, so a caller can recreate the
     * room's `NotificationChannel` under a fresh id (Android channels can't have their sound
     * mutated once created). Marks the room as having custom channel settings.
     */
    suspend fun setRoomNotificationSoundAndIncrementVersion(roomId: RoomId, sound: NotificationSound, title: String?): Int

    /**
     * Persists [priority] for [roomId] and bumps its channel version, same reasoning as
     * [setRoomNotificationSoundAndIncrementVersion]: importance can't be mutated on an existing
     * `NotificationChannel`, so a priority change must force a new channel id.
     */
    suspend fun setRoomNotificationPriority(roomId: RoomId, priority: RoomNotificationPriority): Int

    /** Pure app-level flag; Android channels don't model message-preview visibility. */
    suspend fun setRoomMessagePreviewEnabled(roomId: RoomId, enabled: Boolean)

    /** Clears all persisted customization for [roomId], reverting it to the app's shared channel. */
    suspend fun clearRoomNotificationChannelSettings(roomId: RoomId)

    /** Cheap flag read, without loading the full [RoomNotificationChannelSettings]. */
    fun hasCustomRoomNotificationChannelSettings(roomId: RoomId): Flow<Boolean>

    suspend fun clear()
}
