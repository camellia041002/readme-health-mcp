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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

import nodomain.freeyourgadget.gadgetbridge.devices.huawei.HuaweiPacket;
import nodomain.freeyourgadget.gadgetbridge.devices.huawei.HuaweiTLV;
import nodomain.freeyourgadget.gadgetbridge.util.StringUtils;

/**
 * Debug-build-only builder for the unverified Huawei phone-alarm candidate command.
 *
 * <p>This class deliberately has no transport or scheduler dependency. Constructing or serializing
 * a packet cannot send it to a device. The alarm name is fixed so the probe cannot copy private
 * alarm titles or notes into packets or logs.</p>
 */
public final class HuaweiPhoneAlarmDebugPacketBuilder extends HuaweiPacket {
    private static final Logger LOG = LoggerFactory.getLogger(HuaweiPhoneAlarmDebugPacketBuilder.class);

    public static final byte COMMAND_ID = 0x09;
    public static final String PROBE_ALARM_NAME = "Gadgetbridge probe";

    private final int alarmId;
    private final long rawAlarmTime;
    private final int snoozeDelay;
    private final int snoozeCount;

    public HuaweiPhoneAlarmDebugPacketBuilder(
            ParamsProvider paramsProvider,
            int alarmId,
            long rawAlarmTime,
            int snoozeDelay,
            int snoozeCount
    ) {
        super(Objects.requireNonNull(paramsProvider, "paramsProvider"));

        requireUnsignedByte("snoozeDelay", snoozeDelay);
        requireUnsignedByte("snoozeCount", snoozeCount);

        this.alarmId = alarmId;
        this.rawAlarmTime = rawAlarmTime;
        this.snoozeDelay = snoozeDelay;
        this.snoozeCount = snoozeCount;

        this.serviceId = Alarms.id;
        this.commandId = COMMAND_ID;
        this.tlv = new HuaweiTLV()
                .put(0x01, alarmId)
                .put(0x02, PROBE_ALARM_NAME)
                .put(0x03, rawAlarmTime)
                .put(0x04, (byte) snoozeDelay)
                .put(0x05, (byte) snoozeCount);
        this.complete = true;
    }

    @Override
    public List<byte[]> serialize() throws CryptoException {
        final List<byte[]> packets = super.serialize();
        for (int i = 0; i < packets.size(); i++) {
            LOG.info(
                    "Huawei phone alarm probe packet serialized: service=0x08, command=0x09, " +
                            "alarmId={}, rawAlarmTime={} (unit/epoch unconfirmed), snoozeDelay={}, " +
                            "snoozeCount={}, chunk={}/{}, packetHex={}; serialization alone does not prove a transport write",
                    alarmId,
                    rawAlarmTime,
                    snoozeDelay,
                    snoozeCount,
                    i + 1,
                    packets.size(),
                    StringUtils.bytesToHex(packets.get(i))
            );
        }
        return packets;
    }

    private static void requireUnsignedByte(String field, int value) {
        if (value < 0 || value > 0xff) {
            throw new IllegalArgumentException(field + " must be between 0 and 255");
        }
    }
}
