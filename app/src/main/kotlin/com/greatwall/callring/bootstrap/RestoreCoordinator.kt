package com.greatwall.callring.bootstrap

import android.content.Context
import android.system.Os
import android.system.OsConstants
import com.greatwall.callring.data.backup.RestoreJournal
import java.io.File

/** Runs before ANY normal graph is constructed, including widgets and scheduled jobs. */
object RestoreCoordinator {
    fun journal(context: Context, namespace: String): RestoreJournal = RestoreJournal(
        File(context.noBackupFilesDir, "restore-$namespace"), context.dataDir,
        syncDirectory = { directory ->
            val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
            try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
        },
    )
    fun recover(context: Context, namespace: String) {
        journal(context, namespace).recover()
        val staging = File(context.noBackupFilesDir, "backup-$namespace")
        check(staging.canonicalFile.parentFile == context.noBackupFilesDir.canonicalFile)
        if (staging.exists()) check(staging.deleteRecursively()) { "backup.staging_cleanup_failed" }
    }
}
