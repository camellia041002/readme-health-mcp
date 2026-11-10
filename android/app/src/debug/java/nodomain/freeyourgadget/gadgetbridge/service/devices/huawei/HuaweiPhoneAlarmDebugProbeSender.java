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
package nodomain.freeyourgadget.gadgetbridge.service.devices.huawei;

import android.os.Bundle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import nodomain.freeyourgadget.gadgetbridge.devices.huawei.packets.Alarms;
import nodomain.freeyourgadget.gadgetbridge.devices.huawei.packets.HuaweiPhoneAlarmDebugActionPacketBuilder;
import nodomain.freeyourgadget.gadgetbridge.devices.huawei.packets.HuaweiPhoneAlarmDebugActionProbeSpec;
import nodomain.freeyourgadget.gadgetbridge.devices.huawei.packets.HuaweiPhoneAlarmDebugPacketBuilder;
import nodomain.freeyourgadget.gadgetbridge.devices.huawei.packets.HuaweiPhoneAlarmDebugProbeSpec;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.requests.Request;

/** Debug-only transport bridge. Each request token can queue exactly one packet. */
public final class HuaweiPhoneAlarmDebugProbeSender {
    private static final Logger LOG = LoggerFactory.getLogger(HuaweiPhoneAlarmDebugProbeSender.class);
    private static final Set<String> CONSUMED_REQUEST_TOKENS = ConcurrentHashMap.newKeySet();

    private HuaweiPhoneAlarmDebugProbeSender() {
    }

    public static void sendOnce(final HuaweiSupportProvider support, final Bundle options) {
        final String requestToken = options.getString(HuaweiPhoneAlarmDebugProbeContract.REQUEST_TOKEN_KEY, "");
        final long rawAlarmTime = options.getLong(
                HuaweiPhoneAlarmDebugProbeContract.RAW_ALARM_TIME_KEY,
                Long.MIN_VALUE
        );
        if (rawAlarmTime <= 0 || !consumeRequestToken(requestToken)) {
            LOG.error("Rejecting Huawei phone alarm probe with invalid token or raw alarm time");
            return;
        }

        final Request request = new Request(support) {
            {
                serviceId = Alarms.id;
                commandId = HuaweiPhoneAlarmDebugPacketBuilder.COMMAND_ID;
                addToResponse = false;
                sendingPacket = new HuaweiPhoneAlarmDebugPacketBuilder(
                        paramsProvider,
                        HuaweiPhoneAlarmDebugProbeSpec.ALARM_ID,
                        rawAlarmTime,
                        HuaweiPhoneAlarmDebugProbeSpec.SNOOZE_DELAY,
                        HuaweiPhoneAlarmDebugProbeSpec.SNOOZE_COUNT
                );
            }
        };

        try {
            request.doPerform();
            LOG.info(
                    "Huawei phone alarm probe queued exactly once: requestToken={}, alarmId={}, " +
                            "rawAlarmTime={}, snoozeDelay={}, snoozeCount={}; no retry is configured",
                    requestToken,
                    HuaweiPhoneAlarmDebugProbeSpec.ALARM_ID,
                    rawAlarmTime,
                    HuaweiPhoneAlarmDebugProbeSpec.SNOOZE_DELAY,
                    HuaweiPhoneAlarmDebugProbeSpec.SNOOZE_COUNT
            );
        } catch (IOException e) {
            LOG.error("Failed to queue Huawei phone alarm probe; it will not be retried", e);
        }
    }

    public static void sendDismissOnce(final HuaweiSupportProvider support, final Bundle options) {
        final String requestToken = options.getString(HuaweiPhoneAlarmDebugProbeContract.REQUEST_TOKEN_KEY, "");
        if (!consumeRequestToken(requestToken)) {
            LOG.error("Rejecting Huawei phone alarm dismiss probe with invalid or duplicate token");
            return;
        }

        final Request request = new Request(support) {
            {
                serviceId = Alarms.id;
                commandId = HuaweiPhoneAlarmDebugActionPacketBuilder.COMMAND_ID;
                addToResponse = false;
                sendingPacket = new HuaweiPhoneAlarmDebugActionPacketBuilder(
                        paramsProvider,
                        HuaweiPhoneAlarmDebugActionProbeSpec.ALARM_ID,
                        HuaweiPhoneAlarmDebugActionProbeSpec.DISMISS_ACTION_ID
                );
            }
        };

        try {
            request.doPerform();
            LOG.info(
                    "Huawei phone alarm dismiss probe queued exactly once: requestToken={}, alarmId={}, " +
                            "actionId={}; no retry is configured",
                    requestToken,
                    HuaweiPhoneAlarmDebugActionProbeSpec.ALARM_ID,
                    HuaweiPhoneAlarmDebugActionProbeSpec.DISMISS_ACTION_ID
            );
        } catch (IOException e) {
            LOG.error("Failed to queue Huawei phone alarm dismiss probe; it will not be retried", e);
        }
    }

    private static boolean consumeRequestToken(final String requestToken) {
        if (requestToken.isBlank()) {
            return false;
        }
        if (!CONSUMED_REQUEST_TOKENS.add(requestToken)) {
            LOG.warn("Ignoring duplicate Huawei phone alarm probe request token {}", requestToken);
            return false;
        }
        return true;
    }
}
