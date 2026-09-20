package com.xnotes.platform

/** Parse pasted choice letters in displayed order; question IDs remain the saved identity. */
object BulkAnswerKey {
    data class Preview(val answers: List<String>, val unused: String, val error: String?) {
        val count get() = answers.size
        val valid get() = error == null
    }
    fun parse(input: String, ids: List<String>, optionsFor: (String) -> QuestionAnswerOptions): Preview {
        val chars = input.uppercase().filter(Char::isLetter)
        val answers = chars.take(ids.size).map(Char::toString)
        val invalid = answers.indices.firstOrNull { answers[it] !in optionsFor(ids[it]).choices } ?: -1
        return Preview(answers, chars.drop(ids.size), if (invalid >= 0)
            "Question ${invalid + 1} does not support choice ${answers[invalid]}" else null)
    }
}
