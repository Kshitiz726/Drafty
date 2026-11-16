package app.drafty.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.drafty.claude.Prompts

/** Receives shared or selected text from any app. */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val text = when (intent?.action) {
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            else -> intent?.getStringExtra(Intent.EXTRA_TEXT)
        }.orEmpty()
        val task = if (text.length > 600) Prompts.Task.SUMMARIZE else Prompts.Task.REPLY
        setContent {
            DraftyTheme {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding().imePadding()) {
                    StudioScreen(initialText = text, initialTask = task, autoRun = text.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BrandMark(32)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Ask Drafty", style = MaterialTheme.typography.titleLarge)
                                Text("From shared text", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { finish() }) { Icon(Icons.Outlined.Close, "Close") }
                        }
                    }
                }
            }
        }
    }
}


