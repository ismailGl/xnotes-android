package com.xnotes.platform

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class QuestionFeedback { IMMEDIATE, ON_COMPLETION, MANUAL }
enum class QuestionResult { UNKNOWN, CORRECT, INCORRECT }

enum class QuestionType { SINGLE_CHOICE, OPEN_ENDED }

data class QuestionAnswerOptions(val type: QuestionType = QuestionType.SINGLE_CHOICE, val optionCount: Int = 5) {
    init { require(optionCount in 2..8) }
    val choices: List<String> get() = if (type == QuestionType.OPEN_ENDED) emptyList()
        else (0 until optionCount).map { ('A' + it).toString() }
}

data class QuestionProgress(val lastQuestionId: String? = null, val choices: Map<String, String> = emptyMap(),
    val answerOptions: Map<String, QuestionAnswerOptions> = emptyMap(),
    val feedback: QuestionFeedback = QuestionFeedback.MANUAL,
    val completed: Set<String> = emptySet(),
    val answerKeys: Map<String, String> = emptyMap(),
    val revealed: Set<String> = emptySet()) {
    fun optionsFor(id: String) = answerOptions[id] ?: QuestionAnswerOptions()
    fun resultFor(id: String): QuestionResult = when {
        choices[id] == null || answerKeys[id] == null -> QuestionResult.UNKNOWN
        choices[id] == answerKeys[id] -> QuestionResult.CORRECT
        else -> QuestionResult.INCORRECT
    }
    fun showsResult(id: String, pageIds: Collection<String>): Boolean = when (feedback) {
        QuestionFeedback.IMMEDIATE -> true
        QuestionFeedback.ON_COMPLETION -> pageIds.all { it in completed || it in choices }
        QuestionFeedback.MANUAL -> id in revealed
    }
}

interface QuestionProgressStore {
    suspend fun load(): QuestionProgress
    suspend fun save(progress: QuestionProgress)
}

/** Optional state sidecar; the original QuestionSet JSON and all ink files stay independent. */
class QuestionProgressRepository(private val storage: QuestionFiles, setId: String) : QuestionProgressStore {
    constructor(root: File, setId: String) : this(LocalQuestionFiles(root), setId)
    init { require(setId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) }
    private val path = "$setId/state.json"
    override suspend fun load(): QuestionProgress = withContext(Dispatchers.IO) {
        storage.read(path)?.let { decode(it.toString(Charsets.UTF_8)) } ?: QuestionProgress()
    }
    override suspend fun save(progress: QuestionProgress) = withContext(Dispatchers.IO) {
        storage.write(path) { it.write(encode(progress).toByteArray(Charsets.UTF_8)) }
    }
    companion object {
        fun encode(progress: QuestionProgress): String {
            require(progress.choices.all { (id, choice) -> choice in progress.optionsFor(id).choices })
            require(progress.answerKeys.all { (id, choice) -> choice in progress.optionsFor(id).choices })
            val options = JSONObject()
            progress.answerOptions.forEach { (id, value) ->
                options.put(id, JSONObject().put("type", value.type.name).put("optionCount", value.optionCount))
            }
            return JSONObject().put("version", 1).put("lastQuestionId", progress.lastQuestionId ?: JSONObject.NULL)
                .put("completed", org.json.JSONArray(progress.completed.toList()))
                .put("feedback", progress.feedback.name)
                .put("answerKeys", JSONObject(progress.answerKeys))
                .put("revealed", org.json.JSONArray(progress.revealed.toList()))
                .put("choices", JSONObject(progress.choices)).put("answerOptions", options).toString()
        }
        fun decode(json: String): QuestionProgress {
            val root = JSONObject(json)
            require(root.getInt("version") == 1)
            val choices = root.optJSONObject("choices") ?: JSONObject()
            val keys = root.optJSONObject("answerKeys") ?: JSONObject()
            val optionsJson = root.optJSONObject("answerOptions") ?: JSONObject()
            val options = optionsJson.keys().asSequence().mapNotNull { id ->
                val value = optionsJson.optJSONObject(id) ?: return@mapNotNull null
                val type = QuestionType.entries.firstOrNull { it.name == value.optString("type") }
                    ?: return@mapNotNull null
                val count = value.optInt("optionCount", 5)
                if (count !in 2..8) null else id to QuestionAnswerOptions(type, count)
            }.toMap()
            return QuestionProgress(if (root.isNull("lastQuestionId")) null else root.getString("lastQuestionId"),
                choices.keys().asSequence().mapNotNull { id ->
                    choices.optString(id).takeIf { it in (options[id] ?: QuestionAnswerOptions()).choices }?.let { id to it }
                }.toMap(), options,
                QuestionFeedback.entries.firstOrNull { it.name == root.optString("feedback") } ?: QuestionFeedback.MANUAL,
                root.optJSONArray("completed")?.let { ids -> (0 until ids.length()).map { ids.getString(it) }.toSet() } ?: emptySet(),
                keys.keys().asSequence().mapNotNull { id ->
                    keys.optString(id).takeIf { it in (options[id] ?: QuestionAnswerOptions()).choices }?.let { id to it }
                }.toMap(),
                root.optJSONArray("revealed")?.let { ids -> (0 until ids.length()).map { ids.getString(it) }.toSet() } ?: emptySet())
        }
    }
}
