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

public class TestHuaweiPhoneAlarmDebugPacketBuilder {
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
    public void testSchemaWidthsAndGenericNameAreFixedInTlv() {
        final HuaweiPhoneAlarmDebugPacketBuilder packet = new HuaweiPhoneAlarmDebugPacketBuilder(
                paramsProvider,
                0x10203040,
                0x0102030405060708L,
                10,
                3
        );

        Assert.assertEquals(Alarms.id, packet.serviceId);
        Assert.assertEquals(HuaweiPhoneAlarmDebugPacketBuilder.COMMAND_ID, packet.commandId);
        Assert.assertTrue(packet.complete);
        Assert.assertArrayEquals(
                GB.hexStringToByteArray(
                        "010410203040" +
                                "02124761646765746272696467652070726f6265" +
                                "03080102030405060708" +
                                "04010a" +
                                "050103"
                ),
                packet.getTlv().serialize()
        );
    }

    @Test
    public void testCompletePacketSerializationIncludesHeaderAndCrc() throws HuaweiPacket.CryptoException {
        final HuaweiPhoneAlarmDebugPacketBuilder packet = new HuaweiPhoneAlarmDebugPacketBuilder(
                paramsProvider,
                0x10203040,
                0x0102030405060708L,
                10,
                3
        );

        final List<byte[]> serialized = packet.serialize();

        Assert.assertEquals(1, serialized.size());
        Assert.assertArrayEquals(
                GB.hexStringToByteArray(
                        "5a002d000809" +
                                "010410203040" +
                                "02124761646765746272696467652070726f6265" +
                                "03080102030405060708" +
                                "04010a" +
                                "050103" +
                                "acb5"
                ),
                serialized.get(0)
        );
    }

    @Test
    public void testProbeSpecUsesFixedNonPrivateValuesAndCanonicalPlaintextHex()
            throws HuaweiPacket.CryptoException {
        final HuaweiPhoneAlarmDebugProbeSpec spec = HuaweiPhoneAlarmDebugProbeSpec.create(
                0x0102030405060708L
        );

        Assert.assertEquals(1, spec.alarmId());
        Assert.assertEquals(0x0102030405060708L, spec.rawAlarmTime());
        Assert.assertEquals(10, spec.snoozeDelay());
        Assert.assertEquals(3, spec.snoozeCount());
        Assert.assertEquals(
                "5A002D000809" +
                        "010400000001" +
                        "02124761646765746272696467652070726F6265" +
                        "03080102030405060708" +
                        "04010A" +
                        "050103" +
                        "DB29",
                spec.canonicalPacketHex()
        );
    }

    @Test
    public void testSnoozeFieldsRejectValuesOutsideNumber8Range() {
        assertInvalidUnsignedByte(-1, 0);
        assertInvalidUnsignedByte(256, 0);
        assertInvalidUnsignedByte(0, -1);
        assertInvalidUnsignedByte(0, 256);
    }

    private void assertInvalidUnsignedByte(int snoozeDelay, int snoozeCount) {
        try {
            new HuaweiPhoneAlarmDebugPacketBuilder(paramsProvider, 1, 2L, snoozeDelay, snoozeCount);
            Assert.fail("Expected an IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
