/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.notifications.channels

import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import com.google.common.truth.Truth.assertThat
import io.element.android.features.enterprise.test.FakeEnterpriseService
import io.element.android.libraries.androidutils.hash.hash
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.preferences.api.store.NotificationSound
import io.element.android.libraries.preferences.api.store.RoomNotificationPriority
import io.element.android.libraries.preferences.test.FakeSessionPreferencesStoreFactory
import io.element.android.libraries.preferences.test.InMemorySessionPreferencesStore
import io.element.android.libraries.push.impl.notifications.shortcut.createShortcutId
import io.element.android.tests.testutils.lambda.lambdaRecorder
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class DefaultRoomNotificationChannelManagerTest {
    private val sessionId = SessionId("@alice:example.org")
    private val roomA = RoomId("!roomA:example.org")
    private val roomB = RoomId("!roomB:example.org")
    private val context = RuntimeEnvironment.getApplication()
    private val notificationManager = NotificationManagerCompat.from(context)
    private val store = InMemorySessionPreferencesStore()

    private fun createManager(
        enterpriseService: FakeEnterpriseService = FakeEnterpriseService(getNoisyNotificationChannelIdResult = { null }),
        notificationChannels: FakeNotificationChannels = FakeNotificationChannels(
            channelIdForMessage = { _, noisy -> if (noisy) "SHARED_NOISY" else "SHARED_SILENT" },
        ),
    ) = DefaultRoomNotificationChannelManager(
        context = context,
        notificationManager = notificationManager,
        notificationChannels = notificationChannels,
        enterpriseService = enterpriseService,
        sessionPreferencesStoreFactory = FakeSessionPreferencesStoreFactory(getLambda = lambdaRecorder { _, _ -> store }),
        appCoroutineScope = MainScope(),
    )

    @Test
    fun `room without custom settings and a silent notification falls back to the shared channel`() = runTest {
        val manager = createManager()

        val channelId = manager.getChannelIdForRoom(sessionId, roomA, "Room A", isDm = false, noisy = false)

        assertThat(channelId).isEqualTo("SHARED_SILENT")
        assertThat(manager.shouldShowMessagePreview(sessionId, roomA)).isTrue()
    }

    @Test
    fun `room without custom settings and a noisy notification gets its own ordinary channel`() = runTest {
        val manager = createManager()

        val channelId = manager.getChannelIdForRoom(sessionId, roomA, "Room A", isDm = false, noisy = true)

        assertThat(channelId).isNotEqualTo("SHARED_NOISY")
        val channel = notificationManager.getNotificationChannel(channelId)
        assertThat(channel).isNotNull()
        assertThat(channel!!.name).isEqualTo("Room A")
        assertThat(channel.importance).isEqualTo(NotificationManager.IMPORTANCE_DEFAULT)
        assertThat(channel.conversationId).isEqualTo(createShortcutId(sessionId, roomA))
        assertThat(channel.parentChannelId).isEqualTo("SHARED_NOISY")
        // Uncustomized, so message preview behaves exactly as before this existed.
        assertThat(manager.shouldShowMessagePreview(sessionId, roomA)).isTrue()
    }

    @Test
    fun `a DM room's ordinary channel is filed under the Private chats group`() = runTest {
        val manager = createManager()

        val channelId = manager.getChannelIdForRoom(sessionId, roomA, "Room A", isDm = true, noisy = true)

        assertThat(notificationManager.getNotificationChannel(channelId)!!.group).isEqualTo(PRIVATE_CHATS_CHANNEL_GROUP_ID)
    }

    @Test
    fun `a non-DM room's ordinary channel is filed under the Rooms group`() = runTest {
        val manager = createManager()

        val channelId = manager.getChannelIdForRoom(sessionId, roomA, "Room A", isDm = false, noisy = true)

        assertThat(notificationManager.getNotificationChannel(channelId)!!.group).isEqualTo(ROOMS_CHANNEL_GROUP_ID)
    }

    @Test
    fun `a customized DM room's channel is also filed under the Private chats group`() = runTest {
        val manager = createManager()
        store.setRoomNotificationSoundAndIncrementVersion(roomA, NotificationSound.SystemDefault, null)

        val channelId = manager.getChannelIdForRoom(sessionId, roomA, "Room A", isDm = true, noisy = true)

        assertThat(notificationManager.getNotificationChannel(channelId)!!.group).isEqualTo(PRIVATE_CHATS_CHANNEL_GROUP_ID)
    }

    @Test
    fun `pruneChannelsForSession deletes leftover pre-channel-group legacy room channels`() = runTest {
        val manager = createManager()
        val legacyId = "ROOM_NOTIFICATION_CHANNEL_${sessionId.value.hash().take(16)}_${roomA.value.hash().take(16)}"
        notificationManager.createNotificationChannel(NotificationChannelCompat.Builder(legacyId, NotificationManagerCompat.IMPORTANCE_DEFAULT).build())
        assertThat(notificationManager.getNotificationChannel(legacyId)).isNotNull()

        manager.pruneChannelsForSession(sessionId, roomIds = emptySet())

        assertThat(notificationManager.getNotificationChannel(legacyId)).isNull()
    }

    @Test
    fun `clearAllChannelsForSession deletes leftover pre-channel-group legacy room channels`() = runTest {
        val manager = createManager()
        val legacyId = "ROOM_NOTIFICATION_CHANNEL_${sessionId.value.hash().take(16)}_${roomA.value.hash().take(16)}"
        notificationManager.createNotificationChannel(NotificationChannelCompat.Builder(legacyId, NotificationManagerCompat.IMPORTANCE_DEFAULT).build())
        assertThat(notificationManager.getNotificationChannel(legacyId)).isNotNull()

        manager.clearAllChannelsForSession(sessionId)

        assertThat(notificationManager.getNotificationChannel(legacyId)).isNull()
    }

    @Test
    fun `an ordinary channel is only created once, not on every notification`() = runTest {
        val manager = createManager()

        val firstId = manager.getChannelIdForRoom(sessionId, roomA, "Room A", isDm = false, noisy = true)
        val secondId = manager.getChannelIdForRoom(sessionId, roomA, "Room A", isDm = false, noisy = true)

        assertThat(secondId).isEqualTo(firstId)
    }

    @Test
    fun `a silent notification for a room with an existing ordinary channel still uses the shared silent channel`() = runTest {
        // Regression test: some push rule modes only bing on specific events (e.g. mentions) within
        // an otherwise-quiet room. The room's ordinary channel must not swallow that distinction.
        val manager = createManager()
        manager.getChannelIdForRoom(sessionId, roomA, "Room A", isDm = false, noisy = true)

        val channelId = manager.getChannelIdForRoom(sessionId, roomA, "Room A", isDm = false, noisy = false)

        assertThat(channelId).isEqualTo("SHARED_SILENT")
    }

    @Test
    fun `enterprise override always wins over a per-room channel`() = runTest {
        val manager = createManager(
            enterpriseService = FakeEnterpriseService(getNoisyNotificationChannelIdResult = { "MDM_CHANNEL" }),
        )
        store.setRoomNotificationSoundAndIncrementVersion(roomA, NotificationSound.SystemDefault, null)

        val channelId = manager.getChannelIdForRoom(sessionId, roomA, "Room A", isDm = false, noisy = true)

        assertThat(channelId).isEqualTo("MDM_CHANNEL")
    }

    @Test
    fun `custom settings create a versioned channel with setConversationId and the room name`() = runTest {
        val manager = createManager()
        store.setRoomNotificationSoundAndIncrementVersion(roomA, NotificationSound.SystemDefault, null)
        store.setRoomNotificationPriority(roomA, RoomNotificationPriority.HIGH)

        val channelId = manager.getChannelIdForRoom(sessionId, roomA, "Room A", isDm = false, noisy = true)

        // Both the sound and the priority set above bump the shared channel version, so the id
        // reflects both changes (v1 then v2), not just the first one.
        assertThat(channelId).endsWith("_v2")
        val channel = notificationManager.getNotificationChannel(channelId)
        assertThat(channel).isNotNull()
        assertThat(channel!!.name).isEqualTo("Room A")
        assertThat(channel.importance).isEqualTo(NotificationManager.IMPORTANCE_HIGH)
        assertThat(channel.conversationId).isEqualTo(createShortcutId(sessionId, roomA))
        assertThat(channel.parentChannelId).isEqualTo("SHARED_NOISY")
    }

    @Test
    fun `changing priority after the channel already exists recreates it with the new importance`() = runTest {
        // Regression test: setRoomNotificationPriority must bump the channel version, since
        // Android does not allow mutating an existing NotificationChannel's importance in place.
        val manager = createManager()
        store.setRoomNotificationSoundAndIncrementVersion(roomA, NotificationSound.SystemDefault, null)
        val firstChannelId = manager.getChannelIdForRoom(sessionId, roomA, "Room A", isDm = false, noisy = true)
        assertThat(notificationManager.getNotificationChannel(firstChannelId)!!.importance).isEqualTo(NotificationManager.IMPORTANCE_DEFAULT)

        store.setRoomNotificationPriority(roomA, RoomNotificationPriority.HIGH)
        manager.onRoomNotificationSettingsChanged(sessionId, roomA, "Room A", isDm = false)

        val secondChannelId = manager.getChannelIdForRoom(sessionId, roomA, "Room A", isDm = false, noisy = true)
        assertThat(secondChannelId).isNotEqualTo(firstChannelId)
        assertThat(notificationManager.getNotificationChannel(firstChannelId)).isNull()
        assertThat(notificationManager.getNotificationChannel(secondChannelId)!!.importance).isEqualTo(NotificationManager.IMPORTANCE_HIGH)
    }

    @Test
    fun `shouldShowMessagePreview reflects the persisted preview flag`() = runTest {
        val manager = createManager()
        store.setRoomMessagePreviewEnabled(roomA, false)

        assertThat(manager.shouldShowMessagePreview(sessionId, roomA)).isFalse()
        assertThat(manager.shouldShowMessagePreview(sessionId, roomB)).isTrue()
    }

    @Test
    fun `clearRoomChannel deletes the channel and the persisted settings`() = runTest {
        val manager = createManager()
        store.setRoomNotificationSoundAndIncrementVersion(roomA, NotificationSound.SystemDefault, null)
        val channelId = manager.getChannelIdForRoom(sessionId, roomA, "Room A", isDm = false, noisy = true)
        assertThat(notificationManager.getNotificationChannel(channelId)).isNotNull()

        manager.clearRoomChannel(sessionId, roomA)

        assertThat(notificationManager.getNotificationChannel(channelId)).isNull()
        assertThat(store.getRoomNotificationChannelSettings(roomA)).isNull()
    }

    @Test
    fun `onRoomNotificationSettingsChanged recreates under the new version and removes the stale channel`() = runTest {
        val manager = createManager()
        store.setRoomNotificationSoundAndIncrementVersion(roomA, NotificationSound.SystemDefault, null)
        val firstId = manager.getChannelIdForRoom(sessionId, roomA, "Room A", isDm = false, noisy = true)

        // Simulate the user changing the sound again: bumps the persisted version.
        store.setRoomNotificationSoundAndIncrementVersion(roomA, NotificationSound.Silent, null)
        manager.onRoomNotificationSettingsChanged(sessionId, roomA, "Room A", isDm = false)

        val secondId = manager.getChannelIdForRoom(sessionId, roomA, "Room A", isDm = false, noisy = true)
        assertThat(secondId).isNotEqualTo(firstId)
        assertThat(notificationManager.getNotificationChannel(firstId)).isNull()
        assertThat(notificationManager.getNotificationChannel(secondId)).isNotNull()
    }

    @Test
    fun `onRoomNotificationSettingsChanged with cleared settings removes any leftover channel`() = runTest {
        val manager = createManager()
        store.setRoomNotificationSoundAndIncrementVersion(roomA, NotificationSound.SystemDefault, null)
        val channelId = manager.getChannelIdForRoom(sessionId, roomA, "Room A", isDm = false, noisy = true)

        store.clearRoomNotificationChannelSettings(roomA)
        manager.onRoomNotificationSettingsChanged(sessionId, roomA, "Room A", isDm = false)

        assertThat(notificationManager.getNotificationChannel(channelId)).isNull()
    }

    @Test
    fun `pruneChannelsForSession deletes channels for rooms no longer present`() = runTest {
        val manager = createManager()
        store.setRoomNotificationSoundAndIncrementVersion(roomA, NotificationSound.SystemDefault, null)
        store.setRoomNotificationSoundAndIncrementVersion(roomB, NotificationSound.SystemDefault, null)
        val idA = manager.getChannelIdForRoom(sessionId, roomA, "Room A", isDm = false, noisy = true)
        val idB = manager.getChannelIdForRoom(sessionId, roomB, "Room B", isDm = false, noisy = true)

        manager.pruneChannelsForSession(sessionId, roomIds = setOf(roomB))

        assertThat(notificationManager.getNotificationChannel(idA)).isNull()
        assertThat(notificationManager.getNotificationChannel(idB)).isNotNull()
    }

    @Test
    fun `clearAllChannelsForSession deletes every room channel for that session`() = runTest {
        val manager = createManager()
        store.setRoomNotificationSoundAndIncrementVersion(roomA, NotificationSound.SystemDefault, null)
        store.setRoomNotificationSoundAndIncrementVersion(roomB, NotificationSound.SystemDefault, null)
        val idA = manager.getChannelIdForRoom(sessionId, roomA, "Room A", isDm = false, noisy = true)
        val idB = manager.getChannelIdForRoom(sessionId, roomB, "Room B", isDm = false, noisy = true)

        manager.clearAllChannelsForSession(sessionId)

        assertThat(notificationManager.getNotificationChannel(idA)).isNull()
        assertThat(notificationManager.getNotificationChannel(idB)).isNull()
    }

    @Test
    fun `customizing a room deletes its earlier ordinary channel`() = runTest {
        // Regression test: an ordinary channel uses version 0 (bare id), the same base id a
        // customized channel is versioned from, so the existing delete-stale-versions cleanup
        // must already catch it with no changes of its own.
        val manager = createManager()
        val ordinaryId = manager.getChannelIdForRoom(sessionId, roomA, "Room A", isDm = false, noisy = true)

        store.setRoomNotificationSoundAndIncrementVersion(roomA, NotificationSound.SystemDefault, null)
        manager.onRoomNotificationSettingsChanged(sessionId, roomA, "Room A", isDm = false)

        assertThat(notificationManager.getNotificationChannel(ordinaryId)).isNull()
    }

    @Test
    fun `pruneInactiveOrdinaryChannels leaves customized channels alone regardless of inactivity`() = runTest {
        val manager = createManager()
        store.setRoomNotificationSoundAndIncrementVersion(roomA, NotificationSound.SystemDefault, null)
        val channelId = manager.getChannelIdForRoom(sessionId, roomA, "Room A", isDm = false, noisy = true)

        manager.pruneInactiveOrdinaryChannels(sessionId)

        assertThat(notificationManager.getNotificationChannel(channelId)).isNotNull()
    }

    @Test
    fun `pruneInactiveOrdinaryChannels leaves a channel alone if its live settings were changed`() = runTest {
        val manager = createManager()
        val channelId = manager.getChannelIdForRoom(sessionId, roomA, "Room A", isDm = false, noisy = true)
        // Simulate the user changing importance for this specific channel via system Settings.
        notificationManager.getNotificationChannel(channelId)!!.importance = NotificationManager.IMPORTANCE_LOW
        store.givenOrdinaryRoomChannelLastNotified(roomA, System.currentTimeMillis() - THIRTY_ONE_DAYS_MILLIS)

        manager.pruneInactiveOrdinaryChannels(sessionId)

        assertThat(notificationManager.getNotificationChannel(channelId)).isNotNull()
    }

    @Test
    fun `pruneInactiveOrdinaryChannels deletes an unmodified ordinary channel past the retention window`() = runTest {
        val manager = createManager()
        val channelId = manager.getChannelIdForRoom(sessionId, roomA, "Room A", isDm = false, noisy = true)
        store.givenOrdinaryRoomChannelLastNotified(roomA, System.currentTimeMillis() - THIRTY_ONE_DAYS_MILLIS)

        manager.pruneInactiveOrdinaryChannels(sessionId)

        assertThat(notificationManager.getNotificationChannel(channelId)).isNull()
    }

    @Test
    fun `pruneInactiveOrdinaryChannels keeps a recently-notified ordinary channel`() = runTest {
        val manager = createManager()
        val channelId = manager.getChannelIdForRoom(sessionId, roomA, "Room A", isDm = false, noisy = true)

        manager.pruneInactiveOrdinaryChannels(sessionId)

        assertThat(notificationManager.getNotificationChannel(channelId)).isNotNull()
    }

    @Test
    fun `pruneInactiveOrdinaryChannels trims the oldest channels once over the count limit`() = runTest {
        val manager = createManager()
        val now = System.currentTimeMillis()
        val roomIds = (0 until MAX_ORDINARY_CHANNELS + 2).map { RoomId("!room$it:example.org") }
        val channelIds = roomIds.mapIndexed { index, roomId ->
            val id = manager.getChannelIdForRoom(sessionId, roomId, "Room $index", isDm = false, noisy = true)
            // All well within the retention window, but with a clear oldest-first order to trim.
            store.givenOrdinaryRoomChannelLastNotified(roomId, now - (roomIds.size - index))
            id
        }

        manager.pruneInactiveOrdinaryChannels(sessionId)

        val remaining = channelIds.count { notificationManager.getNotificationChannel(it) != null }
        assertThat(remaining).isEqualTo(MAX_ORDINARY_CHANNELS)
        // The two oldest-notified rooms (index 0 and 1) should be the ones trimmed.
        assertThat(notificationManager.getNotificationChannel(channelIds[0])).isNull()
        assertThat(notificationManager.getNotificationChannel(channelIds[1])).isNull()
        assertThat(notificationManager.getNotificationChannel(channelIds.last())).isNotNull()
    }

    private companion object {
        const val THIRTY_ONE_DAYS_MILLIS = 31L * 24 * 60 * 60 * 1000
        const val MAX_ORDINARY_CHANNELS = 50
    }
}
