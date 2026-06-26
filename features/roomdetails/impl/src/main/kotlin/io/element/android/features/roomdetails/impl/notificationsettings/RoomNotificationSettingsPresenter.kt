/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.roomdetails.impl.notificationsettings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.element.android.features.roomdetails.impl.R
import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.architecture.runCatchingUpdatingState
import io.element.android.libraries.core.coroutine.suspendWithMinimumDuration
import io.element.android.libraries.matrix.api.notificationsettings.NotificationSettingsService
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.room.RoomNotificationMode
import io.element.android.libraries.matrix.api.room.RoomNotificationSettings
import io.element.android.libraries.preferences.api.store.NotificationSound
import io.element.android.libraries.preferences.api.store.RoomNotificationChannelSettings
import io.element.android.libraries.preferences.api.store.SessionPreferencesStore
import io.element.android.libraries.push.api.notifications.RoomNotificationChannelManager
import io.element.android.libraries.push.api.notifications.SoundDisplayNameResolver
import io.element.android.libraries.push.api.notifications.sound.NotificationSoundCopier
import io.element.android.libraries.push.api.notifications.sound.NotificationSoundCopier.CopyResult
import io.element.android.libraries.push.api.notifications.sound.NotificationSoundCopier.SoundSlot
import io.element.android.services.toolbox.api.strings.StringProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import kotlin.time.Duration.Companion.seconds

@AssistedInject
class RoomNotificationSettingsPresenter(
    private val room: JoinedRoom,
    private val notificationSettingsService: NotificationSettingsService,
    private val sessionPreferencesStore: SessionPreferencesStore,
    private val roomNotificationChannelManager: RoomNotificationChannelManager,
    private val notificationSoundCopier: NotificationSoundCopier,
    private val soundDisplayNameResolver: SoundDisplayNameResolver,
    private val stringProvider: StringProvider,
    @Assisted private val showUserDefinedSettingStyle: Boolean,
) : Presenter<RoomNotificationSettingsState> {
    @AssistedFactory
    interface Factory {
        fun create(showUserDefinedSettingStyle: Boolean): RoomNotificationSettingsPresenter
    }

    // Serializes the pick → copy → persist → recreate pipeline, same reasoning as the global
    // sound settings presenter: without this, two rapid picks could interleave and leave the
    // channel id pointing at the older version while DataStore reflects the newer one.
    private val roomSoundLock = Mutex()

    @Composable
    override fun present(): RoomNotificationSettingsState {
        var shouldDisplayMentionsOnlyDisclaimer by remember { mutableStateOf(false) }
        val defaultRoomNotificationMode: MutableState<RoomNotificationMode?> = rememberSaveable {
            mutableStateOf(null)
        }
        val localCoroutineScope = rememberCoroutineScope()
        val setNotificationSettingAction: MutableState<AsyncAction<Unit>> = remember { mutableStateOf(AsyncAction.Uninitialized) }
        val restoreDefaultAction: MutableState<AsyncAction<Unit>> = remember { mutableStateOf(AsyncAction.Uninitialized) }

        val roomNotificationSettings: MutableState<AsyncData<RoomNotificationSettings>> = remember {
            mutableStateOf(AsyncData.Uninitialized)
        }

        // We store state of which mode the user has set via the notification service before the new push settings have been updated.
        // We show this state immediately to the user and debounce updates to notification settings to hide some invalid states returned
        // by the rust sdk during these two events that cause the radio buttons ot toggle quickly back and forth.
        // This is a client side work-around until bulk push rule updates are supported.
        // ref: https://github.com/matrix-org/matrix-spec-proposals/pull/3934
        val pendingRoomNotificationMode: MutableState<RoomNotificationMode?> = remember {
            mutableStateOf(null)
        }

        // We store state of whether the user has set the notifications settings to default or custom via the notification service.
        // We show this state immediately to the user and debounce updates to notification settings to hide some invalid states returned
        // by the rust sdk during these two events that cause the switch ot toggle quickly back and forth.
        // This is a client side work-around until bulk push rule updates are supported.
        // ref: https://github.com/matrix-org/matrix-spec-proposals/pull/3934
        val pendingSetDefault: MutableState<Boolean?> = remember {
            mutableStateOf(null)
        }

        val displayName by produceState(room.info().name) {
            room.roomInfoFlow.collect { value = it.name }
        }

        val isRoomEncrypted by produceState(room.info().isEncrypted) {
            room.roomInfoFlow.collect { value = it.isEncrypted }
        }

        LaunchedEffect(Unit) {
            getDefaultRoomNotificationMode(defaultRoomNotificationMode)
            fetchNotificationSettings(pendingRoomNotificationMode, roomNotificationSettings)
            observeNotificationSettings(pendingRoomNotificationMode, roomNotificationSettings)
        }

        LaunchedEffect(isRoomEncrypted) {
            shouldDisplayMentionsOnlyDisclaimer = isRoomEncrypted == true &&
                !notificationSettingsService.canHomeServerPushEncryptedEventsToDevice().getOrDefault(true)
        }

        val roomChannelSettings: RoomNotificationChannelSettings? by remember {
            sessionPreferencesStore.getRoomNotificationChannelSettingsFlow(room.roomId)
        }.collectAsState(initial = null)

        var soundCopyError by remember { mutableStateOf(false) }
        var showSoundDialog by remember { mutableStateOf(false) }
        var showPriorityDialog by remember { mutableStateOf(false) }
        var pendingSoundPickerLaunch by remember { mutableIntStateOf(0) }

        val defaultSoundLabel = stringProvider.getString(R.string.screen_room_notification_settings_sound_system_default)
        val soundDisplayName = probeRoomSoundDisplayName(
            sound = roomChannelSettings?.sound ?: NotificationSound.SystemDefault,
            persistedTitle = roomChannelSettings?.soundDisplayName,
            defaultLabel = defaultSoundLabel,
        )

        fun handleEvent(event: RoomNotificationSettingsEvent) {
            when (event) {
                is RoomNotificationSettingsEvent.ChangeRoomNotificationMode -> {
                    localCoroutineScope.setRoomNotificationMode(event.mode, pendingRoomNotificationMode, pendingSetDefault, setNotificationSettingAction)
                }
                is RoomNotificationSettingsEvent.SetNotificationMode -> {
                    if (event.isDefault) {
                        localCoroutineScope.restoreDefaultRoomNotificationMode(restoreDefaultAction, pendingSetDefault)
                    } else {
                        defaultRoomNotificationMode.value?.let {
                            localCoroutineScope.setRoomNotificationMode(it, pendingRoomNotificationMode, pendingSetDefault, setNotificationSettingAction)
                        }
                    }
                }
                is RoomNotificationSettingsEvent.DeleteCustomNotification -> {
                    localCoroutineScope.restoreDefaultRoomNotificationMode(restoreDefaultAction, pendingSetDefault)
                }
                RoomNotificationSettingsEvent.ClearSetNotificationError -> {
                    setNotificationSettingAction.value = AsyncAction.Uninitialized
                }
                RoomNotificationSettingsEvent.ClearRestoreDefaultError -> {
                    restoreDefaultAction.value = AsyncAction.Uninitialized
                }
                is RoomNotificationSettingsEvent.SetSound -> localCoroutineScope.applyRoomSound(
                    sound = event.sound,
                    roomDisplayName = displayName.orEmpty(),
                    onCopyError = { soundCopyError = true },
                    onCopySuccess = { soundCopyError = false },
                )
                is RoomNotificationSettingsEvent.SelectSoundPreset -> {
                    showSoundDialog = false
                    localCoroutineScope.applyRoomSound(
                        sound = event.sound,
                        roomDisplayName = displayName.orEmpty(),
                        onCopyError = { soundCopyError = true },
                        onCopySuccess = { soundCopyError = false },
                    )
                }
                RoomNotificationSettingsEvent.ShowSoundDialog -> showSoundDialog = true
                RoomNotificationSettingsEvent.DismissSoundDialog -> showSoundDialog = false
                RoomNotificationSettingsEvent.LaunchSoundPicker -> {
                    showSoundDialog = false
                    pendingSoundPickerLaunch++
                }
                RoomNotificationSettingsEvent.DismissSoundCopyError -> soundCopyError = false
                is RoomNotificationSettingsEvent.SetPriority -> {
                    showPriorityDialog = false
                    localCoroutineScope.launch {
                        sessionPreferencesStore.setRoomNotificationPriority(room.roomId, event.priority)
                        roomNotificationChannelManager.onRoomNotificationSettingsChanged(room.sessionId, room.roomId, displayName.orEmpty())
                    }
                }
                RoomNotificationSettingsEvent.ShowPriorityDialog -> showPriorityDialog = true
                RoomNotificationSettingsEvent.DismissPriorityDialog -> showPriorityDialog = false
                is RoomNotificationSettingsEvent.SetPreviewEnabled -> localCoroutineScope.launch {
                    sessionPreferencesStore.setRoomMessagePreviewEnabled(room.roomId, event.enabled)
                    roomNotificationChannelManager.onRoomNotificationSettingsChanged(room.sessionId, room.roomId, displayName.orEmpty())
                }
                RoomNotificationSettingsEvent.ResetChannelSettings -> localCoroutineScope.launch {
                    roomNotificationChannelManager.clearRoomChannel(room.sessionId, room.roomId)
                }
            }
        }

        return RoomNotificationSettingsState(
            showUserDefinedSettingStyle = showUserDefinedSettingStyle,
            roomName = displayName.orEmpty(),
            roomNotificationSettings = roomNotificationSettings.value,
            pendingRoomNotificationMode = pendingRoomNotificationMode.value,
            pendingSetDefault = pendingSetDefault.value,
            defaultRoomNotificationMode = defaultRoomNotificationMode.value,
            setNotificationSettingAction = setNotificationSettingAction.value,
            restoreDefaultAction = restoreDefaultAction.value,
            displayMentionsOnlyDisclaimer = shouldDisplayMentionsOnlyDisclaimer,
            roomChannelSettings = roomChannelSettings,
            soundDisplayName = soundDisplayName,
            soundCopyError = soundCopyError,
            showSoundDialog = showSoundDialog,
            showPriorityDialog = showPriorityDialog,
            pendingSoundPickerLaunch = pendingSoundPickerLaunch,
            eventSink = ::handleEvent,
        )
    }

    private fun CoroutineScope.applyRoomSound(
        sound: NotificationSound,
        roomDisplayName: String,
        onCopyError: () -> Unit,
        onCopySuccess: () -> Unit,
    ) = launch {
        roomSoundLock.withLock {
            // Re-selecting the current non-Custom sound is a no-op: skip the channel churn and the
            // version bump. Custom still falls through so the copier refreshes the file from the
            // (possibly updated) source URI.
            if (sound !is NotificationSound.Custom &&
                sessionPreferencesStore.getRoomNotificationChannelSettings(room.roomId)?.sound == sound
            ) {
                onCopySuccess()
                return@withLock
            }
            val resolved = resolvePickedSound(sound, onCopyError) ?: return@withLock
            onCopySuccess()
            sessionPreferencesStore.setRoomNotificationSoundAndIncrementVersion(room.roomId, resolved.first, resolved.second)
            roomNotificationChannelManager.onRoomNotificationSettingsChanged(room.sessionId, room.roomId, roomDisplayName)
            // Non-Custom picks bypass the copier, so they don't sweep the prior Custom file inline.
            // Drop it now that the new channel no longer references it.
            if (resolved.first !is NotificationSound.Custom) {
                notificationSoundCopier.deleteStoredSoundFor(SoundSlot.Room(room.roomId))
            }
        }
    }

    /**
     * For a Custom pick, copies into app-private storage and returns (FileProvider URI, title).
     * Returns null on copy failure after invoking [onCopyError]. Pass-through for SystemDefault /
     * Silent.
     */
    private suspend fun resolvePickedSound(
        requested: NotificationSound,
        onCopyError: () -> Unit,
    ): Pair<NotificationSound, String?>? {
        if (requested !is NotificationSound.Custom) return requested to null
        return when (val result = notificationSoundCopier.copyToAppFiles(requested.uri, SoundSlot.Room(room.roomId))) {
            is CopyResult.Success -> NotificationSound.Custom(result.fileProviderUriString) to result.displayName
            is CopyResult.Failure -> {
                Timber.w(result.cause, "Room notification sound copy failed for room ${room.roomId}")
                onCopyError()
                null
            }
            CopyResult.UnplayableSource,
            CopyResult.UnplayableCopy,
            CopyResult.FileTooLarge -> {
                Timber.w("Room notification sound rejected: result=%s room=%s", result::class.simpleName, room.roomId)
                onCopyError()
                null
            }
        }
    }

    /**
     * Custom rows prefer [persistedTitle] (captured at copy time), fall back to a live resolver
     * probe for legacy data, then to a localised "Custom" — never to [defaultLabel], so an
     * unresolvable Custom isn't mislabelled as Default.
     */
    @Composable
    private fun probeRoomSoundDisplayName(
        sound: NotificationSound,
        persistedTitle: String?,
        defaultLabel: String,
    ): String = when (sound) {
        NotificationSound.SystemDefault,
        NotificationSound.ElementDefault,
        NotificationSound.ElementFade -> defaultLabel
        NotificationSound.Silent -> stringProvider.getString(R.string.screen_room_notification_settings_sound_silent)
        is NotificationSound.Custom -> {
            val nonBlankPersisted = persistedTitle?.takeUnless { it.isBlank() }
            if (nonBlankPersisted != null) {
                nonBlankPersisted
            } else {
                val customFallback = stringProvider.getString(R.string.screen_room_notification_settings_sound_custom_fallback)
                val resolved by produceState(initialValue = "", sound.uri) {
                    val title = soundDisplayNameResolver.resolveCustomSoundTitle(sound.uri)
                    value = title?.takeUnless { it.isBlank() } ?: customFallback
                }
                resolved
            }
        }
    }

    @OptIn(FlowPreview::class)
    private fun CoroutineScope.observeNotificationSettings(
        pendingModeState: MutableState<RoomNotificationMode?>,
        roomNotificationSettings: MutableState<AsyncData<RoomNotificationSettings>>
    ) {
        notificationSettingsService.notificationSettingsChangeFlow
            .debounce(0.5.seconds)
            .onEach {
                fetchNotificationSettings(pendingModeState, roomNotificationSettings)
            }
            .launchIn(this)
    }

    private fun CoroutineScope.fetchNotificationSettings(
        pendingModeState: MutableState<RoomNotificationMode?>,
        roomNotificationSettings: MutableState<AsyncData<RoomNotificationSettings>>
    ) = launch {
        suspend {
            val isEncrypted = room.info().isEncrypted ?: room.getUpdatedIsEncrypted().getOrThrow()
            pendingModeState.value = null
            notificationSettingsService.getRoomNotificationSettings(room.roomId, isEncrypted, room.isDm()).getOrThrow()
        }.runCatchingUpdatingState(roomNotificationSettings)
    }

    private fun CoroutineScope.getDefaultRoomNotificationMode(
        defaultRoomNotificationMode: MutableState<RoomNotificationMode?>
    ) = launch {
        val isEncrypted = room.info().isEncrypted ?: room.getUpdatedIsEncrypted().getOrThrow()
        defaultRoomNotificationMode.value = notificationSettingsService.getDefaultRoomNotificationMode(
            isEncrypted,
            room.isDm()
        ).getOrThrow()
    }

    private fun CoroutineScope.setRoomNotificationMode(
        mode: RoomNotificationMode,
        pendingModeState: MutableState<RoomNotificationMode?>,
        pendingDefaultState: MutableState<Boolean?>,
        action: MutableState<AsyncAction<Unit>>
    ) = launch {
        suspendWithMinimumDuration {
            pendingModeState.value = mode
            pendingDefaultState.value = false
            val isEncrypted = room.info().isEncrypted ?: room.getUpdatedIsEncrypted().getOrThrow()
            notificationSettingsService.setRoomNotificationMode(room.roomId, mode, isEncrypted)
                .onFailure {
                    pendingModeState.value = null
                    pendingDefaultState.value = null
                }
                .getOrThrow()
        }.runCatchingUpdatingState(action)
    }

    private fun CoroutineScope.restoreDefaultRoomNotificationMode(
        action: MutableState<AsyncAction<Unit>>,
        pendingDefaultState: MutableState<Boolean?>
    ) = launch {
        suspendWithMinimumDuration {
            pendingDefaultState.value = true
            val result = notificationSettingsService.restoreDefaultRoomNotificationMode(room.roomId)
            if (result.isFailure) {
                pendingDefaultState.value = null
            }
            result.getOrThrow()
        }.runCatchingUpdatingState(action)
    }
}
