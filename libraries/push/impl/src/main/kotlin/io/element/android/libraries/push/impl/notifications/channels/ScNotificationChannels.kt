package io.element.android.libraries.push.impl.notifications.channels

import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import chat.schildi.lib.R
import io.element.android.services.toolbox.api.strings.StringProvider

// Bumped to _V3: these were briefly assigned to a dedicated "Other" NotificationChannelGroup,
// which just duplicated Android's own implicit "Other" bucket for ungrouped channels. Reverted to
// ungrouped, which requires yet another fresh id since NotificationChannel.setGroup() can only be
// set at creation. The old, superseded ids are cleaned up below.
private const val LEGACY_SC_APP_BG_SERVICE_NOTIFICATION_CHANNEL_ID = "SC_APP_BG_SERVICE_NOTIFICATION_CHANNEL_ID"
private const val LEGACY_SC_NOTIFICATION_FAILURE_NOTIFICATION_CHANNEL_ID = "SC_NOTIFICATION_FAILURE_NOTIFICATION_CHANNEL_ID"
private const val LEGACY_SC_APP_BG_SERVICE_NOTIFICATION_CHANNEL_ID_V2 = "SC_APP_BG_SERVICE_NOTIFICATION_CHANNEL_ID_V2"
private const val LEGACY_SC_NOTIFICATION_FAILURE_NOTIFICATION_CHANNEL_ID_V2 = "SC_NOTIFICATION_FAILURE_NOTIFICATION_CHANNEL_ID_V2"
internal const val SC_APP_BG_SERVICE_NOTIFICATION_CHANNEL_ID = "SC_APP_BG_SERVICE_NOTIFICATION_CHANNEL_ID_V3"
internal const val SC_NOTIFICATION_FAILURE_NOTIFICATION_CHANNEL_ID = "SC_NOTIFICATION_FAILURE_NOTIFICATION_CHANNEL_ID_V3"

fun NotificationManagerCompat.updateScNotificationChannels(stringProvider: StringProvider) {
    for (legacyChannelId in listOf(
        LEGACY_SC_APP_BG_SERVICE_NOTIFICATION_CHANNEL_ID,
        LEGACY_SC_NOTIFICATION_FAILURE_NOTIFICATION_CHANNEL_ID,
        LEGACY_SC_APP_BG_SERVICE_NOTIFICATION_CHANNEL_ID_V2,
        LEGACY_SC_NOTIFICATION_FAILURE_NOTIFICATION_CHANNEL_ID_V2,
    )) {
        getNotificationChannel(legacyChannelId)?.let { deleteNotificationChannel(legacyChannelId) }
    }
    createNotificationChannel(
        NotificationChannelCompat.Builder(
            SC_APP_BG_SERVICE_NOTIFICATION_CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_MIN,
        )
            .setName(stringProvider.getString(R.string.sc_bg_notification_channel).ifEmpty { "Background service" })
            .setDescription(stringProvider.getString(R.string.sc_bg_notification_channel))
            .setSound(null, null)
            .setLightsEnabled(true)
            .build()
    )
    createNotificationChannel(
        NotificationChannelCompat.Builder(
            SC_NOTIFICATION_FAILURE_NOTIFICATION_CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_DEFAULT,
        )
            .setName(stringProvider.getString(R.string.sc_push_failure_notification_channel).ifEmpty { "Notification failures" })
            .setDescription(stringProvider.getString(R.string.sc_push_failure_notification_channel))
            .build()
    )
}
