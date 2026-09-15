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
    /** Opt-in local diagnostics; source material stays in device cache/build outputs. */
    @Test fun exportAnchorReplayInputs() = runBlocking {
        val args=InstrumentationRegistry.getArguments()
        val relative=args.getString("anchorPdf")
        org.junit.Assume.assumeTrue(relative != null)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.filesDir,requireNotNull(relative)).canonicalFile
        require(file.path.startsWith(context.filesDir.canonicalPath+File.separator))
        val folder=File(context.cacheDir,"anchor-replay").apply { mkdirs() }
        PdfTextExtractor(context,file).use { reader ->
            for (number in (args.getString("anchorPages") ?: "12,14,16,20,22,24,25,28,30").split(",").map { it.toInt() }) {
                val data=reader.page(number-1)
                val json=org.json.JSONObject().put("page",number-1).put("width",data.layout.width).put("height",data.layout.height)
                    .put("ink",android.util.Base64.encodeToString(ByteArray(data.layout.ink.size) { if(data.layout.ink[it]) 1 else 0 },android.util.Base64.NO_WRAP))
                    .put("runs",org.json.JSONArray().apply { data.runs.forEach { run ->
                        put(org.json.JSONArray(listOf(run.text,run.box.left,run.box.top,run.box.right,run.box.bottom)))
                    } })
                java.util.zip.GZIPOutputStream(File(folder,"page-$number.json.gz").outputStream()).use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
                val d=QuestionLayoutDetector.analyze(number-1,data.runs,data.layout)
                android.util.Log.i("QuestionAnchors","Page $number columns=${d.columnCount} proposals=${d.proposals.size} anchors=${d.anchors}")
            }
        }
    }
    /** Optional real-document replay; supplied by runner args, never a bundled private PDF. */
    @Test fun suppliedPageHasStrictColumnBounds() = runBlocking {
        val args=InstrumentationRegistry.getArguments()
        val relative=args.getString("boundaryPdf")
        org.junit.Assume.assumeTrue("Supply boundaryPdf to replay a cached PDF",relative != null)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.filesDir,requireNotNull(relative)).canonicalFile
        require(file.path.startsWith(context.filesDir.canonicalPath+File.separator))
        val page=(args.getString("boundaryPage") ?: "13").toInt()-1
        PdfTextExtractor(context,file).use { reader ->
            val data=reader.page(page)
            val d=QuestionLayoutDetector.analyze(page,data.runs,data.layout)
            android.util.Log.i("QuestionBoundary", "Page ${page+1} source=${data.textSource} gutter=${d.gutter} bounds=${d.columnBounds}")
            d.proposals.forEach { android.util.Log.i("QuestionBoundary", "${it.id} crop=${it.crop}") }
            assertEquals(2,d.columnCount)
            assertTrue(d.proposals.isNotEmpty())
            d.proposals.forEach { proposal ->
                val column=proposal.id.split("-")[1].toInt()
                val bounds=d.columnBounds[column]
                assertTrue(proposal.crop.left >= bounds.left)
                assertTrue(proposal.crop.right <= bounds.right)
            }
            assertTrue(d.columnBounds[0].right < d.columnBounds[1].left)
        }
    }
    @Test fun scannedTurkishColumnsAndTextBackedPageUseSharedDetector() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val permissions = context.packageManager.getPackageInfo(context.packageName,
            android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse(permissions.contains("android.permission.INTERNET"))
        assertFalse(permissions.contains("android.permission.ACCESS_NETWORK_STATE"))
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
                    val phases = mutableListOf<String>()
                    val data = reader.page(page) { phases += it }
                    assertEquals(if (page == 0) "OCR" else "PDF text", data.textSource.label)
                    assertEquals(listOf("Reading PDF text", "Analyzing page layout") +
                        if (page == 0) listOf("Rendering for OCR", "Recognizing text on device") else emptyList<String>(), phases)
                    assertTrue(data.runs.isNotEmpty())
                    assertTrue(data.runs.all { it.box.left >= 0 && it.box.top >= 0 &&
                        it.box.right <= 1 && it.box.bottom <= 1 &&
                        it.box.right > it.box.left && it.box.bottom > it.box.top })
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
