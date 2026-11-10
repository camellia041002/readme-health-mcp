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

import android.app.DatePickerDialog
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.AbstractPreferenceFragment
import nodomain.freeyourgadget.gadgetbridge.util.GBPrefs
import nodomain.freeyourgadget.gadgetbridge.util.cycle.CycleContext
import nodomain.freeyourgadget.gadgetbridge.util.cycle.CycleContextStore
import nodomain.freeyourgadget.gadgetbridge.util.cycle.CycleContextSyncWorker
import java.time.LocalDate

class CycleContextPreferencesFragment : AbstractPreferenceFragment() {
    /** Guards against a second dialog when the screen is recreated, e.g. on rotation. */
    private var pendingNoticeShown = false

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.cycle_context_preferences, rootKey)
        setupSwitch()
        setupActions()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        // Opening this screen is the one moment the user is demonstrably thinking about cycle
        // data, so it is also the right moment to retry a deletion that never landed.
        if (CycleContextStore.pendingClear()) {
            CycleContextSyncWorker.enqueue(requireContext(), clear = true)
            showPendingClearNotice()
        }
        if (CycleContextStore.rollForwardIfNeeded(LocalDate.now())) {
            CycleContextSyncWorker.enqueue(requireContext())
        }
        refresh()
    }

    override fun onPause() {
        super.onPause()
        // Reset so the next visit asks again while the deletion is still outstanding.
        pendingNoticeShown = false
    }

    /**
     * Comes back on every visit until the server confirms the deletion. Deliberately a dialog the
     * user dismisses rather than a permanent line on the screen: the situation is temporary and
     * self-healing, so it should not sit there looking like a broken setting.
     */
    private fun showPendingClearNotice() {
        if (pendingNoticeShown) return
        pendingNoticeShown = true
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.cycle_pending_clear_title)
            .setMessage(R.string.cycle_pending_clear_summary)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun setupSwitch() {
        findPreference<SwitchPreferenceCompat>(GBPrefs.CYCLE_ENABLED)
            ?.setOnPreferenceChangeListener { preference, newValue ->
                val enabled = newValue == true
                if (enabled) {
                    CycleContextStore.enable()
                } else {
                    // One authenticated tombstone removes the server copy; after that no cycle
                    // values remain locally and no more cycle uploads are scheduled.
                    CycleContextSyncWorker.enqueue(requireContext(), clear = true)
                    CycleContextStore.clear()
                }
                (preference as SwitchPreferenceCompat).isChecked = enabled
                refresh()
                false
            }
    }

    private fun setupActions() {
        findPreference<Preference>(GBPrefs.CYCLE_LAST_START)?.setOnPreferenceClickListener {
            showStartDatePicker()
            true
        }
        findPreference<Preference>(KEY_LENGTHS)?.setOnPreferenceClickListener {
            showLengthsDialog()
            true
        }
        findPreference<Preference>(KEY_ARRIVED)?.setOnPreferenceClickListener {
            saveCalibration(LocalDate.now())
            true
        }
        findPreference<Preference>(KEY_TODAY_DAY)?.setOnPreferenceClickListener {
            showTodayDayDialog()
            true
        }
    }

    private fun showStartDatePicker() {
        val initial = CycleContextStore.lastStart() ?: LocalDate.now()
        DatePickerDialog(
            requireContext(),
            { _, year, month, day -> saveCalibration(LocalDate.of(year, month + 1, day)) },
            initial.year,
            initial.monthValue - 1,
            initial.dayOfMonth
        ).apply {
            datePicker.maxDate = System.currentTimeMillis()
            show()
        }
    }

    private fun showLengthsDialog() {
        val cycleInput = numericInput(
            getString(R.string.cycle_length_days_hint),
            CycleContextStore.cycleLengthDays()
        )
        val periodInput = numericInput(
            getString(R.string.cycle_period_days_hint),
            CycleContextStore.periodDays()
        )
        val padding = (24 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, 0, padding, 0)
            addView(cycleInput)
            addView(periodInput)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.cycle_lengths_title)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val cycleLength = cycleInput.text.toString().toIntOrNull()
                val periodDays = periodInput.text.toString().toIntOrNull()
                if (cycleLength == null || periodDays == null || cycleLength <= 0 ||
                    periodDays <= 0 || periodDays > cycleLength) {
                    Toast.makeText(requireContext(), R.string.cycle_invalid_lengths, Toast.LENGTH_SHORT).show()
                } else {
                    CycleContextStore.saveLengths(cycleLength, periodDays)
                    CycleContextStore.rollForwardIfNeeded(LocalDate.now())
                    if (CycleContextStore.config() != null) CycleContextSyncWorker.enqueue(requireContext())
                    refresh()
                }
            }
            .show()
    }

    private fun showTodayDayDialog() {
        val config = CycleContextStore.config()
        if (config == null) {
            Toast.makeText(requireContext(), R.string.cycle_set_start_first, Toast.LENGTH_SHORT).show()
            return
        }
        val currentDay = CycleContext.calculate(config, LocalDate.now()).cycleDay
        val input = numericInput(getString(R.string.cycle_today_day_hint), currentDay)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.cycle_today_day_edit_title)
            .setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val day = input.text.toString().toIntOrNull()
                if (day == null || day <= 0 || day > config.cycleLengthDays) {
                    Toast.makeText(requireContext(), R.string.cycle_invalid_day, Toast.LENGTH_SHORT).show()
                } else {
                    saveCalibration(LocalDate.now().minusDays((day - 1).toLong()))
                }
            }
            .show()
    }

    private fun numericInput(hint: String, value: Int): EditText = EditText(requireContext()).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(value.toString())
        setSelection(text.length)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun saveCalibration(start: LocalDate) {
        CycleContextStore.saveCalibration(start, LocalDate.now())
        CycleContextSyncWorker.enqueue(requireContext())
        refresh()
    }

    private fun refresh() {
        val enabled = CycleContextStore.isEnabled()
        findPreference<SwitchPreferenceCompat>(GBPrefs.CYCLE_ENABLED)?.isChecked = enabled
        listOf(GBPrefs.CYCLE_LAST_START, KEY_LENGTHS, KEY_ARRIVED, KEY_TODAY_DAY).forEach { key ->
            findPreference<Preference>(key)?.isVisible = enabled
        }

        findPreference<Preference>(GBPrefs.CYCLE_LAST_START)?.summary =
            CycleContextStore.lastStart()?.toString() ?: getString(R.string.not_set)
        findPreference<Preference>(KEY_LENGTHS)?.summary = getString(
            R.string.cycle_lengths_summary,
            CycleContextStore.cycleLengthDays(),
            CycleContextStore.periodDays()
        )
        val config = CycleContextStore.config()
        val todayPreference = findPreference<Preference>(KEY_TODAY_DAY)
        if (config == null) {
            todayPreference?.title = getString(R.string.cycle_today_day_unknown)
            todayPreference?.summary = getString(R.string.cycle_set_start_first)
        } else {
            val status = CycleContext.calculate(config, LocalDate.now())
            todayPreference?.title = getString(R.string.cycle_today_day_title, status.cycleDay)
            todayPreference?.summary = getString(R.string.cycle_today_day_summary)
        }
    }

    companion object {
        private const val KEY_LENGTHS = "cycle_lengths"
        private const val KEY_ARRIVED = "cycle_arrived"
        private const val KEY_TODAY_DAY = "cycle_today_day"
    }
}
