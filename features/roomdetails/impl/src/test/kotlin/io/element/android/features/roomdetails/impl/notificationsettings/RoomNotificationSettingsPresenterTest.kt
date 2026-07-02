/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.roomdetails.impl.notificationsettings

import com.google.common.truth.Truth.assertThat
import io.element.android.features.roomdetails.impl.aJoinedRoom
import io.element.android.libraries.matrix.api.room.RoomNotificationMode
import io.element.android.libraries.matrix.test.AN_EXCEPTION
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.libraries.matrix.test.A_SESSION_ID
import io.element.android.libraries.matrix.test.notificationsettings.FakeNotificationSettingsService
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.preferences.api.store.NotificationSound
import io.element.android.libraries.preferences.api.store.RoomNotificationPriority
import io.element.android.libraries.preferences.test.InMemorySessionPreferencesStore
import io.element.android.libraries.push.api.notifications.sound.NotificationSoundCopier
import io.element.android.libraries.push.test.notifications.FakeSoundDisplayNameResolver
import io.element.android.libraries.push.test.notifications.channels.FakeRoomNotificationChannelManager
import io.element.android.libraries.push.test.notifications.sound.FakeNotificationSoundCopier
import io.element.android.services.toolbox.test.strings.FakeStringProvider
import io.element.android.tests.testutils.awaitLastSequentialItem
import io.element.android.tests.testutils.consumeItemsUntilPredicate
import io.element.android.tests.testutils.test
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RoomNotificationSettingsPresenterTest {
    @Test
    fun `present - initial state is created from room info`() = runTest {
        val presenter = createRoomNotificationSettingsPresenter()
        presenter.test {
            val initialState = awaitItem()
            assertThat(initialState.roomNotificationSettings.dataOrNull()).isNull()
            assertThat(initialState.defaultRoomNotificationMode).isNull()
            val loadedState = awaitItem()
            assertThat(loadedState.displayMentionsOnlyDisclaimer).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - notification mode changed`() = runTest {
        val presenter = createRoomNotificationSettingsPresenter()
        presenter.test {
            awaitItem().eventSink(RoomNotificationSettingsEvent.ChangeRoomNotificationMode(RoomNotificationMode.MENTIONS_AND_KEYWORDS_ONLY))
            val updatedState = consumeItemsUntilPredicate {
                it.roomNotificationSettings.dataOrNull()?.mode == RoomNotificationMode.MENTIONS_AND_KEYWORDS_ONLY
            }.last()
            assertThat(updatedState.roomNotificationSettings.dataOrNull()?.mode).isEqualTo(RoomNotificationMode.MENTIONS_AND_KEYWORDS_ONLY)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - observe notification mode changed`() = runTest {
        val notificationSettingsService = FakeNotificationSettingsService()
        val presenter = createRoomNotificationSettingsPresenter(notificationSettingsService)
        presenter.test {
            notificationSettingsService.setRoomNotificationMode(A_ROOM_ID, RoomNotificationMode.MENTIONS_AND_KEYWORDS_ONLY, isEncrypted = true)
            val updatedState = consumeItemsUntilPredicate {
                it.roomNotificationSettings.dataOrNull()?.mode == RoomNotificationMode.MENTIONS_AND_KEYWORDS_ONLY
            }.last()
            assertThat(updatedState.roomNotificationSettings.dataOrNull()?.mode).isEqualTo(RoomNotificationMode.MENTIONS_AND_KEYWORDS_ONLY)
        }
    }

    @Test
    fun `present - notification settings set custom failed`() = runTest {
        val notificationSettingsService = FakeNotificationSettingsService()
        notificationSettingsService.givenSetNotificationModeError(AN_EXCEPTION)
        val presenter = createRoomNotificationSettingsPresenter(notificationSettingsService)
        presenter.test {
            val initialState = awaitItem()
            initialState.eventSink(RoomNotificationSettingsEvent.SetNotificationMode(false))
            val failedState = consumeItemsUntilPredicate {
                it.setNotificationSettingAction.isFailure()
            }.last()

            assertThat(failedState.roomNotificationSettings.dataOrNull()?.isDefault).isTrue()
            assertThat(failedState.pendingSetDefault).isNull()
            assertThat(failedState.setNotificationSettingAction.isFailure()).isTrue()

            failedState.eventSink(RoomNotificationSettingsEvent.ClearSetNotificationError)

            val errorClearedState = consumeItemsUntilPredicate {
                it.setNotificationSettingAction.isUninitialized()
            }.last()
            assertThat(errorClearedState.setNotificationSettingAction.isUninitialized()).isTrue()
        }
    }

    @Test
    fun `present - notification settings set custom`() = runTest {
        val notificationSettingsService = FakeNotificationSettingsService()
        val presenter = createRoomNotificationSettingsPresenter(notificationSettingsService)
        presenter.test {
            val initialState = awaitItem()
            initialState.eventSink(RoomNotificationSettingsEvent.SetNotificationMode(false))
            skipItems(3)
            val defaultState = awaitItem()
            assertThat(defaultState.roomNotificationSettings.dataOrNull()?.isDefault).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - notification settings restore default`() = runTest {
        val presenter = createRoomNotificationSettingsPresenter()
        presenter.test {
            val initialState = awaitItem()
            initialState.eventSink(RoomNotificationSettingsEvent.ChangeRoomNotificationMode(RoomNotificationMode.MENTIONS_AND_KEYWORDS_ONLY))
            initialState.eventSink(RoomNotificationSettingsEvent.SetNotificationMode(true))
            val defaultState = consumeItemsUntilPredicate {
                it.roomNotificationSettings.dataOrNull()?.mode == RoomNotificationMode.MENTIONS_AND_KEYWORDS_ONLY
            }.last()
            assertThat(defaultState.roomNotificationSettings.dataOrNull()?.mode).isEqualTo(RoomNotificationMode.MENTIONS_AND_KEYWORDS_ONLY)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - notification settings restore default failed`() = runTest {
        val notificationSettingsService = FakeNotificationSettingsService()
        notificationSettingsService.givenRestoreDefaultNotificationModeError(AN_EXCEPTION)
        val presenter = createRoomNotificationSettingsPresenter(notificationSettingsService)
        presenter.test {
            val initialState = awaitItem()
            initialState.eventSink(RoomNotificationSettingsEvent.ChangeRoomNotificationMode(RoomNotificationMode.MENTIONS_AND_KEYWORDS_ONLY))
            initialState.eventSink(RoomNotificationSettingsEvent.SetNotificationMode(true))
            val failedState = consumeItemsUntilPredicate {
                it.restoreDefaultAction.isFailure()
            }.last()
            assertThat(failedState.restoreDefaultAction.isFailure()).isTrue()
            failedState.eventSink(RoomNotificationSettingsEvent.ClearRestoreDefaultError)

            val errorClearedState = consumeItemsUntilPredicate {
                it.restoreDefaultAction.isUninitialized()
            }.last()
            assertThat(errorClearedState.restoreDefaultAction.isUninitialized()).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - display mentions only warning for a room if homeserver does not support it and it's encrypted`() = runTest {
        val notificationService = FakeNotificationSettingsService().apply {
            givenCanHomeServerPushEncryptedEventsToDeviceResult(Result.success(false))
        }
        val room = aJoinedRoom(notificationSettingsService = notificationService, isEncrypted = true)
        val presenter = createRoomNotificationSettingsPresenter(notificationService, room)
        presenter.test {
            assertThat(awaitLastSequentialItem().displayMentionsOnlyDisclaimer).isTrue()
        }
    }

    @Test
    fun `present - do not display mentions only warning for a room it's not encrypted`() = runTest {
        val notificationService = FakeNotificationSettingsService().apply {
            givenCanHomeServerPushEncryptedEventsToDeviceResult(Result.success(false))
        }
        val room = aJoinedRoom(notificationSettingsService = notificationService, isEncrypted = false)
        val presenter = createRoomNotificationSettingsPresenter(notificationService, room)
        presenter.test {
            assertThat(awaitLastSequentialItem().displayMentionsOnlyDisclaimer).isFalse()
        }
    }

    @Test
    fun `present - setting a room sound persists it and recreates the channel`() = runTest {
        val sessionPreferencesStore = InMemorySessionPreferencesStore()
        var recreatedRoom: Any? = null
        val roomNotificationChannelManager = FakeRoomNotificationChannelManager(
            onRoomNotificationSettingsChangedLambda = { sessionId, roomId, _, _ -> recreatedRoom = sessionId to roomId },
        )
        val presenter = createRoomNotificationSettingsPresenter(
            sessionPreferencesStore = sessionPreferencesStore,
            roomNotificationChannelManager = roomNotificationChannelManager,
        )
        presenter.test {
            awaitItem().eventSink(RoomNotificationSettingsEvent.SetSound(NotificationSound.SystemDefault))
            val updatedState = consumeItemsUntilPredicate {
                it.roomChannelSettings?.sound == NotificationSound.SystemDefault
            }.last()
            assertThat(updatedState.roomChannelSettings?.sound).isEqualTo(NotificationSound.SystemDefault)
            assertThat(recreatedRoom).isEqualTo(A_SESSION_ID to A_ROOM_ID)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - setting priority persists it and recreates the channel`() = runTest {
        val sessionPreferencesStore = InMemorySessionPreferencesStore()
        var channelRecreated = false
        val roomNotificationChannelManager = FakeRoomNotificationChannelManager(
            onRoomNotificationSettingsChangedLambda = { _, _, _, _ -> channelRecreated = true },
        )
        val presenter = createRoomNotificationSettingsPresenter(
            sessionPreferencesStore = sessionPreferencesStore,
            roomNotificationChannelManager = roomNotificationChannelManager,
        )
        presenter.test {
            awaitItem().eventSink(RoomNotificationSettingsEvent.SetPriority(RoomNotificationPriority.HIGH))
            val updatedState = consumeItemsUntilPredicate {
                it.roomChannelSettings?.priority == RoomNotificationPriority.HIGH
            }.last()
            assertThat(updatedState.roomChannelSettings?.priority).isEqualTo(RoomNotificationPriority.HIGH)
            assertThat(updatedState.showPriorityDialog).isFalse()
            assertThat(channelRecreated).isTrue()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - disabling the message preview persists it`() = runTest {
        val sessionPreferencesStore = InMemorySessionPreferencesStore()
        val presenter = createRoomNotificationSettingsPresenter(
            sessionPreferencesStore = sessionPreferencesStore,
            roomNotificationChannelManager = FakeRoomNotificationChannelManager(
                onRoomNotificationSettingsChangedLambda = { _, _, _, _ -> },
            ),
        )
        presenter.test {
            awaitItem().eventSink(RoomNotificationSettingsEvent.SetPreviewEnabled(false))
            val updatedState = consumeItemsUntilPredicate {
                it.roomChannelSettings?.showMessagePreview == false
            }.last()
            assertThat(updatedState.roomChannelSettings?.showMessagePreview).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `present - resetting channel settings clears the room channel`() = runTest {
        val sessionPreferencesStore = InMemorySessionPreferencesStore()
        var clearedRoom: Any? = null
        val presenter = createRoomNotificationSettingsPresenter(
            sessionPreferencesStore = sessionPreferencesStore,
            roomNotificationChannelManager = FakeRoomNotificationChannelManager(
                clearRoomChannelLambda = { sessionId, roomId -> clearedRoom = sessionId to roomId },
            ),
        )
        presenter.test {
            awaitItem().eventSink(RoomNotificationSettingsEvent.ResetChannelSettings)
            awaitLastSequentialItem()
            assertThat(clearedRoom).isEqualTo(A_SESSION_ID to A_ROOM_ID)
        }
    }

    @Test
    fun `present - show and dismiss sound and priority dialogs`() = runTest {
        val presenter = createRoomNotificationSettingsPresenter()
        presenter.test {
            val initialState = awaitItem()
            assertThat(initialState.showSoundDialog).isFalse()
            assertThat(initialState.showPriorityDialog).isFalse()

            initialState.eventSink(RoomNotificationSettingsEvent.ShowSoundDialog)
            assertThat(awaitItem().showSoundDialog).isTrue()
            initialState.eventSink(RoomNotificationSettingsEvent.DismissSoundDialog)
            assertThat(awaitItem().showSoundDialog).isFalse()

            initialState.eventSink(RoomNotificationSettingsEvent.ShowPriorityDialog)
            assertThat(awaitItem().showPriorityDialog).isTrue()
            initialState.eventSink(RoomNotificationSettingsEvent.DismissPriorityDialog)
            assertThat(awaitItem().showPriorityDialog).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun createRoomNotificationSettingsPresenter(
        notificationSettingsService: FakeNotificationSettingsService = FakeNotificationSettingsService(),
        room: FakeJoinedRoom = aJoinedRoom(notificationSettingsService = notificationSettingsService),
        sessionPreferencesStore: InMemorySessionPreferencesStore = InMemorySessionPreferencesStore(),
        roomNotificationChannelManager: FakeRoomNotificationChannelManager = FakeRoomNotificationChannelManager(),
        notificationSoundCopier: NotificationSoundCopier = FakeNotificationSoundCopier(),
    ): RoomNotificationSettingsPresenter {
        return RoomNotificationSettingsPresenter(
            room = room,
            notificationSettingsService = notificationSettingsService,
            sessionPreferencesStore = sessionPreferencesStore,
            roomNotificationChannelManager = roomNotificationChannelManager,
            notificationSoundCopier = notificationSoundCopier,
            soundDisplayNameResolver = FakeSoundDisplayNameResolver(),
            stringProvider = FakeStringProvider(),
            showUserDefinedSettingStyle = false,
        )
    }
}
