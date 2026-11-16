package app.drafty.service

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.drafty.claude.Ai
import app.drafty.claude.ContextMath
import app.drafty.claude.Kind
import app.drafty.claude.Msg
import app.drafty.claude.Prompts
import app.drafty.data.ChatMemory
import app.drafty.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Send automatically: answers new 1:1 chat messages through the notification's own Reply action.
 * Works with the screen off or locked, without unlocking. Group chats are never answered.
 */
class AutoReplyService : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val pending = HashMap<String, Job>()
    private val answered = HashMap<String, Int>()
    private val lastSent = HashMap<String, Long>()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun log(m: String) { if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) android.util.Log.d("DraftyAR", m) }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!Prefs.autoReply) return
        if (sbn.packageName !in APPS || sbn.isOngoing) return
        val allowed = Prefs.arApps
        if (allowed.isNotEmpty() && sbn.packageName !in allowed) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val action = replyAction(n) ?: return log("no reply action")
        val convo = conversation(n) ?: return log("no convo")
        val uris = imageUris
        log("convo ${convo.title} group=${convo.group} msgs=${convo.msgs.map { it.who + ':' + it.text.take(20) }}")
        if (convo.group || convo.msgs.isEmpty() || convo.msgs.last().who == "me") return

        val key = sbn.packageName + "|" + (if (convo.hidden || convo.title.isBlank()) sbn.key else convo.title)
        // Hidden content can't tell our own echo from a new message: one away note per chat per 10 minutes.
        if (convo.hidden && System.currentTimeMillis() - (lastSent[key] ?: 0L) < 10 * 60_000) return
        val sig = if (convo.hidden) sbn.postTime.hashCode() else convo.msgs.last().text.hashCode()
        if (answered[key] == sig) return

        // Wait a few seconds so a burst of messages gets one reply.
        pending.remove(key)?.cancel()
        pending[key] = scope.launch {
            delay(4500)
            if (!Prefs.autoReply) return@launch
            log("debounced, active=${userIsActive()}")
            // Only while the phone is locked or the screen is off; never while the user is on it.
            if (userIsActive()) return@launch
            val since = System.currentTimeMillis() - (lastSent[key] ?: 0L)
            if (since < 20_000) delay(20_000 - since)
            answered[key] = sig
            reply(sbn.packageName, convo, action, key, uris)
        }
    }

    private suspend fun reply(pkg: String, c: Convo, action: Notification.Action, key: String, uris: List<android.net.Uri> = emptyList()) {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "drafty:autoreply").apply { acquire(120_000) }
        try {
            DraftyAccessibilityService.instance?.ensureHost()
            // The whole conversation: what Drafty saw in the chat earlier plus the new messages.
            val history = if (c.hidden) c.msgs else ChatMemory.merge(pkg, c.title, c.msgs)
            val sel = ContextMath.select(history, Prefs.budgetTokens, Prefs.halfLife)
            val app = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault("chat")
            val note = Prefs.arNote.trim()
            val text = if (Prefs.arMode == "fixed") Prefs.arFixed.trim() else {
                val mode = if (c.hidden) "away" else Prefs.arMode
                val instruction = when (mode) {
                    "away" -> "I'm away and can't reply properly right now. Write a short, friendly message in the chat's language letting them know I'll get back to them soon. " +
                        "Only answer if it's a simple yes/no or quick fact I'd obviously know. Don't make plans or promises." +
                        (if (note.isNotEmpty()) " Context about where I am or what to say: $note" else "")
                    else -> "Reply naturally as me, like I would text. Keep it short. Don't agree to plans, money or meetings on my behalf; say I'll confirm later." +
                        (if (note.isNotEmpty()) " My instructions: $note" else "")
                }
                val transcript = ContextMath.format(sel.selected, c.title)
                val prompt = Prompts.reply(Kind.CHAT, app, c.title, transcript, sel.omitted, false, instruction, "", "")
                withTimeoutOrNull(90_000) { Ai.generate(prompt).lastOrNull() }?.let { Prompts.clean(it).trim() }
            }
            if (text.isNullOrBlank()) return
            val inputs = action.remoteInputs ?: return
            val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
            val intent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            RemoteInput.addResultsToIntent(inputs, intent, results)
            log("sending: $text")
            action.actionIntent.send(this, 0, intent)
            lastSent[key] = System.currentTimeMillis()
            if (!c.hidden) ChatMemory.merge(pkg, c.title, c.msgs + Msg("me", text))
            Prefs.autoSentCount = Prefs.autoSentCount + 1
        } catch (e: Exception) {
            log("reply failed: $e")
        } finally {
            if (wake.isHeld) wake.release()
        }
    }

    /** The user is on their phone right now (screen on and unlocked). */
    private fun userIsActive(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val km = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        return pm.isInteractive && !km.isKeyguardLocked
    }

    /** Image attachments from the last parsed notification, index N-1 for "[Photo N]". */
    private var imageUris: List<android.net.Uri> = emptyList()

    private class Convo(val title: String, val msgs: List<Msg>, val group: Boolean, val hidden: Boolean = false)

    private val HIDDEN = Regex("^sensitive notification", RegexOption.IGNORE_CASE)

    private fun conversation(n: Notification): Convo? {
        imageUris = emptyList()
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        val extras = n.extras
        val title = (style?.conversationTitle ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE))?.toString() ?: return null
        val shown = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        if (HIDDEN.containsMatchIn(shown) || style?.messages?.any { HIDDEN.containsMatchIn(it.text?.toString() ?: "") } == true) {
            // Android 15+ can hide message text from other apps. We can still answer without reading it.
            return Convo(title, listOf(Msg("them", "(Their message is hidden. Don't guess what it says.)")), extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION), hidden = true)
        }
        if (style != null) {
            val me = style.user.name?.toString()
            val images = ArrayList<android.net.Uri>()
            val msgs = style.messages.mapNotNull { m ->
                val who = if (m.person == null || m.person?.name?.toString() == me) "me" else "them"
                val uri = m.dataUri?.takeIf { m.dataMimeType?.startsWith("image/") == true }
                val t = if (uri != null) {
                    images.add(uri)
                    "[Photo ${images.size}]" + (m.text?.toString()?.takeIf { it.isNotBlank() && !it.contains("photo", true) }?.let { " $it" } ?: "")
                } else m.text?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Msg(who, t, m.person?.name?.toString())
            }
            imageUris = images
            val senders = style.messages.mapNotNull { it.person?.name?.toString() }.filter { it != me }.toSet()
            return Convo(title, msgs, style.isGroupConversation || senders.size > 1)
        }
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() } ?: return null
        val group = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION) || title.contains(":")
        return Convo(title, listOf(Msg("them", text)), group)
    }

    private fun replyAction(n: Notification): Notification.Action? {
        fun ok(a: Notification.Action) = a.remoteInputs?.any { it.allowFreeFormInput } == true
        n.actions?.firstOrNull(::ok)?.let { return it }
        @Suppress("DEPRECATION")
        return Notification.WearableExtender(n).actions.firstOrNull(::ok)
    }

    companion object {
        val APPS = setOf(
            "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thoughtcrime.securesms",
            "com.facebook.orca", "com.instagram.android", "com.google.android.apps.messaging",
            "com.samsung.android.messaging", "com.discord", "com.Slack", "com.viber.voip",
            "jp.naver.line.android", "com.microsoft.teams",
        )

        /** Apps shown in Settings, label to packages. */
        val APP_CHOICES = listOf(
            "WhatsApp" to setOf("com.whatsapp", "com.whatsapp.w4b"),
            "Messages" to setOf("com.google.android.apps.messaging", "com.samsung.android.messaging"),
            "Instagram" to setOf("com.instagram.android"),
            "Messenger" to setOf("com.facebook.orca"),
            "Telegram" to setOf("org.telegram.messenger"),
            "Signal" to setOf("org.thoughtcrime.securesms"),
            "Others" to setOf("com.discord", "com.Slack", "com.viber.voip", "jp.naver.line.android", "com.microsoft.teams"),
        )

        fun hasAccess(ctx: Context) = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

        fun openAccess(ctx: Context) {
            val cn = ComponentName(ctx, AutoReplyService::class.java).flattenToString()
            val detail = Intent("android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS")
                .putExtra("android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME", cn)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (android.os.Build.VERSION.SDK_INT >= 30 && runCatching { ctx.startActivity(detail) }.isSuccess) return
            runCatching { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}
