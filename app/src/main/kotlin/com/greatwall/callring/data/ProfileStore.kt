package com.greatwall.callring.data

import android.database.sqlite.SQLiteDatabase
import com.greatwall.callring.application.ports.*

internal class ProfileStore(private val db: SQLiteDatabase) {
    private val positions = TableSnapshot(db, "positions", "id")
    private val profiles = TableSnapshot(db, "callers", "number")

    fun load(state: BackendState) {
        db.query("positions", null, null, null, null, null, "id").use { cursor ->
            while (cursor.moveToNext()) {
                val row =
                    PositionRecord(
                        cursor.long("id"),
                        cursor.text("name"),
                        cursor.long("level").toInt(),
                        cursor.flag("active"),
                    )
                state.positions[row.id] = row
            }
        }
        db.query("callers", null, null, null, null, null, "number").use { cursor ->
            while (cursor.moveToNext()) {
                val row =
                    ProfileRecord(
                        cursor.text("number"),
                        cursor.text("name"),
                        cursor.text("department"),
                        cursor.optionalLong("position_id"),
                        cursor.flag("whitelist"),
                    )
                state.profiles[row.number] = row
            }
        }
        state.nextPositionId = db.nextId("positions")
    }

    fun saveReferences(state: BackendState) {
        state.positions.values.forEach {
            positions.save(
                it.id,
                values("id" to it.id, "name" to it.name, "level" to it.level, "active" to it.active),
            )
        }
        state.profiles.values.forEach {
            profiles.save(
                it.number,
                values(
                    "number" to it.number,
                    "name" to it.name,
                    "department" to it.department,
                    "position_id" to it.positionId,
                    "whitelist" to it.whitelisted,
                ),
            )
        }
    }

    fun deleteUnreferenced(state: BackendState) {
        profiles.deleteMissing(state.profiles.keys)
        positions.deleteMissing(state.positions.keys)
    }
}
