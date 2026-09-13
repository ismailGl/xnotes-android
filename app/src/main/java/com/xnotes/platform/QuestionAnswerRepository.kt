package com.xnotes.platform

import com.xnotes.core.model.Document
import com.xnotes.core.model.Orientation
import com.xnotes.format.DocumentCodec
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface AnswerStore {
    suspend fun load(answerId: String): Document
    suspend fun save(answerId: String, document: Document)
}

/** Metadata stays at <set-id>.json; editable answers have their own sibling directory. */
class QuestionAnswerRepository(root: File, setId: String, private val codec: DocumentCodec,
    category: String = "answers",
    private val blank: suspend (String) -> Document = { Document.blank(orientation = Orientation.LANDSCAPE) },
) : AnswerStore {
    private val directory = File(File(root, safeId(setId)), safeId(category))
    private fun file(id: String) = File(directory, "${safeId(id)}.xnote")

    override suspend fun load(answerId: String): Document = withContext(Dispatchers.IO) {
        val target = file(answerId)
        if (!target.exists()) return@withContext blank(answerId)
        // No fallback write on corruption: leave the user's original bytes intact.
        target.inputStream().use { codec.read(it) }.also {
            require(!it.hasPdf && it.pages.isNotEmpty() && it.pages.all { page -> page.pdfPage == null }) {
                "Invalid answer document"
            }
        }
    }

    override suspend fun save(answerId: String, document: Document) = withContext(Dispatchers.IO) {
        require(!document.hasPdf && document.pages.all { it.pdfPage == null })
        val target = file(answerId)
        Files.createDirectories(directory.toPath())
        val temp = File.createTempFile("answer-", ".tmp", directory)
        try {
            temp.outputStream().use { out ->
                // Codec closes its stream, so fsync through a second handle afterwards.
                codec.write(document, out)
            }
            java.io.RandomAccessFile(temp, "rw").use { it.fd.sync() }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            Unit
        } finally { temp.delete() }
    }

    companion object {
        private fun safeId(id: String): String {
            require(id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid answer identity" }
            return id
        }
    }
}
