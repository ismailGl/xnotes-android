package com.xnotes.platform

import com.xnotes.core.model.NormalizedRect
import com.xnotes.core.model.Question
import com.xnotes.core.model.QuestionSet
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** Separate versioned JSON; failures propagate so the UI never reports an unsaved question as saved. */
class QuestionSetRepository(private val directory: File) {
    /** A malformed entry keeps its position in the viewer; strict decoding for writes is unchanged. */
    data class Entry(val question: Question?, val error: String? = null)
    data class LoadedSet(val id: String, val title: String, val entries: List<Entry>)

    /** Read-only lookup by exactly the same identity as append; never creates or rewrites files. */
    fun find(notebookUri: String, pdf: File): LoadedSet? {
        require(notebookUri.isNotBlank()) { "Save the notebook before opening Question Mode" }
        val (id, hash) = sourceIdentity(notebookUri, pdf)
        synchronized(writeLock) {
            val file = File(directory, "$id.json")
            if (!file.exists()) return null
            val root = JSONObject(file.readText(Charsets.UTF_8))
            require(root.getInt("version") == 1) { "Unsupported question set version" }
            require(root.getString("id") == id && root.getString("sourceNotebookUri") == notebookUri &&
                root.getString("sourcePdfSha256") == hash) { "Question set does not match this PDF-backed notebook" }
            val items = root.getJSONArray("questions")
            val entries = (0 until items.length()).map { i ->
                try {
                    val q = items.getJSONObject(i)
                    val c = q.getJSONObject("crop")
                    Entry(Question(q.getString("id"), q.getInt("sourcePageIndex"), NormalizedRect(
                        c.getDouble("left"), c.getDouble("top"), c.getDouble("right"), c.getDouble("bottom"))))
                } catch (_: Exception) { Entry(null, "This question has invalid page or crop metadata") }
            }
            return LoadedSet(id, root.getString("title"), entries)
        }
    }

    fun append(notebookUri: String, title: String, pdf: File, question: Question): QuestionSet {
        require(notebookUri.isNotBlank()) { "Save the notebook before adding questions" }
        val (id, hash) = sourceIdentity(notebookUri, pdf)
        // Both split panes may append to the same set through different repository instances.
        synchronized(writeLock) {
            val file = File(directory, "$id.json")
            val old = if (file.exists()) decode(file.readText(Charsets.UTF_8)) else
                QuestionSet(id, title, emptyList(), notebookUri, hash)
            require(old.id == id && old.sourceNotebookUri == notebookUri && old.sourcePdfSha256 == hash)
            val updated = old.copy(title = title, questions = old.questions + question)
            Files.createDirectories(directory.toPath())
            val temp = File.createTempFile("question-", ".tmp", directory)
            try {
                temp.outputStream().use { out ->
                    out.write(encode(updated).toByteArray(Charsets.UTF_8))
                    out.fd.sync()
                }
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { temp.delete() }
            return updated
        }
    }

    private fun sourceIdentity(notebookUri: String, pdf: File): Pair<String, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        pdf.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        val id = UUID.nameUUIDFromBytes("$notebookUri\n$hash".toByteArray(Charsets.UTF_8)).toString()
        return id to hash
    }

    companion object {
        private val writeLock = Any()

        fun encode(set: QuestionSet): String = JSONObject()
            .put("version", 1).put("id", set.id).put("title", set.title)
            .put("sourceNotebookUri", set.sourceNotebookUri).put("sourcePdfSha256", set.sourcePdfSha256)
            .put("questions", JSONArray().apply {
                set.questions.forEach { q -> put(JSONObject().put("id", q.id).put("sourcePageIndex", q.sourcePageIndex)
                    .put("crop", JSONObject().put("left", q.crop.left).put("top", q.crop.top)
                        .put("right", q.crop.right).put("bottom", q.crop.bottom))) }
            }).toString()

        fun decode(json: String): QuestionSet {
            val root = JSONObject(json)
            require(root.getInt("version") == 1) { "Unsupported question set version" }
            val items = root.getJSONArray("questions")
            val questions = (0 until items.length()).map { i ->
                val q = items.getJSONObject(i)
                val c = q.getJSONObject("crop")
                Question(q.getString("id"), q.getInt("sourcePageIndex"),
                    NormalizedRect(c.getDouble("left"), c.getDouble("top"), c.getDouble("right"), c.getDouble("bottom")))
            }
            return QuestionSet(root.getString("id"), root.getString("title"), questions,
                root.getString("sourceNotebookUri"), root.getString("sourcePdfSha256"))
        }
    }
}
