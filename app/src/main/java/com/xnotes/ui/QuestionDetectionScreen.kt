package com.xnotes.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.ZoomPanTransform
import com.xnotes.core.model.*
import com.xnotes.platform.*
import java.io.File
import kotlinx.coroutines.*
import kotlin.math.abs

@Composable
fun QuestionDetectionScreen(editor: Editor) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val doc = remember { editor.state.document }
    val sourceFile = remember { doc.pdfFile!! }
    val sourceUri = remember { doc.path!! }
    val pages = remember { doc.pages.mapNotNull { it.pdfPage }.groupingBy { it }.eachCount().filterValues { it == 1 }.keys.sorted() }
    val aiConfig = remember { GeminiVerifierConfig(com.xnotes.BuildConfig.GEMINI_API_KEY, com.xnotes.BuildConfig.GEMINI_MODEL) }
    val aiRenderer = remember { VerifierPageRenderer(context.applicationContext, sourceFile) }
    val session = remember { QuestionDetectionSession(sourceFile, sourceUri, doc.title, pages,
        QuestionSetRepository(File(context.filesDir, "questions")), scope,
        { editor.state.document === doc && doc.pdfFile == sourceFile && doc.path == sourceUri &&
            doc.pages.mapNotNull { it.pdfPage }.groupingBy { it }.eachCount().filterValues { it == 1 }.keys.sorted() == pages },
        { PdfTextExtractor(context, sourceFile) },
        verifier = if (com.xnotes.BuildConfig.DEBUG && aiConfig.available) GeminiQuestionCropVerifier(aiConfig) else null,
        verifierVersion = aiConfig.version, verifierPage = aiRenderer::page) }
    var from by remember { mutableStateOf(((doc.pages.getOrNull(editor.state.currentPageIndex())?.pdfPage ?: pages.firstOrNull() ?: 0) + 1).toString()) }
    var to by remember { mutableStateOf(from) }
    var pageSlot by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<String?>(null) }
    var showAiDiagnostic by remember { mutableStateOf(false) }
    var debugLayout by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf(false) }
    var add by remember { mutableStateOf(false) }
    val dismiss = { if (!session.saving) { session.cancel(); editor.closeQuestionDetection() } }
    DisposableEffect(session) { onDispose { session.cancel() } }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false,
        dismissOnBackPress = !session.saving, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(8.dp)) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    Text("Auto Detect Questions", Modifier.padding(end = 12.dp))
                    OutlinedTextField(from, { from = it }, label = { Text("PDF page from") }, modifier = Modifier.width(130.dp), singleLine = true)
                    OutlinedTextField(to, { to = it }, label = { Text("To") }, modifier = Modifier.width(90.dp), singleLine = true)
                    TextButton(enabled = !session.busy && !session.saving, onClick = {
                        pageSlot = 0; selected = null
                        session.scan(from.toIntOrNull()?.let { listOf(it - 1) } ?: emptyList())
                    }) { Text("One page") }
                    TextButton(enabled = !session.busy && !session.saving, onClick = {
                        pageSlot = 0; selected = null
                        val a = from.toIntOrNull(); val b = to.toIntOrNull()
                        session.scan(if (a != null && b != null && a > 0 && b >= a && b <= (pages.lastOrNull() ?: -1) + 1)
                            pages.filter { it in (a - 1)..(b - 1) } else emptyList())
                    }) { Text("Range") }
                    TextButton(enabled = !session.busy && !session.saving, onClick = { pageSlot = 0; selected = null; session.scan(pages) }) { Text("All PDF pages") }
                    TextButton(onClick = dismiss, enabled = !session.saving) { Text("Cancel") }
                }
                Text(session.status)
                if (pages.size != doc.pages.count { it.pdfPage != null }) Text("Pages with ambiguous PDF mappings are excluded; each source page must match one notebook page.")
                if (session.busy || session.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                val page = session.pages.getOrNull(pageSlot)
                LaunchedEffect(page) { session.review(page) }
                LaunchedEffect(page, edit) { if (edit && page != null) session.beginManualReview(page) }
                val proposal = session.proposals.firstOrNull { it.id == selected }
                Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { pageSlot--; selected = null }, enabled = pageSlot > 0 && !session.saving) { Text("◀ Page") }
                    Text(if (page == null) "No preview" else "PDF page ${page + 1}" +
                        (session.pageSources[page]?.let { " · ${it.label}" } ?: ""))
                    TextButton(onClick = { pageSlot++; selected = null }, enabled = pageSlot + 1 < session.pages.size && !session.saving) { Text("Page ▶") }
                    TextButton(onClick = { edit = !edit; add = false; if (edit) page?.let(session::beginManualReview) }, enabled = !session.busy && !session.saving) { Text(if (edit) "Edit rectangles ✓" else "Pan / zoom ✓ · Edit") }
                    TextButton(onClick = { edit = true; add = true; selected = null; page?.let(session::beginManualReview) }, enabled = page != null && !session.busy && !session.saving) { Text(if (add) "Draw a rectangle…" else "Add rectangle") }
                    TextButton(onClick = { selected?.let { session.accept(it, true) } }, enabled = proposal != null && !session.busy && !session.saving) { Text("Accept") }
                    TextButton(onClick = { selected?.let { session.accept(it, false) } }, enabled = proposal != null && !session.busy && !session.saving) { Text("Reject") }
                    TextButton(onClick = session::acceptAll, enabled = !session.busy && !session.saving) { Text("Accept All") }
                    Button(onClick = { session.commit { message -> editor.message = message; editor.questionRevision++; editor.closeQuestionDetection() } },
                        enabled = session.proposals.any { it.accepted } && !session.busy && !session.saving) { Text("Add accepted (${session.proposals.count { it.accepted }})") }
                }
                if (com.xnotes.BuildConfig.DEBUG) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = session.aiEnabled, onCheckedChange = session::enableAi,
                            enabled = session.aiAvailable && !session.saving && !session.busy)
                        Text(if (session.aiAvailable) "AI verify · sends page images to Google (up to 10 pages ahead)"
                            else "AI unavailable · configure GEMINI_API_KEY in local development settings")
                        TextButton(onClick = { edit = false; add = false; selected = null; page?.let(session::verifyPage) },
                            enabled = page != null && session.aiEnabled && !session.busy && !session.saving) { Text("Verify page") }
                        TextButton(onClick = { page?.let(session::revertAi); selected = null },
                            enabled = page != null && !session.busy && !session.saving && session.aiStatuses.containsKey(page)) { Text("Restore detector crops") }
                    }
                    if (session.aiEnabled) Text(session.aiStatuses[page] ?: "AI ready · waiting for completed scan")
                    session.aiDiagnostics[page]?.let { diagnostic ->
                        TextButton(onClick = { showAiDiagnostic = !showAiDiagnostic }) {
                            Text(if (showAiDiagnostic) "Hide AI response diagnostic" else "Show AI response diagnostic")
                        }
                        if (showAiDiagnostic) SelectionContainer {
                            Text(diagnostic, Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()))
                        }
                    }
                }
                TextButton(onClick = { debugLayout = !debugLayout }) { Text(if (debugLayout) "Hide layout diagnostics" else "Layout diagnostics") }
                if (debugLayout) session.pageDiagnostics[page]?.let { d ->
                    SelectionContainer {
                        Text(buildString {
                            appendLine("Page ${d.page+1}: ${d.columnCount} columns; gutter=${d.gutter}")
                            d.regions.forEach { appendLine("Region ${it.role}: ${it.box}; confidence=${it.confidence}: ${it.reason}") }
                            d.columnBounds.forEachIndexed { index, bounds -> appendLine("Column ${index+1} allowed x: ${bounds.left}..${bounds.right}") }
                            d.anchors.forEach { appendLine("${it.source} '${it.text}' x=${it.box.left} y=${it.box.top} -> column ${it.column?.plus(1)} confidence=${it.confidence}: ${it.reason}") }
                            d.proposals.forEach { appendLine("Proposal ${it.id}: ${it.crop}") }
                        }, Modifier.heightIn(max=140.dp).verticalScroll(rememberScrollState()))
                    }
                }
                Text(proposal?.let { (if (it.accepted) "Accepted · " else "Not accepted · ") + (if (it.likely) "Likely" else "Review needed") +
                    if (session.duplicate(it.sourcePageIndex, it.crop)) " · Existing duplicate: will be skipped"
                    else if (session.possibleDuplicate(it.sourcePageIndex,it.crop)) " · Possible existing duplicate: review" else "" } ?: "Select a rectangle. Edit mode: drag inside to move, or a corner to resize.")
                proposal?.reasons?.takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(" · "), maxLines = 2) }
                session.errors.filter { page != null && it.startsWith("Page ${page + 1}:") }.forEach { Text(it, maxLines = 2) }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (page != null) key(page) {
                        DetectionPreview(session, page, selected, edit && !session.busy && !session.saving, add,
                            { selected = it }, { selected = it; add = false })
                    }
                }
            }
        }
    }
}

private data class DetectionFrame(val plan: QuestionRenderPlan, val bitmap: Bitmap)

@Composable
private fun DetectionPreview(session: QuestionDetectionSession, page: Int, selected: String?, edit: Boolean, add: Boolean,
                             select: (String?) -> Unit, added: (String?) -> Unit) {
    val context = LocalContext.current
    val renderer = remember { QuestionPdfRenderer(context, session.pdf) }
    val question = remember(page) { Question("preview", page, NormalizedRect(0.0, 0.0, 1.0, 1.0)) }
    var dimensions by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(renderer) {
        try { dimensions = renderer.pageSize(question); awaitCancellation() }
        catch (e: CancellationException) { throw e }
        catch (_: OutOfMemoryError) { error = "Not enough memory to preview this page" }
        catch (_: Exception) { error = "Could not preview this PDF page" }
        finally { renderer.close() }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val size = dimensions
        val w = with(LocalDensity.current) { maxWidth.toPx().toDouble() }
        val h = with(LocalDensity.current) { maxHeight.toPx().toDouble() }
        if (size != null && w > 0 && h > 0) key(size, w, h) {
            var transform by remember { mutableStateOf(ZoomPanTransform(size.first.toDouble(), size.second.toDouble(), w, h)) }
            var frame by remember { mutableStateOf<DetectionFrame?>(null) }
            var draft by remember { mutableStateOf<NormalizedRect?>(null) }
            val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
            val label = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLUE; textSize = 28f } }
            LaunchedEffect(transform) {
                delay(70)
                try {
                    val plan = QuestionRenderPlan.create(page, size.first, size.second, question.crop, transform)
                    frame = DetectionFrame(plan, renderer.render(plan)); error = null
                } catch (e: CancellationException) { throw e }
                catch (_: OutOfMemoryError) { error = "Not enough memory to render this preview" }
                catch (_: Exception) { error = "Could not render preview; pan or zoom to retry" }
            }
            val latestSelected by rememberUpdatedState(selected)
            fun point(at: Offset) = Pt(((at.x - transform.left) / transform.scale / size.first).coerceIn(0.0, 1.0),
                ((at.y - transform.top) / transform.scale / size.second).coerceIn(0.0, 1.0))
            fun hit(at: Pt) = session.proposals.lastOrNull { it.sourcePageIndex == page && at.x in it.crop.left..it.crop.right && at.y in it.crop.top..it.crop.bottom }
            Canvas(Modifier.fillMaxSize().clipToBounds()
                .pointerInput(edit, add) {
                    detectTapGestures(onDoubleTap = { transform = transform.reset() }, onTap = { select(hit(point(it))?.id) })
                }.pointerInput(edit, add) {
                    if (!edit) detectTransformGestures { center, pan, zoom, _ -> transform = transform.gesture(Pt(center.x.toDouble(), center.y.toDouble()), Pt(pan.x.toDouble(), pan.y.toDouble()), zoom.toDouble()) }
                    else {
                        var start = Pt(0.0, 0.0); var original: NormalizedRect? = null; var id: String? = null; var corner = -1
                        detectDragGestures(onDragStart = { offset ->
                            start = point(offset)
                            val current = session.proposals.firstOrNull { it.id == latestSelected &&
                                start.x >= it.crop.left - 32/(size.first*transform.scale) && start.x <= it.crop.right + 32/(size.first*transform.scale) &&
                                start.y >= it.crop.top - 32/(size.second*transform.scale) && start.y <= it.crop.bottom + 32/(size.second*transform.scale) } ?: hit(start)
                            id = if (add) null else current?.id; original = if (add) null else current?.crop
                            select(id)
                            corner = original?.let { r -> listOf(Pt(r.left,r.top), Pt(r.right,r.top), Pt(r.right,r.bottom), Pt(r.left,r.bottom)).indexOfFirst {
                                abs(it.x-start.x)*size.first*transform.scale < 32 && abs(it.y-start.y)*size.second*transform.scale < 32
                            } } ?: -1
                        }, onDragCancel = { draft = null }, onDragEnd = {
                            draft?.let { r -> if (add) added(session.add(page, r)) else id?.let { session.edit(it, r) } }; draft = null
                        }) { change, _ ->
                            change.consume(); val at = point(change.position); val r = original
                            draft = if (add) ProposalGeometry.between(start.x,start.y,at.x,at.y)
                            else if (r == null) null else if (corner >= 0) ProposalGeometry.resize(r,corner,at.x,at.y)
                            else ProposalGeometry.move(r,at.x-start.x,at.y-start.y)
                        }
                    }
                }) {
                drawRect(Color.White)
                drawIntoCanvas { canvas -> frame?.let { f ->
                    val r=f.plan.contentRect
                    canvas.nativeCanvas.drawBitmap(f.bitmap, null, RectF((transform.left+r.left*transform.scale).toFloat(),
                        (transform.top+r.top*transform.scale).toFloat(),(transform.left+r.right*transform.scale).toFloat(),
                        (transform.top+r.bottom*transform.scale).toFloat()),paint)
                } }
                val items=session.proposals.filter { it.sourcePageIndex == page }
                (items.map { it.crop to it } + listOfNotNull(draft?.let { it to null })).forEach { (r,p) ->
                    val x=(transform.left+r.left*size.first*transform.scale).toFloat(); val y=(transform.top+r.top*size.second*transform.scale).toFloat()
                    val rw=((r.right-r.left)*size.first*transform.scale).toFloat(); val rh=((r.bottom-r.top)*size.second*transform.scale).toFloat()
                    drawRect(if (p?.id == selected || p == null) Color.Blue else if(p.accepted) Color(0xFF16813C) else Color(0xFFCE6500), Offset(x,y), androidx.compose.ui.geometry.Size(rw,rh), style=Stroke(3f))
                    if(p?.id == selected) listOf(Offset(x,y),Offset(x+rw,y),Offset(x+rw,y+rh),Offset(x,y+rh)).forEach { drawCircle(Color.Blue,8f,it) }
                    if(p != null) drawIntoCanvas { it.nativeCanvas.drawText("${items.indexOf(p)+1}",x+6,y+28,label) }
                }
            }
        }
        error?.let { Text(it, Modifier.align(Alignment.Center)) }
    }
}
