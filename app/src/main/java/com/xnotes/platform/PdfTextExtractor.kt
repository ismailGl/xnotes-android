package com.xnotes.platform

import android.content.Context
import android.graphics.Color
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import com.xnotes.core.model.QuestionTextQuality
import com.xnotes.core.model.QuestionTextSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import com.xnotes.core.model.NormalizedRect
import com.xnotes.core.model.QuestionLayoutDetector
import java.io.Closeable
import java.io.File
import java.io.StringWriter
import kotlin.math.roundToInt

/** Job-owned document: open once, process sequentially on a worker, close on cancellation. */
interface QuestionPageReader : Closeable {
    suspend fun page(index: Int, progress: suspend (String) -> Unit = {}): PdfTextExtractor.PageData
}

class PdfTextExtractor(context: Context, file: File) : QuestionPageReader {
    private val document: PDDocument?
    private var ocr: MlKitQuestionTextReader? = null
    private val source: PdfSource
    init {
        PDFBoxResourceLoader.init(context.applicationContext)
        document = try { PDDocument.load(file, MemoryUsageSetting.setupMixed(32L * 1024 * 1024).setTempDir(context.cacheDir)) }
        catch (_: Exception) { null } // A broken text parser must not block raster/OCR.
        source = try { PdfSource.create(context, file) ?: error("Cannot open PDF") }
        catch (e: Exception) { document?.close(); throw e }
    }
    data class PageData(val runs: List<QuestionLayoutDetector.TextRun>, val layout: QuestionLayoutDetector.Layout,
                        val textSource: QuestionTextSource = QuestionTextSource.PDF_TEXT)
    override suspend fun page(index: Int, progress: suspend (String) -> Unit): PageData {
        val job = currentCoroutineContext()
        job.ensureActive()
        require(index in 0 until source.pageCount)
        progress("Reading PDF text")
        val size = source.pageSizePoints(index) ?: error("Invalid PDF page")
        val runs = mutableListOf<QuestionLayoutDetector.TextRun>()
        val stripper = object : PDFTextStripper() {
            override fun writeString(text: String, positions: MutableList<TextPosition>) {
                // getX/getY account for page rotation; Y is the baseline, not the top edge.
                // PDFTextStripper has already translated the CropBox origin.
                job.ensureActive()
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
        try {
            if (document != null) stripper.writeText(document, StringWriter())
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { runs.clear() }
        job.ensureActive()
        progress("Analyzing page layout")
        val scale = 1400.0 / maxOf(size.first, size.second)
        val w = (size.first * scale).roundToInt().coerceAtLeast(1)
        val h = (size.second * scale).roundToInt().coerceAtLeast(1)
        val bitmap = source.renderPage(index, w, h)?.bitmap ?: error("Could not render layout")
        val ink = BooleanArray(w * h)
        try {
            val row = IntArray(w)
            for (y in 0 until h) {
                job.ensureActive()
                bitmap.getPixels(row, 0, w, 0, y, w, 1)
                for (x in 0 until w) ink[y * w + x] = minOf(Color.red(row[x]), Color.green(row[x]), Color.blue(row[x])) < 210
            }
        } finally { bitmap.recycle() }
        val layout = QuestionLayoutDetector.Layout(w, h, ink)
        if (QuestionTextQuality.usable(runs)) return PageData(runs, layout)
        job.ensureActive()
        // Approximately 300 dpi for A4, bounded to 3500 on the long edge (~35 MB).
        // The layout bitmap is already recycled; only this page is held during inference.
        progress("Rendering for OCR")
        val ocrScale = minOf(300.0 / 72.0, 3500.0 / maxOf(size.first, size.second))
        val ocrBitmap = source.renderPage(index, (size.first * ocrScale).roundToInt().coerceAtLeast(1),
            (size.second * ocrScale).roundToInt().coerceAtLeast(1))?.bitmap ?: error("Could not render OCR page")
        try {
            job.ensureActive()
            progress("Recognizing text on device")
            val reader = ocr ?: MlKitQuestionTextReader().also { ocr = it }
            return PageData(reader.read(ocrBitmap), layout, QuestionTextSource.OCR)
        } finally { ocrBitmap.recycle() }
    }
    override fun close() { try { ocr?.close() } finally { try { document?.close() } finally { source.close() } } }
}
