package com.xnotes.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.ZoomPanTransform
import com.xnotes.core.model.Question
import com.xnotes.platform.QuestionPdfRenderer
import com.xnotes.platform.QuestionRenderPlan
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

private data class QuestionFrame(val plan: QuestionRenderPlan, val bitmap: Bitmap)

/** Fit preview + one sharp visible region. No bitmap cache survives a question/viewport change. */
@Composable
fun ZoomableQuestion(renderer: QuestionPdfRenderer, question: Question, pageSize: Pair<Int, Int>,
                     widthPx: Int, heightPx: Int, annotation: AnswerCanvasController? = null) {
    val fitted = remember(question, pageSize, widthPx, heightPx) {
        ZoomPanTransform((question.crop.right - question.crop.left) * pageSize.first,
            (question.crop.bottom - question.crop.top) * pageSize.second, widthPx.toDouble(), heightPx.toDouble())
    }
    var transform by remember(fitted, question) { mutableStateOf(fitted) }
    var preview by remember(fitted, question) { mutableStateOf<QuestionFrame?>(null) }
    var sharp by remember(fitted, question) { mutableStateOf<QuestionFrame?>(null) }
    var previewError by remember(fitted, question) { mutableStateOf<String?>(null) }
    var sharpError by remember(fitted, question) { mutableStateOf<String?>(null) }
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
    val viewport = remember(annotation, fitted) {
        annotation?.let { QuestionInkViewport(it, { transform }, { transform = it }) }
    }
    DisposableEffect(viewport) {
        if (viewport != null && annotation != null) {
            annotation.finishInput()
            annotation.viewportTouch = viewport::touch
            annotation.controller.panOverride = viewport::pan
            annotation.view.afterLayout = { viewport.apply(transform) }
            viewport.apply(transform)
        }
        onDispose {
            annotation?.finishInput()
            annotation?.viewportTouch = null
            annotation?.controller?.panOverride = null
            annotation?.view?.afterLayout = null
        }
    }

    suspend fun render(view: ZoomPanTransform): QuestionFrame {
        val plan = QuestionRenderPlan.create(question.sourcePageIndex, pageSize.first, pageSize.second, question.crop, view)
        return QuestionFrame(plan, renderer.render(plan))
    }
    LaunchedEffect(renderer, question, fitted) {
        try { preview = render(fitted) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { previewError = "Could not render this question. Try reopening Question Mode." }
        catch (_: OutOfMemoryError) { previewError = "Not enough memory to render this question" }
    }
    LaunchedEffect(renderer, question, fitted) {
        snapshotFlow { transform }.collectLatest { view ->
            if (view.zoom == 1.0) { sharp = null; sharpError = null; return@collectLatest }
            // Transform existing frames immediately; refresh the PDF after gesture motion settles
            // briefly. Cancellation prevents old zoom/pan requests from publishing late.
            delay(80)
            try { sharp = render(view); sharpError = null }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { sharpError = "Could not sharpen this view. Pinch or pan to retry." }
            catch (_: OutOfMemoryError) { sharpError = "Not enough memory to sharpen this view" }
        }
    }
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize().clipToBounds().then(if (annotation != null) Modifier else Modifier
            .pointerInput(fitted, question) {
                detectTransformGestures(panZoomLock = true) { centroid, pan, zoom, _ ->
                    transform = transform.gesture(Pt(centroid.x.toDouble(), centroid.y.toDouble()),
                        Pt(pan.x.toDouble(), pan.y.toDouble()), zoom.toDouble())
                }
            }
            .pointerInput(fitted, question) { detectTapGestures(onDoubleTap = { transform = transform.reset() }) })
        ) {
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                native.save()
                native.translate(transform.left.toFloat(), transform.top.toFloat())
                native.scale(transform.scale.toFloat(), transform.scale.toFloat())
                native.clipRect(0f, 0f, fitted.contentWidth.toFloat(), fitted.contentHeight.toFloat())
                native.drawColor(android.graphics.Color.WHITE)
                fun draw(frame: QuestionFrame?) {
                    frame ?: return
                    val r = frame.plan.contentRect
                    native.drawBitmap(frame.bitmap, null, RectF(r.left.toFloat(), r.top.toFloat(),
                        r.right.toFloat(), r.bottom.toFloat()), paint)
                }
                draw(preview)
                draw(sharp)
                native.restore()
            }
        }
        if (annotation != null) {
            androidx.compose.ui.viewinterop.AndroidView(factory = {
                (annotation.surfaces.parent as? android.view.ViewGroup)?.removeView(annotation.surfaces)
                annotation.surfaces
            }, modifier = Modifier.fillMaxSize(), update = { annotation.view.requestRender() })
        }
        val error = if (preview == null && sharp == null) previewError ?: sharpError else sharpError
        if (preview == null && sharp == null && error == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
        error?.let { Text(it, Modifier.align(Alignment.BottomCenter).padding(12.dp), color = LocalPalette.current.text.toComposeColor()) }
    }
}
