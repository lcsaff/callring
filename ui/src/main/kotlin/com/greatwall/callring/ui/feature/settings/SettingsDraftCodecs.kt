package com.greatwall.callring.ui.feature.settings

import com.greatwall.callring.application.api.*
import com.greatwall.callring.ui.state.DraftCodec
import java.io.*
import java.util.Base64

/** A transient saved-state representation, independent of the backend's persistence format. */
object SettingsDraftCodecs {
    val appearance = binaryCodec<AppearanceSettings>(
        write = { value ->
            writeUTF(value.selection.name); writeUTF(value.presetId)
            writeBoolean(value.customSeedRgb != null); value.customSeedRgb?.let(::writeInt)
            writeUTF(value.colorMode.name)
        },
        read = { AppearanceSettings(ThemeSelection.valueOf(readUTF()), readUTF(), if (readBoolean()) readInt() else null, ColorMode.valueOf(readUTF())) }
    )
    val score = binaryCodec<ScoreSettings>(
        write = { value -> writeInt(value.offHoursWeight); writeInt(value.repeatWeight); writeInt(value.repeatMinutes); writeInt(value.observationDays) },
        read = { ScoreSettings(readInt(), readInt(), readInt(), readInt()) }
    )
    val quiet = binaryCodec<QuietSettings>(
        write = { value -> writeUTF(value.mode.name); writeBoolean(value.allNumbers); writeWeekly(value.weeklyWindows) },
        read = { QuietSettings(QuietMode.valueOf(readUTF()), readBoolean(), readWeekly()) }
    )
    val work = binaryCodec<WorkScheduleSettings>(
        write = { value -> writeUTF(value.zoneId); writeWeekly(value.weekly); writeWindows(value.makeup); writeLong(value.configVersion); writeLong(value.effectiveFromEpochMillis) },
        read = { WorkScheduleSettings(readUTF(), readWeekly(), readWindows(), readLong(), readLong()) }
    )

    private fun DataOutputStream.writeWeekly(value: Map<Int, List<TimeWindow>>) {
        writeInt(value.size)
        value.forEach { (day, windows) -> writeInt(day); writeWindows(windows) }
    }
    private fun DataInputStream.readWeekly(): Map<Int, List<TimeWindow>> = buildMap {
        repeat(readInt().also { require(it in 0..7) }) { put(readInt(), readWindows()) }
    }
    private fun DataOutputStream.writeWindows(value: List<TimeWindow>) {
        writeInt(value.size)
        value.forEach { writeInt(it.startMinute); writeInt(it.endMinute) }
    }
    private fun DataInputStream.readWindows(): List<TimeWindow> =
        List(readInt().also { require(it in 0..1440) }) { TimeWindow(readInt(), readInt()) }

    private fun <T> binaryCodec(write: DataOutputStream.(T) -> Unit, read: DataInputStream.() -> T) = object : DraftCodec<T> {
        override fun encode(value: T): String = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { write.invoke(it, value) }
            Base64.getEncoder().encodeToString(bytes.toByteArray())
        }
        override fun decode(value: String): T? = try {
            DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(value))).use { input ->
                read.invoke(input).also { require(input.available() == 0) }
            }
        } catch (_: IllegalArgumentException) { null } catch (_: IOException) { null }
    }
}
