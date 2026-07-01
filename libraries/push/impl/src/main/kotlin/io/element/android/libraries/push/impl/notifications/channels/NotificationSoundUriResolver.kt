/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.push.impl.notifications.channels

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.net.toUri
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.preferences.api.store.NotificationSound
import io.element.android.libraries.push.impl.R
import timber.log.Timber

/**
 * Resolves the sound URI for a noisy message channel. The app's bundled tones are used for
 * [NotificationSound.ElementDefault] / [NotificationSound.ElementFade], since a message channel
 * has one.
 */
internal fun Context.resolveNoisySoundUri(sound: NotificationSound): Uri? = when (sound) {
    NotificationSound.Silent -> null
    NotificationSound.SystemDefault -> Settings.System.DEFAULT_NOTIFICATION_URI
    NotificationSound.ElementDefault -> bundledMessageSoundUri()
    NotificationSound.ElementFade -> bundledFadeSoundUri()
    is NotificationSound.Custom -> parseUriOrFallback(sound.uri) { Settings.System.DEFAULT_NOTIFICATION_URI }
}

/**
 * Resolves the sound URI for the ringing-call channel. The ringing channel has no bundled tone,
 * so treat [NotificationSound.ElementDefault] / [NotificationSound.ElementFade] like SystemDefault.
 */
internal fun Context.resolveRingingSoundUri(sound: NotificationSound): Uri? = when (sound) {
    NotificationSound.Silent -> null
    NotificationSound.SystemDefault,
    NotificationSound.ElementDefault,
    NotificationSound.ElementFade -> Settings.System.DEFAULT_RINGTONE_URI
    is NotificationSound.Custom -> parseUriOrFallback(sound.uri) { Settings.System.DEFAULT_RINGTONE_URI }
}

internal fun Context.bundledMessageSoundUri(): Uri =
    "${ContentResolver.SCHEME_ANDROID_RESOURCE}://$packageName/${R.raw.message}".toUri()

internal fun Context.bundledFadeSoundUri(): Uri =
    "${ContentResolver.SCHEME_ANDROID_RESOURCE}://$packageName/${R.raw.element_fade}".toUri()

/**
 * Lets system_server ("android") and SystemUI read our FileProvider sound URI; no-op otherwise.
 * SystemUI hosts the lock-screen notification surface on most OEMs, so a missing grant there
 * silently mutes ringtones when the device is locked.
 */
internal fun Context.grantSoundUriToSystem(uri: Uri?) {
    if (uri == null || uri.scheme != ContentResolver.SCHEME_CONTENT) return
    for (pkg in arrayOf("android", "com.android.systemui")) {
        runCatchingExceptions {
            grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.onFailure { Timber.w(it, "grantUriPermission(%s) failed for notification sound", pkg) }
    }
}

/** Parses [uriString], or returns [fallback] (the SystemDefault URI) on failure. */
internal inline fun parseUriOrFallback(uriString: String, fallback: () -> Uri): Uri =
    runCatchingExceptions { uriString.toUri() }
        .getOrElse {
            // Don't pass the throwable: legacy persisted URIs may carry SAF auth tokens.
            Timber.w("Failed to parse persisted sound URI; falling back to default cause=%s", it::class.simpleName)
            fallback()
        }
