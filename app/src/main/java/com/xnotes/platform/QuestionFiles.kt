package com.xnotes.platform

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract as Documents
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Question repositories use the same selected SAF tree as the notebook explorer. */
interface QuestionFiles {
    fun read(path: String): ByteArray?
    fun write(path: String, encode: (OutputStream) -> Unit)
    fun delete(path: String)
}

class LocalQuestionFiles(private val root: File) : QuestionFiles {
    override fun delete(path: String) {
        val target = File(root, path).canonicalFile
        require(target.toPath().startsWith(root.canonicalFile.toPath()) && target != root.canonicalFile)
        if (target.exists()) check(target.deleteRecursively())
    }
    override fun read(path: String) = File(root, path).takeIf { it.exists() }?.readBytes()
    override fun write(path: String, encode: (OutputStream) -> Unit) {
        val target = File(root, path)
        target.parentFile!!.mkdirs()
        val temp = File.createTempFile("question-", ".tmp", target.parentFile)
        try {
            temp.outputStream().use(encode)
            java.io.RandomAccessFile(temp, "rw").use { it.fd.sync() }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
    }
}

/** Small document-provider boundary so migration and interrupted writes can be tested without Android. */
interface QuestionFolderDocuments {
    val root: String
    fun child(parent: String, name: String): String?
    fun create(parent: String, name: String, directory: Boolean): String
    fun read(uri: String): ByteArray
    fun write(uri: String, bytes: ByteArray)
    fun rename(uri: String, name: String): String
    fun delete(uri: String)
}

private class SafQuestionFolder(context: Context, tree: String) : QuestionFolderDocuments {
    private val resolver = context.applicationContext.contentResolver
    private val treeUri = Uri.parse(tree)
    override val root = Documents.buildDocumentUriUsingTree(treeUri, Documents.getTreeDocumentId(treeUri)).toString()
    override fun child(parent: String, name: String): String? {
        val children = Documents.buildChildDocumentsUriUsingTree(treeUri, Documents.getDocumentId(Uri.parse(parent)))
        requireNotNull(resolver.query(children, arrayOf(Documents.Document.COLUMN_DOCUMENT_ID, Documents.Document.COLUMN_DISPLAY_NAME), null, null, null)).use { cursor ->
            while (cursor.moveToNext()) if (cursor.getString(1) == name)
                return Documents.buildDocumentUriUsingTree(treeUri, cursor.getString(0)).toString()
        }
        return null
    }
    override fun create(parent: String, name: String, directory: Boolean): String = requireNotNull(
        Documents.createDocument(resolver, Uri.parse(parent), if (directory) Documents.Document.MIME_TYPE_DIR else "application/octet-stream", name)).toString()
    override fun read(uri: String) = requireNotNull(resolver.openInputStream(Uri.parse(uri))).use { it.readBytes() }
    override fun write(uri: String, bytes: ByteArray) {
        requireNotNull(resolver.openOutputStream(Uri.parse(uri), "wt")).use { it.write(bytes) }
    }
    override fun rename(uri: String, name: String) = requireNotNull(Documents.renameDocument(resolver, Uri.parse(uri), name)).toString()
    override fun delete(uri: String) { check(Documents.deleteDocument(resolver, Uri.parse(uri))) }
}

/** Encode before touching SAF; keep a recoverable previous generation for interrupted writes. */
class FolderQuestionFiles(private val documents: QuestionFolderDocuments, private val legacy: File?,
    private val metadataRoot: String = ".xnote/questions") : QuestionFiles {
    constructor(context: Context, tree: String, legacy: File?, metadataRoot: String = ".xnote/questions") :
        this(SafQuestionFolder(context, tree), legacy, metadataRoot)
    private fun parent(path: String, create: Boolean): Pair<String, String>? {
        val parts = ("$metadataRoot/$path").split('/')
        require(parts.none { it.isBlank() || it == "." || it == ".." || '\\' in it })
        var folder = documents.root
        for (part in parts.dropLast(1)) folder = documents.child(folder, part)
            ?: if (create) documents.create(folder, part, true) else return null
        return folder to parts.last()
    }
    override fun read(path: String): ByteArray? = synchronized(lock) {
        val location = parent(path, false)
        val uri = location?.let { (folder, name) -> documents.child(folder, name) ?: documents.child(folder, "$name.previous") }
        if (uri != null) return@synchronized documents.read(uri)
        // Import only repository-requested data, never credentials or unrelated app files.
        val old = legacy?.let { LocalQuestionFiles(it).read(path) } ?: return@synchronized null
        write(path) { it.write(old) }
        old
    }
    override fun delete(path: String) = synchronized(lock) {
        parent(path, false)?.let { (folder, name) ->
            documents.child(folder, name)?.let(documents::delete)
            documents.child(folder, "$name.previous")?.let(documents::delete)
        }
        // Delete the migration source too: a later lookup must never resurrect deleted work.
        legacy?.let { LocalQuestionFiles(it).delete(path) }
        Unit
    }
    fun documentUri(path: String): String? = synchronized(lock) {
        if (read(path) == null) return@synchronized null
        parent(path, false)?.let { (folder, name) -> documents.child(folder, name) ?: documents.child(folder, "$name.previous") }
    }
    override fun write(path: String, encode: (OutputStream) -> Unit) = synchronized(lock) {
        val bytes = java.io.ByteArrayOutputStream().also { encode(it) }.toByteArray()
        val (folder, name) = requireNotNull(parent(path, true))
        val pending = documents.create(folder, "$name.pending-${java.util.UUID.randomUUID()}", false)
        try {
            documents.write(pending, bytes)
            check(documents.read(pending).contentEquals(bytes))
            documents.child(folder, name)?.let { current ->
                documents.child(folder, "$name.previous")?.let(documents::delete)
                documents.rename(current, "$name.previous")
            }
            documents.rename(pending, name)
        } catch (e: Exception) {
            runCatching { documents.delete(pending) }
            throw e
        }
        Unit
    }
    companion object { private val lock = Any() }
}
