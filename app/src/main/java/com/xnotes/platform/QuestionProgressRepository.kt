package com.xnotes.platform

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class QuestionProgress(val lastQuestionId: String? = null, val choices: Map<String, String> = emptyMap())

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
        val CHOICES = listOf("A", "B", "C", "D", "E")
        fun encode(progress: QuestionProgress): String {
            require(progress.choices.values.all { it in CHOICES })
            return JSONObject().put("version", 1).put("lastQuestionId", progress.lastQuestionId ?: JSONObject.NULL)
                .put("choices", JSONObject(progress.choices)).toString()
        }
        fun decode(json: String): QuestionProgress {
            val root = JSONObject(json)
            require(root.getInt("version") == 1)
            val choices = root.optJSONObject("choices") ?: JSONObject()
            return QuestionProgress(if (root.isNull("lastQuestionId")) null else root.getString("lastQuestionId"),
                choices.keys().asSequence().mapNotNull { id ->
                    choices.optString(id).takeIf { it in CHOICES }?.let { id to it }
                }.toMap())
        }
    }
}
