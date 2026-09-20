package com.xnotes.platform

import com.xnotes.core.model.NormalizedRect
import com.xnotes.core.model.Question
import com.xnotes.core.model.QuestionSet
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Separate versioned JSON; failures propagate so the UI never reports an unsaved question as saved. */
class QuestionSetRepository(private val storage: QuestionFiles) {
    constructor(directory: File) : this(LocalQuestionFiles(directory))
    /** A malformed entry keeps its position in the viewer; strict decoding for writes is unchanged. */
    data class Entry(val question: Question?, val error: String? = null)
    data class LoadedSet(val id: String, val title: String, val entries: List<Entry>)

    /** Read-only lookup by exactly the same identity as append; never creates or rewrites files. */
    fun find(notebookUri: String, pdf: File): LoadedSet? {
        require(notebookUri.isNotBlank()) { "Save the notebook before opening Question Mode" }
        val (id, hash) = sourceIdentity(notebookUri, pdf)
        synchronized(writeLock) {
            val bytes = storage.read("$id.json") ?: return null
            val root = JSONObject(bytes.toString(Charsets.UTF_8))
            require(root.getInt("version") == 1) { "Unsupported question set version" }
            require(root.getString("id") == id && root.getString("sourceNotebookUri") == notebookUri &&
                root.getString("sourcePdfSha256") == hash) { "Question set does not match this PDF-backed notebook" }
            finishDeletes(root, id)
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
        return appendBatch(notebookUri, title, pdf, listOf(question), deduplicate = false)
    }

    /** One identity verification and atomic write. Existing question identities are never replaced. */
    fun appendBatch(notebookUri: String, title: String, pdf: File, questions: List<Question>,
                    expectedSourceId: String? = null, deduplicate: Boolean = true): QuestionSet {
        require(notebookUri.isNotBlank()) { "Save the notebook before adding questions" }
        val (id, hash) = sourceIdentity(notebookUri, pdf)
        require(expectedSourceId == null || expectedSourceId == id) { "The source PDF changed; scan again" }
        // Both split panes may append to the same set through different repository instances.
        synchronized(writeLock) {
            val bytes = storage.read("$id.json")
            val old = if (bytes != null) decode(bytes.toString(Charsets.UTF_8)) else
                QuestionSet(id, title, emptyList(), notebookUri, hash)
            require(old.id == id && old.sourceNotebookUri == notebookUri && old.sourcePdfSha256 == hash)
            val accepted = old.questions.toMutableList()
            questions.forEach { question ->
                if (accepted.none { it.id == question.id || (deduplicate && it.sourcePageIndex == question.sourcePageIndex &&
                    com.xnotes.core.model.QuestionOverlap.duplicate(it.crop, question.crop)) }) accepted += question
            }
            if (accepted.size == old.questions.size) return old
            val updated = old.copy(title = title, questions = accepted)
            storage.write("$id.json") { it.write(encode(updated).toByteArray(Charsets.UTF_8)) }
            return updated
        }
    }

    fun updateCrop(notebookUri: String, pdf: File, questionId: String, crop: NormalizedRect): Question {
        val (id, hash) = sourceIdentity(notebookUri, pdf)
        synchronized(writeLock) {
            val root = JSONObject(requireNotNull(storage.read("$id.json")).toString(Charsets.UTF_8))
            require(root.getInt("version") == 1 && root.getString("id") == id &&
                root.getString("sourceNotebookUri") == notebookUri && root.getString("sourcePdfSha256") == hash)
            val questions = root.getJSONArray("questions")
            val target = (0 until questions.length()).map { questions.getJSONObject(it) }.single { it.optString("id") == questionId }
            val updated = Question(questionId, target.getInt("sourcePageIndex"), crop)
            target.put("crop", JSONObject().put("left", crop.left).put("top", crop.top)
                .put("right", crop.right).put("bottom", crop.bottom))
            storage.write("$id.json") { it.write(root.toString().toByteArray(Charsets.UTF_8)) }
            return updated
        }
    }

    /** Reorder existing JSON objects, retaining stable IDs and unknown future metadata. */
    fun reorder(notebookUri: String, pdf: File, ids: List<String>) {
        val (id, hash) = sourceIdentity(notebookUri, pdf)
        synchronized(writeLock) {
            val root = JSONObject(requireNotNull(storage.read("$id.json")).decodeToString())
            require(root.getString("sourceNotebookUri") == notebookUri && root.getString("sourcePdfSha256") == hash)
            val items = root.getJSONArray("questions")
            val byId = (0 until items.length()).map { items.getJSONObject(it) }.associateBy { it.getString("id") }
            require(ids.size == items.length() && ids.toSet() == byId.keys)
            root.put("questions", JSONArray(ids.map { byId.getValue(it) }))
            storage.write("$id.json") { it.write(root.toString().toByteArray()) }
        }
    }

    /** Journal first, then idempotent cleanup. Reopening finishes an interrupted deletion. */
    fun deleteQuestion(notebookUri: String, pdf: File, questionId: String) {
        require(questionId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        val (id, hash) = sourceIdentity(notebookUri, pdf)
        synchronized(writeLock) {
            val root = JSONObject(requireNotNull(storage.read("$id.json")).decodeToString())
            require(root.getString("sourceNotebookUri") == notebookUri && root.getString("sourcePdfSha256") == hash)
            val pending = root.optJSONArray("pendingDeletes") ?: JSONArray()
            if ((0 until pending.length()).none { pending.getString(it) == questionId }) pending.put(questionId)
            root.put("pendingDeletes", pending)
            storage.write("$id.json") { it.write(root.toString().toByteArray()) }
            finishDeletes(root, id)
        }
    }

    private fun finishDeletes(root: JSONObject, setId: String) {
        val pending = root.optJSONArray("pendingDeletes") ?: return
        val ids = (0 until pending.length()).map { pending.getString(it) }.toSet()
        ids.forEach { require(it.matches(Regex("[A-Za-z0-9_-]{1,128}"))) }
        storage.read("$setId/state.json")?.let { bytes ->
            val state = JSONObject(bytes.decodeToString())
            listOf("choices", "answerOptions", "results", "answerKeys").forEach { key ->
                state.optJSONObject(key)?.let { map -> ids.forEach(map::remove) }
            }
            state.optJSONArray("completed")?.let { completed ->
                state.put("completed", JSONArray((0 until completed.length()).map { completed.getString(it) }.filterNot { it in ids }))
            }
            state.optJSONArray("revealed")?.let { revealed ->
                state.put("revealed", JSONArray((0 until revealed.length()).map { revealed.getString(it) }.filterNot { it in ids }))
            }
            if (state.optString("lastQuestionId") in ids) state.put("lastQuestionId", JSONObject.NULL)
            storage.write("$setId/state.json") { it.write(state.toString().toByteArray()) }
        }
        for (id in ids) {
            storage.delete("$setId/questions/$id")
            storage.delete("$setId/answers/$id.xnote")
        }
        val questions = root.getJSONArray("questions")
        root.put("questions", JSONArray((0 until questions.length()).map { questions.getJSONObject(it) }.filterNot { it.optString("id") in ids }))
        root.remove("pendingDeletes")
        storage.write("$setId.json") { it.write(root.toString().toByteArray()) }
        storage.delete("$setId/state.json.previous")
        storage.delete("$setId.json.previous")
    }

    /** Capture identity even when no set exists yet; never creates a file. */
    fun identity(notebookUri: String, pdf: File): String = sourceIdentity(notebookUri, pdf).first

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
