package com.greatwall.callring.data

import android.database.sqlite.SQLiteDatabase
import com.greatwall.callring.contracts.common.*
import com.greatwall.callring.application.ports.BackendFailure

/** Runs in the same read transaction, before allocating any complete business state. */
fun checkSnapshotSize(db: SQLiteDatabase) {
    var rows = 0L
    var bytes = 0L
    val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
    for (table in tables) {
        require(table.matches(Regex("[A-Za-z_][A-Za-z_0-9]*")))
        val columns = db.rawQuery("PRAGMA table_info(\"$table\")", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(1)) } }
        val sum = columns.joinToString("+") { name -> require(name.matches(Regex("[A-Za-z_][A-Za-z_0-9]*"))); "COALESCE(length(CAST(\"$name\" AS BLOB)),0)" }
        db.rawQuery("SELECT count(*),COALESCE(sum($sum),0) FROM \"$table\"", null).use { c ->
            check(c.moveToFirst()); rows = Math.addExact(rows, c.getLong(0)); bytes = Math.addExact(bytes, c.getLong(1))
        }
        if (rows > 20000 || bytes >= 8L * 1024 * 1024) throw BackendFailure(ApiError(ErrorCode.SYSTEM_RESTRICTED, "ai.context_budget_exceeded"))
    }
}
