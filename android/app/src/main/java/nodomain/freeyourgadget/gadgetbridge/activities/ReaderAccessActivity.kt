/*  Copyright (C) 2026 Gadgetbridge contributors

    This file is part of Gadgetbridge and is licensed under the GNU Affero
    General Public License, version 3 or later. */
package nodomain.freeyourgadget.gadgetbridge.activities

import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.util.healthquery.HealthQueryConnectionStore
import nodomain.freeyourgadget.gadgetbridge.util.healthquery.HealthQueryTransport
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

class ReaderAccessActivity : AppCompatActivity() {
    private data class DetailRow(val label: String, val value: String = "", val section: Boolean = false)

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.reader_access_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
        }
        scroll.addView(content)
        setContentView(scroll)
        val cached = getSharedPreferences("reader_access_cache", MODE_PRIVATE).getString("events", null)
        if (cached != null) runCatching { render(org.json.JSONArray(cached)) }
        else showMessage(getString(R.string.reader_access_loading))
        load()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }

    private fun load() = executor.execute {
        val connection = HealthQueryConnectionStore(this).load()
        val result = runCatching {
            if (connection == null) error("not_paired")
            HealthQueryTransport(this).readerAccess(connection)
        }
        runOnUiThread {
            val events = result.getOrNull()
            when {
                events == null && content.childCount == 0 -> showMessage(getString(R.string.reader_access_failed))
                events != null -> {
                    getSharedPreferences("reader_access_cache", MODE_PRIVATE).edit().putString("events", events.toString()).apply()
                    render(events)
                }
            }
        }
    }

    private fun render(events: org.json.JSONArray) {
        content.removeAllViews()
        if (events.length() == 0) { showMessage(getString(R.string.reader_access_empty)); return }
        val grouped = linkedMapOf<LocalDate, LinkedHashMap<String, LinkedHashMap<String, MutableList<JSONObject>>>>()
        for (index in 0 until events.length()) {
            val event = events.optJSONObject(index) ?: continue
            val reader = event.optString("reader_name", getString(R.string.reader_access_unknown))
            val instant = parseInstant(event.optString("occurred_at")) ?: Instant.EPOCH
            val local = instant.atZone(ZoneId.systemDefault())
            val day = local.toLocalDate()
            val minuteKey = local.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.CHINA))
            grouped.getOrPut(day) { linkedMapOf() }
                .getOrPut(reader) { linkedMapOf() }
                .getOrPut(minuteKey) { mutableListOf() }
                .add(event)
        }
        grouped.forEach { (date, readers) -> addDayGroup(date, readers) }
    }

    private fun addDayGroup(
        date: LocalDate,
        readers: LinkedHashMap<String, LinkedHashMap<String, MutableList<JSONObject>>>
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = ContextCompat.getDrawable(this@ReaderAccessActivity, R.drawable.readme_summary_card)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
            visibility = if (date == LocalDate.now()) View.VISIBLE else View.GONE
        }
        val readerSummary = readers.entries.joinToString("\n") { (reader, groups) -> "$reader：${groups.size}次" }
        card.addView(foldHeader(date.format(DateTimeFormatter.ofPattern("yyyy年M月d日")), readerSummary, body, 18f))
        readers.forEach { (reader, minuteGroups) -> addReaderGroup(body, reader, minuteGroups) }
        card.addView(body)
        content.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        })
    }

    private fun addReaderGroup(parent: LinearLayout, reader: String, minuteGroups: LinkedHashMap<String, MutableList<JSONObject>>) {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), 0, dp(10))
        }
        section.addView(TextView(this).apply {
            text = "$reader · ${minuteGroups.size}次"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@ReaderAccessActivity, R.color.heartbeat_charcoal))
            setPadding(dp(4), dp(4), 0, dp(8))
        })
        val entries = minuteGroups.entries.toList()
        entries.take(RECENT_QUERY_LIMIT).forEach { (_, events) -> addQueryGroup(section, events) }
        if (entries.size > RECENT_QUERY_LIMIT) {
            val older = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
            }
            entries.drop(RECENT_QUERY_LIMIT).forEach { (_, events) -> addQueryGroup(older, events) }
            section.addView(foldHeader("查看更早的 ${entries.size - RECENT_QUERY_LIMIT} 次", "", older, 13f))
            section.addView(older)
        }
        parent.addView(section)
    }

    private fun addQueryGroup(parent: LinearLayout, events: List<JSONObject>) {
        val categories = events.flatMap { eventCategories(it) }.distinct()
        val detailRows = buildDetailRows(events)
        val detail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(8), dp(12))
            visibility = android.view.View.GONE
        }
        detailRows.forEach { addDetailRow(detail, it) }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(12), dp(2))
        }
        row.addView(foldHeader(
            formatClockOnly(events.first().optString("occurred_at")),
            "查看了${categories.joinToString("、").ifEmpty { getString(R.string.reader_access_general) }}",
            detail,
            14f
        ))
        if (detailRows.isNotEmpty()) row.addView(detail)
        parent.addView(row)
    }

    private fun foldHeader(title: String, subtitle: String, target: View, titleSize: Float): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val text = TextView(this).apply {
            this.text = if (subtitle.isBlank()) title else "$title\n$subtitle"
            textSize = titleSize
            setTypeface(typeface, if (titleSize >= 18f) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(ContextCompat.getColor(this@ReaderAccessActivity, R.color.heartbeat_charcoal))
            setLineSpacing(0f, 1.12f)
        }
        val arrow = ImageView(this).apply {
            setImageResource(if (target.visibility == View.VISIBLE) R.drawable.ic_readme_chevron_down else R.drawable.ic_readme_chevron_right)
            setColorFilter(ContextCompat.getColor(this@ReaderAccessActivity, R.color.secondarytext))
            setPadding(dp(9), dp(9), dp(9), dp(9))
        }
        row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(arrow, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginStart = dp(10) })
        row.setOnClickListener {
            val opening = target.visibility != View.VISIBLE
            target.visibility = if (opening) View.VISIBLE else View.GONE
            arrow.setImageResource(if (opening) R.drawable.ic_readme_chevron_down else R.drawable.ic_readme_chevron_right)
        }
        return row
    }

    private fun buildDetailRows(events: List<JSONObject>): List<DetailRow> {
        val rows = mutableListOf<DetailRow>()
        val sections = mutableSetOf<String>()
        val rawTypes = events.flatMap { event ->
            val array = event.optJSONArray("data_types") ?: return@flatMap emptyList()
            (0 until array.length()).map { array.optString(it) }
        }.toSet()
        fun section(name: String) {
            if (sections.add(name)) rows.add(DetailRow(name, section = true))
        }

        events.forEach { event ->
            if (eventCategories(event).isEmpty()) return@forEach
            val summary = event.optJSONObject("result_summary") ?: return@forEach
            when (val kind = summary.optString("kind")) {
                "sleep" -> {
                    val sessions = summary.optJSONArray("sessions")
                    if (sessions != null && sections.add("睡眠")) {
                        rows.add(DetailRow("睡眠", section = true))
                        val session = sessions.optJSONObject(0)
                        if (session != null) {
                            rows.add(DetailRow("睡眠时段", formatSleepRange(session.optString("start"), session.optString("end"))))
                            val minutes = session.optInt("minutes", -1)
                            if (minutes >= 0) rows.add(DetailRow("总时长", formatDuration(minutes)))
                        }
                    }
                    val stageTotals = summary.optJSONArray("stage_totals")
                    if (stageTotals != null && stageTotals.length() > 0 && sections.add("睡眠阶段")) {
                        rows.add(DetailRow("睡眠阶段", section = true))
                        val byStage = (0 until stageTotals.length()).mapNotNull { stageTotals.optJSONObject(it) }
                            .associateBy { it.optString("stage") }
                        listOf("deep", "light", "rem", "awake").forEach { key ->
                            val stage = byStage[key] ?: return@forEach
                            val minutes = (stage.optInt("seconds", 0) / 60.0).toInt()
                            rows.add(DetailRow(stageLabel(key), "${formatDuration(minutes)}　${formatNumber(stage.optDouble("percent"))}%"))
                        }
                    }
                }
                "heart_rate", "metric_points" -> if (summary.optString("unit") == "bpm" || kind == "heart_rate") {
                    section("心率")
                    if (summary.has("minimum")) {
                        rows.add(DetailRow("范围", "${formatNumber(summary.optDouble("minimum"))}–${formatNumber(summary.optDouble("maximum"))}次/分"))
                        rows.add(DetailRow("平均", "${formatNumber(summary.optDouble("average"))}次/分"))
                        rows.add(DetailRow("记录", "${summary.optInt("count")}条"))
                    }
                }
                "steps", "metric_intervals" -> when {
                    kind == "steps" || summary.optString("unit") == "count" -> {
                        section("步数")
                        if (summary.has("minimum")) rows.add(DetailRow("合计", "${formatNumber(summary.optDouble("maximum"))}步"))
                    }
                    kind == "screen_time" -> addScreenTimeRows(rows, sections, summary)
                }
                "screen_time" -> addScreenTimeRows(rows, sections, summary)
                "oxygen_saturation" -> {
                    section("血氧")
                    if (summary.has("minimum")) rows.add(DetailRow("范围", "${formatNumber(summary.optDouble("minimum"))}–${formatNumber(summary.optDouble("maximum"))}%"))
                }
                "daily" -> {
                    val values = summary.optJSONArray("values") ?: return@forEach
                    for (i in 0 until values.length()) {
                        val value = values.optJSONObject(i) ?: continue
                        if (!rawTypes.contains("metric_intervals") && value.has("steps") && !value.isNull("steps")) {
                            section("步数")
                            rows.add(DetailRow("合计", "${value.optInt("steps")}步"))
                        }
                        if (rawTypes.none { it == "sleep_sessions" || it == "sleep_stages" } && value.has("sleep_minutes") && !value.isNull("sleep_minutes")) {
                            section("睡眠")
                            rows.add(DetailRow("总时长", formatDuration(value.optInt("sleep_minutes"))))
                        }
                    }
                }
            }
        }
        return rows.distinct()
    }

    private fun addScreenTimeRows(rows: MutableList<DetailRow>, sections: MutableSet<String>, summary: JSONObject) {
        if (!sections.add("屏幕使用")) return
        rows.add(DetailRow("屏幕使用", section = true))
        val values = summary.optJSONArray("values") ?: return
        rows.add(DetailRow("本次返回", "${values.length()}个应用"))
        for (i in 0 until values.length()) {
            val value = values.optJSONObject(i) ?: continue
            rows.add(DetailRow(value.optString("app", "应用"), "${formatNumber(value.optDouble("minutes"))}分钟"))
        }
    }

    private fun addDetailRow(parent: LinearLayout, item: DetailRow) {
        if (item.section) {
            parent.addView(TextView(this).apply {
                text = item.label
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(this@ReaderAccessActivity, R.color.accent))
                setPadding(0, if (parent.childCount == 0) dp(2) else dp(14), 0, dp(6))
            })
            return
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, dp(5))
        }
        row.addView(TextView(this).apply {
            text = item.label
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@ReaderAccessActivity, R.color.secondarytext))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply {
            text = item.value
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            gravity = android.view.Gravity.END
            setTextColor(ContextCompat.getColor(this@ReaderAccessActivity, R.color.heartbeat_charcoal))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.35f))
        parent.addView(row)
    }

    private fun eventCategories(event: JSONObject): List<String> = buildList {
        val dataTypes = event.optJSONArray("data_types")
        if (dataTypes != null) for (i in 0 until dataTypes.length()) {
            val type = dataTypes.optString(i)
            if (type == "device_sync_batches") continue
            val metric = event.optJSONObject("result_summary")?.optString("kind").orEmpty()
            if (type == "daily_summaries") addAll(dailyCategories(event.optJSONObject("result_summary")))
            else add(if (type == "metric_intervals" && metric == "screen_time") "屏幕使用" else label(type))
        }
    }

    private fun dailyCategories(summary: JSONObject?): List<String> {
        val values = summary?.optJSONArray("values") ?: return listOf("每日汇总")
        var hasSteps = false
        var hasSleep = false
        for (i in 0 until values.length()) {
            val value = values.optJSONObject(i) ?: continue
            hasSteps = hasSteps || (value.has("steps") && !value.isNull("steps"))
            hasSleep = hasSleep || (value.has("sleep_minutes") && !value.isNull("sleep_minutes"))
        }
        return buildList {
            if (hasSteps) add("步数")
            if (hasSleep) add("睡眠")
            if (!hasSteps && !hasSleep) add("每日汇总")
        }
    }

    private fun parseInstant(raw: String): Instant? = runCatching { Instant.parse(raw) }.getOrNull()

    private fun formatClockOnly(raw: String): String = parseInstant(raw)?.let {
        DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(it)
    } ?: raw

    private fun formatTime(raw: String): String = runCatching {
        DateTimeFormatter.ofPattern("MM月dd日 HH:mm")
            .withZone(ZoneId.systemDefault()).format(Instant.parse(raw))
    }.getOrDefault(raw)

    private fun label(value: String): String = when (value) {
        "metric_points" -> "心率"
        "metric_intervals" -> "步数"
        "sleep_sessions", "sleep_stages" -> "睡眠"
        "workouts" -> "运动"
        "daily_summaries" -> "每日汇总"
        "health_connect_records" -> "健康记录"
        else -> value
    }

    private fun friendlyTool(value: String): String = when (value) {
        "get_data_coverage" -> "查看数据时间范围"
        "query_records" -> "查看详细记录"
        "aggregate" -> "统计数据"
        "summarize_trends" -> "查看变化趋势"
        "list_data_types", "describe_data_type", "list_health_connect_record_types" -> "查看可用数据"
        "list_workouts", "get_workout" -> "查看运动记录"
        "get_session_data" -> "查看睡眠记录"
        else -> "读取健康数据"
    }

    private fun formatResultSummary(summary: JSONObject?): String {
        if (summary == null) return ""
        return when (summary.optString("kind")) {
            "sleep" -> {
                val sessions = summary.optJSONArray("sessions") ?: return ""
                buildList {
                    for (i in 0 until sessions.length()) {
                        val session = sessions.optJSONObject(i) ?: continue
                        val minutes = session.optInt("minutes", -1)
                        val duration = if (minutes >= 0) "，共${formatDuration(minutes)}" else ""
                        add("${formatSleepRange(session.optString("start"), session.optString("end"))}$duration")
                    }
                    val stageTotals = summary.optJSONArray("stage_totals")
                    if (stageTotals != null && stageTotals.length() > 0) {
                        val byStage = (0 until stageTotals.length()).mapNotNull { stageTotals.optJSONObject(it) }
                            .associateBy { it.optString("stage") }
                        add("睡眠阶段")
                        listOf("deep", "light", "rem", "awake").forEach { key ->
                            val stage = byStage[key] ?: return@forEach
                            val seconds = stage.optInt("seconds", 0)
                            val percent = stage.optDouble("percent", 0.0)
                            add("${stageLabel(key)}　${formatDuration((seconds / 60.0).toInt())}　${formatNumber(percent)}%")
                        }
                    }
                }.joinToString("\n")
            }
            "daily" -> {
                val values = summary.optJSONArray("values") ?: return ""
                buildList {
                    for (i in 0 until values.length()) {
                        val value = values.optJSONObject(i) ?: continue
                        if (value.has("steps") && !value.isNull("steps")) add("步数：${value.optInt("steps")}步")
                        if (value.has("sleep_minutes") && !value.isNull("sleep_minutes")) {
                            val minutes = value.optInt("sleep_minutes")
                            add("睡眠：${minutes / 60}小时${minutes % 60}分")
                        }
                    }
                }.joinToString("\n")
            }
            "heart_rate" -> metricLine(summary, "心率", "次/分")
            "oxygen_saturation" -> metricLine(summary, "血氧", "%")
            "steps" -> metricLine(summary, "步数", "步")
            "screen_time" -> screenTimeLines(summary)
            "metric_intervals" -> if (summary.optString("unit") == "count") metricLine(summary, "步数", "步") else genericMetricLine(summary)
            "metric_points" -> if (summary.optString("unit") == "bpm") metricLine(summary, "心率", "次/分") else genericMetricLine(summary)
            else -> {
                val values = summary.optJSONArray("values") ?: return ""
                buildList {
                    for (i in 0 until values.length()) {
                        val value = values.optJSONObject(i) ?: continue
                        val amount = when {
                            value.has("value") -> value.optDouble("value")
                            value.has("total") -> value.optDouble("total")
                            else -> continue
                        }
                        add(formatNumber(amount))
                    }
                }.joinToString("\n")
            }
        }
    }

    private fun metricLine(summary: JSONObject, name: String, fallbackUnit: String): String {
        val count = summary.optInt("count", 0)
        if (!summary.has("minimum")) return "$name：没有返回记录"
        val storedUnit = summary.optString("unit")
        val unit = when {
            name == "步数" && storedUnit == "count" -> "步"
            name == "心率" && storedUnit == "bpm" -> "次/分"
            else -> storedUnit.ifBlank { fallbackUnit }
        }
        val value = if (summary.optDouble("minimum") == summary.optDouble("maximum")) {
            "${formatNumber(summary.optDouble("minimum"))}$unit"
        } else "${formatNumber(summary.optDouble("minimum"))}–${formatNumber(summary.optDouble("maximum"))}$unit，平均${formatNumber(summary.optDouble("average"))}$unit，共${count}条"
        return "$name：$value"
    }

    private fun genericMetricLine(summary: JSONObject): String = metricLine(summary, "测量值", summary.optString("unit"))

    private fun screenTimeLines(summary: JSONObject): String {
        val values = summary.optJSONArray("values") ?: return genericMetricLine(summary)
        val lines = buildList {
            add("本次返回 ${values.length()} 个应用，合计 ${formatNumber(summary.optDouble("total_minutes"))} 分钟")
            for (i in 0 until values.length()) {
                val value = values.optJSONObject(i) ?: continue
                add("${value.optString("app", "应用")} ${formatNumber(value.optDouble("minutes"))} 分钟")
            }
        }
        return lines.joinToString("\n")
    }

    private fun stageLabel(value: String): String = when (value.lowercase()) {
        "deep" -> "深睡"
        "light" -> "浅睡"
        "rem" -> "快速眼动"
        "awake" -> "清醒"
        else -> "其他"
    }

    private fun formatClock(raw: String): String = runCatching {
        java.time.OffsetDateTime.parse(raw)
            .atZoneSameInstant(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("HH:mm"))
    }.getOrDefault(raw)

    private fun formatSleepRange(start: String, end: String): String {
        val startText = formatClock(start)
        val endText = formatClock(end)
        return "$startText–$endText"
    }

    private fun formatDuration(minutes: Int): String = when {
        minutes < 60 -> "${minutes}分"
        minutes % 60 == 0 -> "${minutes / 60}小时"
        else -> "${minutes / 60}小时${minutes % 60}分"
    }

    private fun formatNumber(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(value)

    private fun showMessage(message: String) { content.addView(TextView(this).apply { text = message; textSize = 16f }) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val RECENT_QUERY_LIMIT = 5
    }
}
