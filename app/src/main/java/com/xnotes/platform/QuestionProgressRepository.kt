package com.xnotes.platform

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class QuestionType { SINGLE_CHOICE, OPEN_ENDED }

data class QuestionAnswerOptions(val type: QuestionType = QuestionType.SINGLE_CHOICE, val optionCount: Int = 5) {
    init { require(optionCount in 2..8) }
    val choices: List<String> get() = if (type == QuestionType.OPEN_ENDED) emptyList()
        else (0 until optionCount).map { ('A' + it).toString() }
}

data class QuestionProgress(val lastQuestionId: String? = null, val choices: Map<String, String> = emptyMap(),
    val answerOptions: Map<String, QuestionAnswerOptions> = emptyMap()) {
    fun optionsFor(id: String) = answerOptions[id] ?: QuestionAnswerOptions()
}

interface QuestionProgressStore {
    suspend fun load(): QuestionProgress
    suspend fun save(progress: QuestionProgress)
}

/** Optional state sidecar; the original QuestionSet JSON and all ink files stay independent. */
class QuestionProgressRepository(root: File, setId: String) : QuestionProgressStore {
    init { require(setId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) }
    private val file = File(File(root, setId), "state.json")
    override suspend fun load(): QuestionProgress = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext QuestionProgress()
        decode(file.readText())
    }
    override suspend fun save(progress: QuestionProgress) = withContext(Dispatchers.IO) {
        Files.createDirectories(file.parentFile!!.toPath())
        val temp = File.createTempFile("state-", ".tmp", file.parentFile)
        try {
            temp.outputStream().use { out -> out.write(encode(progress).toByteArray(Charsets.UTF_8)); out.fd.sync() }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            Unit
        } finally { temp.delete() }
    }
    companion object {
        fun encode(progress: QuestionProgress): String {
            require(progress.choices.all { (id, choice) -> choice in progress.optionsFor(id).choices })
            val options = JSONObject()
            progress.answerOptions.forEach { (id, value) ->
                options.put(id, JSONObject().put("type", value.type.name).put("optionCount", value.optionCount))
            }
            return JSONObject().put("version", 1).put("lastQuestionId", progress.lastQuestionId ?: JSONObject.NULL)
                .put("choices", JSONObject(progress.choices)).put("answerOptions", options).toString()
        }
        fun decode(json: String): QuestionProgress {
            val root = JSONObject(json)
            require(root.getInt("version") == 1)
            val choices = root.optJSONObject("choices") ?: JSONObject()
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
                }.toMap(), options)
        }
    }
}
