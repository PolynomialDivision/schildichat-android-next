/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.roomdetails.impl.notificationsettings

import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.matrix.api.room.RoomNotificationMode
import io.element.android.libraries.matrix.api.room.RoomNotificationSettings
import io.element.android.libraries.preferences.api.store.RoomNotificationChannelSettings

data class RoomNotificationSettingsState(
    val showUserDefinedSettingStyle: Boolean,
    val roomName: String,
    val roomNotificationSettings: AsyncData<RoomNotificationSettings>,
    val pendingRoomNotificationMode: RoomNotificationMode?,
    val pendingSetDefault: Boolean?,
    val defaultRoomNotificationMode: RoomNotificationMode?,
    val setNotificationSettingAction: AsyncAction<Unit>,
    val restoreDefaultAction: AsyncAction<Unit>,
    val displayMentionsOnlyDisclaimer: Boolean,
    /**
     * The room's client-local Android channel customization (sound/priority/preview), or null
     * when the room has none and uses the app's shared channel. Independent of [roomNotificationSettings],
     * which is the Matrix-level push rule mode.
     */
    val roomChannelSettings: RoomNotificationChannelSettings?,
    val soundDisplayName: String,
    val soundCopyError: Boolean,
    val showSoundDialog: Boolean,
    val showPriorityDialog: Boolean,
    /**
     * One-shot trigger for launching the system ringtone picker. The view watches this in a
     * `LaunchedEffect` and calls the launcher whenever it increments. **Always start at 0**, or
     * the picker auto-opens on screen entry.
     */
    val pendingSoundPickerLaunch: Int,
    val eventSink: (RoomNotificationSettingsEvent) -> Unit
)

val RoomNotificationSettingsState.displayNotificationMode: RoomNotificationMode? get() {
    return pendingRoomNotificationMode ?: roomNotificationSettings.dataOrNull()?.mode
}

val RoomNotificationSettingsState.displayIsDefault: Boolean? get() {
    return pendingSetDefault ?: roomNotificationSettings.dataOrNull()?.isDefault
}
