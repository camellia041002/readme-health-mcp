package nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.requests;

import java.io.IOException;

import nodomain.freeyourgadget.gadgetbridge.devices.huawei.packets.Alarms;
import nodomain.freeyourgadget.gadgetbridge.devices.huawei.packets.PhoneAlarm;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.HuaweiSupportProvider;

/** One native command per phone alarm lifecycle transition. */
public final class PhoneAlarmRequest extends Request {
    public PhoneAlarmRequest(final HuaweiSupportProvider support, final boolean start, final long alarmTime) {
        super(support);
        serviceId = Alarms.id;
        addToResponse = false;
        if (start) {
            commandId = PhoneAlarm.Start.COMMAND_ID;
            sendingPacket = new PhoneAlarm.Start(paramsProvider, alarmTime);
        } else {
            commandId = PhoneAlarm.Dismiss.COMMAND_ID;
            sendingPacket = new PhoneAlarm.Dismiss(paramsProvider);
        }
    }

    public void send() throws IOException {
        doPerform();
    }
}
