package com.xnotes.platform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.platform.app.InstrumentationRegistry
import com.xnotes.core.model.QuestionLayoutDetector
import com.xnotes.core.model.QuestionTextSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Uses the installed bundled model and real PDF rendering, not mocked OCR text. */
class QuestionOcrDeviceTest {
    @Test fun scannedTurkishColumnsAndTextBackedPageUseSharedDetector() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("turkish-ocr", ".pdf", context.cacheDir)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 18f }
        fun draw(canvas: Canvas) {
            canvas.drawColor(Color.WHITE)
            for ((x, number) in listOf(35f to 1, 335f to 3)) {
                for (row in 0..1) {
                    val y = 90f + row * 350f
                    canvas.drawText("${number + row}. Aşağıdaki soruyu çözünüz.", x, y, paint)
                    canvas.drawText("Türkiye, ışık, ölçü ve değişim.", x, y + 30, paint)
                    canvas.drawText("Şekli açıklayınız ve gösteriniz.", x, y + 60, paint)
                    if (row == 0) canvas.drawText("A) Üç   B) Beş   C) Yedi", x, y + 100, paint)
                    // Non-text diagram must remain inside the proposed crop.
                    paint.style = Paint.Style.STROKE
                    canvas.drawRect(x + 50, y + 130, x + 150, y + 210, paint)
                    paint.style = Paint.Style.FILL
                }
            }
        }
        try {
            val pdf = PdfDocument()
            try {
                val bitmap = Bitmap.createBitmap(1200, 1680, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(bitmap)
                    canvas.scale(2f, 2f)
                    draw(canvas)
                    val scan = pdf.startPage(PdfDocument.PageInfo.Builder(600,840,1).create())
                    scan.canvas.drawBitmap(bitmap, null, android.graphics.Rect(0,0,600,840), null)
                    pdf.finishPage(scan)
                } finally { bitmap.recycle() }
                val text = pdf.startPage(PdfDocument.PageInfo.Builder(600,840,2).create())
                draw(text.canvas)
                pdf.finishPage(text)
                file.outputStream().use { pdf.writeTo(it) }
            } finally { pdf.close() }
            PdfTextExtractor(context,file).use { reader ->
                for (page in 0..1) {
                    val data = reader.page(page)
                    assertEquals(if (page == 0) QuestionTextSource.OCR else QuestionTextSource.PDF_TEXT, data.textSource)
                    assertTrue(data.runs.any { it.text.contains("ç", ignoreCase=true) || it.text.contains("ü", ignoreCase=true) })
                    val proposals = QuestionLayoutDetector.detect(page,data.runs,data.layout)
                    assertEquals("Page $page anchors: ${data.runs.map { it.text }}",4,proposals.size)
                    assertTrue(proposals.take(2).all { it.crop.right in 0.45..0.57 })
                    assertTrue(proposals.drop(2).all { it.crop.left in 0.45..0.57 })
                    assertTrue(proposals.first().crop.bottom > 300.0 / 840)
                }
            }
        } finally { file.delete() }
    }
}
