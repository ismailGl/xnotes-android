package com.xnotes.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.xnotes.platform.QuestionProgress
import com.xnotes.platform.QuestionProgressRepository
import com.xnotes.platform.QuestionResult
import com.xnotes.platform.QuestionSetRepository
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/** The page sidebar's thumbnail source, cropped to each saved question rectangle. */
@Composable
fun QuestionSidebar(editor: Editor, set: QuestionSetRepository.LoadedSet, session: QuestionSession? = null,
    dismissOnSelect: Boolean = false) {
    val palette = LocalPalette.current
    val shownSession = session ?: editor.sibling?.questionSession?.takeIf {
        editor.currentUri == editor.sibling?.currentUri && it.set.id == set.id
    }
    val saved by produceState(QuestionProgress(), set.id, editor.questionRevision, shownSession) {
        if (shownSession == null) value = runCatching { QuestionProgressRepository(editor.questionFiles(), set.id).load() }.getOrDefault(QuestionProgress())
    }
    LazyColumn(Modifier.width(224.dp).fillMaxHeight().background(palette.panel.toComposeColor()).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(set.entries, key = { index, entry -> entry.question?.id ?: "invalid-$index" }) { index, entry ->
            val q = entry.question ?: return@itemsIndexed
            val page = editor.state.document.pages.firstOrNull { it.pdfPage == q.sourcePageIndex }
            val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, page, editor.contentVersion, q.crop) {
                if (page != null) value = editor.pageThumbnail(page, 300)
            }
            val active = shownSession?.current?.question?.id == q.id ||
                (shownSession == null && (editor.selectedQuestionOverlayId ?: saved.lastQuestionId) == q.id)
            val choice = shownSession?.choiceFor(q.id) ?: saved.choices[q.id]
            val result = shownSession?.visibleResultFor(q.id) ?: if (saved.showsResult(q.id,
                set.entries.mapNotNull { it.question }.filter { it.sourcePageIndex == q.sourcePageIndex }.map { it.id })) saved.resultFor(q.id) else null
            val tint = when (result) {
                QuestionResult.CORRECT -> Color(0xFF16813C)
                QuestionResult.INCORRECT -> Color(0xFFCE2828)
                else -> if (active) palette.accent.toComposeColor() else palette.border.toComposeColor()
            }
            Row(Modifier.fillMaxWidth().border(if (active) 2.dp else 1.dp, tint)
                .clickable {
                    if (shownSession != null) {
                        if (session == null && editor.pane == Pane.SECONDARY) editor.sibling?.focusPane(Pane.PRIMARY)
                        shownSession.jumpTo(q.id)
                        if (dismissOnSelect) shownSession.sidebarVisible = false
                    } else editor.selectQuestionFromPage(q.id)
                }
                .padding(5.dp), verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(72.dp, 58.dp).background(Color.White)) {
                    bitmap?.let { image ->
                        val left = (q.crop.left * image.width).toInt().coerceIn(0, image.width - 1)
                        val top = (q.crop.top * image.height).toInt().coerceIn(0, image.height - 1)
                        val right = (q.crop.right * image.width).toInt().coerceIn(left + 1, image.width)
                        val bottom = (q.crop.bottom * image.height).toInt().coerceIn(top + 1, image.height)
                        drawImage(image, srcOffset = IntOffset(left, top), srcSize = IntSize(right - left, bottom - top),
                            dstSize = IntSize(size.width.toInt(), size.height.toInt()))
                    }
                }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Question ${index + 1}", color = palette.text.toComposeColor())
                    Text(when (result) {
                        QuestionResult.CORRECT -> "✓ Correct"
                        QuestionResult.INCORRECT -> "✕ Incorrect"
                        else -> if (choice == null) "Unanswered" else "Answered: $choice"
                    }, color = tint)
                }
            }
        }
    }
}
