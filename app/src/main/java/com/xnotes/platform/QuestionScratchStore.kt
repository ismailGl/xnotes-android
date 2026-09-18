package com.xnotes.platform

import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.format.CanvasCodec
import java.io.File

/** Only question-set storage is writable here; no source notebook writer is accepted. */
class QuestionScratchStore(private val files: QuestionFiles, private val setId: String,
    private val codec: CanvasCodec, private val imageDir: File) {
    private fun path(id: String): String {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        return "$setId/questions/$id/ink.xcanvas"
    }
    fun load(id: String): InfiniteDocument? = files.read(path(id))?.inputStream()?.use { codec.read(it,imageDir) }
    fun save(id: String, snapshot: InfiniteDocument) = files.write(path(id)) { codec.write(snapshot,it) }
}
