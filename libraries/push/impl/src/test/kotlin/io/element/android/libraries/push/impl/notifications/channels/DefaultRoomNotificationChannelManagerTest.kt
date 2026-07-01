/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.notifications.channels

import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.google.common.truth.Truth.assertThat
import io.element.android.features.enterprise.test.FakeEnterpriseService
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.preferences.api.store.NotificationSound
import io.element.android.libraries.preferences.api.store.RoomNotificationPriority
import io.element.android.libraries.preferences.test.FakeSessionPreferencesStoreFactory
import io.element.android.libraries.preferences.test.InMemorySessionPreferencesStore
import io.element.android.libraries.push.impl.notifications.shortcut.createShortcutId
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
        sessionPreferencesStoreFactory = FakeSessionPreferencesStoreFactory(getLambda = { _, _ -> store }),
        appCoroutineScope = MainScope(),
    )

    @Test
    fun `room without custom settings falls back to the shared channel`() = runTest {
        val manager = createManager()

        val channelId = manager.getChannelIdForRoom(sessionId, roomA, "Room A", noisy = true)

        assertThat(channelId).isEqualTo("SHARED_NOISY")
        assertThat(manager.shouldShowMessagePreview(sessionId, roomA)).isTrue()
    }

    @Test
    fun `enterprise override always wins over a per-room channel`() = runTest {
        val manager = createManager(
            enterpriseService = FakeEnterpriseService(getNoisyNotificationChannelIdResult = { "MDM_CHANNEL" }),
        )
        store.setRoomNotificationSoundAndIncrementVersion(roomA, NotificationSound.SystemDefault, null)

        val channelId = manager.getChannelIdForRoom(sessionId, roomA, "Room A", noisy = true)

        assertThat(channelId).isEqualTo("MDM_CHANNEL")
    }

    @Test
    fun `custom settings create a versioned channel with setConversationId and the room name`() = runTest {
        val manager = createManager()
        store.setRoomNotificationSoundAndIncrementVersion(roomA, NotificationSound.SystemDefault, null)
        store.setRoomNotificationPriority(roomA, RoomNotificationPriority.HIGH)

        val channelId = manager.getChannelIdForRoom(sessionId, roomA, "Room A", noisy = true)

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
        val firstChannelId = manager.getChannelIdForRoom(sessionId, roomA, "Room A", noisy = true)
        assertThat(notificationManager.getNotificationChannel(firstChannelId)!!.importance).isEqualTo(NotificationManager.IMPORTANCE_DEFAULT)

        store.setRoomNotificationPriority(roomA, RoomNotificationPriority.HIGH)
        manager.onRoomNotificationSettingsChanged(sessionId, roomA, "Room A")

        val secondChannelId = manager.getChannelIdForRoom(sessionId, roomA, "Room A", noisy = true)
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
        val channelId = manager.getChannelIdForRoom(sessionId, roomA, "Room A", noisy = true)
        assertThat(notificationManager.getNotificationChannel(channelId)).isNotNull()

        manager.clearRoomChannel(sessionId, roomA)

        assertThat(notificationManager.getNotificationChannel(channelId)).isNull()
        assertThat(store.getRoomNotificationChannelSettings(roomA)).isNull()
    }

    @Test
    fun `onRoomNotificationSettingsChanged recreates under the new version and removes the stale channel`() = runTest {
        val manager = createManager()
        store.setRoomNotificationSoundAndIncrementVersion(roomA, NotificationSound.SystemDefault, null)
        val firstId = manager.getChannelIdForRoom(sessionId, roomA, "Room A", noisy = true)

        // Simulate the user changing the sound again: bumps the persisted version.
        store.setRoomNotificationSoundAndIncrementVersion(roomA, NotificationSound.Silent, null)
        manager.onRoomNotificationSettingsChanged(sessionId, roomA, "Room A")

        val secondId = manager.getChannelIdForRoom(sessionId, roomA, "Room A", noisy = true)
        assertThat(secondId).isNotEqualTo(firstId)
        assertThat(notificationManager.getNotificationChannel(firstId)).isNull()
        assertThat(notificationManager.getNotificationChannel(secondId)).isNotNull()
    }

    @Test
    fun `onRoomNotificationSettingsChanged with cleared settings removes any leftover channel`() = runTest {
        val manager = createManager()
        store.setRoomNotificationSoundAndIncrementVersion(roomA, NotificationSound.SystemDefault, null)
        val channelId = manager.getChannelIdForRoom(sessionId, roomA, "Room A", noisy = true)

        store.clearRoomNotificationChannelSettings(roomA)
        manager.onRoomNotificationSettingsChanged(sessionId, roomA, "Room A")

        assertThat(notificationManager.getNotificationChannel(channelId)).isNull()
    }

    @Test
    fun `pruneChannelsForSession deletes channels for rooms no longer present`() = runTest {
        val manager = createManager()
        store.setRoomNotificationSoundAndIncrementVersion(roomA, NotificationSound.SystemDefault, null)
        store.setRoomNotificationSoundAndIncrementVersion(roomB, NotificationSound.SystemDefault, null)
        val idA = manager.getChannelIdForRoom(sessionId, roomA, "Room A", noisy = true)
        val idB = manager.getChannelIdForRoom(sessionId, roomB, "Room B", noisy = true)

        manager.pruneChannelsForSession(sessionId, roomIds = setOf(roomB))

        assertThat(notificationManager.getNotificationChannel(idA)).isNull()
        assertThat(notificationManager.getNotificationChannel(idB)).isNotNull()
    }

    @Test
    fun `clearAllChannelsForSession deletes every room channel for that session`() = runTest {
        val manager = createManager()
        store.setRoomNotificationSoundAndIncrementVersion(roomA, NotificationSound.SystemDefault, null)
        store.setRoomNotificationSoundAndIncrementVersion(roomB, NotificationSound.SystemDefault, null)
        val idA = manager.getChannelIdForRoom(sessionId, roomA, "Room A", noisy = true)
        val idB = manager.getChannelIdForRoom(sessionId, roomB, "Room B", noisy = true)

        manager.clearAllChannelsForSession(sessionId)

        assertThat(notificationManager.getNotificationChannel(idA)).isNull()
        assertThat(notificationManager.getNotificationChannel(idB)).isNull()
    }
}
