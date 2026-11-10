package app.healthquery.sync

import android.content.Context
import android.content.SharedPreferences
class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "life_dashboard_prefs"

        private const val KEY_HEALTH_SYNC_INTERVAL_MINUTES = "health_sync_interval_minutes"
    }

    fun getHealthSyncIntervalMinutes(): Int {
        val saved = prefs.getInt(KEY_HEALTH_SYNC_INTERVAL_MINUTES, SyncFrequencies.default.minutes)
        return SyncFrequencies.normalizedMinutes(saved)
    }

    fun setHealthSyncIntervalMinutes(minutes: Int) {
        require(SyncFrequencies.optionFor(minutes) != null) { "Unsupported sync frequency: $minutes minutes" }
        prefs.edit().putInt(KEY_HEALTH_SYNC_INTERVAL_MINUTES, minutes).apply()
    }

}
