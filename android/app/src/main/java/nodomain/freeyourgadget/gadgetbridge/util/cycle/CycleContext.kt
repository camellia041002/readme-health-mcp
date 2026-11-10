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
package nodomain.freeyourgadget.gadgetbridge.util.cycle

import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.util.GBPrefs
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

data class CycleConfig(
    val lastStart: LocalDate,
    val cycleLengthDays: Int,
    val periodDays: Int,
    val lastConfirmed: LocalDate?
)

data class CycleStatus(
    val cycleStart: LocalDate,
    val cycleDay: Int,
    val periodDay: Int?,
    val daysUntilPeriod: Int?,
    val confirmed: Boolean
)

/** Pure date arithmetic shared by the settings screen and its unit tests. */
object CycleContext {
    @JvmStatic
    fun rollForward(config: CycleConfig, today: LocalDate): CycleConfig {
        if (today.isBefore(config.lastStart)) return config
        val elapsedDays = ChronoUnit.DAYS.between(config.lastStart, today)
        val skippedCycles = elapsedDays / config.cycleLengthDays
        if (skippedCycles <= 0) return config
        return config.copy(lastStart = config.lastStart.plusDays(skippedCycles * config.cycleLengthDays.toLong()))
    }

    @JvmStatic
    fun calculate(config: CycleConfig, date: LocalDate): CycleStatus {
        val elapsedDays = ChronoUnit.DAYS.between(config.lastStart, date)
        val offset = Math.floorMod(elapsedDays, config.cycleLengthDays.toLong()).toInt()
        val cycleStart = date.minusDays(offset.toLong())
        val confirmed = config.lastConfirmed?.let {
            !it.isBefore(cycleStart) && it.isBefore(cycleStart.plusDays(config.cycleLengthDays.toLong()))
        } ?: false
        val cycleDay = offset + 1
        val periodDay = cycleDay.takeIf { it <= config.periodDays }
        return CycleStatus(
            cycleStart = cycleStart,
            cycleDay = cycleDay,
            periodDay = periodDay,
            daysUntilPeriod = if (periodDay == null) {
                (config.cycleLengthDays - offset).takeIf { it <= 3 }
            } else {
                null
            },
            confirmed = confirmed
        )
    }
}

/** Persistence boundary: disabling removes every cycle value instead of retaining hidden history. */
object CycleContextStore {
    const val DEFAULT_CYCLE_LENGTH_DAYS = 28
    const val DEFAULT_PERIOD_DAYS = 5

    @JvmStatic
    fun isEnabled(): Boolean = GBApplication.getPrefs().getBoolean(GBPrefs.CYCLE_ENABLED, false)

    fun config(): CycleConfig? {
        if (!isEnabled()) return null
        val prefs = GBApplication.getPrefs()
        val lastStart = parseDate(prefs.getString(GBPrefs.CYCLE_LAST_START, null)) ?: return null
        val cycleLength = prefs.getInt(GBPrefs.CYCLE_LENGTH_DAYS, DEFAULT_CYCLE_LENGTH_DAYS)
        val periodDays = prefs.getInt(GBPrefs.CYCLE_PERIOD_DAYS, DEFAULT_PERIOD_DAYS)
        if (cycleLength <= 0 || periodDays <= 0 || periodDays > cycleLength) return null
        return CycleConfig(
            lastStart,
            cycleLength,
            periodDays,
            parseDate(prefs.getString(GBPrefs.CYCLE_LAST_CONFIRMED, null))
        )
    }

    fun cycleLengthDays(): Int = GBApplication.getPrefs()
        .getInt(GBPrefs.CYCLE_LENGTH_DAYS, DEFAULT_CYCLE_LENGTH_DAYS)

    fun periodDays(): Int = GBApplication.getPrefs()
        .getInt(GBPrefs.CYCLE_PERIOD_DAYS, DEFAULT_PERIOD_DAYS)

    fun lastStart(): LocalDate? = parseDate(
        GBApplication.getPrefs().getString(GBPrefs.CYCLE_LAST_START, null)
    )

    fun enable() {
        GBApplication.getPrefs().preferences.edit()
            .putBoolean(GBPrefs.CYCLE_ENABLED, true)
            .apply()
    }

    fun saveCalibration(start: LocalDate, confirmedOn: LocalDate) {
        GBApplication.getPrefs().preferences.edit()
            .putBoolean(GBPrefs.CYCLE_ENABLED, true)
            .putString(GBPrefs.CYCLE_LAST_START, start.toString())
            .putString(GBPrefs.CYCLE_LAST_CONFIRMED, confirmedOn.toString())
            .putInt(GBPrefs.CYCLE_LENGTH_DAYS, cycleLengthDays())
            .putInt(GBPrefs.CYCLE_PERIOD_DAYS, periodDays())
            .apply()
    }

    fun saveLengths(cycleLength: Int, periodDays: Int) {
        GBApplication.getPrefs().preferences.edit()
            .putInt(GBPrefs.CYCLE_LENGTH_DAYS, cycleLength)
            .putInt(GBPrefs.CYCLE_PERIOD_DAYS, periodDays)
            .apply()
    }

    fun rollForwardIfNeeded(today: LocalDate): Boolean {
        val current = config() ?: return false
        val rolled = CycleContext.rollForward(current, today)
        if (rolled.lastStart == current.lastStart) return false
        GBApplication.getPrefs().preferences.edit()
            .putString(GBPrefs.CYCLE_LAST_START, rolled.lastStart.toString())
            .apply()
        return true
    }

    /**
     * Wipes every local cycle value immediately — the user asked for it to be gone, so it goes
     * even if the server copy cannot be reached right now. [GBPrefs.CYCLE_PENDING_CLEAR] is raised
     * in the same edit so the unfinished server-side deletion is not forgotten; it holds no cycle
     * data itself. [pendingClear] stays true until the server confirms the removal.
     */
    fun clear() {
        GBApplication.getPrefs().preferences.edit()
            .remove(GBPrefs.CYCLE_ENABLED)
            .remove(GBPrefs.CYCLE_LAST_START)
            .remove(GBPrefs.CYCLE_LENGTH_DAYS)
            .remove(GBPrefs.CYCLE_PERIOD_DAYS)
            .remove(GBPrefs.CYCLE_LAST_CONFIRMED)
            .putBoolean(GBPrefs.CYCLE_PENDING_CLEAR, true)
            .apply()
    }

    @JvmStatic
    fun pendingClear(): Boolean =
        GBApplication.getPrefs().getBoolean(GBPrefs.CYCLE_PENDING_CLEAR, false)

    /** Called only after the server has confirmed there is nothing left to delete. */
    @JvmStatic
    fun resolvePendingClear() {
        GBApplication.getPrefs().preferences.edit()
            .remove(GBPrefs.CYCLE_PENDING_CLEAR)
            .apply()
    }

    private fun parseDate(raw: String?): LocalDate? = try {
        raw?.let(LocalDate::parse)
    } catch (_: DateTimeParseException) {
        null
    }
}
