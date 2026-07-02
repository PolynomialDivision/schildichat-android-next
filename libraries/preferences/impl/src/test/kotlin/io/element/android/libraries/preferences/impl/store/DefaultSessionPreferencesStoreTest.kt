/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.preferences.impl.store

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.androidutils.hash.hash
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.preferences.api.store.NotificationSound
import io.element.android.libraries.preferences.api.store.RoomNotificationPriority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DefaultSessionPreferencesStoreTest {
    private val roomA = RoomId("!roomA:example.org")
    private val roomB = RoomId("!roomB:example.org")

    private fun CoroutineScope.createStore(sessionId: SessionId = SessionId("@alice:example.org")) =
        DefaultSessionPreferencesStore(RuntimeEnvironment.getApplication(), sessionId, this)

    @Test
    fun `room with no custom settings returns null`() = runTest {
        val store = createStore()
        assertThat(store.getRoomNotificationChannelSettings(roomA)).isNull()
        assertThat(store.hasCustomRoomNotificationChannelSettings(roomA).first()).isFalse()
    }

    @Test
    fun `setting a room sound marks the room as custom and round-trips`() = runTest {
        val store = createStore()

        val version = store.setRoomNotificationSoundAndIncrementVersion(roomA, NotificationSound.Custom("content://a"), "My tone")

        assertThat(version).isEqualTo(1)
        assertThat(store.hasCustomRoomNotificationChannelSettings(roomA).first()).isTrue()
        val settings = store.getRoomNotificationChannelSettings(roomA)
        assertThat(settings).isNotNull()
        assertThat(settings!!.sound).isEqualTo(NotificationSound.Custom("content://a"))
        assertThat(settings.soundDisplayName).isEqualTo("My tone")
        assertThat(settings.channelVersion).isEqualTo(1)
        // Defaults for fields not yet set explicitly.
        assertThat(settings.priority).isEqualTo(RoomNotificationPriority.DEFAULT)
        assertThat(settings.showMessagePreview).isTrue()
    }

    @Test
    fun `setting the sound again increments the version`() = runTest {
        val store = createStore()
        store.setRoomNotificationSoundAndIncrementVersion(roomA, NotificationSound.ElementDefault, null)
        val secondVersion = store.setRoomNotificationSoundAndIncrementVersion(roomA, NotificationSound.SystemDefault, null)

        assertThat(secondVersion).isEqualTo(2)
        assertThat(store.getRoomNotificationChannelSettings(roomA)!!.channelVersion).isEqualTo(2)
    }

    @Test
    fun `setRoomNotificationPriority marks the room as custom`() = runTest {
        val store = createStore()

        store.setRoomNotificationPriority(roomA, RoomNotificationPriority.HIGH)

        val settings = store.getRoomNotificationChannelSettings(roomA)
        assertThat(settings).isNotNull()
        assertThat(settings!!.priority).isEqualTo(RoomNotificationPriority.HIGH)
    }

    @Test
    fun `setRoomNotificationPriority bumps the channel version so a stale channel gets recreated`() = runTest {
        val store = createStore()
        val firstVersion = store.setRoomNotificationSoundAndIncrementVersion(roomA, NotificationSound.SystemDefault, null)

        val secondVersion = store.setRoomNotificationPriority(roomA, RoomNotificationPriority.HIGH)

        assertThat(secondVersion).isGreaterThan(firstVersion)
        assertThat(store.getRoomNotificationChannelSettings(roomA)!!.channelVersion).isEqualTo(secondVersion)
    }

    @Test
    fun `setRoomMessagePreviewEnabled marks the room as custom`() = runTest {
        val store = createStore()

        store.setRoomMessagePreviewEnabled(roomA, false)

        val settings = store.getRoomNotificationChannelSettings(roomA)
        assertThat(settings).isNotNull()
        assertThat(settings!!.showMessagePreview).isFalse()
    }

    @Test
    fun `settings for one room do not affect another room`() = runTest {
        val store = createStore()

        store.setRoomNotificationSoundAndIncrementVersion(roomA, NotificationSound.Custom("content://a"), "A")
        store.setRoomNotificationPriority(roomA, RoomNotificationPriority.HIGH)

        assertThat(store.getRoomNotificationChannelSettings(roomB)).isNull()
        assertThat(store.hasCustomRoomNotificationChannelSettings(roomB).first()).isFalse()

        store.setRoomNotificationSoundAndIncrementVersion(roomB, NotificationSound.Silent, null)

        val settingsA = store.getRoomNotificationChannelSettings(roomA)!!
        val settingsB = store.getRoomNotificationChannelSettings(roomB)!!
        assertThat(settingsA.sound).isEqualTo(NotificationSound.Custom("content://a"))
        assertThat(settingsA.priority).isEqualTo(RoomNotificationPriority.HIGH)
        assertThat(settingsB.sound).isEqualTo(NotificationSound.Silent)
        assertThat(settingsB.priority).isEqualTo(RoomNotificationPriority.DEFAULT)
    }

    @Test
    fun `clearRoomNotificationChannelSettings removes all custom state for the room`() = runTest {
        val store = createStore()
        store.setRoomNotificationSoundAndIncrementVersion(roomA, NotificationSound.Custom("content://a"), "A")
        store.setRoomNotificationPriority(roomA, RoomNotificationPriority.HIGH)
        store.setRoomMessagePreviewEnabled(roomA, false)

        store.clearRoomNotificationChannelSettings(roomA)

        assertThat(store.getRoomNotificationChannelSettings(roomA)).isNull()
        assertThat(store.hasCustomRoomNotificationChannelSettings(roomA).first()).isFalse()
    }

    @Test
    fun `clearing the whole session store also clears room settings`() = runTest {
        val store = createStore()
        store.setRoomNotificationSoundAndIncrementVersion(roomA, NotificationSound.Custom("content://a"), "A")

        store.clear()

        val fresh = createStore()
        assertThat(fresh.getRoomNotificationChannelSettings(roomA)).isNull()
    }

    @Test
    fun `recordOrdinaryRoomChannelNotified is readable back by the room's hash`() = runTest {
        val store = createStore()

        store.recordOrdinaryRoomChannelNotified(roomA)

        val byHash = store.getOrdinaryRoomChannelLastNotifiedByHash()
        assertThat(byHash).containsKey(roomA.value.hash().take(16))
    }

    @Test
    fun `ordinary last-notified timestamps for different rooms do not collide`() = runTest {
        val store = createStore()

        store.recordOrdinaryRoomChannelNotified(roomA)
        store.recordOrdinaryRoomChannelNotified(roomB)

        val byHash = store.getOrdinaryRoomChannelLastNotifiedByHash()
        assertThat(byHash).hasSize(2)
        assertThat(byHash).containsKey(roomA.value.hash().take(16))
        assertThat(byHash).containsKey(roomB.value.hash().take(16))
    }

    @Test
    fun `clearOrdinaryRoomChannelLastNotified removes only that room's entry`() = runTest {
        val store = createStore()
        store.recordOrdinaryRoomChannelNotified(roomA)
        store.recordOrdinaryRoomChannelNotified(roomB)

        store.clearOrdinaryRoomChannelLastNotified(roomA)

        val byHash = store.getOrdinaryRoomChannelLastNotifiedByHash()
        assertThat(byHash).doesNotContainKey(roomA.value.hash().take(16))
        assertThat(byHash).containsKey(roomB.value.hash().take(16))
    }

    @Test
    fun `clearOrdinaryRoomChannelLastNotifiedByHash removes the entry for that hash`() = runTest {
        val store = createStore()
        store.recordOrdinaryRoomChannelNotified(roomA)

        store.clearOrdinaryRoomChannelLastNotifiedByHash(roomA.value.hash().take(16))

        assertThat(store.getOrdinaryRoomChannelLastNotifiedByHash()).isEmpty()
    }
}
