package com.xnotes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
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
import com.xnotes.platform.QuestionPdfRenderer
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation

/** Read-only question review; no answer state or notebook editor is created. */
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
    Column(Modifier.fillMaxSize().background(palette.bg.toComposeColor())
        .focusRequester(focus).focusable().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back to notebook") }
            Text("Question ${if (session.count == 0) 0 else session.index + 1} / ${session.count}",
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp), color = palette.text.toComposeColor())
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
            val heightPx = with(LocalDensity.current) { maxHeight.roundToPx() }
            key(session.index, widthPx, heightPx) {
                val entry = session.current
                when {
                    entry == null -> ViewerMessage("No questions saved for this notebook")
                    entry.question == null -> ViewerMessage(entry.error ?: "Invalid question metadata")
                    widthPx > 0 && heightPx > 0 -> QuestionBody(renderer, entry.question, widthPx, heightPx)
                }
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = session::previous, enabled = session.canPrevious) { Text("◀ Previous") }
            Spacer(Modifier.width(32.dp))
            TextButton(onClick = session::next, enabled = session.canNext) { Text("Next ▶") }
        }
    }
}

@Composable
private fun QuestionBody(renderer: QuestionPdfRenderer, question: com.xnotes.core.model.Question, widthPx: Int, heightPx: Int) {
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
        else -> ZoomableQuestion(renderer, question, current, widthPx, heightPx)
    }
}

@Composable
private fun ViewerMessage(message: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(message, color = LocalPalette.current.text.toComposeColor())
    }
}
