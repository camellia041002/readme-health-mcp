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
package nodomain.freeyourgadget.gadgetbridge.activities.debug

import android.os.Bundle
import android.widget.Toast
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.devices.huawei.HuaweiCoordinator
import nodomain.freeyourgadget.gadgetbridge.devices.huawei.HuaweiPacket
import nodomain.freeyourgadget.gadgetbridge.devices.huawei.packets.HuaweiPhoneAlarmDebugActionProbeSpec
import nodomain.freeyourgadget.gadgetbridge.devices.huawei.packets.HuaweiPhoneAlarmDebugPacketBuilder
import nodomain.freeyourgadget.gadgetbridge.devices.huawei.packets.HuaweiPhoneAlarmDebugProbeSpec
import nodomain.freeyourgadget.gadgetbridge.service.devices.huawei.HuaweiPhoneAlarmDebugProbeContract
import nodomain.freeyourgadget.gadgetbridge.util.GB
import java.text.DateFormat
import java.util.Date
import java.util.UUID

class HuaweiPhoneAlarmDebugProbeFragment : AbstractDebugFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.debug_preferences_empty, rootKey)
        preferenceScreen?.title = getString(R.string.huawei_phone_alarm_probe_title)

        lateinit var sendPreference: Preference
        sendPreference = addDynamicPref(
            title = getString(R.string.huawei_phone_alarm_probe_prepare_title),
            summary = getString(R.string.huawei_phone_alarm_probe_prepare_summary),
            icon = R.drawable.ic_access_time
        ) {
            selectDeviceAndConfirm(sendPreference)
        }

        lateinit var dismissPreference: Preference
        dismissPreference = addDynamicPref(
            title = getString(R.string.huawei_phone_alarm_dismiss_probe_prepare_title),
            summary = getString(R.string.huawei_phone_alarm_dismiss_probe_prepare_summary),
            icon = R.drawable.ic_stop
        ) {
            selectDeviceAndConfirmDismiss(dismissPreference)
        }
    }

    private fun selectDeviceAndConfirm(sendPreference: Preference) {
        runOnDebugDevices(getString(R.string.huawei_phone_alarm_probe_choose_device), true) { device ->
            if (device.deviceCoordinator !is HuaweiCoordinator) {
                GB.toast(
                    requireContext(),
                    getString(R.string.huawei_phone_alarm_probe_huawei_only),
                    Toast.LENGTH_LONG,
                    GB.ERROR
                )
                return@runOnDebugDevices
            }

            val rawAlarmTime = System.currentTimeMillis()
            val spec = try {
                HuaweiPhoneAlarmDebugProbeSpec.create(rawAlarmTime)
            } catch (e: HuaweiPacket.CryptoException) {
                GB.toast(
                    requireContext(),
                    getString(R.string.huawei_phone_alarm_probe_preview_failed),
                    Toast.LENGTH_LONG,
                    GB.ERROR,
                    e
                )
                return@runOnDebugDevices
            }

            val localTime = DateFormat.getDateTimeInstance().format(Date(rawAlarmTime))
            val message = getString(
                R.string.huawei_phone_alarm_probe_confirmation,
                device.aliasOrName,
                spec.alarmId(),
                HuaweiPhoneAlarmDebugPacketBuilder.PROBE_ALARM_NAME,
                spec.rawAlarmTime(),
                localTime,
                spec.snoozeDelay(),
                spec.snoozeCount(),
                spec.canonicalPacketHex()
            )

            MaterialAlertDialogBuilder(requireContext())
                .setCancelable(true)
                .setIcon(R.drawable.ic_warning)
                .setTitle(R.string.huawei_phone_alarm_probe_confirm_title)
                .setMessage(message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.huawei_phone_alarm_probe_send_once) { _, _ ->
                    sendPreference.isEnabled = false
                    sendPreference.summary = getString(R.string.huawei_phone_alarm_probe_sent_summary)

                    val options = Bundle().apply {
                        putString(
                            HuaweiPhoneAlarmDebugProbeContract.ACTION_KEY,
                            HuaweiPhoneAlarmDebugProbeContract.ACTION_SEND_ONCE
                        )
                        putLong(HuaweiPhoneAlarmDebugProbeContract.RAW_ALARM_TIME_KEY, rawAlarmTime)
                        putString(
                            HuaweiPhoneAlarmDebugProbeContract.REQUEST_TOKEN_KEY,
                            UUID.randomUUID().toString()
                        )
                    }
                    GBApplication.deviceService(device).onTestNewFunction(options)
                    GB.toast(
                        requireContext(),
                        getString(R.string.huawei_phone_alarm_probe_queued),
                        Toast.LENGTH_LONG,
                        GB.INFO
                    )
                }
                .show()
        }
    }

    private fun selectDeviceAndConfirmDismiss(dismissPreference: Preference) {
        runOnDebugDevices(getString(R.string.huawei_phone_alarm_probe_choose_device), true) { device ->
            if (device.deviceCoordinator !is HuaweiCoordinator) {
                GB.toast(
                    requireContext(),
                    getString(R.string.huawei_phone_alarm_probe_huawei_only),
                    Toast.LENGTH_LONG,
                    GB.ERROR
                )
                return@runOnDebugDevices
            }

            val spec = try {
                HuaweiPhoneAlarmDebugActionProbeSpec.createDismiss()
            } catch (e: HuaweiPacket.CryptoException) {
                GB.toast(
                    requireContext(),
                    getString(R.string.huawei_phone_alarm_probe_preview_failed),
                    Toast.LENGTH_LONG,
                    GB.ERROR,
                    e
                )
                return@runOnDebugDevices
            }

            val message = getString(
                R.string.huawei_phone_alarm_dismiss_probe_confirmation,
                device.aliasOrName,
                spec.alarmId(),
                spec.actionId(),
                spec.canonicalPacketHex()
            )

            MaterialAlertDialogBuilder(requireContext())
                .setCancelable(true)
                .setIcon(R.drawable.ic_warning)
                .setTitle(R.string.huawei_phone_alarm_probe_confirm_title)
                .setMessage(message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.huawei_phone_alarm_dismiss_probe_send_once) { _, _ ->
                    dismissPreference.isEnabled = false
                    dismissPreference.summary =
                        getString(R.string.huawei_phone_alarm_dismiss_probe_sent_summary)

                    val options = Bundle().apply {
                        putString(
                            HuaweiPhoneAlarmDebugProbeContract.ACTION_KEY,
                            HuaweiPhoneAlarmDebugProbeContract.ACTION_SEND_DISMISS_ONCE
                        )
                        putString(
                            HuaweiPhoneAlarmDebugProbeContract.REQUEST_TOKEN_KEY,
                            UUID.randomUUID().toString()
                        )
                    }
                    GBApplication.deviceService(device).onTestNewFunction(options)
                    GB.toast(
                        requireContext(),
                        getString(R.string.huawei_phone_alarm_dismiss_probe_queued),
                        Toast.LENGTH_LONG,
                        GB.INFO
                    )
                }
                .show()
        }
    }
}
