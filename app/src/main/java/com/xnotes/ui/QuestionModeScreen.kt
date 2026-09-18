package com.xnotes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xnotes.core.model.DetectedQuestion
import com.xnotes.core.tools.ToolbarItem
import com.xnotes.platform.QuestionAnswerOptions
import com.xnotes.platform.QuestionFeedback
import com.xnotes.platform.QuestionType

/** Question Mode adds dialogs to the existing EditorPane; it owns no drawing surface. */
@Composable
fun QuestionModeScreen(editor: Editor, session: QuestionSession) {
    session.progressError?.let { error ->
        AlertDialog(onDismissRequest = {}, title = { Text("Question save failed") }, text = { Text(error) },
            confirmButton = { TextButton(onClick = session::retryProgress) { Text("Retry") } })
    }
    if (session.current?.question == null) {
        AlertDialog(onDismissRequest = editor::closeQuestionMode, title = { Text("Question unavailable") },
            text = { Text(session.current?.error ?: "No questions saved") },
            confirmButton = { TextButton(onClick = session::next, enabled = session.canNext) { Text("Next") } },
            dismissButton = { TextButton(onClick = editor::closeQuestionMode) { Text("Back to notebook") } })
    }
    if (session.confirmDelete) AlertDialog(
        onDismissRequest = { if (!session.busy) session.confirmDelete = false },
        title = { Text("Delete question?") },
        text = { Text("Delete this question, its saved workspace and answer/progress? The original PDF stays unchanged.") },
        confirmButton = { TextButton(onClick = session::deleteCurrent, enabled = !session.busy) { Text("Delete question") } },
        dismissButton = { TextButton(onClick = { session.confirmDelete = false }, enabled = !session.busy) { Text("Cancel") } })
    val question = session.current?.question
    if (session.editingCrop && question != null) {
        var crop by remember(question) { mutableStateOf(question.crop) }
        var edit by remember(question) { mutableStateOf(true) }
        Dialog(onDismissRequest = { if (!session.savingCrop) session.editingCrop = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { session.editingCrop = false }, enabled = !session.savingCrop) { Text("Cancel") }
                        TextButton(onClick = { edit = !edit }) { Text(if (edit) "Pan / zoom" else "Move / resize crop") }
                        TextButton(onClick = { editor.saveCurrentQuestionCrop(crop) }, enabled = !session.busy) { Text("Save crop") }
                    }
                    Box(Modifier.weight(1f)) {
                        PdfCropReview(session.sourcePdf,
                            listOf(DetectedQuestion(question.id, question.sourcePageIndex, crop)),
                            question.sourcePageIndex, question.id, edit = edit,
                            edited = { _, updated -> crop = updated })
                    }
                }
            }
        }
    }
}

/** Ordinary toolbar items: visibility, ordering and grouping come from the xnote layout. */
@Composable
internal fun QuestionToolbarItem(editor: Editor, session: QuestionSession, item: ToolbarItem) {
    when (item) {
        ToolbarItem.QUESTION_NAV -> {
            TextButton(onClick = session::previous, enabled = session.canPrevious) { Text("Previous") }
            Text("Question ${if (session.count == 0) 0 else session.index + 1} / ${session.count}")
            if (session.index == session.count - 1 && session.count > 0) {
                TextButton(onClick = session::showPageReview, enabled = !session.busy) { Text("Review page") }
            } else TextButton(onClick = session::next, enabled = session.canNext) { Text("Next") }
        }
        ToolbarItem.QUESTION_PEEK -> TextButton(onClick = session::cyclePeek, enabled = !session.busy) {
            Text(when (session.peek) {
                QuestionPeek.FOCUSED -> "Page peek"
                QuestionPeek.FADED -> "Return to question"
            })
        }
        ToolbarItem.QUESTION_CROP -> TextButton(onClick = {
            editor.infinite.finishInput()
            session.editingCrop = true
        }, enabled = !session.busy && session.current?.question != null) { Text("Edit crop") }
        ToolbarItem.QUESTION_DELETE -> TextButton(onClick = { session.confirmDelete = true },
            enabled = !session.busy && session.current?.question != null) { Text("Delete") }
        ToolbarItem.QUESTION_ANSWER -> {
            var expanded by remember { mutableStateOf(false) }
            session.answerOptions.choices.forEach { choice ->
                FilterChip(selected = session.selectedChoice == choice,
                    onClick = { session.selectChoice(choice) }, label = { Text(choice) },
                    enabled = !session.busy, modifier = Modifier.padding(horizontal = 2.dp))
            }
            Box {
                TextButton(onClick = { expanded = true }, enabled = !session.busy) {
                    Text("Answer settings")
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(text = { Text(if (session.currentComplete) "Completed" else "Mark complete") },
                        onClick = { session.markComplete(); expanded = false })
                    if (session.pageComplete) DropdownMenuItem(text = { Text("Review completed page") },
                        onClick = { session.showPageReview(); expanded = false })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Open-ended") }, onClick = {
                        session.setAnswerOptions(QuestionAnswerOptions(QuestionType.OPEN_ENDED)); expanded = false
                    })
                    (2..8).forEach { count ->
                        DropdownMenuItem(text = { Text("$count choices") }, onClick = {
                            session.setAnswerOptions(QuestionAnswerOptions(optionCount = count)); expanded = false
                        })
                    }
                    HorizontalDivider()
                    Text("Feedback (when an answer key is available)", Modifier.padding(12.dp))
                    QuestionFeedback.entries.forEach { feedback ->
                        DropdownMenuItem(text = { Text((if (session.feedback == feedback) "✓ " else "") + when (feedback) {
                            QuestionFeedback.IMMEDIATE -> "After each question"
                            QuestionFeedback.ON_COMPLETION -> "After page / test completion"
                            QuestionFeedback.MANUAL -> "No automatic feedback"
                        }) }, onClick = { session.setFeedback(feedback); expanded = false })
                    }
                }
            }
        }
        else -> Unit
    }
}

internal val QUESTION_TOOLBAR_ITEMS = setOf(ToolbarItem.QUESTION_NAV, ToolbarItem.QUESTION_ANSWER,
    ToolbarItem.QUESTION_PEEK, ToolbarItem.QUESTION_CROP, ToolbarItem.QUESTION_DELETE)

/** Same stored toolbar entries and item components, presented as a compact footer. */
@Composable
fun QuestionBottomBar(editor: Editor, session: QuestionSession) {
    Row(Modifier.fillMaxWidth().height(44.dp).background(LocalPalette.current.panel.toComposeColor())
        .horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        editor.toolbarLayout.visibleSections.forEach { section ->
            section.visibleEntries.filter { it.item in QUESTION_TOOLBAR_ITEMS }.forEach {
                QuestionToolbarItem(editor, session, it.item)
            }
        }
    }
}
