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
import com.xnotes.platform.QuestionResult
import com.xnotes.ui.icons.XnotesIcons
import androidx.compose.ui.graphics.Color

/** Question Mode adds dialogs to the existing EditorPane; it owns no drawing surface. */
@Composable
fun QuestionModeScreen(editor: Editor, session: QuestionSession) {
    var bulkText by remember { mutableStateOf("") }
    if (session.bulkEditorOpen) {
        val preview = session.bulkKeyPreview(bulkText)
        AlertDialog(onDismissRequest = { session.bulkEditorOpen = false }, title = { Text("Bulk answer key") },
            text = { Column {
                OutlinedTextField(bulkText, { bulkText = it }, label = { Text("Paste choices in question order") }, minLines = 3)
                Text("${preview.count} answers parsed for ${session.count} questions")
                if (preview.count < session.count) Text("Remaining ${session.count - preview.count} question keys stay unchanged")
                if (preview.unused.isNotEmpty()) Text("Unused suffix: ${preview.unused}")
                preview.error?.let { Text(it, color = Color(0xFFCE2828)) }
            } },
            confirmButton = { TextButton(onClick = { session.applyBulkKey(bulkText); session.bulkEditorOpen = false },
                enabled = preview.valid && preview.count > 0 && !session.busy && !session.readOnly) { Text("Apply ${preview.count}") } },
            dismissButton = { TextButton(onClick = { session.bulkEditorOpen = false }) { Text("Cancel") } })
    }
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
    val review = session.review
    if (session.reviewingPage && review != null) Dialog(onDismissRequest = { session.reviewingPage = false },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Page ${review.sourcePageIndex + 1} results", Modifier.padding(12.dp))
                    if (session.feedback == QuestionFeedback.MANUAL) TextButton(onClick = session::revealPage) { Text("Check answers") }
                    TextButton(onClick = { session.reviewingPage = false }) { Text("Close") }
                }
                Box(Modifier.weight(1f)) {
                    PdfCropReview(session.sourcePdf, review.overlays.map { DetectedQuestion(it.question.id, it.question.sourcePageIndex, it.question.crop) },
                        review.sourcePageIndex, null, edit = false, edited = { _, _ -> },
                        results = review.overlays.associate { it.question.id to it.result })
                }
            }
        }
    }
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
            TextButton(onClick = { session.sidebarVisible = !session.sidebarVisible }) { Text(if (session.sidebarVisible) "Hide questions" else "Questions") }
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
        }, enabled = !session.busy && !session.readOnly && session.current?.question != null) { Text("Edit crop") }
        ToolbarItem.QUESTION_DELETE -> TextButton(onClick = { session.current?.question?.id?.let(session::requestDelete) },
            enabled = !session.busy && !session.readOnly && session.current?.question != null) { Text("Delete") }
        ToolbarItem.QUESTION_OVERLAY -> {
            TextButton(onClick = editor::toggleQuestionOverlays) { Text(if (editor.questionOverlaysEnabled) "Outlines ✓" else "Outlines") }
            if (editor.questionOverlaysEnabled && !session.readOnly) TextButton(onClick = editor::toggleQuestionOverlayEditing) {
                Text(if (editor.questionOverlayEditing) "Edit outlines ✓" else "Edit outlines")
            }
            if (editor.questionOverlayEditing && editor.selectedQuestionOverlayId != null && !session.readOnly)
                TextButton(onClick = { editor.selectedQuestionOverlayId?.let(session::requestDelete) }) { Text("Delete selected") }
        }
        ToolbarItem.QUESTION_ANSWER -> {
            var expanded by remember { mutableStateOf(false) }
            session.answerOptions.choices.forEach { choice ->
                val result = if (session.selectedChoice == choice) session.visibleResult else null
                val tint = when (result) {
                    QuestionResult.CORRECT -> Color(0xFF16813C)
                    QuestionResult.INCORRECT -> Color(0xFFCE2828)
                    else -> Color.Unspecified
                }
                FilterChip(selected = session.selectedChoice == choice,
                    onClick = { session.selectChoice(choice) }, label = { Text(choice + when (result) {
                        QuestionResult.CORRECT -> " ✓"
                        QuestionResult.INCORRECT -> " ✕"
                        else -> ""
                    }, color = tint) },
                    enabled = !session.busy && !session.readOnly, modifier = Modifier.padding(horizontal = 2.dp))
            }
            if (session.visibleResult == QuestionResult.INCORRECT) Text("Correct: ${session.correctChoice}", color = Color(0xFF16813C))
            Box {
                TextButton(onClick = { expanded = true }, enabled = !session.busy && !session.readOnly) {
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
                    DropdownMenuItem(text = { Text("Paste answer key for set") }, onClick = { expanded = false; session.bulkEditorOpen = true })
                    DropdownMenuItem(text = { Text("Move question earlier") }, onClick = { session.moveCurrentTo(session.index - 1); expanded = false },
                        enabled = session.index > 0)
                    DropdownMenuItem(text = { Text("Move question later") }, onClick = { session.moveCurrentTo(session.index + 1); expanded = false },
                        enabled = session.index + 1 < session.count)
                    HorizontalDivider()
                    Text("Correct answer", Modifier.padding(12.dp))
                    DropdownMenuItem(text = { Text((if (session.correctChoice == null) "✓ " else "") + "Not set") },
                        onClick = { session.setCorrectChoice(null); expanded = false })
                    session.answerOptions.choices.forEach { choice ->
                        DropdownMenuItem(text = { Text((if (session.correctChoice == choice) "✓ " else "") + choice) },
                            onClick = { session.setCorrectChoice(choice); expanded = false })
                    }
                    HorizontalDivider()
                    if (session.feedback == QuestionFeedback.MANUAL) DropdownMenuItem(text = { Text("Check answers on this page") },
                        onClick = { session.revealPage(); expanded = false })
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
    ToolbarItem.QUESTION_PEEK, ToolbarItem.QUESTION_CROP, ToolbarItem.QUESTION_DELETE, ToolbarItem.QUESTION_OVERLAY)

/** Same stored toolbar entries and item components, presented as a compact footer. */
@Composable
fun QuestionBottomBar(editor: Editor, session: QuestionSession, compact: Boolean = false,
    onOpenSecondDocument: () -> Unit = {}) {
    var more by remember { mutableStateOf(false) }
    if (compact) {
        Column(Modifier.fillMaxWidth().background(LocalPalette.current.panel.toComposeColor())) {
            Row(Modifier.fillMaxWidth().height(36.dp).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = session::previous, enabled = session.canPrevious, modifier = Modifier.size(34.dp)) {
                    Icon(XnotesIcons.prev, "Previous question")
                }
                TextButton(onClick = { session.sidebarVisible = !session.sidebarVisible }, modifier = Modifier.height(34.dp)) {
                    Text("${session.index + 1}/${session.count}")
                }
                IconButton(onClick = session::next, enabled = session.canNext, modifier = Modifier.size(34.dp)) {
                    Icon(XnotesIcons.next, "Next question")
                }
                IconButton(onClick = { session.sidebarVisible = !session.sidebarVisible }, modifier = Modifier.size(34.dp)) {
                    Icon(XnotesIcons.listBullet, "Question list")
                }
                IconButton(onClick = onOpenSecondDocument, modifier = Modifier.size(34.dp)) {
                    Icon(XnotesIcons.folder, "Open source notebook")
                }
                IconButton(onClick = { more = !more }, modifier = Modifier.size(34.dp)) {
                    Icon(XnotesIcons.more, "More question actions")
                }
            }
            Row(Modifier.fillMaxWidth().height(38.dp).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically) {
                session.answerOptions.choices.forEach { choice ->
                    val result = if (session.selectedChoice == choice) session.visibleResult else null
                    val tint = when (result) {
                        QuestionResult.CORRECT -> Color(0xFF16813C)
                        QuestionResult.INCORRECT -> Color(0xFFCE2828)
                        else -> Color.Unspecified
                    }
                    FilterChip(selected = session.selectedChoice == choice,
                        onClick = { session.selectChoice(choice) }, label = { Text(choice, color = tint) },
                        enabled = !session.busy && !session.readOnly, modifier = Modifier.padding(horizontal = 1.dp))
                }
                if (session.visibleResult == QuestionResult.INCORRECT)
                    Text("✓ ${session.correctChoice}", color = Color(0xFF16813C))
            }
            if (more) FullQuestionBottomBar(editor, session)
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FullQuestionBottomBar(editor, session, Modifier.weight(1f))
            if (editor.pane == Pane.PRIMARY) IconButton(onClick = onOpenSecondDocument) {
                Icon(XnotesIcons.folder, "Open source notebook")
            }
        }
    }
}

@Composable
private fun FullQuestionBottomBar(editor: Editor, session: QuestionSession, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(44.dp).background(LocalPalette.current.panel.toComposeColor())
        .horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        editor.toolbarLayout.visibleSections.forEach { section ->
            section.visibleEntries.filter { it.item in QUESTION_TOOLBAR_ITEMS }.forEach {
                QuestionToolbarItem(editor, session, it.item)
            }
        }
    }
}
