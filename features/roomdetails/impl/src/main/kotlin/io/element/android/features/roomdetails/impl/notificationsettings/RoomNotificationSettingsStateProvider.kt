/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.roomdetails.impl.notificationsettings

import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import io.element.android.features.roomdetails.impl.aRoomNotificationSettings
import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.matrix.api.room.RoomNotificationMode
import io.element.android.libraries.preferences.api.store.NotificationSound
import io.element.android.libraries.preferences.api.store.RoomNotificationChannelSettings
import io.element.android.libraries.preferences.api.store.RoomNotificationPriority

internal class RoomNotificationSettingsStateProvider : PreviewParameterProvider<RoomNotificationSettingsState> {
    override val values: Sequence<RoomNotificationSettingsState>
        get() = sequenceOf(
            aRoomNotificationSettingsState(),
            aRoomNotificationSettingsState(isDefault = false),
            aRoomNotificationSettingsState(setNotificationSettingAction = AsyncAction.Loading),
            aRoomNotificationSettingsState(setNotificationSettingAction = AsyncAction.Failure(RuntimeException("error"))),
            aRoomNotificationSettingsState(restoreDefaultAction = AsyncAction.Loading),
            aRoomNotificationSettingsState(restoreDefaultAction = AsyncAction.Failure(RuntimeException("error"))),
            aRoomNotificationSettingsState(displayMentionsOnlyDisclaimer = true),
            aRoomNotificationSettingsState(
                isDefault = false,
                roomChannelSettings = RoomNotificationChannelSettings(
                    sound = NotificationSound.Custom("content://media/1"),
                    soundDisplayName = "Marimba",
                    channelVersion = 1,
                    priority = RoomNotificationPriority.HIGH,
                    showMessagePreview = false,
                ),
                soundDisplayName = "Marimba",
            ),
        )

    private fun aRoomNotificationSettingsState(
        isDefault: Boolean = true,
        setNotificationSettingAction: AsyncAction<Unit> = AsyncAction.Uninitialized,
        restoreDefaultAction: AsyncAction<Unit> = AsyncAction.Uninitialized,
        displayMentionsOnlyDisclaimer: Boolean = false,
        roomChannelSettings: RoomNotificationChannelSettings? = null,
        soundDisplayName: String = "Default",
    ): RoomNotificationSettingsState {
        return RoomNotificationSettingsState(
            showUserDefinedSettingStyle = false,
            roomName = "Room 1",
            AsyncData.Success(aRoomNotificationSettings(
                mode = RoomNotificationMode.MUTE,
                isDefault = isDefault
            )),
            pendingRoomNotificationMode = null,
            pendingSetDefault = null,
            defaultRoomNotificationMode = RoomNotificationMode.ALL_MESSAGES,
            setNotificationSettingAction = setNotificationSettingAction,
            restoreDefaultAction = restoreDefaultAction,
            displayMentionsOnlyDisclaimer = displayMentionsOnlyDisclaimer,
            roomChannelSettings = roomChannelSettings,
            soundDisplayName = soundDisplayName,
            soundCopyError = false,
            showSoundDialog = false,
            showPriorityDialog = false,
            pendingSoundPickerLaunch = 0,
            eventSink = { },
        )
    }
}
