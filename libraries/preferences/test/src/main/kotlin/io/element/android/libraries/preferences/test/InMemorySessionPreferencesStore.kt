/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.preferences.test

import io.element.android.libraries.androidutils.hash.hash
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.preferences.api.store.NotificationSound
import io.element.android.libraries.preferences.api.store.RoomNotificationChannelSettings
import io.element.android.libraries.preferences.api.store.RoomNotificationPriority
import io.element.android.libraries.preferences.api.store.SessionPreferencesStore
import io.element.android.libraries.preferences.api.store.VideoCompressionPreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class InMemorySessionPreferencesStore(
    isSharePresenceEnabled: Boolean = true,
    isSendPublicReadReceiptsEnabled: Boolean = true,
    isRenderReadReceiptsEnabled: Boolean = true,
    isSendTypingNotificationsEnabled: Boolean = true,
    isRenderTypingNotificationsEnabled: Boolean = true,
    isSessionVerificationSkipped: Boolean = false,
    doesCompressMedia: Boolean = true,
    videoCompressionPreset: VideoCompressionPreset = VideoCompressionPreset.STANDARD,
) : SessionPreferencesStore {
    private val isSharePresenceEnabled = MutableStateFlow(isSharePresenceEnabled)
    private val isSendPublicReadReceiptsEnabled = MutableStateFlow(isSendPublicReadReceiptsEnabled)
    private val isRenderReadReceiptsEnabled = MutableStateFlow(isRenderReadReceiptsEnabled)
    private val isSendTypingNotificationsEnabled = MutableStateFlow(isSendTypingNotificationsEnabled)
    private val isRenderTypingNotificationsEnabled = MutableStateFlow(isRenderTypingNotificationsEnabled)
    private val isSessionVerificationSkipped = MutableStateFlow(isSessionVerificationSkipped)
    private val doesCompressMedia = MutableStateFlow(doesCompressMedia)
    private val videoCompressionPreset = MutableStateFlow(videoCompressionPreset)
    private val roomNotificationChannelSettings = mutableMapOf<RoomId, MutableStateFlow<RoomNotificationChannelSettings?>>()

    // Keyed by the same room-id hash used in channel ids (see DefaultRoomNotificationChannelManager),
    // matching how the real store has to bridge RoomId <-> channel-id-embedded hash.
    private val ordinaryRoomChannelLastNotified = mutableMapOf<String, Long>()
    var clearCallCount = 0
        private set

    private fun slotFor(roomId: RoomId) = roomNotificationChannelSettings.getOrPut(roomId) { MutableStateFlow(null) }

    private fun roomHash(roomId: RoomId): String = roomId.value.hash().take(16)

    override suspend fun setSharePresence(enabled: Boolean) {
        isSharePresenceEnabled.tryEmit(enabled)
    }

    override fun isSharePresenceEnabled(): Flow<Boolean> = isSharePresenceEnabled

    override suspend fun setSendPublicReadReceipts(enabled: Boolean) {
        isSendPublicReadReceiptsEnabled.tryEmit(enabled)
    }

    override fun isSendPublicReadReceiptsEnabled(): Flow<Boolean> = isSendPublicReadReceiptsEnabled

    override suspend fun setRenderReadReceipts(enabled: Boolean) {
        isRenderReadReceiptsEnabled.tryEmit(enabled)
    }

    override fun isRenderReadReceiptsEnabled(): Flow<Boolean> = isRenderReadReceiptsEnabled

    override suspend fun setSendTypingNotifications(enabled: Boolean) {
        isSendTypingNotificationsEnabled.tryEmit(enabled)
    }

    override fun isSendTypingNotificationsEnabled(): Flow<Boolean> = isSendTypingNotificationsEnabled

    override suspend fun setRenderTypingNotifications(enabled: Boolean) {
        isRenderTypingNotificationsEnabled.tryEmit(enabled)
    }

    override fun isRenderTypingNotificationsEnabled(): Flow<Boolean> = isRenderTypingNotificationsEnabled

    override suspend fun setSkipSessionVerification(skip: Boolean) {
        isSessionVerificationSkipped.tryEmit(skip)
    }

    override fun isSessionVerificationSkipped(): Flow<Boolean> {
        return isSessionVerificationSkipped
    }

    override suspend fun setOptimizeImages(compress: Boolean) = doesCompressMedia.emit(compress)

    override fun doesOptimizeImages(): Flow<Boolean> = doesCompressMedia

    override suspend fun setVideoCompressionPreset(preset: VideoCompressionPreset) {
        videoCompressionPreset.value = preset
    }

    override fun getVideoCompressionPreset(): Flow<VideoCompressionPreset> {
        return videoCompressionPreset
    }

    override fun getRoomNotificationChannelSettingsFlow(roomId: RoomId): Flow<RoomNotificationChannelSettings?> = slotFor(roomId)

    override suspend fun getRoomNotificationChannelSettings(roomId: RoomId): RoomNotificationChannelSettings? = slotFor(roomId).first()

    override suspend fun setRoomNotificationSoundAndIncrementVersion(roomId: RoomId, sound: NotificationSound, title: String?): Int {
        val slot = slotFor(roomId)
        val newVersion = (slot.value?.channelVersion ?: 0) + 1
        slot.value = (slot.value ?: defaultRoomNotificationChannelSettings()).copy(
            sound = sound,
            soundDisplayName = title.takeIf { sound is NotificationSound.Custom },
            channelVersion = newVersion,
        )
        return newVersion
    }

    override suspend fun setRoomNotificationPriority(roomId: RoomId, priority: RoomNotificationPriority): Int {
        val slot = slotFor(roomId)
        val newVersion = (slot.value?.channelVersion ?: 0) + 1
        slot.value = (slot.value ?: defaultRoomNotificationChannelSettings()).copy(priority = priority, channelVersion = newVersion)
        return newVersion
    }

    override suspend fun setRoomMessagePreviewEnabled(roomId: RoomId, enabled: Boolean) {
        val slot = slotFor(roomId)
        slot.value = (slot.value ?: defaultRoomNotificationChannelSettings()).copy(showMessagePreview = enabled)
    }

    override suspend fun clearRoomNotificationChannelSettings(roomId: RoomId) {
        slotFor(roomId).value = null
    }

    override fun hasCustomRoomNotificationChannelSettings(roomId: RoomId): Flow<Boolean> = slotFor(roomId).map { it != null }

    override suspend fun recordOrdinaryRoomChannelNotified(roomId: RoomId) {
        ordinaryRoomChannelLastNotified[roomHash(roomId)] = System.currentTimeMillis()
    }

    override suspend fun clearOrdinaryRoomChannelLastNotified(roomId: RoomId) {
        ordinaryRoomChannelLastNotified.remove(roomHash(roomId))
    }

    override suspend fun getOrdinaryRoomChannelLastNotifiedByHash(): Map<String, Long> = ordinaryRoomChannelLastNotified.toMap()

    override suspend fun clearOrdinaryRoomChannelLastNotifiedByHash(roomHash: String) {
        ordinaryRoomChannelLastNotified.remove(roomHash)
    }

    /** Test-only helper to backdate a room's ordinary-channel last-notified timestamp. */
    fun givenOrdinaryRoomChannelLastNotified(roomId: RoomId, timestampMs: Long) {
        ordinaryRoomChannelLastNotified[roomHash(roomId)] = timestampMs
    }

    private fun defaultRoomNotificationChannelSettings() = RoomNotificationChannelSettings(
        sound = NotificationSound.SystemDefault,
        soundDisplayName = null,
        channelVersion = 0,
        priority = RoomNotificationPriority.DEFAULT,
        showMessagePreview = true,
    )

    override suspend fun clear() {
        clearCallCount++
        isSendPublicReadReceiptsEnabled.tryEmit(true)
    }
}
