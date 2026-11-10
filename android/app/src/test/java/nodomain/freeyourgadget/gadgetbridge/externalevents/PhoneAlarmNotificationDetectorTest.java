package nodomain.freeyourgadget.gadgetbridge.externalevents;

import android.app.Notification;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PhoneAlarmNotificationDetectorTest {
    @Test
    public void ringingAlarmWithFullScreenIntentIsAccepted() {
        assertTrue(PhoneAlarmNotificationDetector.isRinging(
                true, true, true, false, false, false, false));
    }

    @Test
    public void ongoingAlarmWithAlarmAudioIsAccepted() {
        assertTrue(PhoneAlarmNotificationDetector.isRinging(
                true, false, true, false, true, false, false));
    }

    @Test
    public void staticFutureAlarmIsRejected() {
        assertFalse(PhoneAlarmNotificationDetector.isRinging(
                true, false, false, false, false, false, false));
    }

    @Test
    public void ongoingFlagAloneIsRejected() {
        assertFalse(PhoneAlarmNotificationDetector.isRinging(
                true, false, true, false, false, false, false));
    }

    @Test
    public void countdownTimerIsRejectedEvenWithInterruptiveSignals() {
        assertFalse(PhoneAlarmNotificationDetector.isRinging(
                true, true, true, true, true, true, true));
    }

    @Test
    public void ordinaryNotificationIsRejected() {
        assertFalse(PhoneAlarmNotificationDetector.isRinging(
                false, true, true, true, true, true, false));
    }

    @Test
    public void hyperOsAlarmChannelIsAcceptedWithoutAndroidAlarmCategory() {
        assertTrue(PhoneAlarmNotificationDetector.isAlarmNotification(
                "com.android.deskclock", null, "channel_id_deskclock_alarm"));
        assertTrue(PhoneAlarmNotificationDetector.isRinging(
                true, true, true, false, false, false, false));
    }

    @Test
    public void hyperOsArrivingAlarmIsNotTreatedAsRinging() {
        assertTrue(PhoneAlarmNotificationDetector.isAlarmNotification(
                "com.android.deskclock", null, "channel_id_deskclock_alarm"));
        assertFalse(PhoneAlarmNotificationDetector.isRinging(
                true, false, false, false, false, false, false));
    }

    @Test
    public void hyperOsTimerChannelIsRejected() {
        assertFalse(PhoneAlarmNotificationDetector.isAlarmNotification(
                "com.android.deskclock", null, "channel_id_deskclock_timer"));
    }

    @Test
    public void anotherAppCannotClaimHyperOsAlarmChannel() {
        assertFalse(PhoneAlarmNotificationDetector.isAlarmNotification(
                "example.clock", null, "channel_id_deskclock_alarm"));
    }
}
