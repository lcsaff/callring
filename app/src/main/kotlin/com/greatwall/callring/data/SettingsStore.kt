package com.greatwall.callring.data

import android.database.sqlite.SQLiteDatabase
import com.greatwall.callring.application.api.*
import com.greatwall.callring.domain.BackendState
import com.greatwall.callring.domain.StatisticsInterval
import org.json.JSONObject

internal class SettingsStore(private val db: SQLiteDatabase) {
    private val settings = TableSnapshot(db, "settings", "key")
    private val intervalIds = mutableListOf<Long>()
    private val intervals = TableSnapshot(db, "statistics_intervals", "id")
    private var initialQuiet = QuietSettings(QuietMode.OFF, false, emptyMap())
    private var initialLastQuietMode = QuietMode.NON_WORK
    private var initialScore = ScoreSettings(5, 2, 10, 30)
    private var initialPosition: Long? = null
    private var initialOverlay = false
    private var initialLeaveVersion = 0L

    private fun setting(key: String, fallback: String): String =
        settings.previous(key)?.getAsString("value") ?: fallback

    fun load(state: BackendState) {
        state.myPositionId = settings.previous("my_position")?.getAsString("value")?.toLong()
        state.quiet =
            QuietSettings(
                QuietMode.valueOf(setting("quiet_mode", "OFF")),
                setting("all_numbers", "0") == "1",
                WorkConfigCodec.weekly(JSONObject(setting("quiet_windows", "{}"))),
            )
        state.lastQuietMode = if (state.quiet.mode != QuietMode.OFF) state.quiet.mode else {
            runCatching { QuietMode.valueOf(setting("last_quiet_mode", QuietMode.NON_WORK.name)) }
                .getOrDefault(QuietMode.NON_WORK).takeUnless { it == QuietMode.OFF } ?: QuietMode.NON_WORK
        }
        state.score =
            ScoreSettings(
                setting("off_weight", "5").toInt(),
                setting("repeat_weight", "2").toInt(),
                setting("repeat_minutes", "10").toInt(),
                setting("score_days", "30").toInt(),
            )
        state.overlayEnabled = setting("overlay", "0") == "1"
        state.leaveVersion = setting("leave_revision", "0").toLong()
        db.query("statistics_intervals", null, null, null, null, null, "id").use { cursor ->
            while (cursor.moveToNext()) {
                intervalIds.add(cursor.long("id"))
                state.statisticsIntervals.add(
                    StatisticsInterval(cursor.long("start"), cursor.optionalLong("end"))
                )
            }
        }
        initialQuiet = state.quiet
        initialLastQuietMode = state.lastQuietMode
        initialScore = state.score
        initialPosition = state.myPositionId
        initialOverlay = state.overlayEnabled
        initialLeaveVersion = state.leaveVersion
    }

    fun save(state: BackendState) {
        if (state.myPositionId != initialPosition)
            settings.saveReplacing(
                "my_position",
                values(
                    "key" to "my_position",
                    "value" to state.myPositionId?.toString(),
                    "position_id" to state.myPositionId,
                ),
            )
        if (state.quiet.mode != initialQuiet.mode) {
            put("quiet_mode", state.quiet.mode.name)
        }
        val rememberedMode = state.quiet.mode.takeUnless { it == QuietMode.OFF } ?: state.lastQuietMode
        if (rememberedMode != initialLastQuietMode || state.quiet.mode != initialQuiet.mode)
            put("last_quiet_mode", rememberedMode.name)
        if (state.quiet.allNumbers != initialQuiet.allNumbers)
            put("all_numbers", if (state.quiet.allNumbers) "1" else "0")
        if (state.quiet.weeklyWindows != initialQuiet.weeklyWindows)
            put("quiet_windows", WorkConfigCodec.weeklyJson(state.quiet.weeklyWindows).toString())
        if (state.overlayEnabled != initialOverlay)
            put("overlay", if (state.overlayEnabled) "1" else "0")
        if (state.leaveVersion != initialLeaveVersion)
            put("leave_revision", state.leaveVersion.toString())
        if (state.score.offHoursWeight != initialScore.offHoursWeight)
            put("off_weight", state.score.offHoursWeight.toString())
        if (state.score.repeatWeight != initialScore.repeatWeight)
            put("repeat_weight", state.score.repeatWeight.toString())
        if (state.score.repeatMinutes != initialScore.repeatMinutes)
            put("repeat_minutes", state.score.repeatMinutes.toString())
        if (state.score.observationDays != initialScore.observationDays)
            put("score_days", state.score.observationDays.toString())
        var next = db.nextId("statistics_intervals")
        val kept = mutableSetOf<Long>()
        state.statisticsIntervals.forEachIndexed { index, interval ->
            val id = intervalIds.getOrNull(index) ?: next++
            kept.add(id)
            intervals.save(
                id,
                values("id" to id, "start" to interval.startMillis, "end" to interval.endMillis),
            )
        }
        intervals.deleteMissing(kept)
    }

    private fun put(key: String, value: String) {
        settings.saveReplacing(key, values("key" to key, "value" to value))
    }
}
