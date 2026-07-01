/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.notifications.channels

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioAttributes.USAGE_NOTIFICATION
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.SingleIn
import io.element.android.appconfig.NotificationConfig
import io.element.android.features.enterprise.api.EnterpriseService
import io.element.android.libraries.androidutils.hash.hash
import io.element.android.libraries.di.annotations.AppCoroutineScope
import io.element.android.libraries.di.annotations.ApplicationContext
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.preferences.api.store.RoomNotificationChannelSettings
import io.element.android.libraries.preferences.api.store.RoomNotificationPriority
import io.element.android.libraries.preferences.api.store.SessionPreferencesStore
import io.element.android.libraries.preferences.api.store.SessionPreferencesStoreFactory
import io.element.android.libraries.push.api.notifications.RoomNotificationChannelManager
import io.element.android.libraries.push.impl.notifications.shortcut.createShortcutId
import kotlinx.coroutines.CoroutineScope

private const val ROOM_NOTIFICATION_CHANNEL_ID_BASE = "ROOM_NOTIFICATION_CHANNEL"

@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.O)
private fun supportNotificationChannels() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DefaultRoomNotificationChannelManager(
    @ApplicationContext private val context: Context,
    private val notificationManager: NotificationManagerCompat,
    private val notificationChannels: NotificationChannels,
    private val enterpriseService: EnterpriseService,
    private val sessionPreferencesStoreFactory: SessionPreferencesStoreFactory,
    @AppCoroutineScope private val appCoroutineScope: CoroutineScope,
) : RoomNotificationChannelManager {
    override suspend fun getChannelIdForRoom(sessionId: SessionId, roomId: RoomId, roomDisplayName: String, noisy: Boolean): String {
        // An MDM-managed channel always wins over a per-room customization, same as
        // NotificationChannels.getChannelIdForMessage: this only ever applies to the noisy path,
        // the silent channel is never enterprise-overridden.
        if (noisy && enterpriseService.getNoisyNotificationChannelId(sessionId) != null) {
            return notificationChannels.getChannelIdForMessage(sessionId, noisy)
        }
        val settings = sessionStore(sessionId).getRoomNotificationChannelSettings(roomId)
            ?: return notificationChannels.getChannelIdForMessage(sessionId, noisy)
        return ensureRoomChannel(sessionId, roomId, roomDisplayName, settings)
    }

    override suspend fun shouldShowMessagePreview(sessionId: SessionId, roomId: RoomId): Boolean {
        return sessionStore(sessionId).getRoomNotificationChannelSettings(roomId)?.showMessagePreview ?: true
    }

    override suspend fun clearRoomChannel(sessionId: SessionId, roomId: RoomId) {
        sessionStore(sessionId).clearRoomNotificationChannelSettings(roomId)
        deleteRoomChannels(sessionId, roomId, keepId = null)
    }

    override suspend fun onRoomNotificationSettingsChanged(sessionId: SessionId, roomId: RoomId, roomDisplayName: String) {
        val settings = sessionStore(sessionId).getRoomNotificationChannelSettings(roomId)
        if (settings == null) {
            // Settings were just cleared: no version to keep, remove every channel we ever created.
            deleteRoomChannels(sessionId, roomId, keepId = null)
        } else {
            val currentId = ensureRoomChannel(sessionId, roomId, roomDisplayName, settings)
            deleteRoomChannels(sessionId, roomId, keepId = currentId)
        }
    }

    override suspend fun pruneChannelsForSession(sessionId: SessionId, roomIds: Set<RoomId>) {
        if (!supportNotificationChannels()) return
        val prefix = roomChannelSessionPrefix(sessionId)
        val validRoomHashes = roomIds.mapTo(mutableSetOf()) { it.value.hash().take(ROOM_HASH_LENGTH) }
        for (channel in notificationManager.notificationChannels) {
            val id = channel.id
            if (!id.startsWith(prefix)) continue
            val roomHash = id.removePrefix(prefix).take(ROOM_HASH_LENGTH)
            if (roomHash !in validRoomHashes) {
                notificationManager.deleteNotificationChannel(id)
            }
        }
    }

    override suspend fun clearAllChannelsForSession(sessionId: SessionId) {
        if (!supportNotificationChannels()) return
        val prefix = roomChannelSessionPrefix(sessionId)
        for (channel in notificationManager.notificationChannels) {
            if (channel.id.startsWith(prefix)) {
                notificationManager.deleteNotificationChannel(channel.id)
            }
        }
    }

    private fun sessionStore(sessionId: SessionId): SessionPreferencesStore =
        sessionPreferencesStoreFactory.get(sessionId, appCoroutineScope)

    /** Creates the room's versioned channel if it doesn't already exist on the system, and returns its id. */
    private fun ensureRoomChannel(
        sessionId: SessionId,
        roomId: RoomId,
        roomDisplayName: String,
        settings: RoomNotificationChannelSettings,
    ): String {
        if (!supportNotificationChannels()) return notificationChannels.getChannelIdForMessage(sessionId, noisy = true)
        val id = roomChannelId(sessionId, roomId, settings.channelVersion)
        if (notificationManager.getNotificationChannel(id) == null) {
            notificationManager.createNotificationChannel(buildRoomChannel(id, sessionId, roomId, roomDisplayName, settings))
        }
        return id
    }

    private fun buildRoomChannel(
        id: String,
        sessionId: SessionId,
        roomId: RoomId,
        roomDisplayName: String,
        settings: RoomNotificationChannelSettings,
    ): NotificationChannelCompat {
        val importance = when (settings.priority) {
            RoomNotificationPriority.LOW -> NotificationManagerCompat.IMPORTANCE_LOW
            RoomNotificationPriority.DEFAULT -> NotificationManagerCompat.IMPORTANCE_DEFAULT
            RoomNotificationPriority.HIGH -> NotificationManagerCompat.IMPORTANCE_HIGH
        }
        val soundUri = context.resolveNoisySoundUri(settings.sound)
        context.grantSoundUriToSystem(soundUri)
        val accentColor = NotificationConfig.NOTIFICATION_ACCENT_COLOR
        val builder = NotificationChannelCompat.Builder(id, importance)
            .setName(roomDisplayName)
            .setVibrationEnabled(true)
            .setLightsEnabled(true)
            .setLightColor(accentColor)
            // Lets Android group this under "Conversations" in system settings and surface the
            // Priority toggle scoped to this one room, rather than the whole shared channel.
            // The conversationId must match the shortcut's id exactly, since that's how system
            // Settings resolves the shortcut (and its icon) for this conversation.
            .setConversationId(notificationChannels.getChannelIdForMessage(sessionId, noisy = true), createShortcutId(sessionId, roomId))
        if (soundUri != null) {
            builder.setSound(
                soundUri,
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(USAGE_NOTIFICATION)
                    .build(),
            )
        } else {
            builder.setSound(null, null)
        }
        return builder.build()
    }

    private fun deleteRoomChannels(sessionId: SessionId, roomId: RoomId, keepId: String?) {
        if (!supportNotificationChannels()) return
        val baseId = roomChannelBaseId(sessionId, roomId)
        for (channel in notificationManager.notificationChannels) {
            val id = channel.id
            val isBaseOrVersioned = id == baseId || id.startsWith("${baseId}_v")
            if (isBaseOrVersioned && id != keepId) {
                notificationManager.deleteNotificationChannel(id)
            }
        }
    }

    companion object {
        private const val ROOM_HASH_LENGTH = 16

        private fun roomChannelSessionPrefix(sessionId: SessionId): String =
            "${ROOM_NOTIFICATION_CHANNEL_ID_BASE}_${sessionId.value.hash().take(ROOM_HASH_LENGTH)}_"

        private fun roomChannelBaseId(sessionId: SessionId, roomId: RoomId): String =
            "${roomChannelSessionPrefix(sessionId)}${roomId.value.hash().take(ROOM_HASH_LENGTH)}"

        private fun roomChannelId(sessionId: SessionId, roomId: RoomId, version: Int): String =
            versionedChannelId(roomChannelBaseId(sessionId, roomId), version)
    }
}
