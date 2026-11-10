/*  Copyright (C) 2026 Gadgetbridge contributors

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
package nodomain.freeyourgadget.gadgetbridge.activities.preferences

import android.content.Intent
import android.database.Cursor
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.BuildConfig
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.AbstractPreferenceFragment
import nodomain.freeyourgadget.gadgetbridge.activities.AbstractSettingsActivityV2
import nodomain.freeyourgadget.gadgetbridge.activities.selfhostedhealth.SelfHostedHealthLogActivity
import nodomain.freeyourgadget.gadgetbridge.util.GB
import nodomain.freeyourgadget.gadgetbridge.util.GBPrefs
import nodomain.freeyourgadget.gadgetbridge.util.cycle.CycleContextStore
import nodomain.freeyourgadget.gadgetbridge.util.cycle.CycleContextSyncWorker
import nodomain.freeyourgadget.gadgetbridge.util.selfhostedhealth.SelfHostedHealthSyncWorker
import nodomain.freeyourgadget.gadgetbridge.util.healthquery.HealthQueryConnectionStore
import nodomain.freeyourgadget.gadgetbridge.util.healthquery.HealthQueryHistoryImport
import nodomain.freeyourgadget.gadgetbridge.util.healthquery.HealthQueryImportPreview
import nodomain.freeyourgadget.gadgetbridge.util.healthquery.HealthQueryPayloadMapper
import nodomain.freeyourgadget.gadgetbridge.util.healthquery.HealthQueryPairingParser
import nodomain.freeyourgadget.gadgetbridge.util.healthquery.HealthQueryTransport
import java.time.LocalDate
import java.time.ZoneId
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SelfHostedHealthPreferencesActivity : AbstractSettingsActivityV2() {
    override fun newFragment(): PreferenceFragmentCompat = SelfHostedHealthPreferencesFragment()

    class SelfHostedHealthPreferencesFragment : AbstractPreferenceFragment() {

        private val historyImportLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val context = context ?: return@registerForActivityResult
            Thread {
                val preview = runCatching {
                    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { cursor: Cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                        ?: "history.json"
                    val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("unreadable_file")
                    HealthQueryHistoryImport.parse(name, text)
                }.getOrNull()
                activity?.runOnUiThread {
                    if (preview == null) {
                        GB.toast(context, R.string.selfhosted_health_import_invalid, Toast.LENGTH_LONG, GB.ERROR)
                    } else {
                        confirmImport(preview)
                    }
                }
            }.start()
        }

        override fun getPreferenceKeysWithSummary(): Set<String> = emptySet()

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.selfhosted_health_preferences, rootKey)

            setupEnabledSwitch()
            setupUrl()
            setupInterval()
            setupDeviceSelection()
            setupActions()
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            // The worker writes its outcome to preferences, so "Upload now" reports back on this
            // screen instead of only on the next visit.
            WorkManager.getInstance(requireContext())
                .getWorkInfosByTagLiveData(SelfHostedHealthSyncWorker.WORK_TAG)
                .observe(viewLifecycleOwner) { updateStatusSummary() }
        }

        override fun onResume() {
            super.onResume()
            updateStatusSummary()
            updateUrlSummary()
        }

        /**
         * Freezes a start date the first time upload is switched on.
         *
         * Without it the first run would walk back as far as the sample database goes and replay a
         * previous device's history into the server's day files.
         */
        private fun setupEnabledSwitch() {
            findPreference<Preference>(GBPrefs.SELF_HOSTED_HEALTH_ENABLED)?.setOnPreferenceChangeListener { _, newValue ->
                val enabling = newValue == true
                if (enabling) {
                    val prefs = GBApplication.getPrefs()
                    if (prefs.getLong(GBPrefs.SELF_HOSTED_HEALTH_INITIAL_SYNC_TS, 0L) <= 0L) {
                        val startOfToday = LocalDate.now().minusDays(30)
                            .atStartOfDay(ZoneId.systemDefault())
                            .toEpochSecond()
                        prefs.preferences.edit()
                            .putLong(GBPrefs.SELF_HOSTED_HEALTH_INITIAL_SYNC_TS, startOfToday)
                            .apply()
                    }
                }
                // Turning the feature off must also stop the periodic upload; turning it on re-arms
                // it if an interval is set. The listener fires before the new value is persisted, so
                // the fresh state is passed in explicitly.
                SelfHostedHealthSyncWorker.reschedulePeriodic(requireContext(), enabled = enabling)
                if (enabling && CycleContextStore.isEnabled()) {
                    CycleContextSyncWorker.enqueue(requireContext())
                }
                true
            }
        }

        /**
         * The periodic upload lives in WorkManager, not the preference store, so a cadence change has
         * to be turned into a (re)schedule here. useSimpleSummaryProvider already shows the choice.
         */
        private fun setupInterval() {
            findPreference<ListPreference>(GBPrefs.SELF_HOSTED_HEALTH_SYNC_INTERVAL)?.setOnPreferenceChangeListener { _, newValue ->
                val minutes = (newValue as? String)?.toIntOrNull() ?: 0
                SelfHostedHealthSyncWorker.reschedulePeriodic(requireContext(), minutes = minutes)
                true
            }
        }

        /** Exchange a short-lived pairing link without ever persisting the enrollment token. */
        private fun setupUrl() {
            val urlPref = findPreference<EditTextPreference>(GBPrefs.SELF_HOSTED_HEALTH_URL) ?: return
            urlPref.setOnPreferenceChangeListener { _, newValue ->
                val raw = (newValue as? String).orEmpty()
                val pairing = runCatching { HealthQueryPairingParser.parse(raw) }.getOrNull()
                if (pairing == null) {
                    GB.toast(requireContext(), R.string.healthquery_pairing_invalid, Toast.LENGTH_SHORT, GB.ERROR)
                    return@setOnPreferenceChangeListener false
                }
                val appContext = requireContext().applicationContext
                Thread {
                    val serverUrl = runCatching {
                        val connection = HealthQueryTransport(appContext).exchange(
                            pairing, "Android ${Build.MODEL}", BuildConfig.VERSION_NAME
                        )
                        HealthQueryConnectionStore(appContext).save(connection)
                        GBApplication.getPrefs().preferences.edit()
                            .remove(GBPrefs.SELF_HOSTED_HEALTH_URL)
                            .remove(GBPrefs.SELF_HOSTED_HEALTH_TOKEN)
                            .apply()
                        connection.serverUrl
                    }.getOrNull()
                    activity?.runOnUiThread {
                        updateUrlSummary()
                        GB.toast(requireContext(), if (serverUrl != null) R.string.healthquery_pairing_success else R.string.healthquery_pairing_failed, Toast.LENGTH_SHORT, if (serverUrl != null) GB.INFO else GB.ERROR)
                    }
                }.start()
                false
            }
            updateUrlSummary()
        }

        private fun updateUrlSummary() {
            findPreference<EditTextPreference>(GBPrefs.SELF_HOSTED_HEALTH_URL)?.let { preference ->
                val connection = HealthQueryConnectionStore(requireContext()).load()
                preference.text = ""
                preference.summary = connection?.serverUrl ?: getString(R.string.pref_selfhosted_health_url_summary)
            }
        }

        private fun setupDeviceSelection() {
            val devicesPref = findPreference<MultiSelectListPreference>(GBPrefs.SELF_HOSTED_HEALTH_DEVICE_SELECTION)
                ?: return
            val devices = GBApplication.app().deviceManager.devices
            devicesPref.entryValues = devices.map { it.address }.toTypedArray()
            devicesPref.entries = devices.map { it.aliasOrName }.toTypedArray()
        }

        private fun setupActions() {
            findPreference<Preference>(GBPrefs.SELF_HOSTED_HEALTH_IMPORT)?.setOnPreferenceClickListener {
                val context = context ?: return@setOnPreferenceClickListener false
                if (HealthQueryConnectionStore(context).load() == null) {
                    GB.toast(context, R.string.selfhosted_health_import_not_paired, Toast.LENGTH_LONG, GB.WARN)
                    return@setOnPreferenceClickListener true
                }
                historyImportLauncher.launch(arrayOf("application/json", "text/csv", "text/comma-separated-values", "text/plain"))
                true
            }

            findPreference<Preference>(GBPrefs.SELF_HOSTED_HEALTH_SYNC_NOW)?.setOnPreferenceClickListener {
                val context = context ?: return@setOnPreferenceClickListener false
                val request = OneTimeWorkRequest.Builder(SelfHostedHealthSyncWorker::class.java)
                    .addTag(SelfHostedHealthSyncWorker.WORK_TAG)
                    // Tell the run it was hand-triggered so the sync log can label it "manual".
                    .setInputData(
                        Data.Builder()
                            .putBoolean(SelfHostedHealthSyncWorker.INPUT_MANUAL, true)
                            .build()
                    )
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    ONE_TIME_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    request
                )
                GB.toast(context, R.string.selfhosted_health_upload_started, Toast.LENGTH_SHORT, GB.INFO)
                true
            }

            findPreference<Preference>(GBPrefs.SELF_HOSTED_HEALTH_LOG)?.setOnPreferenceClickListener {
                val context = context ?: return@setOnPreferenceClickListener false
                startActivity(Intent(context, SelfHostedHealthLogActivity::class.java))
                true
            }

            findPreference<Preference>(GBPrefs.SELF_HOSTED_HEALTH_RESET_CURSOR)?.setOnPreferenceClickListener {
                val context = context ?: return@setOnPreferenceClickListener false
                resetCursors()
                GB.toast(context, R.string.pref_selfhosted_health_reset_cursor_done, Toast.LENGTH_SHORT, GB.INFO)
                true
            }
        }

        private fun confirmImport(preview: HealthQueryImportPreview) {
            val labels = preview.categories.joinToString("、") {
                when (it) {
                    "steps" -> "步数"
                    "heart_rate" -> "心率"
                    "sleep" -> "睡眠"
                    "workout" -> "锻炼"
                    else -> it
                }
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.pref_selfhosted_health_import_title)
                .setMessage(getString(
                    R.string.selfhosted_health_import_preview,
                    preview.firstDate, preview.lastDate, preview.recordCount, labels
                ))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.selfhosted_health_import_confirm) { _, _ -> uploadImport(preview) }
                .show()
        }

        private fun uploadImport(preview: HealthQueryImportPreview) {
            val appContext = requireContext().applicationContext
            Thread {
                val connection = HealthQueryConnectionStore(appContext).load()
                var succeeded = 0
                var failed = 0
                if (connection == null) {
                    failed = preview.days.size
                } else {
                    val requests = HealthQueryPayloadMapper.buildDayRequests(
                        preview.days, ZoneId.systemDefault(), connection.deviceId, "snapshot"
                    )
                    for ((_, request) in requests) {
                        when (HealthQueryTransport(appContext).upload(connection, request)) {
                            is nodomain.freeyourgadget.gadgetbridge.util.healthquery.HealthQueryTransportResult.Success -> succeeded++
                            is nodomain.freeyourgadget.gadgetbridge.util.healthquery.HealthQueryTransportResult.Failure -> failed++
                        }
                    }
                }
                activity?.runOnUiThread {
                    GB.toast(
                        requireContext(),
                        getString(R.string.selfhosted_health_import_result, succeeded, failed),
                        Toast.LENGTH_LONG,
                        if (failed == 0) GB.INFO else GB.WARN
                    )
                }
            }.start()
        }

        /** Drops the per-device upload and sleep cursors so the next run re-sends from the start
         *  date. Safe because the server merges: steps take the larger value and heart rate dedups. */
        private fun resetCursors() {
            val preferences = GBApplication.getPrefs().preferences
            val editor = preferences.edit()
            for (address in GBApplication.app().deviceManager.devices.mapNotNull { it.address }) {
                editor.remove(SelfHostedHealthSyncWorker.cursorKey(address))
                editor.remove(SelfHostedHealthSyncWorker.sleepCursorKey(address))
            }
            editor.apply()
        }

        private fun updateStatusSummary() {
            val status = GBApplication.getPrefs().getString(GBPrefs.SELF_HOSTED_HEALTH_STATUS, "").orEmpty()
            findPreference<Preference>(GBPrefs.SELF_HOSTED_HEALTH_STATUS)?.let { preference ->
                preference.summary = status
                preference.isVisible = status.isNotEmpty()
            }
        }

        companion object {
            private const val ONE_TIME_WORK_NAME = "SelfHostedHealthSyncWorker_OneTime"
        }
    }
}
