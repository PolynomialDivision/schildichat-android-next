/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.roomdetails.impl.notificationsettings

import android.app.Activity
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import io.element.android.features.roomdetails.impl.R
import io.element.android.libraries.core.bool.orTrue
import io.element.android.libraries.designsystem.components.async.AsyncActionView
import io.element.android.libraries.designsystem.components.button.BackButton
import io.element.android.libraries.designsystem.components.dialogs.ListOption
import io.element.android.libraries.designsystem.components.dialogs.SingleSelectionDialog
import io.element.android.libraries.designsystem.components.preferences.PreferenceCategory
import io.element.android.libraries.designsystem.components.preferences.PreferenceSwitch
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.ListItem
import io.element.android.libraries.designsystem.theme.components.ListItemStyle
import io.element.android.libraries.designsystem.theme.components.Scaffold
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.theme.components.TopAppBar
import io.element.android.libraries.matrix.api.room.RoomNotificationMode
import io.element.android.libraries.preferences.api.store.NotificationSound
import io.element.android.libraries.preferences.api.store.RoomNotificationPriority
import io.element.android.libraries.push.api.notifications.sound.buildRingtonePickerIntent
import io.element.android.libraries.push.api.notifications.sound.toPickedNotificationSound
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

@Composable
fun UserDefinedRoomNotificationSettingsView(
    state: RoomNotificationSettingsState,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            UserDefinedRoomNotificationSettingsTopBar(
                roomName = state.roomName,
                onBackClick = { onBackClick() }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .consumeWindowInsets(padding),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val roomNotificationSettings = state.roomNotificationSettings.dataOrNull()
            if (roomNotificationSettings != null && state.displayNotificationMode != null) {
                RoomNotificationSettingsOptions(
                    selected = state.displayNotificationMode,
                    enabled = !state.displayIsDefault.orTrue(),
                    displayMentionsOnlyDisclaimer = state.displayMentionsOnlyDisclaimer,
                    onSelectOption = {
                        state.eventSink(RoomNotificationSettingsEvent.ChangeRoomNotificationMode(it.mode))
                    },
                )
            }

            if (state.displayNotificationMode != RoomNotificationMode.MUTE) {
                RoomChannelPreferenceCategory(state = state)
            }

            ListItem(
                headlineContent = { Text(stringResource(R.string.screen_room_notification_settings_edit_remove_setting)) },
                style = ListItemStyle.Destructive,
                onClick = {
                    state.eventSink(RoomNotificationSettingsEvent.DeleteCustomNotification)
                }
            )

            AsyncActionView(
                async = state.setNotificationSettingAction,
                onSuccess = {},
                errorMessage = { stringResource(R.string.screen_notification_settings_edit_failed_updating_default_mode) },
                onErrorDismiss = { state.eventSink(RoomNotificationSettingsEvent.ClearSetNotificationError) },
            )

            AsyncActionView(
                async = state.restoreDefaultAction,
                onSuccess = { onBackClick() },
                errorMessage = { stringResource(R.string.screen_notification_settings_edit_failed_updating_default_mode) },
                onErrorDismiss = { state.eventSink(RoomNotificationSettingsEvent.ClearRestoreDefaultError) },
            )
        }
    }
}

@Composable
internal fun RoomChannelPreferenceCategory(state: RoomNotificationSettingsState) {
    PreferenceCategory(title = stringResource(id = R.string.screen_room_notification_settings_channel_section_title)) {
        val launchSoundPicker = rememberRoomSoundPickerOnClick(
            current = state.roomChannelSettings?.sound ?: NotificationSound.SystemDefault,
            onSoundPick = { sound -> state.eventSink(RoomNotificationSettingsEvent.SetSound(sound)) },
        )
        // Skip the initial 0 emission so the picker doesn't auto-open on screen entry; only
        // increments fired by LaunchSoundPicker should launch it.
        LaunchedEffect(state.pendingSoundPickerLaunch) {
            if (state.pendingSoundPickerLaunch > 0) {
                launchSoundPicker()
            }
        }
        ListItem(
            headlineContent = { Text(stringResource(id = R.string.screen_room_notification_settings_sound_label)) },
            supportingContent = { Text(state.soundDisplayName) },
            onClick = { state.eventSink(RoomNotificationSettingsEvent.ShowSoundDialog) },
        )
        if (state.soundCopyError) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.screen_room_notification_settings_sound_set_sound_error_title)) },
                onClick = { state.eventSink(RoomNotificationSettingsEvent.DismissSoundCopyError) },
            )
        }
        if (state.showSoundDialog) {
            RoomSoundDialog(state)
        }

        val priority = state.roomChannelSettings?.priority ?: RoomNotificationPriority.DEFAULT
        ListItem(
            headlineContent = { Text(stringResource(id = R.string.screen_room_notification_settings_priority_label)) },
            supportingContent = { Text(titleForPriority(priority)) },
            onClick = { state.eventSink(RoomNotificationSettingsEvent.ShowPriorityDialog) },
        )
        if (state.showPriorityDialog) {
            RoomPriorityDialog(state, currentPriority = priority)
        }

        PreferenceSwitch(
            title = stringResource(id = R.string.screen_room_notification_settings_preview_label),
            subtitle = stringResource(id = R.string.screen_room_notification_settings_preview_footnote),
            isChecked = state.roomChannelSettings?.showMessagePreview ?: true,
            onCheckedChange = { state.eventSink(RoomNotificationSettingsEvent.SetPreviewEnabled(it)) },
        )

        if (state.roomChannelSettings != null) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.screen_room_notification_settings_reset_channel_settings)) },
                style = ListItemStyle.Destructive,
                onClick = { state.eventSink(RoomNotificationSettingsEvent.ResetChannelSettings) },
            )
        }
    }
}

@Composable
private fun RoomSoundDialog(state: RoomNotificationSettingsState) {
    val initialSelection = when (state.roomChannelSettings?.sound ?: NotificationSound.SystemDefault) {
        NotificationSound.SystemDefault, NotificationSound.ElementDefault, NotificationSound.ElementFade -> 0
        else -> null
    }
    val subtitle = if (initialSelection == null) {
        stringResource(id = R.string.screen_room_notification_settings_sound_dialog_current_subtitle, state.soundDisplayName)
    } else {
        null
    }
    SingleSelectionDialog(
        title = stringResource(id = R.string.screen_room_notification_settings_sound_dialog_title),
        subtitle = subtitle,
        options = persistentListOf(
            ListOption(title = stringResource(id = R.string.screen_room_notification_settings_sound_system_default)),
            ListOption(title = stringResource(id = R.string.screen_room_notification_settings_sound_dialog_choose_other)),
        ),
        initialSelection = initialSelection,
        onSelectOption = { index ->
            when (index) {
                0 -> state.eventSink(RoomNotificationSettingsEvent.SelectSoundPreset(NotificationSound.SystemDefault))
                else -> state.eventSink(RoomNotificationSettingsEvent.LaunchSoundPicker)
            }
        },
        onDismissRequest = { state.eventSink(RoomNotificationSettingsEvent.DismissSoundDialog) },
    )
}

@Composable
private fun RoomPriorityDialog(state: RoomNotificationSettingsState, currentPriority: RoomNotificationPriority) {
    val priorities = listOf(RoomNotificationPriority.HIGH, RoomNotificationPriority.DEFAULT, RoomNotificationPriority.LOW)
    SingleSelectionDialog(
        title = stringResource(id = R.string.screen_room_notification_settings_priority_dialog_title),
        options = priorities.map { ListOption(title = titleForPriority(it)) }.toImmutableList(),
        initialSelection = priorities.indexOf(currentPriority),
        onSelectOption = { index -> state.eventSink(RoomNotificationSettingsEvent.SetPriority(priorities[index])) },
        onDismissRequest = { state.eventSink(RoomNotificationSettingsEvent.DismissPriorityDialog) },
    )
}

@Composable
private fun titleForPriority(priority: RoomNotificationPriority) = when (priority) {
    RoomNotificationPriority.HIGH -> stringResource(id = R.string.screen_room_notification_settings_priority_high)
    RoomNotificationPriority.DEFAULT -> stringResource(id = R.string.screen_room_notification_settings_priority_default)
    RoomNotificationPriority.LOW -> stringResource(id = R.string.screen_room_notification_settings_priority_low)
}

@Composable
private fun rememberRoomSoundPickerOnClick(
    current: NotificationSound,
    onSoundPick: (NotificationSound) -> Unit,
): () -> Unit {
    // Paparazzi previews don't provide a LocalActivityResultRegistryOwner, which
    // rememberLauncherForActivityResult requires. Skip the launcher in inspection mode and
    // return a no-op click handler — previews don't need to launch the picker.
    if (LocalInspectionMode.current) return {}
    val defaultUri: Uri = Settings.System.DEFAULT_NOTIFICATION_URI
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val sound = result.data?.toPickedNotificationSound(defaultUri)
            if (sound != null) {
                onSoundPick(sound)
            }
        }
    }
    return {
        launcher.launch(buildRingtonePickerIntent(type = RingtoneManager.TYPE_NOTIFICATION, current = current, defaultUri = defaultUri))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserDefinedRoomNotificationSettingsTopBar(
    roomName: String,
    onBackClick: () -> Unit,
) {
    TopAppBar(
        titleStr = roomName,
        navigationIcon = { BackButton(onClick = onBackClick) },
    )
}

@PreviewsDayNight
@Composable
internal fun UserDefinedRoomNotificationSettingsViewPreview(
    @PreviewParameter(UserDefinedRoomNotificationSettingsStateProvider::class) state: RoomNotificationSettingsState
) = ElementPreview {
    UserDefinedRoomNotificationSettingsView(
        state = state,
        onBackClick = {},
    )
}
