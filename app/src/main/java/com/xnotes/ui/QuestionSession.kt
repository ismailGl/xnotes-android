package com.xnotes.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.xnotes.platform.QuestionSetRepository
import java.io.File

/** Read-only navigation state, independent of Document, History and notebook recovery. */
class QuestionSession(val set: QuestionSetRepository.LoadedSet, val sourcePdf: File) {
    var index by mutableIntStateOf(0)
        private set
    val count get() = set.entries.size
    val current get() = set.entries.getOrNull(index)
    val canPrevious get() = index > 0
    val canNext get() = index + 1 < count
    fun previous() { if (canPrevious) index-- }
    fun next() { if (canNext) index++ }
}
