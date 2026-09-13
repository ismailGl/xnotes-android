package com.xnotes.ui

import com.xnotes.core.history.History
import com.xnotes.core.model.Document

/** Model identities must survive with their commands. No Views or render caches are retained. */
class QuestionHistoryCache(private val limit: Int = 12) {
    data class Entry(val document: Document, val history: History)
    private val entries = linkedMapOf<String, Entry>()
    fun take(key: String): Entry? = entries.remove(key)
    fun retain(key: String, document: Document, history: History) {
        entries.remove(key)
        entries[key] = Entry(document, history)
        while (entries.size > limit) entries.remove(entries.keys.first())
    }
    val size get() = entries.size
}
