package app.drafty.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Summarize
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.drafty.claude.Ai
import app.drafty.claude.ClaudeBridge
import app.drafty.claude.ClaudeStatus
import app.drafty.claude.Provider
import app.drafty.data.Prefs
import app.drafty.service.DraftyAccessibilityService
import app.drafty.service.AutoReplyService
import android.widget.Toast

class MainActivity : ComponentActivity() {
    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DraftyTheme {
                var onboarded by remember { mutableStateOf(Prefs.onboarded) }
                if (onboarded) AppShell(resumeTick.intValue) { Prefs.onboarded = false; onboarded = false }
                else Onboarding(resumeTick.intValue) { onboarded = true }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }
}

private enum class Screen(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Outlined.Home),
    SETTINGS("Settings", Icons.Outlined.Settings),
}

@Composable
private fun AppShell(resumeTick: Int, openGuide: () -> Unit) {
    var screen by remember { mutableStateOf(Screen.HOME) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                Screen.entries.forEach { s ->
                    NavigationBarItem(
                        selected = screen == s,
                        onClick = { screen = s },
                        icon = { Icon(s.icon, null) },
                        label = { Text(s.label) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Accent, selectedTextColor = Accent, indicatorColor = AccentSoft),
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (screen) {
                Screen.HOME -> HomeScreen(resumeTick, openGuide) { screen = Screen.SETTINGS }
                Screen.SETTINGS -> SettingsScreen(resumeTick)
            }
        }
    }
}

@Composable
fun BrandMark(size: Int = 40) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.3).dp)).background(Accent), contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.AutoAwesome, null, tint = Color.White, modifier = Modifier.size((size * 0.55).dp))
    }
}

@Composable
private fun HomeScreen(resumeTick: Int, openGuide: () -> Unit, openSettings: () -> Unit) {
    val ctx = LocalContext.current
    var status by remember { mutableStateOf<ClaudeStatus?>(null) }
    var serviceOn by remember { mutableStateOf(false) }
    val provider = remember(resumeTick) { Ai.provider }

    LaunchedEffect(resumeTick) {
        serviceOn = DraftyAccessibilityService.isEnabled(ctx)
        status = null
        status = Ai.status(provider)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandMark(44)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Drafty", style = MaterialTheme.typography.headlineMedium)
                Text("One tap replies in every app", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val dark = isDark()
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .clickable { ThemeMode.set(if (dark) "light" else "dark") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (dark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode, if (dark) "Light mode" else "Dark mode",
                    tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.height(4.dp))

        val st = status
        SetupCard(
            step = 1,
            title = "AI model: ${provider.label}",
            done = st?.connected == true,
            body = when {
                st == null -> "Checking connection..."
                st.connected && provider == Provider.CLAUDE -> "Connected to Claude ${st.plan}${if (st.orgName.isNotBlank()) " · ${st.orgName}" else ""}"
                st.code == "guest" -> "ChatGPT works without an account. Sign in for better answers and higher limits."
                st.connected -> "Connected to ${provider.label}. Your own account, no API key."
                else -> "Sign in to ${provider.label} with your own account. No API key needed."
            },
            action = if (st?.connected == true && st.code != "guest") "Change model" else "Connect ${provider.label}",
            primary = st?.connected != true || st.code == "guest",
            onAction = { if (st?.connected == true && st.code != "guest") openSettings() else LoginActivity.open(ctx, provider) },
        )

        SetupCard(
            step = 2,
            title = "Drafty button in other apps",
            done = serviceOn,
            body = if (serviceOn) "On. Tap the floating Drafty button in any app."
            else "Turn on Drafty in Accessibility settings. Find it under Installed apps or Downloaded apps.",
            action = if (serviceOn) "Manage" else "Turn on",
            primary = !serviceOn,
            onAction = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
        )

        Text("How it works", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(4.dp)) {
                HowRow(Icons.Outlined.TouchApp, "Tap to reply", "In a chat, tap the Drafty button and the reply is written into the box. In a comment box, it writes a comment using the post and other comments.")
                Divider()
                HowRow(Icons.Outlined.EditNote, "Long-press for everything", "Regenerate, shorter, warmer, formal, translate, catch up, analyze, and on posts: summarize comments, best comments, opinions and more.")
                Divider()
                HowRow(Icons.Outlined.Summarize, "Your rules", "Choose in Settings whether replies are drafted on tap or automatically, and sent by you or by Drafty.")
                Divider()
                HowRow(Icons.Outlined.Lock, "Private by design", "Screen text is read only when you tap Drafty and goes only to your chosen AI.")
            }
        }
        TextButton(onClick = openGuide, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Open setup guide", color = Accent) }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Divider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(horizontal = 16.dp))

@Composable
private fun SetupCard(step: Int, title: String, done: Boolean, body: String, action: String, primary: Boolean, onAction: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(if (done) Success.copy(alpha = 0.15f) else AccentSoft), contentAlignment = Alignment.Center) {
                    if (done) Icon(Icons.Outlined.CheckCircle, null, tint = Success, modifier = Modifier.size(18.dp))
                    else Text("$step", color = Accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (done) Text("Ready", color = Success, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp, bottom = 14.dp))
            if (primary) Button(onClick = onAction, shape = RoundedCornerShape(12.dp)) { Text(action) }
            else OutlinedButton(onClick = onAction, shape = RoundedCornerShape(12.dp)) { Text(action) }
        }
    }
}

@Composable
private fun HowRow(icon: ImageVector, title: String, body: String) {
    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(AccentSoft), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Accent, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SettingsScreen(resumeTick: Int) {
    val ctx = LocalContext.current
    var provider by remember { mutableStateOf(Ai.provider) }
    val statuses = remember { mutableStateMapOf<Provider, ClaudeStatus>() }
    var name by remember { mutableStateOf(Prefs.myName) }
    var about by remember { mutableStateOf(Prefs.aboutMe) }
    var autoInsert by remember { mutableStateOf(Prefs.autoInsert) }
    var bubble by remember { mutableStateOf(Prefs.showBubble) }
    var cleanup by remember { mutableStateOf(Prefs.cleanup) }
    var replyLang by remember { mutableStateOf(Prefs.replyLang) }
    var translateTo by remember { mutableStateOf(Prefs.translateTo) }
    var autoDraft by remember { mutableStateOf(Prefs.autoDraft) }
    var autoSend by remember { mutableStateOf(Prefs.autoSend) }
    var confirmSend by remember { mutableStateOf(false) }
    var autoReply by remember { mutableStateOf(Prefs.autoReply) }
    var confirmReply by remember { mutableStateOf(false) }
    var comments by remember { mutableStateOf(Prefs.commentLimit.toFloat()) }
    var budget by remember { mutableStateOf(Prefs.budgetTokens.toFloat()) }
    var half by remember { mutableStateOf(Prefs.halfLife.toFloat()) }

    LaunchedEffect(resumeTick) {
        Provider.visible.forEach { statuses[it] = Ai.status(it) }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        Section("AI model") {
            Text("Pick the one Drafty uses for every reply.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            Provider.visible.forEach { p ->
                val st = statuses[p]
                val selected = provider == p
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .border(1.5.dp, if (selected) Accent else MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                        .background(if (selected) AccentSoft else Color.Transparent)
                        .clickable { provider = p; Prefs.provider = p.id }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = { provider = p; Prefs.provider = p.id }, colors = RadioButtonDefaults.colors(selectedColor = Accent))
                    Column(Modifier.weight(1f)) {
                        Text(p.label, fontWeight = FontWeight.SemiBold)
                        Text(
                            when {
                                st == null -> "Checking..."
                                st.code == "guest" -> "Works without signing in"
                                st.connected -> if (p == Provider.CLAUDE) "Connected · ${st.plan}" else "Connected"
                                else -> "Not connected"
                            },
                            fontSize = 12.sp, color = if (st?.connected == true) Success else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { LoginActivity.open(ctx, p) }) {
                        Text(if (st?.connected == true && st.code != "guest") "Reconnect" else "Sign in", color = Accent)
                    }
                }
            }
            if (statuses[Provider.CLAUDE]?.connected == true) {
                TextButton(onClick = { ClaudeBridge.signOut { } }) { Text("Sign out of everything", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }

        Section("Appearance") {
            Segmented(listOf("system" to "System", "light" to "Light", "dark" to "Dark"), ThemeMode.mode) { ThemeMode.set(it) }
        }

        Section("Replies") {
            ToggleRow(
                "Draft automatically",
                if (autoDraft) "A reply is written as soon as you open a chat's reply box" else "A reply is written when you tap the Drafty button",
                autoDraft,
            ) { autoDraft = it; Prefs.autoDraft = it }
            ToggleRow(
                "Send automatically",
                if (autoSend) "After a draft is written in an open chat, Drafty sends it" else "Drafts stay in the box until you send them",
                autoSend,
            ) { on -> if (on) confirmSend = true else { autoSend = false; Prefs.autoSend = false } }
            ToggleRow(
                "Reply when phone is locked",
                if (autoReply) "New messages are answered from their notification while your screen is off or locked" else "Off: nothing is answered while you're away",
                autoReply,
            ) { on -> if (on) confirmReply = true else { autoReply = false; Prefs.autoReply = false } }
            if (autoReply) {
                val access = remember(resumeTick) { AutoReplyService.hasAccess(ctx) }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(if (access) Success.copy(alpha = 0.10f) else AccentSoft).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(if (access) "Replying in the background" else "One more step", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (access) "New 1:1 messages are answered while your phone is locked. ${Prefs.autoSentCount} sent so far."
                            else "Allow notification access so Drafty can answer new messages while you're away.",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!access) TextButton(onClick = {
                        Toast.makeText(ctx, "Turn on Drafty auto reply, then tap Allow", Toast.LENGTH_LONG).show()
                        AutoReplyService.openAccess(ctx)
                    }) { Text("Allow", color = Accent, fontWeight = FontWeight.SemiBold) }
                }
            }
            if (autoReply) AutoReplyPanel()
            ToggleRow("Insert into the text box", "Off: show the draft in a card first", autoInsert) { autoInsert = it; Prefs.autoInsert = it }
            ToggleRow("Show the Drafty button", "Floating button over other apps", bubble) { bubble = it; Prefs.showBubble = it }
            ToggleRow("Keep chat history clean", "Fully delete Drafty's chats from your Claude or Gemini account", cleanup) { cleanup = it; Prefs.cleanup = it }
        }

        Section("Comments") {
            SliderRow("Comments to read", "up to ${comments.toInt()}", comments, 5f..100f, steps = 18) {
                comments = it; Prefs.commentLimit = it.toInt()
            }
            Text(
                "Drafty scrolls through the thread to read this many comments, then returns you to where you were. Used for comment replies and the comment tools.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (confirmSend) AlertDialog(
            onDismissRequest = { confirmSend = false },
            title = { Text("Send drafts automatically?") },
            text = {
                Text(
                    "When Drafty writes a reply in an open chat, it will tap send for you.\n\n" +
                        "Comments and group posts are never sent automatically. Turn this off anytime.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmSend = false; autoSend = true; Prefs.autoSend = true }) {
                    Text("Turn on", color = Accent, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = { TextButton(onClick = { confirmSend = false }) { Text("Cancel") } },
            containerColor = MaterialTheme.colorScheme.surface,
        )

        if (confirmReply) AlertDialog(
            onDismissRequest = { confirmReply = false },
            title = { Text("Reply when phone is locked?") },
            text = {
                Text(
                    "While your screen is off or locked, Drafty answers new 1:1 messages as you, using the whole conversation.\n\n" +
                        "• Choose the style below: as me, I'm away, or a fixed message.\n" +
                        "• Nothing is sent while you're using your phone.\n" +
                        "• Group chats and comments are never answered.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReply = false; autoReply = true; Prefs.autoReply = true
                    if (!AutoReplyService.hasAccess(ctx)) {
                        Toast.makeText(ctx, "Turn on Drafty auto reply, then tap Allow", Toast.LENGTH_LONG).show()
                        AutoReplyService.openAccess(ctx)
                    }
                }) { Text("Turn on", color = Accent, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { confirmReply = false }) { Text("Cancel") } },
            containerColor = MaterialTheme.colorScheme.surface,
        )

        Section("Language") {
            PickerRow("Reply in", if (replyLang == "Auto") "Same as chat" else replyLang, listOf("Auto") + Prefs.LANGS) {
                replyLang = it; Prefs.replyLang = it
            }
            PickerRow("Translate and summarize into", translateTo, Prefs.LANGS) { translateTo = it; Prefs.translateTo = it }
        }

        Section("Personalisation") {
            OutlinedTextField(value = name, onValueChange = { name = it; Prefs.myName = it }, label = { Text("Your name") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
            OutlinedTextField(value = about, onValueChange = { about = it; Prefs.aboutMe = it }, label = { Text("About you") },
                placeholder = { Text("e.g. Founder. I text in Hinglish, lowercase, few emojis.") },
                minLines = 2, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
        }

        Section("Context") {
            SliderRow("Reply budget", "${budget.toInt()} tokens", budget, 500f..8000f) { budget = it; Prefs.budgetTokens = it.toInt() }
            SliderRow("Memory half-life", "${half.toInt()} messages", half, 3f..100f) { half = it; Prefs.halfLife = it.toInt() }
        }

        Text("Drafty 1.3.0", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title.uppercase(), fontSize = 11.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun ToggleRow(label: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = value, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White, checkedTrackColor = Accent, checkedBorderColor = Accent,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant, uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                uncheckedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            ),
        )
    }
}

@Composable
private fun PickerRow(label: String, value: String, options: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { open = true }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Box {
            Text(value, color = Accent, fontWeight = FontWeight.SemiBold)
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { o ->
                    DropdownMenuItem(text = { Text(if (o == "Auto") "Same as chat" else o) }, onClick = { onPick(o); open = false })
                }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SliderRow(label: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(label, modifier = Modifier.weight(1f))
            Text(valueText, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val track = MaterialTheme.colorScheme.outlineVariant
        Slider(
            value = value, onValueChange = onChange, valueRange = range, steps = steps,
            thumb = {
                Box(Modifier.size(22.dp).clip(CircleShape).background(Color.White).border(5.dp, Accent, CircleShape))
            },
            track = { st ->
                val f = ((st.value - st.valueRange.start) / (st.valueRange.endInclusive - st.valueRange.start)).coerceIn(0f, 1f)
                Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(track)) {
                    Box(Modifier.fillMaxWidth(f).height(6.dp).clip(CircleShape).background(Accent))
                }
            },
        )
    }
}

/** How Drafty answers new messages while Send automatically is on. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AutoReplyPanel() {
    var mode by remember { mutableStateOf(Prefs.arMode) }
    var note by remember { mutableStateOf(Prefs.arNote) }
    var fixed by remember { mutableStateOf(Prefs.arFixed) }
    var apps by remember { mutableStateOf(Prefs.arApps) }
    val all = AutoReplyService.APP_CHOICES.flatMap { it.second }.toSet()

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("How to reply", fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.fillMaxWidth())
        Segmented(listOf("me" to "As me", "away" to "I'm away", "fixed" to "Fixed text"), mode) { mode = it; Prefs.arMode = it }
        Text(
            when (mode) {
                "away" -> "Lets people know you're away and will reply later. Answers only quick, obvious questions."
                "fixed" -> "Sends exactly this message, every time."
                else -> "Replies naturally in your style, using the conversation. Never agrees to plans or money for you."
            },
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (mode == "fixed") OutlinedTextField(
            value = fixed, onValueChange = { fixed = it; Prefs.arFixed = it }, label = { Text("Message") },
            minLines = 2, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
        ) else OutlinedTextField(
            value = note, onValueChange = { note = it; Prefs.arNote = it },
            label = { Text(if (mode == "away") "Where are you? (optional)" else "Instructions (optional)") },
            placeholder = { Text(if (mode == "away") "e.g. Driving, back around 6" else "e.g. Keep it short. I'm in meetings until 5.") },
            minLines = 2, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
        )
        Text("Reply in", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AutoReplyService.APP_CHOICES.forEach { (label, pkgs) ->
                val on = apps.isEmpty() || pkgs.any { it in apps }
                Box(
                    Modifier.clip(CircleShape)
                        .background(if (on) AccentSoft else Color.Transparent)
                        .border(1.dp, if (on) Accent else MaterialTheme.colorScheme.outline, CircleShape)
                        .clickable {
                            val cur = if (apps.isEmpty()) all else apps
                            var next = if (on) cur - pkgs else cur + pkgs
                            if (next.containsAll(all)) next = emptySet()
                            if (next.isEmpty() && on) next = setOf("none")
                            apps = next; Prefs.arApps = next
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) { Text(label, fontSize = 13.sp, color = if (on) Accent else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal) }
            }
        }
        Text("Group chats and comments are never answered automatically.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Segmented(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(4.dp),
    ) {
        options.forEach { (id, label) ->
            val on = id == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(11.dp))
                    .background(if (on) MaterialTheme.colorScheme.surface else Color.Transparent)
                    .clickable { onPick(id) }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
