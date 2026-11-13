package com.greatwall.callring

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.test.InstrumentationTestCase
import com.greatwall.callring.application.mapping.requiredValue
import com.greatwall.callring.bootstrap.*
import com.greatwall.callring.contracts.settings.*
import com.greatwall.callring.domain.model.CallDirection
import com.greatwall.callring.domain.model.NumberIdentity
import com.greatwall.callring.platform.calling.AndroidCallLogSource
import com.greatwall.callring.platform.runtime.UiNotificationEntryPoints
import kotlinx.coroutines.runBlocking

/** Opt-in real provider reads; every application write uses a fresh backendqa namespace. */
class NumberAdmissionDeviceTest : InstrumentationTestCase() {
    fun testRealHistoryAdmissionAndOutgoingIsolation(): Unit = runBlocking {
        val context = instrumentation.targetContext
        check(context.packageName == "com.greatwall.callring.backendqa")
        val namespace = "numberadmission_${System.nanoTime()}"
        val configuration = BackendConfiguration(
            AppearanceSettings(ThemeSelection.PRESET, "default", null, ColorMode.FOLLOW_SYSTEM),
            databaseName = namespace, ringPreferencesName = namespace + "_ring",
            widgetPreferencesName = namespace + "_widgets", appearancePreferencesName = namespace + "_appearance")
        val notifications = object : UiNotificationEntryPoints {
            override fun home(requestCode: Int) = PendingIntent.getActivity(context, requestCode,
                Intent("qa.number.admission"), PendingIntent.FLAG_IMMUTABLE)
            override fun playback(preview: Boolean, stopAction: PendingIntent) = Notification()
        }
        val end = System.currentTimeMillis() + 1
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.READ_CALL_LOG)
        try {
            val source = AndroidCallLogSource(context.contentResolver)
            val before = source.read(0, end)
            assertTrue("Real provider history required", before.isNotEmpty())
            val expected = before.filter {
                val identity = NumberIdentity.parse(it.number)
                identity.validProfileNumber && identity.normalized.length == 11 &&
                    identity.normalized.all { digit -> digit in '0'..'9' }
            }
            assertTrue("Real outgoing history required", expected.any { it.direction == CallDirection.OUTGOING })
            BackendGraph(BackupGraphOwner { _, _ -> error("qa.no_restore") }, context, configuration, notifications).use { graph ->
                graph.backend.prepareNumberRule().requiredValue()
                val initial = graph.api.settings.getNumberRule().requiredValue()
                graph.api.settings.saveNumberRule(SaveNumberRule(initial, emptyList())).requiredValue()
                assertTrue(graph.backend.importSystemCalls(before).isEmpty())
                SQLiteDatabase.openDatabase(context.getDatabasePath(namespace).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    for (table in listOf("callers", "calls")) db.rawQuery("SELECT count(*) FROM $table", null).use {
                        assertTrue(it.moveToFirst()); assertEquals(0L, it.getLong(0))
                    }
                }
                val empty = graph.api.settings.getNumberRule().requiredValue()
                graph.api.settings.saveNumberRule(SaveNumberRule(empty, listOf(NumberRuleEntry("", 11)))).requiredValue()
                graph.backend.importSystemCalls(before)
                assertTrue(graph.backend.importSystemCalls(before).isEmpty())
            }
            SQLiteDatabase.openDatabase(context.getDatabasePath(namespace).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val admitted = linkedMapOf<String, String>()
                db.rawQuery("SELECT log_id,number,direction,counted FROM calls", null).use { rows ->
                    while (rows.moveToNext()) {
                        val original = expected.single { it.logId == rows.getString(0) }
                        assertEquals(NumberIdentity.parse(original.number).normalized, rows.getString(1))
                        assertEquals(original.direction.name, rows.getString(2))
                        if (original.direction == CallDirection.OUTGOING) assertEquals(0, rows.getInt(3))
                        admitted[original.logId] = rows.getString(1)
                    }
                }
                assertEquals(expected.map { it.logId }.toSet(), admitted.keys)
                db.rawQuery("SELECT number FROM callers", null).use { rows ->
                    while (rows.moveToNext()) assertTrue(admitted.values.contains(rows.getString(0)))
                }
            }
            assertEquals("Provider facts changed during isolated verification", before, source.read(0, end))
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            context.deleteDatabase(namespace)
            context.deleteDatabase(namespace + "_recording.db")
        }
    }
}
