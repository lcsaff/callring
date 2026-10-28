package com.greatwall.callring.platform.storage

import android.content.Context
import android.os.StatFs
import com.greatwall.callring.application.ports.storage.StorageInventoryPort
import com.greatwall.callring.contracts.storage.StorageInventory
import java.io.File

/** Samples only app-owned roots. In-use caches are observed, never offered for deletion. */
class AndroidStorageInventory(private val context: Context, private val recordingDirectory: File,
    private val available: () -> Long? = { runCatching { StatFs(context.filesDir.absolutePath).availableBytes }.getOrNull() }) : StorageInventoryPort {
    private fun size(root: File, accept: (File) -> Boolean = { true }): Long? = runCatching {
        if (!root.exists()) return@runCatching 0L
        val canonical = root.canonicalFile
        var total = 0L
        val visited = mutableSetOf<String>()
        fun visit(file: File) {
            check(!java.nio.file.Files.isSymbolicLink(file.toPath())) { "storage.link" }
            val resolved = file.canonicalFile
            check(resolved == canonical || resolved.path.startsWith(canonical.path + File.separator)) { "storage.outside_root" }
            check(visited.add(resolved.path)) { "storage.duplicate" }
            if (file.isDirectory) {
                val children = checkNotNull(file.listFiles())
                children.forEach(::visit)
            } else {
                check(file.isFile && file.canRead())
                if (accept(file)) total = Math.addExact(total, file.length())
            }
        }
        visit(canonical)
        total
    }.getOrNull()
    override fun sample(): StorageInventory {
        val db = context.getDatabasePath("inventory-placeholder").parentFile
        val cache = size(context.cacheDir)
        val scratch = size(recordingDirectory) { it.extension == "tmp" }
        val staging = runCatching {
            val roots = checkNotNull(context.noBackupFilesDir.listFiles()).filter { it.name.startsWith("backup-") || it.name.startsWith("restore-") }
            roots.fold(0L) { total, root -> Math.addExact(total, checkNotNull(size(root))) }
        }.getOrNull()
        return StorageInventory(size(recordingDirectory) { it.extension != "tmp" }, db?.let { size(it) },
            if (cache == null || scratch == null || staging == null) null else Math.addExact(Math.addExact(cache, scratch), staging),
            context.getExternalFilesDir("Download/update")?.let { size(it) }, runCatching { available() }.getOrNull())
    }
}
