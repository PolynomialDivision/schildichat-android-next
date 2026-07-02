/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.notifications.channels

import android.app.NotificationChannel
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
import io.element.android.libraries.preferences.api.store.NotificationSound
import io.element.android.libraries.preferences.api.store.RoomNotificationChannelSettings
import io.element.android.libraries.preferences.api.store.RoomNotificationPriority
import io.element.android.libraries.preferences.api.store.SessionPreferencesStore
import io.element.android.libraries.preferences.api.store.SessionPreferencesStoreFactory
import io.element.android.libraries.push.api.notifications.RoomNotificationChannelManager
import io.element.android.libraries.push.impl.notifications.shortcut.createShortcutId
import kotlinx.coroutines.CoroutineScope

// Bumped to _V2: NotificationChannel.setGroup() can only be set at creation, so filing per-room
// channels under "Private chats"/"Rooms" requires a fresh id space. The old, ungrouped family is
// deleted wholesale the next time a session's channels are touched - see deleteLegacyRoomChannels.
private const val ROOM_NOTIFICATION_CHANNEL_ID_BASE = "ROOM_NOTIFICATION_CHANNEL_V2"
private const val LEGACY_ROOM_NOTIFICATION_CHANNEL_ID_BASE = "ROOM_NOTIFICATION_CHANNEL"

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
    override suspend fun getChannelIdForRoom(sessionId: SessionId, roomId: RoomId, roomDisplayName: String, isDm: Boolean, noisy: Boolean): String {
        // An MDM-managed channel always wins over a per-room customization, same as
        // NotificationChannels.getChannelIdForMessage: this only ever applies to the noisy path,
        // the silent channel is never enterprise-overridden.
        if (noisy && enterpriseService.getNoisyNotificationChannelId(sessionId) != null) {
            return notificationChannels.getChannelIdForMessage(sessionId, noisy)
        }
        val settings = sessionStore(sessionId).getRoomNotificationChannelSettings(roomId)
        if (settings != null) {
            return ensureRoomChannel(sessionId, roomId, roomDisplayName, isDm, settings)
        }
        if (!noisy) {
            // See the class doc: only ever promote an uncustomized room to its own channel from a
            // genuinely noisy notification, so a room whose push rules only bing on mentions doesn't
            // get permanently stuck at whatever importance its first (possibly silent) event implied.
            return notificationChannels.getChannelIdForMessage(sessionId, noisy)
        }
        return ensureOrdinaryRoomChannel(sessionId, roomId, roomDisplayName, isDm)
    }

    override suspend fun shouldShowMessagePreview(sessionId: SessionId, roomId: RoomId): Boolean {
        return sessionStore(sessionId).getRoomNotificationChannelSettings(roomId)?.showMessagePreview ?: true
    }

    override suspend fun clearRoomChannel(sessionId: SessionId, roomId: RoomId) {
        sessionStore(sessionId).clearRoomNotificationChannelSettings(roomId)
        deleteRoomChannels(sessionId, roomId, keepId = null)
    }

    override suspend fun onRoomNotificationSettingsChanged(sessionId: SessionId, roomId: RoomId, roomDisplayName: String, isDm: Boolean) {
        val settings = sessionStore(sessionId).getRoomNotificationChannelSettings(roomId)
        if (settings == null) {
            // Settings were just cleared: no version to keep, remove every channel we ever created.
            deleteRoomChannels(sessionId, roomId, keepId = null)
        } else {
            val currentId = ensureRoomChannel(sessionId, roomId, roomDisplayName, isDm, settings)
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
        deleteLegacyRoomChannels(sessionId)
    }

    /**
     * One-time cleanup for the pre-channel-group room channel family (no "_V2" suffix): those are
     * permanently superseded, since NotificationChannel.setGroup() can't be applied retroactively.
     * Piggybacks on the existing pruneChannelsForSession cadence rather than a dedicated migration
     * step, since it's already called routinely (app resume) for every session.
     */
    private fun deleteLegacyRoomChannels(sessionId: SessionId) {
        val legacyPrefix = legacyRoomChannelSessionPrefix(sessionId)
        for (channel in notificationManager.notificationChannels) {
            if (channel.id.startsWith(legacyPrefix)) {
                notificationManager.deleteNotificationChannel(channel.id)
            }
        }
    }

    override suspend fun clearAllChannelsForSession(sessionId: SessionId) {
        if (!supportNotificationChannels()) return
        deleteLegacyRoomChannels(sessionId)
        val prefix = roomChannelSessionPrefix(sessionId)
        for (channel in notificationManager.notificationChannels) {
            if (channel.id.startsWith(prefix)) {
                notificationManager.deleteNotificationChannel(channel.id)
            }
        }
    }

    override suspend fun pruneInactiveOrdinaryChannels(sessionId: SessionId) {
        if (!supportNotificationChannels()) return
        val prefix = roomChannelSessionPrefix(sessionId)
        val lastNotifiedByHash = sessionStore(sessionId).getOrdinaryRoomChannelLastNotifiedByHash()
        val now = System.currentTimeMillis()

        val candidates = notificationManager.notificationChannels.mapNotNull { channel ->
            val id = channel.id
            if (!id.startsWith(prefix)) return@mapNotNull null
            val roomHash = id.removePrefix(prefix).take(ROOM_HASH_LENGTH)
            // A "_v<n>" suffix means this is a customized channel (see roomChannelId/versionedChannelId):
            // those are never eligible here, only the bare/version-0 ordinary channel is.
            val isOrdinary = id == "$prefix$roomHash"
            if (!isOrdinary || isProtectedOrdinaryChannel(channel)) return@mapNotNull null
            OrdinaryChannelCandidate(channelId = id, roomHash = roomHash, lastNotifiedAt = lastNotifiedByHash[roomHash] ?: 0L)
        }

        val staleByRetention = candidates.filter { now - it.lastNotifiedAt > ORDINARY_CHANNEL_RETENTION_MILLIS }
        val remaining = candidates - staleByRetention.toSet()
        val overBudgetCount = (remaining.size - MAX_ORDINARY_CHANNELS).coerceAtLeast(0)
        val oldestOverBudget = remaining.sortedBy { it.lastNotifiedAt }.take(overBudgetCount)

        for (candidate in staleByRetention + oldestOverBudget) {
            notificationManager.deleteNotificationChannel(candidate.channelId)
            sessionStore(sessionId).clearOrdinaryRoomChannelLastNotifiedByHash(candidate.roomHash)
        }
    }

    /**
     * True if [channel]'s live settings no longer match what [ordinaryChannelDefaultSettings] would
     * create, or the user marked it a Priority Conversation - either way, something other than this
     * manager's own creation logic touched it, so [pruneInactiveOrdinaryChannels] must leave it alone.
     */
    private fun isProtectedOrdinaryChannel(channel: NotificationChannel): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && channel.isImportantConversation) return true
        val expectedSoundUri = context.resolveNoisySoundUri(NotificationSound.SystemDefault)
        return channel.importance != NotificationManagerCompat.IMPORTANCE_DEFAULT ||
            channel.sound != expectedSoundUri ||
            !channel.shouldVibrate() ||
            !channel.shouldShowLights() ||
            channel.lightColor != NotificationConfig.NOTIFICATION_ACCENT_COLOR
    }

    private data class OrdinaryChannelCandidate(val channelId: String, val roomHash: String, val lastNotifiedAt: Long)

    private fun sessionStore(sessionId: SessionId): SessionPreferencesStore =
        sessionPreferencesStoreFactory.get(sessionId, appCoroutineScope)

    /** Creates the room's versioned channel if it doesn't already exist on the system, and returns its id. */
    private fun ensureRoomChannel(
        sessionId: SessionId,
        roomId: RoomId,
        roomDisplayName: String,
        isDm: Boolean,
        settings: RoomNotificationChannelSettings,
    ): String {
        if (!supportNotificationChannels()) return notificationChannels.getChannelIdForMessage(sessionId, noisy = true)
        val id = roomChannelId(sessionId, roomId, settings.channelVersion)
        if (notificationManager.getNotificationChannel(id) == null) {
            notificationManager.createNotificationChannel(buildRoomChannel(id, sessionId, roomId, roomDisplayName, isDm, settings))
        }
        return id
    }

    /**
     * Creates the room's ordinary (version 0, uncustomized) channel if it doesn't already exist,
     * records that it was just used (for retention pruning), and returns its id.
     */
    private suspend fun ensureOrdinaryRoomChannel(
        sessionId: SessionId,
        roomId: RoomId,
        roomDisplayName: String,
        isDm: Boolean,
    ): String {
        if (!supportNotificationChannels()) return notificationChannels.getChannelIdForMessage(sessionId, noisy = true)
        val id = roomChannelId(sessionId, roomId, version = 0)
        if (notificationManager.getNotificationChannel(id) == null) {
            notificationManager.createNotificationChannel(buildRoomChannel(id, sessionId, roomId, roomDisplayName, isDm, ordinaryChannelDefaultSettings()))
        }
        sessionStore(sessionId).recordOrdinaryRoomChannelNotified(roomId)
        return id
    }

    private fun buildRoomChannel(
        id: String,
        sessionId: SessionId,
        roomId: RoomId,
        roomDisplayName: String,
        isDm: Boolean,
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
            .setGroup(if (isDm) PRIVATE_CHATS_CHANNEL_GROUP_ID else ROOMS_CHANNEL_GROUP_ID)
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

    private suspend fun deleteRoomChannels(sessionId: SessionId, roomId: RoomId, keepId: String?) {
        if (!supportNotificationChannels()) return
        val baseId = roomChannelBaseId(sessionId, roomId)
        var deletedOrdinaryChannel = false
        for (channel in notificationManager.notificationChannels) {
            val id = channel.id
            val isBaseOrVersioned = id == baseId || id.startsWith("${baseId}_v")
            if (isBaseOrVersioned && id != keepId) {
                notificationManager.deleteNotificationChannel(id)
                if (id == baseId) deletedOrdinaryChannel = true
            }
        }
        if (deletedOrdinaryChannel) {
            sessionStore(sessionId).clearOrdinaryRoomChannelLastNotified(roomId)
        }
    }

    /**
     * The importance/sound/vibration/lights an ordinary channel is created with: matches the app's
     * shared noisy channel (default importance, default sound, vibration and lights on), since an
     * ordinary channel is only ever created from a notification that was already noisy - see the
     * class doc on [RoomNotificationChannelManager].
     */
    private fun ordinaryChannelDefaultSettings() = RoomNotificationChannelSettings(
        sound = NotificationSound.SystemDefault,
        soundDisplayName = null,
        channelVersion = 0,
        priority = RoomNotificationPriority.DEFAULT,
        showMessagePreview = true,
    )

    companion object {
        private const val ROOM_HASH_LENGTH = 16

        /** Retention window for an ordinary channel that hasn't notified: 30 days. */
        private const val ORDINARY_CHANNEL_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000

        /** Soft cap on ordinary channels per session; the oldest-notified ones are trimmed above this. */
        private const val MAX_ORDINARY_CHANNELS = 50

        private fun roomChannelSessionPrefix(sessionId: SessionId): String =
            "${ROOM_NOTIFICATION_CHANNEL_ID_BASE}_${sessionId.value.hash().take(ROOM_HASH_LENGTH)}_"

        private fun legacyRoomChannelSessionPrefix(sessionId: SessionId): String =
            "${LEGACY_ROOM_NOTIFICATION_CHANNEL_ID_BASE}_${sessionId.value.hash().take(ROOM_HASH_LENGTH)}_"

        private fun roomChannelBaseId(sessionId: SessionId, roomId: RoomId): String =
            "${roomChannelSessionPrefix(sessionId)}${roomId.value.hash().take(ROOM_HASH_LENGTH)}"

        private fun roomChannelId(sessionId: SessionId, roomId: RoomId, version: Int): String =
            versionedChannelId(roomChannelBaseId(sessionId, roomId), version)
    }
}
