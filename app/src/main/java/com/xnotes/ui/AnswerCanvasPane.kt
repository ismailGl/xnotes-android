package com.xnotes.ui

import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.xnotes.core.tools.Tool
import com.xnotes.R
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

@Composable
fun AnswerCanvasPane(answers: QuestionAnswerSession, modifier: Modifier = Modifier) {
    val canvas = answers.surface as? AnswerCanvasController
    val palette = LocalPalette.current
    Column(modifier.onPreviewKeyEvent { event ->
        event.type == KeyEventType.KeyDown && canvas?.handleKey(event.nativeKeyEvent) == true
    }) {
        answers.error?.let { message ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(message, Modifier.weight(1f).padding(horizontal = 8.dp), color = palette.text.toComposeColor())
                TextButton(onClick = answers::retry, enabled = !answers.busy) { Text("Retry") }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds(), contentAlignment = Alignment.Center) {
            if (canvas != null) {
                key(canvas) {
                    AndroidView(factory = {
                        (canvas.surfaces.parent as? ViewGroup)?.removeView(canvas.surfaces)
                        canvas.surfaces
                    }, modifier = Modifier.fillMaxSize(), update = {
                        if (canvas.state.palette != palette) {
                            canvas.state.palette = palette
                            canvas.state.invalidateAllCaches()
                        }
                        canvas.view.requestRender()
                    })
                }
            } else if (!answers.busy && answers.error == null) {
                Text("No answer canvas for this question", color = palette.text.toComposeColor())
            }
            if (answers.busy) CircularProgressIndicator()
        }
    }
}
