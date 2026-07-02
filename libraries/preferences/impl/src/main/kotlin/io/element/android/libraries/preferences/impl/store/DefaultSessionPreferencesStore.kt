/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2024, 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.preferences.impl.store

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import io.element.android.libraries.androidutils.file.safeDelete
import io.element.android.libraries.androidutils.hash.hash
import io.element.android.libraries.core.data.tryOrNull
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.preferences.api.store.NotificationSound
import io.element.android.libraries.preferences.api.store.NotificationSound.Companion.toStored
import io.element.android.libraries.preferences.api.store.RoomNotificationChannelSettings
import io.element.android.libraries.preferences.api.store.RoomNotificationPriority
import io.element.android.libraries.preferences.api.store.SessionPreferencesStore
import io.element.android.libraries.preferences.api.store.VideoCompressionPreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

class DefaultSessionPreferencesStore(
    context: Context,
    sessionId: SessionId,
    @SessionCoroutineScope sessionCoroutineScope: CoroutineScope,
) : SessionPreferencesStore {
    companion object {
        fun storeFile(context: Context, sessionId: SessionId): File {
            val hashedUserId = sessionId.value.hash().take(16)
            return context.preferencesDataStoreFile("session_${hashedUserId}_preferences")
        }
    }

    private val sharePresenceKey = booleanPreferencesKey("sharePresence")
    private val sendPublicReadReceiptsKey = booleanPreferencesKey("sendPublicReadReceipts")
    private val renderReadReceiptsKey = booleanPreferencesKey("renderReadReceipts")
    private val sendTypingNotificationsKey = booleanPreferencesKey("sendTypingNotifications")
    private val renderTypingNotificationsKey = booleanPreferencesKey("renderTypingNotifications")
    private val skipSessionVerification = booleanPreferencesKey("skipSessionVerification")
    private val compressImages = booleanPreferencesKey("compressMedia")
    private val compressMediaPreset = stringPreferencesKey("compressMediaPreset")

    // Per-room keys are built on demand rather than declared statically: DataStore doesn't
    // require enumerable keys, and one room can't collide with another's hashed prefix.
    private fun roomHash(roomId: RoomId): String = roomId.value.hash().take(16)
    private fun roomKeyPrefix(roomId: RoomId): String = roomKeyPrefixFromHash(roomHash(roomId))
    private fun roomKeyPrefixFromHash(roomHash: String): String = "room_${roomHash}_"
    private fun roomHasCustomKey(roomId: RoomId) = booleanPreferencesKey("${roomKeyPrefix(roomId)}notifCustom")
    private fun roomSoundKey(roomId: RoomId) = stringPreferencesKey("${roomKeyPrefix(roomId)}notifSoundUri")
    private fun roomSoundDisplayNameKey(roomId: RoomId) = stringPreferencesKey("${roomKeyPrefix(roomId)}notifSoundDisplayName")
    private fun roomSoundVersionKey(roomId: RoomId) = intPreferencesKey("${roomKeyPrefix(roomId)}notifSoundVersion")
    private fun roomPriorityKey(roomId: RoomId) = stringPreferencesKey("${roomKeyPrefix(roomId)}notifPriority")
    private fun roomPreviewKey(roomId: RoomId) = booleanPreferencesKey("${roomKeyPrefix(roomId)}notifPreview")

    // Ordinary (auto-created, uncustomized) per-room channels only need a last-notified timestamp,
    // used for retention pruning. Looked up by raw room-id hash too, since a channel id enumerated
    // from the system only carries that hash, not the original RoomId.
    private val ordinaryLastNotifiedKeySuffix = "notifOrdinaryLastNotified"
    private fun roomOrdinaryLastNotifiedKey(roomId: RoomId) = longPreferencesKey("${roomKeyPrefix(roomId)}$ordinaryLastNotifiedKeySuffix")
    private fun roomOrdinaryLastNotifiedKeyFromHash(roomHash: String) =
        longPreferencesKey("${roomKeyPrefixFromHash(roomHash)}$ordinaryLastNotifiedKeySuffix")

    private val dataStoreFile = storeFile(context, sessionId)
    private val store = PreferenceDataStoreFactory.create(
        scope = sessionCoroutineScope,
        migrations = listOf(
            SessionPreferencesStoreMigration(
                sharePresenceKey,
                sendPublicReadReceiptsKey,
            )
        ),
    ) { dataStoreFile }

    override suspend fun setSharePresence(enabled: Boolean) {
        update(sharePresenceKey, enabled)
        // Also update all the other settings
        setSendPublicReadReceipts(enabled)
        setRenderReadReceipts(enabled)
        setSendTypingNotifications(enabled)
        setRenderTypingNotifications(enabled)
    }

    override fun isSharePresenceEnabled(): Flow<Boolean> {
        return get(sharePresenceKey) { true }
    }

    override suspend fun setSendPublicReadReceipts(enabled: Boolean) = update(sendPublicReadReceiptsKey, enabled)
    override fun isSendPublicReadReceiptsEnabled(): Flow<Boolean> = get(sendPublicReadReceiptsKey) { true }

    override suspend fun setRenderReadReceipts(enabled: Boolean) = update(renderReadReceiptsKey, enabled)
    override fun isRenderReadReceiptsEnabled(): Flow<Boolean> = get(renderReadReceiptsKey) { true }

    override suspend fun setSendTypingNotifications(enabled: Boolean) = update(sendTypingNotificationsKey, enabled)
    override fun isSendTypingNotificationsEnabled(): Flow<Boolean> = get(sendTypingNotificationsKey) { true }

    override suspend fun setRenderTypingNotifications(enabled: Boolean) = update(renderTypingNotificationsKey, enabled)
    override fun isRenderTypingNotificationsEnabled(): Flow<Boolean> = get(renderTypingNotificationsKey) { true }

    override suspend fun setSkipSessionVerification(skip: Boolean) = update(skipSessionVerification, skip)
    override fun isSessionVerificationSkipped(): Flow<Boolean> = get(skipSessionVerification) { false }

    override suspend fun setOptimizeImages(compress: Boolean) = update(compressImages, compress)
    override fun doesOptimizeImages(): Flow<Boolean> = get(compressImages) { true }

    override suspend fun setVideoCompressionPreset(preset: VideoCompressionPreset) = update(compressMediaPreset, preset.name)
    override fun getVideoCompressionPreset(): Flow<VideoCompressionPreset> = get(compressMediaPreset) { VideoCompressionPreset.STANDARD.name }
        .map { tryOrNull { VideoCompressionPreset.valueOf(it) } ?: VideoCompressionPreset.STANDARD }

    override fun getRoomNotificationChannelSettingsFlow(roomId: RoomId): Flow<RoomNotificationChannelSettings?> {
        return store.data.map { prefs -> prefs.toRoomNotificationChannelSettings(roomId) }
    }

    override suspend fun getRoomNotificationChannelSettings(roomId: RoomId): RoomNotificationChannelSettings? {
        return store.data.first().toRoomNotificationChannelSettings(roomId)
    }

    override suspend fun setRoomNotificationSoundAndIncrementVersion(roomId: RoomId, sound: NotificationSound, title: String?): Int {
        var newVersion = 0
        store.edit { prefs ->
            val stored = sound.toStored()
            if (stored != null) {
                prefs[roomSoundKey(roomId)] = stored
            } else {
                prefs.remove(roomSoundKey(roomId))
            }
            if (sound is NotificationSound.Custom && !title.isNullOrBlank()) {
                prefs[roomSoundDisplayNameKey(roomId)] = title
            } else {
                prefs.remove(roomSoundDisplayNameKey(roomId))
            }
            newVersion = (prefs[roomSoundVersionKey(roomId)] ?: 0) + 1
            prefs[roomSoundVersionKey(roomId)] = newVersion
            prefs[roomHasCustomKey(roomId)] = true
        }
        return newVersion
    }

    override suspend fun setRoomNotificationPriority(roomId: RoomId, priority: RoomNotificationPriority): Int {
        var newVersion = 0
        store.edit { prefs ->
            prefs[roomPriorityKey(roomId)] = priority.name
            newVersion = (prefs[roomSoundVersionKey(roomId)] ?: 0) + 1
            prefs[roomSoundVersionKey(roomId)] = newVersion
            prefs[roomHasCustomKey(roomId)] = true
        }
        return newVersion
    }

    override suspend fun setRoomMessagePreviewEnabled(roomId: RoomId, enabled: Boolean) {
        store.edit { prefs ->
            prefs[roomPreviewKey(roomId)] = enabled
            prefs[roomHasCustomKey(roomId)] = true
        }
    }

    override suspend fun clearRoomNotificationChannelSettings(roomId: RoomId) {
        store.edit { prefs ->
            prefs.remove(roomHasCustomKey(roomId))
            prefs.remove(roomSoundKey(roomId))
            prefs.remove(roomSoundDisplayNameKey(roomId))
            prefs.remove(roomSoundVersionKey(roomId))
            prefs.remove(roomPriorityKey(roomId))
            prefs.remove(roomPreviewKey(roomId))
        }
    }

    override fun hasCustomRoomNotificationChannelSettings(roomId: RoomId): Flow<Boolean> {
        return get(roomHasCustomKey(roomId)) { false }
    }

    override suspend fun recordOrdinaryRoomChannelNotified(roomId: RoomId) {
        update(roomOrdinaryLastNotifiedKey(roomId), System.currentTimeMillis())
    }

    override suspend fun clearOrdinaryRoomChannelLastNotified(roomId: RoomId) {
        store.edit { prefs -> prefs.remove(roomOrdinaryLastNotifiedKey(roomId)) }
    }

    override suspend fun getOrdinaryRoomChannelLastNotifiedByHash(): Map<String, Long> {
        val suffix = "_$ordinaryLastNotifiedKeySuffix"
        return store.data.first().asMap().entries
            .mapNotNull { (key, value) ->
                if (key.name.startsWith("room_") && key.name.endsWith(suffix) && value is Long) {
                    key.name.removePrefix("room_").removeSuffix(suffix) to value
                } else {
                    null
                }
            }
            .toMap()
    }

    override suspend fun clearOrdinaryRoomChannelLastNotifiedByHash(roomHash: String) {
        store.edit { prefs -> prefs.remove(roomOrdinaryLastNotifiedKeyFromHash(roomHash)) }
    }

    private fun Preferences.toRoomNotificationChannelSettings(roomId: RoomId): RoomNotificationChannelSettings? {
        if (this[roomHasCustomKey(roomId)] != true) return null
        return RoomNotificationChannelSettings(
            sound = NotificationSound.fromStored(this[roomSoundKey(roomId)]),
            soundDisplayName = this[roomSoundDisplayNameKey(roomId)],
            channelVersion = this[roomSoundVersionKey(roomId)] ?: 0,
            priority = this[roomPriorityKey(roomId)]?.let { tryOrNull { RoomNotificationPriority.valueOf(it) } } ?: RoomNotificationPriority.DEFAULT,
            showMessagePreview = this[roomPreviewKey(roomId)] ?: true,
        )
    }

    override suspend fun clear() {
        dataStoreFile.safeDelete()
    }

    private suspend fun <T> update(key: Preferences.Key<T>, value: T) {
        store.edit { prefs -> prefs[key] = value }
    }

    private fun <T> get(key: Preferences.Key<T>, default: () -> T): Flow<T> {
        return store.data.map { prefs -> prefs[key] ?: default() }
    }
}
