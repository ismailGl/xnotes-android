package com.xnotes.platform

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract as Documents
import com.xnotes.ui.Editor
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import java.util.concurrent.atomic.AtomicBoolean

/** SAF adapter for the existing selected-folder PDF importer and .xnote metadata storage. */
class SafAutoImport(private val context: Context, private val editor: Editor) {
    private val resolver = context.contentResolver
    private val running = AtomicBoolean(false)

    fun scan(shouldContinue: () -> Boolean = { true }): AutoImportReport? {
        if (!running.compareAndSet(false, true)) return null
        try {
            val source = editor.autoImportSourceUri ?: return AutoImportReport(emptyList(), emptyList(), "Choose an Auto Import folder.")
            val destination = editor.browseRoot ?: return AutoImportReport(emptyList(), emptyList(), "Choose an xNotes storage Folder first.")
            val granted = resolver.persistedUriPermissions.any { it.uri.toString() == source && it.isReadPermission }
            if (!granted) return AutoImportReport(emptyList(), emptyList(), "Auto Import folder permission is missing. Choose the folder again.")
            val tree = Uri.parse(source)
            val root = Documents.buildDocumentUriUsingTree(tree, Documents.getTreeDocumentId(tree)).toString()
            return AutoImportWorkflow.scan(Store(source, destination), source, root) {
                shouldContinue() && !Thread.currentThread().isInterrupted
            }
        } finally { running.set(false) }
    }

    fun deleteOriginals(items: List<AutoImportItem>): Int {
        val source = editor.autoImportSourceUri ?: return 0
        val destination = editor.browseRoot ?: return 0
        if (resolver.persistedUriPermissions.none { it.uri.toString() == source && it.isWritePermission }) return 0
        return runCatching { AutoImportWorkflow.deleteImportedOriginals(Store(source, destination), source, items) }.getOrDefault(0)
    }

    private inner class Store(private val sourceTree: String, private val destinationTree: String) : AutoImportStorage {
        private val source = Uri.parse(sourceTree)
        private val dest = Uri.parse(destinationTree)
        private val files = FolderQuestionFiles(context, destinationTree, null, ".xnote")

        private fun document(uri: String) = Uri.parse(uri)
        override fun children(folder: String): List<AutoImportFile> {
            val childUri = Documents.buildChildDocumentsUriUsingTree(source, Documents.getDocumentId(document(folder)))
            val result = ArrayList<AutoImportFile>()
            requireNotNull(resolver.query(childUri, arrayOf(
                Documents.Document.COLUMN_DOCUMENT_ID, Documents.Document.COLUMN_DISPLAY_NAME,
                Documents.Document.COLUMN_MIME_TYPE, Documents.Document.COLUMN_SIZE,
                Documents.Document.COLUMN_LAST_MODIFIED,
            ), null, null, null)).use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val uri = Documents.buildDocumentUriUsingTree(source, id).toString()
                    val directory = c.getString(2) == Documents.Document.MIME_TYPE_DIR
                    result += AutoImportFile(name, uri, if (directory) 0 else if (c.isNull(3)) -1 else c.getLong(3),
                        if (c.isNull(4)) 0 else c.getLong(4))
                    if (directory) dirs += uri
                }
            }
            return result
        }
        private val dirs = HashSet<String>()
        override fun isDirectory(file: AutoImportFile) = file.uri in dirs

        override fun hash(file: AutoImportFile): String {
            val digest = MessageDigest.getInstance("SHA-256")
            requireNotNull(resolver.openInputStream(document(file.uri))).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun metadata(uri: String): Pair<Long, Long>? = runCatching {
            resolver.query(document(uri), arrayOf(Documents.Document.COLUMN_SIZE, Documents.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
                if (c.moveToFirst()) (if (c.isNull(0)) -1L else c.getLong(0)) to (if (c.isNull(1)) 0L else c.getLong(1)) else null
            }
        }.getOrNull()

        override fun stillSame(file: AutoImportFile, hash: String?): Boolean {
            val (size, modified) = metadata(file.uri) ?: return false
            return size == file.size && modified == file.modified && (hash == null || this.hash(file) == hash)
        }

        private fun child(parent: Uri, name: String, directory: Boolean): Uri? {
            val children = Documents.buildChildDocumentsUriUsingTree(dest, Documents.getDocumentId(parent))
            return resolver.query(children, arrayOf(Documents.Document.COLUMN_DOCUMENT_ID,
                Documents.Document.COLUMN_DISPLAY_NAME, Documents.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
                while (c.moveToNext()) if (c.getString(1) == name &&
                    (c.getString(2) == Documents.Document.MIME_TYPE_DIR) == directory)
                    return@use Documents.buildDocumentUriUsingTree(dest, c.getString(0))
                null
            }
        }

        override fun copyPdf(file: AutoImportFile): String? {
            var parent = Documents.buildDocumentUriUsingTree(dest, Documents.getTreeDocumentId(dest))
            for (part in file.path.split('/').dropLast(1)) {
                parent = child(parent, part, true)
                    ?: Documents.createDocument(resolver, parent, Documents.Document.MIME_TYPE_DIR, part)
                    ?: return null
            }
            val temp = File.createTempFile("auto-import-", ".pdf", context.cacheDir)
            try {
                requireNotNull(resolver.openInputStream(document(file.uri))).use { input ->
                    FileOutputStream(temp).use { output -> input.copyTo(output, 64 * 1024) }
                }
                if ((file.size >= 0 && temp.length() != file.size) || !stillSame(file, null)) return null
                // The regular import path validates the PDF, bundles it, and removes a partial note on failure.
                val name = file.path.substringAfterLast('/').dropLast(4) // accepted .pdf, case-insensitively
                return editor.createPdfNoteFile(destinationTree, Documents.getDocumentId(parent), name, temp)
            } finally { temp.delete() }
        }

        override fun destinationUsable(uri: String): Boolean = runCatching {
            val size = metadata(uri)?.first ?: return@runCatching false
            if (size in 0..99) return@runCatching false
            requireNotNull(resolver.openInputStream(document(uri))).use { input ->
                val magic = ByteArray(4)
                input.read(magic) == 4 && magic.contentEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04))
            }
        }.getOrDefault(false)

        override fun destinationFullyUsable(uri: String): Boolean = runCatching {
            if (!destinationUsable(uri)) return@runCatching false
            var pdf = false
            var manifest = false
            requireNotNull(resolver.openInputStream(document(uri))).use { raw ->
                ZipInputStream(raw).use { zip ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.name == "assets/source.pdf") pdf = true
                        if (entry.name == "manifest.json") manifest = true
                        while (zip.read(buffer) >= 0) Unit // checks CRC and catches truncated bundles
                        zip.closeEntry()
                    }
                }
            }
            pdf && manifest
        }.getOrDefault(false)

        override fun deleteDestination(uri: String) { runCatching { Documents.deleteDocument(resolver, document(uri)) } }
        override fun deleteSource(uri: String): Boolean = runCatching { Documents.deleteDocument(resolver, document(uri)) }.getOrDefault(false)

        override fun records(): List<AutoImportRecord> {
            val bytes = files.read("auto_import.json") ?: return emptyList()
            return AutoImportLedgerJson.decode(bytes)
        }

        override fun saveRecords(records: List<AutoImportRecord>) {
            val bytes = AutoImportLedgerJson.encode(records)
            files.write("auto_import.json") { it.write(bytes) }
        }
    }
}
