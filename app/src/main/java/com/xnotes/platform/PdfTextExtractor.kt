package com.xnotes.platform

import android.content.Context
import android.graphics.Color
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import com.xnotes.core.model.NormalizedRect
import com.xnotes.core.model.QuestionLayoutDetector
import java.io.Closeable
import java.io.File
import java.io.StringWriter
import kotlin.math.roundToInt

/** Job-owned document: open once, process sequentially on a worker, close on cancellation. */
interface QuestionPageReader : Closeable {
    fun page(index: Int): PdfTextExtractor.PageData
}

class PdfTextExtractor(context: Context, file: File) : QuestionPageReader {
    private val document: PDDocument
    private val source: PdfSource
    init {
        PDFBoxResourceLoader.init(context.applicationContext)
        document = PDDocument.load(file, MemoryUsageSetting.setupMixed(32L * 1024 * 1024).setTempDir(context.cacheDir))
        source = try { PdfSource.create(context, file) ?: error("Cannot open PDF") }
        catch (e: Exception) { document.close(); throw e }
    }
    data class PageData(val runs: List<QuestionLayoutDetector.TextRun>, val layout: QuestionLayoutDetector.Layout)
    override fun page(index: Int): PageData {
        require(index in 0 until source.pageCount)
        val size = source.pageSizePoints(index) ?: error("Invalid PDF page")
        val runs = mutableListOf<QuestionLayoutDetector.TextRun>()
        val stripper = object : PDFTextStripper() {
            override fun writeString(text: String, positions: MutableList<TextPosition>) {
                // getX/getY account for page rotation; Y is the baseline, not the top edge.
                // PDFTextStripper has already translated the CropBox origin.
                for (p in positions) {
                    val l = (p.x.toDouble() / size.first).coerceIn(0.0, 1.0)
                    val t = ((p.y - p.height).toDouble() / size.second).coerceIn(0.0, 1.0)
                    val r = ((p.x + p.width).toDouble() / size.first).coerceIn(0.0, 1.0)
                    val b = (p.y.toDouble() / size.second).coerceIn(0.0, 1.0)
                    if (r > l && b > t && p.unicode.isNotBlank()) runs += QuestionLayoutDetector.TextRun(p.unicode, NormalizedRect(l, t, r, b))
                }
            }
        }
        stripper.sortByPosition = true
        stripper.startPage = index + 1
        stripper.endPage = index + 1
        stripper.writeText(document, StringWriter())
        val scale = 1400.0 / maxOf(size.first, size.second)
        val w = (size.first * scale).roundToInt().coerceAtLeast(1)
        val h = (size.second * scale).roundToInt().coerceAtLeast(1)
        val bitmap = source.renderPage(index, w, h)?.bitmap ?: error("Could not render layout")
        val ink = BooleanArray(w * h)
        try {
            val row = IntArray(w)
            for (y in 0 until h) {
                bitmap.getPixels(row, 0, w, 0, y, w, 1)
                for (x in 0 until w) ink[y * w + x] = minOf(Color.red(row[x]), Color.green(row[x]), Color.blue(row[x])) < 210
            }
        } finally { bitmap.recycle() }
        return PageData(runs, QuestionLayoutDetector.Layout(w, h, ink))
    }
    override fun close() { try { document.close() } finally { source.close() } }
}
