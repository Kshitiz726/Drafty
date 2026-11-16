package app.drafty.claude

import android.webkit.CookieManager
import app.drafty.data.Prefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.runningFold

enum class Provider(
    val id: String,
    val label: String,
    val loginUrl: String,
    val homeUrl: String,
    val cookieUrl: String = "",
    val cookieNames: List<String> = emptyList(),
) {
    CLAUDE("claude", "Claude", "https://claude.ai/login", "https://claude.ai/new"),
    CHATGPT("chatgpt", "ChatGPT", "https://chatgpt.com/auth/login", "https://chatgpt.com/?temporary-chat=true",
        "https://chatgpt.com", listOf("__Secure-next-auth.session-token", "__Secure-next-auth.session-token.0")),
    GEMINI("gemini", "Gemini", "https://accounts.google.com/ServiceLogin?continue=https://gemini.google.com/app", "https://gemini.google.com/app",
        "https://gemini.google.com", listOf("__Secure-1PSID", "SID")),
    GROK("grok", "Grok", "https://accounts.x.ai/sign-in?redirect=grok-com", "https://grok.com/",
        "https://grok.com", listOf("sso", "sso-rw"));

    companion object {
        /** Models offered in the app. ChatGPT is hidden for now. */
        /** Models offered in the app: Claude and Gemini. */
        val visible get() = listOf(CLAUDE, GEMINI)

        fun from(id: String) = visible.firstOrNull { it.id == id } ?: CLAUDE
    }
}

/** One entry point for every provider. Emits the full reply text so far on each update. */
object Ai {
    val provider: Provider get() = Provider.from(Prefs.provider)

    fun generate(prompt: String): Flow<String> = when (val p = provider) {
        Provider.GEMINI -> GeminiClient.generate(prompt)
        Provider.CLAUDE -> ClaudeBridge.generate(prompt).runningFold("") { acc, d -> acc + d }.drop(1)
        else -> ProviderBridge.generate(p, prompt)
    }

    /** Fast local check from cookies; Claude is verified against its API. */
    fun hasSession(p: Provider): Boolean {
        if (p == Provider.CLAUDE) return true
        val cookies = CookieManager.getInstance().getCookie(p.cookieUrl) ?: return false
        return p.cookieNames.any { name -> cookies.split(';').any { it.trim().startsWith("$name=") } }
    }

    suspend fun status(p: Provider): ClaudeStatus =
        if (p == Provider.CLAUDE) ClaudeBridge.status()
        else if (hasSession(p)) ClaudeStatus(true, plan = "Signed in")
        else if (p == Provider.CHATGPT) ClaudeStatus(true, plan = "Guest mode", code = "guest")
        else ClaudeStatus(false, code = "auth", message = "Not signed in")
}
