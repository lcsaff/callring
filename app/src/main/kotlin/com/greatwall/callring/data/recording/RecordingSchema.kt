package com.greatwall.callring.data.recording

import android.database.sqlite.SQLiteDatabase
import com.greatwall.callring.contracts.recording.ArchiveAnalysisIntent
import com.greatwall.callring.contracts.recording.ArchiveStatus
import com.greatwall.callring.contracts.recording.CallArchive
import com.greatwall.callring.contracts.recording.ManualAnalysisState

/** One migration path for the live database and private legacy backup candidates. */
internal object RecordingSchema {
    // Payload migration is lazy so upgrading a large archive library never materializes its transcripts.
    fun decodeLegacyAnalysis(archive: CallArchive): CallArchive {
        val intent = legacyIntent(archive.status, archive.manualAnalysis)
        val saved = intent != ArchiveAnalysisIntent.AUTOMATIC && archive.analysis != null &&
            archive.status in setOf(ArchiveStatus.ENRICHING, ArchiveStatus.ENRICHMENT_FAILED)
        return if (saved) archive.copy(
            analysisIntent = intent, status = ArchiveStatus.COMPLETE, error = null, nextAttemptAt = 0,
            manualAnalysisState = ManualAnalysisState.PENDING_CONFIRMATION, manualAnalysisResultVersion = archive.generation,
        ) else archive.copy(analysisIntent = intent)
    }

    fun legacyIntent(status: ArchiveStatus, manual: Boolean): ArchiveAnalysisIntent = when {
        status !in setOf(ArchiveStatus.QUEUED, ArchiveStatus.TRANSCRIBING, ArchiveStatus.ANALYZING,
            ArchiveStatus.FAILED, ArchiveStatus.ENRICHING, ArchiveStatus.ENRICHMENT_FAILED) -> ArchiveAnalysisIntent.AUTOMATIC
        manual -> ArchiveAnalysisIntent.MANUAL_LEGACY
        else -> ArchiveAnalysisIntent.LEGACY_UNCLASSIFIED
    }

    fun upgrade(db: SQLiteDatabase, old: Int) {
        require(old in 1..6)
        if (old < 2) {
            db.execSQL("CREATE TABLE recording_events (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL UNIQUE, number TEXT NOT NULL, started INTEGER NOT NULL, account TEXT)")
            db.execSQL("CREATE TABLE recording_statistics_links (statistics_id INTEGER PRIMARY KEY, session_id TEXT NOT NULL)")
        }
        if (old < 3) ArchiveProjectionIndex.create(db)
        if (old < 4) ArchiveProjectionIndex.createProgress(db)
        if (old < 5) RecordingArchivePages.create(db)
        if (old < 6) SystemRecordingImports.create(db)
    }
}
