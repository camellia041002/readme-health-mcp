/*  Copyright (C) 2017-2024 Andreas Shimokawa, Carsten Pfeiffer, Daniele
    Gobbetti

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.externalevents;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AlarmClockReceiver extends BroadcastReceiver {
    private static final Logger LOG = LoggerFactory.getLogger(AlarmClockReceiver.class);

    public static final String PACKAGE_AOSP = "com.android.deskclock";
    public static final String PACKAGE_CLOCK_GOOGLE = "com.google.android.deskclock";

    /**
     * AlarmActivity and AlarmService (when unbound) listen for this broadcast intent
     * so that other applications can snooze the alarm (after ALARM_ALERT_ACTION and before
     * ALARM_DONE_ACTION).
     */
    public static final String ALARM_SNOOZE_ACTION = "com.android.deskclock.ALARM_SNOOZE";

    /**
     * AlarmActivity and AlarmService listen for this broadcast intent so that other
     * applications can dismiss the alarm (after ALARM_ALERT_ACTION and before ALARM_DONE_ACTION).
     */
    public static final String ALARM_DISMISS_ACTION = "com.android.deskclock.ALARM_DISMISS";

    /** A public action sent by AlarmService when the alarm has started. */
    public static final String ALARM_ALERT_ACTION = "com.android.deskclock.ALARM_ALERT";
    public static final String GOOGLE_CLOCK_ALARM_ALERT_ACTION = "com.google.android.deskclock.action.ALARM_ALERT";

    /** A public action sent by AlarmService when the alarm has stopped for any reason. */
    public static final String ALARM_DONE_ACTION = "com.android.deskclock.ALARM_DONE";
    public static final String GOOGLE_CLOCK_ALARM_DONE_ACTION = "com.google.android.deskclock.action.ALARM_DONE";
    @Override
    public void onReceive(final Context context, final Intent intent) {
        final String action = intent.getAction();
        if (action == null) {
            return;
        }

        LOG.debug("Got alarm action: {}", action);

        final String packageName;
        if (action.startsWith(PACKAGE_AOSP)) {
            packageName = PACKAGE_AOSP;
        } else if (action.startsWith(PACKAGE_CLOCK_GOOGLE)) {
            packageName = PACKAGE_CLOCK_GOOGLE;
        } else {
            LOG.warn("Unknown clock app package name");
            return;
        }

        if (ALARM_ALERT_ACTION.equals(action) || GOOGLE_CLOCK_ALARM_ALERT_ACTION.equals(action)) {
            PhoneAlarmBridge.getInstance().onBroadcastChanged(context, packageName, true);
        } else if (ALARM_DONE_ACTION.equals(action) || GOOGLE_CLOCK_ALARM_DONE_ACTION.equals(action)) {
            PhoneAlarmBridge.getInstance().onBroadcastChanged(context, packageName, false);
        }
    }
}
