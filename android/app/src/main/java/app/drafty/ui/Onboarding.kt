package app.drafty.ui

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.drafty.claude.Ai
import app.drafty.claude.ClaudeStatus
import app.drafty.claude.Provider
import app.drafty.data.Prefs
import app.drafty.service.DraftyAccessibilityService

private const val STEPS = 5

/** Guided first run: welcome, connect AI, accessibility, battery, done. */
@Composable
fun Onboarding(resumeTick: Int, onFinish: () -> Unit) {
    val ctx = LocalContext.current
    var step by rememberSaveable { mutableIntStateOf(0) }
    var provider by remember { mutableStateOf(Ai.provider) }
    val statuses = remember { mutableStateMapOf<Provider, ClaudeStatus>() }
    var serviceOn by remember { mutableStateOf(false) }
    var batteryOk by remember { mutableStateOf(false) }
    var showRestricted by rememberSaveable { mutableStateOf(false) }
    var triedAccess by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(resumeTick) {
        serviceOn = DraftyAccessibilityService.isEnabled(ctx)
        batteryOk = Setup.batteryOk(ctx)
        if (triedAccess && !serviceOn && Build.VERSION.SDK_INT >= 33) showRestricted = true
        // Move on by itself when the user comes back with the step done.
        if (step == 2 && serviceOn) step = 3
        if (step == 3 && batteryOk) step = 4
        Provider.visible.forEach { statuses[it] = Ai.status(it) }
        if (step == 1 && triedLogin && statuses[provider]?.connected == true) { triedLogin = false; step = 2 }
    }

    val progress by animateFloatAsState((step + 1f) / STEPS, tween(400), label = "p")

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant)) {
                Box(Modifier.fillMaxWidth(progress).height(6.dp).clip(CircleShape).background(Accent))
            }
            Spacer(Modifier.width(14.dp))
            if (step in 1..3) TextButton(onClick = { step++ }) { Text("Skip", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else Spacer(Modifier.height(48.dp))
        }

        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (slideInHorizontally(tween(320)) { it / 4 * dir } + fadeIn(tween(320)))
                    .togetherWith(slideOutHorizontally(tween(240)) { -it / 4 * dir } + fadeOut(tween(200)))
            },
            modifier = Modifier.weight(1f),
            label = "step",
        ) { s ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (s) {
                    0 -> StepHeader(Icons.Outlined.AutoAwesome, "Welcome to Drafty",
                        "Replies, summaries and translations in every app, from one floating button. Setup takes about a minute.").also {
                        Bullet("1", "Connect the AI you already use")
                        Bullet("2", "Turn on the Drafty button")
                        Bullet("3", "Keep it running in the background")
                    }

                    1 -> {
                        StepHeader(Icons.Outlined.Link, "Connect your AI",
                            "Pick one and sign in with your own account. No API key, no extra cost.")
                        Provider.visible.forEach { p ->
                            val st = statuses[p]
                            val sel = provider == p
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                                    .border(1.5.dp, if (sel) Accent else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                                    .background(if (sel) AccentSoft else Color.Transparent)
                                    .clickable { provider = p; Prefs.provider = p.id }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(p.label, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        when {
                                            st == null -> "Checking..."
                                            st.code == "guest" -> "Works without signing in"
                                            st.connected -> "Connected"
                                            else -> "Not connected"
                                        },
                                        fontSize = 12.sp,
                                        color = if (st?.connected == true) Success else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (st?.connected == true && st.code != "guest")
                                    Icon(Icons.Outlined.CheckCircle, null, tint = Success, modifier = Modifier.size(22.dp))
                            }
                        }
                    }

                    2 -> {
                        StepHeader(Icons.Outlined.AccessibilityNew, "Turn on the Drafty button",
                            "Android asks you to allow this once. Drafty reads the screen only when you tap its button.")
                        if (serviceOn) Done("Drafty is on")
                        else {
                            Guide(
                                "Tap Open settings below",
                                "Tap Downloaded apps (on some phones: Installed apps or Installed services)",
                                "Tap Drafty, switch it on, then tap Allow",
                            )
                            Text("Drafty brings you back here once it is on.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                            if (showRestricted) Box(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(AccentSoft).padding(16.dp),
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("Switch greyed out or \"Restricted setting\"?", fontWeight = FontWeight.SemiBold)
                                    Text("Android blocks this for apps installed from a file. Unlock it once:", fontSize = 14.sp)
                                    Guide(
                                        "Tap Open app info below",
                                        "Tap the ⋮ menu at the top right",
                                        "Tap Allow restricted settings and confirm",
                                        "Come back and tap Open settings again",
                                    )
                                    TextButton(onClick = { Setup.openAppInfo(ctx) }) { Text("Open app info", color = Accent, fontWeight = FontWeight.SemiBold) }
                                }
                            } else TextButton(onClick = { showRestricted = true }) {
                                Text("Can't switch it on?", color = Accent)
                            }
                        }
                    }

                    3 -> {
                        StepHeader(Icons.Outlined.BatteryChargingFull, "Keep Drafty running",
                            "Some phones close background apps to save battery, which hides the button. Allow Drafty to stay on.")
                        if (batteryOk) Done("Battery limits removed")
                        if (Setup.aggressiveOem()) {
                            Text("On ${Setup.brand()} phones, also do this:", fontWeight = FontWeight.SemiBold)
                            Guide(
                                "Tap Open app info below",
                                "Tap Battery usage (or Battery)",
                                "Turn on Allow background activity",
                            )
                            TextButton(onClick = { Setup.openAppInfo(ctx) }) { Text("Open app info", color = Accent, fontWeight = FontWeight.SemiBold) }
                        }
                    }

                    else -> {
                        StepHeader(Icons.Outlined.TouchApp, "You're all set",
                            "Open any chat and look for the Drafty button on the edge of the screen.")
                        Bullet("1", "Tap the button in an empty reply box. The reply is written for you, never sent.")
                        Bullet("2", "Tap again to refine it, catch up, translate or analyze the chat.")
                        Bullet("3", "On posts, it summarizes the comments, finds the best ones and more.")
                        Bullet("4", "Drag it anywhere. Long-press opens the menu.")
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        // Bottom action
        val st = statuses[provider]
        val (label, action) = when (step) {
            0 -> "Get started" to { step = 1 }
            1 -> if (st?.connected == true && st.code != "guest") "Continue" to { step = 2 }
                 else "Sign in to ${provider.label}" to { triedLogin = true; LoginActivity.open(ctx, provider) }
            2 -> if (serviceOn) "Continue" to { step = 3 }
                 else "Open settings" to { triedAccess = true; Setup.openAccessibility(ctx) }
            3 -> if (batteryOk) "Continue" to { step = 4 }
                 else "Allow" to { Setup.requestBattery(ctx) }
            else -> "Start using Drafty" to { Prefs.onboarded = true; onFinish() }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
            Button(
                onClick = action,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
            ) { Text(label, fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
            if (step in 1..4) TextButton(onClick = { step-- }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Back", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private var triedLogin = false

@Composable
private fun StepHeader(icon: ImageVector, title: String, body: String) {
    Spacer(Modifier.height(12.dp))
    Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(AccentSoft), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Accent, modifier = Modifier.size(32.dp))
    }
    Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
    Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp, lineHeight = 23.sp)
}

@Composable
private fun Bullet(n: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(AccentSoft), contentAlignment = Alignment.Center) {
            Text(n, color = Accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, modifier = Modifier.padding(top = 3.dp), lineHeight = 21.sp)
    }
}

@Composable
private fun Guide(vararg lines: String) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        lines.forEachIndexed { i, l -> Bullet("${i + 1}", l) }
    }
}

@Composable
private fun Done(text: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Success.copy(alpha = 0.12f)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.CheckCircle, null, tint = Success)
        Spacer(Modifier.width(10.dp))
        Text(text, fontWeight = FontWeight.SemiBold, color = Success, textAlign = TextAlign.Start)
    }
}

object Setup {
    fun brand(): String = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }

    fun aggressiveOem(): Boolean = Build.MANUFACTURER.lowercase() in
        setOf("realme", "oppo", "oneplus", "vivo", "iqoo", "xiaomi", "redmi", "poco", "huawei", "honor", "samsung", "tecno", "infinix", "itel", "motorola", "asus", "meizu")

    fun batteryOk(ctx: Context): Boolean =
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)

    @SuppressLint("BatteryLife")
    fun requestBattery(ctx: Context) {
        val ok = runCatching {
            ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
        }.isSuccess
        if (!ok) runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            .onFailure { openAppInfo(ctx) }
    }

    fun openAppInfo(ctx: Context) {
        runCatching {
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
        }
    }

    /** Opens Drafty's own accessibility page where supported, else the list with Drafty highlighted. */
    fun openAccessibility(ctx: Context) {
        val cn = ComponentName(ctx, DraftyAccessibilityService::class.java).flattenToString()
        val args = Bundle().apply { putString(":settings:fragment_args_key", cn) }
        val direct = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            .putExtra(Intent.EXTRA_COMPONENT_NAME, cn)
            .putExtra(":settings:fragment_args_key", cn)
            .putExtra(":settings:show_fragment_args", args)
        val list = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .putExtra(":settings:fragment_args_key", cn)
            .putExtra(":settings:show_fragment_args", args)
        val opened = Build.VERSION.SDK_INT >= 30 && runCatching { ctx.startActivity(direct) }.isSuccess
        if (!opened) runCatching { ctx.startActivity(list) }
        Toast.makeText(ctx, "Find Drafty, switch it on, then tap Allow", Toast.LENGTH_LONG).show()
    }
}
