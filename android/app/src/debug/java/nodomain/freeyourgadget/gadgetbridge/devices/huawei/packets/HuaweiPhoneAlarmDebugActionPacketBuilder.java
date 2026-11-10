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

/** Debug-build-only builder for one candidate phone-alarm action packet. */
public final class HuaweiPhoneAlarmDebugActionPacketBuilder extends HuaweiPacket {
    private static final Logger LOG = LoggerFactory.getLogger(HuaweiPhoneAlarmDebugActionPacketBuilder.class);

    public static final byte COMMAND_ID = 0x0a;

    private final int alarmId;
    private final int actionId;

    public HuaweiPhoneAlarmDebugActionPacketBuilder(
            ParamsProvider paramsProvider,
            int alarmId,
            int actionId
    ) {
        super(Objects.requireNonNull(paramsProvider, "paramsProvider"));
        if (actionId < 0 || actionId > 0xff) {
            throw new IllegalArgumentException("actionId must be between 0 and 255");
        }

        this.alarmId = alarmId;
        this.actionId = actionId;
        this.serviceId = Alarms.id;
        this.commandId = COMMAND_ID;
        this.tlv = new HuaweiTLV()
                .put(0x01, alarmId)
                .put(0x02, (byte) actionId);
        this.complete = true;
    }

    @Override
    public List<byte[]> serialize() throws CryptoException {
        final List<byte[]> packets = super.serialize();
        for (int i = 0; i < packets.size(); i++) {
            LOG.info(
                    "Huawei phone alarm dismiss probe packet serialized: service=0x08, command=0x0A, " +
                            "alarmId={}, actionId={}, chunk={}/{}, packetHex={}; " +
                            "serialization alone does not prove a transport write",
                    alarmId,
                    actionId,
                    i + 1,
                    packets.size(),
                    StringUtils.bytesToHex(packets.get(i))
            );
        }
        return packets;
    }
}
