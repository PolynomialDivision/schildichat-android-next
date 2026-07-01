/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.preferences.api.store

import androidx.compose.runtime.Immutable

/**
 * The user's chosen importance for a room's own notification channel, mapped to
 * `NotificationManagerCompat.IMPORTANCE_*` when the channel is (re)created.
 */
enum class RoomNotificationPriority { LOW, DEFAULT, HIGH }

/**
 * Snapshot of a room's client-local Android notification-channel customization, or null when the
 * room has none and should keep using the app's shared channel. This is distinct from the
 * Matrix-level push rule mode (all/mentions/mute): it only controls how Android renders and ranks
 * the notification, not whether one is raised.
 */
@Immutable
data class RoomNotificationChannelSettings(
    val sound: NotificationSound,
    val soundDisplayName: String?,
    val channelVersion: Int,
    val priority: RoomNotificationPriority,
    val showMessagePreview: Boolean,
)
