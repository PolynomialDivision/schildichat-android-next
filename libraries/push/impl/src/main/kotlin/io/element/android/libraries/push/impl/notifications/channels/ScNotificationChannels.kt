package io.element.android.libraries.push.impl.notifications.channels

import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import chat.schildi.lib.R
import io.element.android.services.toolbox.api.strings.StringProvider

// Bumped to _V2: NotificationChannel.setGroup() can only be set at creation, so assigning these
// to the "Other" group requires a fresh id. The old, ungrouped ids are cleaned up below.
private const val LEGACY_SC_APP_BG_SERVICE_NOTIFICATION_CHANNEL_ID = "SC_APP_BG_SERVICE_NOTIFICATION_CHANNEL_ID"
private const val LEGACY_SC_NOTIFICATION_FAILURE_NOTIFICATION_CHANNEL_ID = "SC_NOTIFICATION_FAILURE_NOTIFICATION_CHANNEL_ID"
internal const val SC_APP_BG_SERVICE_NOTIFICATION_CHANNEL_ID = "SC_APP_BG_SERVICE_NOTIFICATION_CHANNEL_ID_V2"
internal const val SC_NOTIFICATION_FAILURE_NOTIFICATION_CHANNEL_ID = "SC_NOTIFICATION_FAILURE_NOTIFICATION_CHANNEL_ID_V2"

fun NotificationManagerCompat.updateScNotificationChannels(stringProvider: StringProvider) {
    for (legacyChannelId in listOf(
        LEGACY_SC_APP_BG_SERVICE_NOTIFICATION_CHANNEL_ID,
        LEGACY_SC_NOTIFICATION_FAILURE_NOTIFICATION_CHANNEL_ID,
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
            .setGroup(OTHER_CHANNEL_GROUP_ID)
            .build()
    )
    createNotificationChannel(
        NotificationChannelCompat.Builder(
            SC_NOTIFICATION_FAILURE_NOTIFICATION_CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_DEFAULT,
        )
            .setName(stringProvider.getString(R.string.sc_push_failure_notification_channel).ifEmpty { "Notification failures" })
            .setDescription(stringProvider.getString(R.string.sc_push_failure_notification_channel))
            .setGroup(OTHER_CHANNEL_GROUP_ID)
            .build()
    )
}
