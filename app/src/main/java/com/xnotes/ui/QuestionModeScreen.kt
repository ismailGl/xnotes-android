package com.xnotes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import com.xnotes.platform.QuestionPdfRenderer
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation

/** Question preview over an independently owned paged answer canvas. */
@Composable
fun QuestionModeScreen(session: QuestionSession, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val palette = LocalPalette.current
    val renderer = remember(session) { QuestionPdfRenderer(context, session.sourcePdf) }
    val focus = remember(session) { FocusRequester() }
    LaunchedEffect(renderer) {
        runCatching { focus.requestFocus() }
        try { awaitCancellation() } finally { renderer.close() }
    }
    var contentHeight by remember { mutableIntStateOf(1) }
    Column(Modifier.fillMaxSize().background(palette.bg.toComposeColor())
        .focusRequester(focus).focusable().onSizeChanged { contentHeight = it.height.coerceAtLeast(1) }) {
        BoxWithConstraints(Modifier.weight(if (session.answers == null) 1f else session.split).fillMaxWidth()) {
            val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
            val heightPx = with(LocalDensity.current) { maxHeight.roundToPx() }
            key(session.index, widthPx, heightPx) {
                val entry = session.current
                when {
                    entry == null -> ViewerMessage("No questions saved for this notebook")
                    entry.question == null -> ViewerMessage(entry.error ?: "Invalid question metadata")
                    widthPx > 0 && heightPx > 0 -> QuestionBody(renderer, entry.question, widthPx, heightPx,
                        session.annotations?.surface as? AnswerCanvasController)
                }
            }
        }
        session.annotations?.let { annotations ->
            annotations.error?.let { error ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Question ink: $error", Modifier.weight(1f), color = palette.text.toComposeColor())
                    TextButton(onClick = annotations::retry, enabled = !session.busy) { Text("Retry") }
                }
            }
        }
        session.answers?.let { answers ->
            Box(Modifier.fillMaxWidth().height(12.dp).background(palette.panel.toComposeColor())
                .pointerInput(session, contentHeight) {
                    detectVerticalDragGestures(onDragStart = {
                        session.answers.surface?.finishInput()
                        session.annotations?.surface?.finishInput()
                    }) { change, dy ->
                        change.consume()
                        session.split = (session.split + dy / contentHeight).coerceIn(0.2f, 0.75f)
                    }
                }, contentAlignment = Alignment.Center) {
                Box(Modifier.width(48.dp).height(3.dp).background(palette.border.toComposeColor()))
            }
            AnswerCanvasPane(answers, Modifier.weight(1f - session.split).fillMaxWidth())
        }
        session.progressError?.let { error ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(error, Modifier.weight(1f), color = palette.text.toComposeColor())
                TextButton(onClick = session::retryProgress, enabled = !session.busy) { Text("Retry") }
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            com.xnotes.platform.QuestionProgressRepository.CHOICES.forEach { choice ->
                val chosen = session.selectedChoice == choice
                TextButton(onClick = { session.selectChoice(choice) },
                    enabled = !session.busy && session.current?.question != null,
                    modifier = Modifier.width(48.dp).semantics { selected = chosen },
                    contentPadding = PaddingValues(4.dp)) {
                    Text(if (chosen) "● $choice" else choice)
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = session::previous, enabled = session.canPrevious) { Text("◀ Previous") }
            Text("Question ${if (session.count == 0) 0 else session.index + 1} / ${session.count}",
                Modifier.padding(horizontal = 16.dp), color = palette.text.toComposeColor())
            TextButton(onClick = session::next, enabled = session.canNext) { Text("Next ▶") }
        }
    }
}

@Composable
private fun QuestionBody(renderer: QuestionPdfRenderer, question: com.xnotes.core.model.Question, widthPx: Int, heightPx: Int,
    annotation: AnswerCanvasController?) {
    var pageSize by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(renderer, question) {
        try { pageSize = renderer.pageSize(question) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Could not open this question" }
        catch (_: OutOfMemoryError) { error = "Not enough memory to open this question" }
    }
    val current = pageSize
    when {
        error != null -> ViewerMessage(error!!)
        current == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> key(annotation) { ZoomableQuestion(renderer, question, current, widthPx, heightPx, annotation) }
    }
}

@Composable
private fun ViewerMessage(message: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(message, color = LocalPalette.current.text.toComposeColor())
    }
}
