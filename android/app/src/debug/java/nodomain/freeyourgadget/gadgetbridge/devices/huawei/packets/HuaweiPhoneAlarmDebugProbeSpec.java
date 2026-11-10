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

/** Fixed, non-private values and canonical plaintext preview for one controlled probe. */
public record HuaweiPhoneAlarmDebugProbeSpec(
        int alarmId,
        long rawAlarmTime,
        int snoozeDelay,
        int snoozeCount,
        String canonicalPacketHex
) {
    public static final int ALARM_ID = 1;
    public static final int SNOOZE_DELAY = 10;
    public static final int SNOOZE_COUNT = 3;

    public static HuaweiPhoneAlarmDebugProbeSpec create(final long rawAlarmTime)
            throws HuaweiPacket.CryptoException {
        final HuaweiPacket.ParamsProvider previewParams = new HuaweiPacket.ParamsProvider();
        previewParams.setTransactionsCrypted(false);

        final HuaweiPhoneAlarmDebugPacketBuilder packet = new HuaweiPhoneAlarmDebugPacketBuilder(
                previewParams,
                ALARM_ID,
                rawAlarmTime,
                SNOOZE_DELAY,
                SNOOZE_COUNT
        );
        final List<byte[]> serialized = packet.serialize();
        if (serialized.size() != 1) {
            throw new IllegalStateException("Expected one canonical Huawei phone alarm probe packet");
        }

        return new HuaweiPhoneAlarmDebugProbeSpec(
                ALARM_ID,
                rawAlarmTime,
                SNOOZE_DELAY,
                SNOOZE_COUNT,
                StringUtils.bytesToHex(serialized.get(0))
        );
    }
}
