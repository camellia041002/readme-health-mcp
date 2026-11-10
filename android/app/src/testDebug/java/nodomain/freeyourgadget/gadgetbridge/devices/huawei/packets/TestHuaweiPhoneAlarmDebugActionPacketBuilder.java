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

import org.junit.Assert;
import org.junit.Test;

import java.util.List;

import nodomain.freeyourgadget.gadgetbridge.devices.huawei.HuaweiPacket;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

public class TestHuaweiPhoneAlarmDebugActionPacketBuilder {
    private final HuaweiPacket.ParamsProvider paramsProvider = new HuaweiPacket.ParamsProvider() {
        @Override
        public byte getDeviceSupportType() {
            return 0;
        }

        @Override
        public byte[] getSecretKey() {
            return new byte[16];
        }

        @Override
        public byte[] getIv() {
            return new byte[16];
        }

        @Override
        public boolean areTransactionsCrypted() {
            return false;
        }

        @Override
        public int getMtu() {
            return 0;
        }

        @Override
        public int getSliceSize() {
            return 0xf4;
        }
    };

    @Test
    public void testDismissSchemaUsesNumber32AndNumber8() {
        final HuaweiPhoneAlarmDebugActionPacketBuilder packet =
                new HuaweiPhoneAlarmDebugActionPacketBuilder(paramsProvider, 0x10203040, 2);

        Assert.assertEquals(Alarms.id, packet.serviceId);
        Assert.assertEquals(HuaweiPhoneAlarmDebugActionPacketBuilder.COMMAND_ID, packet.commandId);
        Assert.assertTrue(packet.complete);
        Assert.assertArrayEquals(
                GB.hexStringToByteArray("010410203040020102"),
                packet.getTlv().serialize()
        );
    }

    @Test
    public void testCompleteDismissPacketIncludesHeaderAndCrc() throws HuaweiPacket.CryptoException {
        final HuaweiPhoneAlarmDebugActionPacketBuilder packet =
                new HuaweiPhoneAlarmDebugActionPacketBuilder(paramsProvider, 1, 2);

        final List<byte[]> serialized = packet.serialize();

        Assert.assertEquals(1, serialized.size());
        Assert.assertArrayEquals(
                GB.hexStringToByteArray("5A000C00080A010400000001020102D94E"),
                serialized.get(0)
        );
    }

    @Test
    public void testDismissSpecUsesObservedIdsAndCanonicalPlaintextHex()
            throws HuaweiPacket.CryptoException {
        final HuaweiPhoneAlarmDebugActionProbeSpec spec =
                HuaweiPhoneAlarmDebugActionProbeSpec.createDismiss();

        Assert.assertEquals(1, spec.alarmId());
        Assert.assertEquals(2, spec.actionId());
        Assert.assertEquals("5A000C00080A010400000001020102D94E", spec.canonicalPacketHex());
    }

    @Test
    public void testActionRejectsValuesOutsideNumber8Range() {
        assertInvalidAction(-1);
        assertInvalidAction(256);
    }

    private void assertInvalidAction(int actionId) {
        try {
            new HuaweiPhoneAlarmDebugActionPacketBuilder(paramsProvider, 1, actionId);
            Assert.fail("Expected an IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
