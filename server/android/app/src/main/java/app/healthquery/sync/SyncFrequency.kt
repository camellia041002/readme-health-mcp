package app.healthquery.sync

data class SyncFrequency(
    val minutes: Int,
    val label: String,
    val description: String,
)

object SyncFrequencies {
    val options = listOf(
        SyncFrequency(24 * 60, "每天一次", "及时更新，适合日常使用"),
        SyncFrequency(3 * 24 * 60, "每 3 天一次", "更省电、更省流量"),
        SyncFrequency(7 * 24 * 60, "每周一次", "最轻量的自动同步"),
    )

    val default = options.first { it.minutes == 24 * 60 }

    fun optionFor(minutes: Int): SyncFrequency? = options.firstOrNull { it.minutes == minutes }

    fun normalizedMinutes(minutes: Int): Int = optionFor(minutes)?.minutes ?: default.minutes

    fun labelFor(minutes: Int): String = optionFor(minutes)?.label ?: default.label
}
