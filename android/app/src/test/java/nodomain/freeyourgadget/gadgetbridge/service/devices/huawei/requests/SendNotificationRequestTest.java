package nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.requests;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import nodomain.freeyourgadget.gadgetbridge.devices.huawei.packets.Notifications;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationType;

public class SendNotificationRequestTest {
    @Test
    public void alarmClockUsesGenericNotificationInsteadOfSms() {
        assertEquals(Notifications.NotificationType.generic,
                SendNotificationRequest.getNotificationType(NotificationType.GENERIC_ALARM_CLOCK));
    }
}
