/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.roomdetails.impl.notificationsettings

import io.element.android.libraries.matrix.api.room.RoomNotificationMode
import io.element.android.libraries.preferences.api.store.NotificationSound
import io.element.android.libraries.preferences.api.store.RoomNotificationPriority

sealed interface RoomNotificationSettingsEvent {
    data class ChangeRoomNotificationMode(val mode: RoomNotificationMode) : RoomNotificationSettingsEvent
    data class SetNotificationMode(val isDefault: Boolean) : RoomNotificationSettingsEvent
    data object DeleteCustomNotification : RoomNotificationSettingsEvent
    data object ClearSetNotificationError : RoomNotificationSettingsEvent
    data object ClearRestoreDefaultError : RoomNotificationSettingsEvent

    // Client-local Android channel customization: sound, priority, message preview.
    data class SetSound(val sound: NotificationSound) : RoomNotificationSettingsEvent
    data class SelectSoundPreset(val sound: NotificationSound) : RoomNotificationSettingsEvent
    data object ShowSoundDialog : RoomNotificationSettingsEvent
    data object DismissSoundDialog : RoomNotificationSettingsEvent
    data object LaunchSoundPicker : RoomNotificationSettingsEvent
    data object DismissSoundCopyError : RoomNotificationSettingsEvent
    data class SetPriority(val priority: RoomNotificationPriority) : RoomNotificationSettingsEvent
    data object ShowPriorityDialog : RoomNotificationSettingsEvent
    data object DismissPriorityDialog : RoomNotificationSettingsEvent
    data class SetPreviewEnabled(val enabled: Boolean) : RoomNotificationSettingsEvent
    data object ResetChannelSettings : RoomNotificationSettingsEvent
}
