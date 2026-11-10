/*  Copyright (C) 2026 Gadgetbridge contributors

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version. */
package nodomain.freeyourgadget.gadgetbridge.externalevents;

import android.app.Notification;

/**
 * Classifies alarm notifications using Android's structured notification fields only.
 */
final class PhoneAlarmNotificationDetector {
    private static final String HYPEROS_CLOCK_PACKAGE = "com.android.deskclock";
    private static final String HYPEROS_ALARM_CHANNEL = "channel_id_deskclock_alarm";

    private PhoneAlarmNotificationDetector() {
    }

    static boolean isAlarmNotification(
            final String packageName,
            final String category,
            final String channelId
    ) {
        return Notification.CATEGORY_ALARM.equals(category)
                || (HYPEROS_CLOCK_PACKAGE.equals(packageName)
                && HYPEROS_ALARM_CHANNEL.equals(channelId));
    }

    static boolean isRinging(
            final boolean alarmNotification,
            final boolean hasFullScreenIntent,
            final boolean isOngoing,
            final boolean isInsistent,
            final boolean hasAlarmAudio,
            final boolean hasAlarmChannelAudio,
            final boolean isCountdownTimer
    ) {
        if (!alarmNotification || isCountdownTimer) {
            return false;
        }

        // Ongoing alone is deliberately insufficient: future-alarm and status notifications can
        // also be persistent. Alarm audio is strong only while the notification is ongoing.
        return hasFullScreenIntent
                || isInsistent
                || (isOngoing && (hasAlarmAudio || hasAlarmChannelAudio));
    }
}
