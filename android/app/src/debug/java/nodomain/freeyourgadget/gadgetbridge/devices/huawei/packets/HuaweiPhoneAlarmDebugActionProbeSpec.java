/*  Copyright (C) 2026 toge

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
package nodomain.freeyourgadget.gadgetbridge.devices.huawei.packets;

import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.devices.huawei.HuaweiPacket;
import nodomain.freeyourgadget.gadgetbridge.util.StringUtils;

/** Fixed values and canonical plaintext preview for one controlled Dismiss probe. */
public record HuaweiPhoneAlarmDebugActionProbeSpec(
        int alarmId,
        int actionId,
        String canonicalPacketHex
) {
    public static final int ALARM_ID = HuaweiPhoneAlarmDebugProbeSpec.ALARM_ID;
    public static final int DISMISS_ACTION_ID = 2;

    public static HuaweiPhoneAlarmDebugActionProbeSpec createDismiss()
            throws HuaweiPacket.CryptoException {
        final HuaweiPacket.ParamsProvider previewParams = new HuaweiPacket.ParamsProvider();
        previewParams.setTransactionsCrypted(false);

        final HuaweiPhoneAlarmDebugActionPacketBuilder packet =
                new HuaweiPhoneAlarmDebugActionPacketBuilder(
                        previewParams,
                        ALARM_ID,
                        DISMISS_ACTION_ID
                );
        final List<byte[]> serialized = packet.serialize();
        if (serialized.size() != 1) {
            throw new IllegalStateException("Expected one canonical Huawei phone alarm action packet");
        }

        return new HuaweiPhoneAlarmDebugActionProbeSpec(
                ALARM_ID,
                DISMISS_ACTION_ID,
                StringUtils.bytesToHex(serialized.get(0))
        );
    }
}
