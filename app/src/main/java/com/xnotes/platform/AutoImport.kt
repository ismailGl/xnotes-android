package com.xnotes.platform

import org.json.JSONArray
import org.json.JSONObject

/** A source is identified by its path in the granted tree, never its leaf name alone. */
data class AutoImportFile(val path: String, val uri: String, val size: Long, val modified: Long)
data class AutoImportRecord(
    val sourceTree: String, val path: String, val size: Long, val modified: Long,
    val sha256: String?, val destination: String,
)
data class AutoImportItem(val source: AutoImportFile, val destination: String, val updated: Boolean)
data class AutoImportReport(val imported: List<AutoImportItem>, val failed: List<String>, val error: String? = null)

/** Small, versioned ledger in the selected Folder's existing .xnote metadata directory. */
object AutoImportLedgerJson {
    fun encode(records: List<AutoImportRecord>): ByteArray {
        val array = JSONArray()
        records.forEach { r -> array.put(JSONObject().put("source_tree", r.sourceTree).put("path", r.path)
            .put("size", r.size).put("modified", r.modified).put("sha256", r.sha256 ?: "")
            .put("destination", r.destination)) }
        return JSONObject().put("version", 1).put("records", array).toString().toByteArray()
    }

    fun decode(bytes: ByteArray): List<AutoImportRecord> {
        val root = JSONObject(bytes.decodeToString())
        require(root.optInt("version") == 1)
        val array = root.getJSONArray("records")
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            AutoImportRecord(o.getString("source_tree"), o.getString("path"), o.getLong("size"),
                o.getLong("modified"), o.optString("sha256").ifEmpty { null }, o.getString("destination"))
        }
    }
}

/** The Android boundary owns SAF operations; this workflow is shared by launch and Scan now. */
interface AutoImportStorage {
    fun children(folder: String): List<AutoImportFile>
    fun isDirectory(file: AutoImportFile): Boolean
    fun hash(file: AutoImportFile): String
    fun copyPdf(file: AutoImportFile): String?
    fun destinationUsable(uri: String): Boolean
    fun destinationFullyUsable(uri: String): Boolean
    fun deleteDestination(uri: String)
    fun deleteSource(uri: String): Boolean
    fun records(): List<AutoImportRecord>
    fun saveRecords(records: List<AutoImportRecord>)
    fun stillSame(file: AutoImportFile, hash: String?): Boolean
}

object AutoImportWorkflow {
    fun canDeleteOriginals(items: List<AutoImportItem>): Boolean =
        items.any { it.destination != it.source.uri }

    fun ignored(name: String, directory: Boolean): Boolean =
        name.startsWith('.') || name.equals("DO_NOT_DELETE.txt", true) ||
            (directory && (name.equals(".stfolder", true) || name.equals(".stversions", true) ||
                name.startsWith(".stfolder.removed-", true)))

    fun collect(storage: AutoImportStorage, root: String): List<AutoImportFile> {
        val found = ArrayList<AutoImportFile>()
        fun walk(folder: String, prefix: String) {
            for (entry in storage.children(folder)) {
                val name = entry.path.substringAfterLast('/')
                val directory = storage.isDirectory(entry)
                if (ignored(name, directory)) continue
                val path = if (prefix.isEmpty()) name else "$prefix/$name"
                if (directory) walk(entry.uri, path)
                else if (name.endsWith(".pdf", true) && entry.size != 0L) found += entry.copy(path = path)
            }
        }
        walk(root, "")
        return found
    }

    fun scan(storage: AutoImportStorage, tree: String, root: String, shouldContinue: () -> Boolean = { true }): AutoImportReport {
        val imported = ArrayList<AutoImportItem>()
        val failed = ArrayList<String>()
        val ledger = try { storage.records().toMutableList() } catch (_: Exception) {
            return AutoImportReport(emptyList(), emptyList(), "Auto Import history could not be read.")
        }
        val sources = try { collect(storage, root) } catch (_: Exception) {
            return AutoImportReport(emptyList(), emptyList(), "Auto Import folder is unavailable or permission was revoked.")
        }
        for (source in sources) {
            if (!shouldContinue()) break
            val old = ledger.firstOrNull { it.sourceTree == tree && it.path == source.path }
            try {
                // Providers without a useful timestamp need a digest to detect same-size replacements.
                val hash = if (source.modified <= 0 || source.size < 0) storage.hash(source) else null
                if (old != null && old.size == source.size && old.modified == source.modified &&
                    (hash == null || old.sha256 == hash) &&
                    storage.destinationUsable(old.destination)) continue
                val destination = storage.copyPdf(source) ?: error("Could not import PDF")
                try {
                    // Hash newly copied sources once. Later launches use size/mtime; deletion can
                    // compare the digest and never remove a replaced source with matching metadata.
                    val copiedHash = hash ?: storage.hash(source)
                    if (!storage.stillSame(source, copiedHash) || !storage.destinationFullyUsable(destination) || !shouldContinue())
                        error("Source changed during import or destination could not be verified")
                    val record = AutoImportRecord(tree, source.path, source.size, source.modified, copiedHash, destination)
                    val next = ledger.filterNot { it.sourceTree == tree && it.path == source.path } + record
                    storage.saveRecords(next)
                    ledger.clear(); ledger.addAll(next)
                    imported += AutoImportItem(source, destination, old != null)
                } catch (e: Exception) {
                    storage.deleteDestination(destination)
                    throw e
                }
            } catch (_: Exception) { failed += source.path }
        }
        return AutoImportReport(imported, failed)
    }

    /** Only the exact source generation copied into a verified destination may be removed. */
    fun deleteImportedOriginals(storage: AutoImportStorage, tree: String, items: List<AutoImportItem>): Int {
        val records = storage.records()
        var deleted = 0
        for (item in items) {
            if (item.destination == item.source.uri) continue
            val record = records.firstOrNull { it.sourceTree == tree && it.path == item.source.path && it.destination == item.destination }
                ?: continue
            if (record.destination == item.source.uri || record.size != item.source.size || record.modified != item.source.modified ||
                !storage.destinationFullyUsable(record.destination) || !storage.stillSame(item.source, record.sha256)) continue
            if (storage.deleteSource(item.source.uri)) deleted++
        }
        return deleted
    }
}
