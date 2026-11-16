package app.drafty.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.drafty.claude.Ai
import app.drafty.claude.ClaudeException
import app.drafty.claude.Prompts
import app.drafty.claude.Prompts.Task
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private fun copyText(ctx: Context, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Drafty", text))
    Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
}

private fun shareText(ctx: Context, text: String) {
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioScreen(initialText: String = "", initialTask: Task = Task.REPLY, autoRun: Boolean = false, header: @Composable () -> Unit = {
    Text("Studio", style = MaterialTheme.typography.headlineMedium)
    Text("Paste a chat, email, post or comments.", color = MaterialTheme.colorScheme.onSurfaceVariant)
}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var task by remember { mutableStateOf(initialTask) }
    var input by remember { mutableStateOf(initialText) }
    var instruction by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    val busy = job?.isActive == true

    fun run(mod: String = "", previous: String = "") {
        if (input.isBlank()) { error = "Paste or share some text first."; return }
        job?.cancel()
        error = ""
        output = ""
        val prompt = Prompts.studio(task, input, mod.ifBlank { instruction }, previous)
        job = scope.launch {
            try {
                Ai.generate(prompt).collect { output = Prompts.clean(it) }
            } catch (e: ClaudeException) {
                error = e.message ?: "Something went wrong."
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Something went wrong."
            } finally {
                job = null
            }
        }
    }

    LaunchedEffect(Unit) { if (autoRun && initialText.isNotBlank()) run() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column { header() }

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Task.entries.forEachIndexed { i, t ->
                SegmentedButton(
                    selected = task == t,
                    onClick = { task = t },
                    shape = SegmentedButtonDefaults.itemShape(i, Task.entries.size),
                    colors = SegmentedButtonDefaults.colors(activeContainerColor = AccentSoft, activeContentColor = Accent),
                    icon = {},
                ) { Text(t.label, fontSize = 13.sp) }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Text", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.let { input = it }
                    }) {
                        Icon(Icons.Outlined.ContentPaste, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Paste")
                    }
                    if (input.isNotEmpty()) TextButton(onClick = { input = ""; output = "" }) { Text("Clear", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                OutlinedTextField(
                    value = input, onValueChange = { input = it },
                    placeholder = { Text("Paste the conversation or post here") },
                    minLines = 5, maxLines = 12, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                )
                OutlinedTextField(
                    value = instruction, onValueChange = { instruction = it },
                    placeholder = {
                        Text(when (task) {
                            Task.REPLY -> "What do you want to say? (optional)"
                            Task.TRANSLATE -> "Language (optional, default from settings)"
                            else -> "Focus on something specific (optional)"
                        })
                    },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                )
                Button(
                    onClick = { if (busy) job?.cancel() else run() },
                    modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp),
                ) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Stop")
                    } else Text(if (task == Task.REPLY) "Draft reply" else task.label)
                }
            }
        }

        if (error.isNotEmpty()) Text(error, color = Danger)

        AnimatedVisibility(output.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (task == Task.REPLY) "Draft" else task.label, fontWeight = FontWeight.SemiBold, color = Accent)
                    if (task == Task.REPLY) {
                        OutlinedTextField(value = output, onValueChange = { output = it }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                    } else {
                        SelectionContainer { Text(Markdown.annotated(output), style = MaterialTheme.typography.bodyLarge) }
                    }
                    if (task == Task.REPLY && !busy) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("Shorter", "Warmer", "More formal", "Say no nicely").forEach { label ->
                                SuggestionChip(onClick = { run(Prompts.MODS[label] ?: "", output) }, label = { Text(label, fontSize = 12.sp) })
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { copyText(ctx, output) }) { Icon(Icons.Outlined.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Copy") }
                        TextButton(onClick = { shareText(ctx, output) }) { Icon(Icons.Outlined.Share, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Share") }
                        TextButton(onClick = { run() }, enabled = !busy) { Icon(Icons.Outlined.Refresh, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Redo") }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
