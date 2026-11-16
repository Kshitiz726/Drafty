package app.drafty.claude

import android.content.Context
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Gemini through its own web endpoint with the user's signed-in Google session (cookies from sign-in).
 * No page automation, so nothing on the page can block it. Emits the full text so far.
 */
object GeminiClient {
    private lateinit var appContext: Context
    private var tokens: Tokens? = null

    private class Tokens(val at: String, val bl: String, val sid: String, val time: Long)

    fun init(context: Context) { appContext = context.applicationContext }

    private fun cookies(): String {
        val cm = CookieManager.getInstance()
        val parts = LinkedHashMap<String, String>()
        for (u in listOf("https://google.com", "https://www.google.com", "https://gemini.google.com")) {
            cm.getCookie(u)?.split(';')?.forEach { c ->
                val kv = c.trim()
                val i = kv.indexOf('=')
                if (i > 0) parts[kv.substring(0, i)] = kv.substring(i + 1)
            }
        }
        return parts.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 90_000
        instanceFollowRedirects = true
        setRequestProperty("Cookie", cookies())
        setRequestProperty("User-Agent", ProviderBridge.browserUserAgent(appContext))
        setRequestProperty("Origin", "https://gemini.google.com")
        setRequestProperty("Referer", "https://gemini.google.com/")
    }

    private fun auth(force: Boolean): Tokens {
        tokens?.let { if (!force && System.currentTimeMillis() - it.time < 20 * 60_000) return it }
        val c = open("https://gemini.google.com/app")
        val html = try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
        fun pick(key: String) = Regex("\"$key\":\"(.*?)\"").find(html)?.groupValues?.get(1) ?: ""
        val at = pick("SNlM0e")
        if (at.isEmpty()) throw ClaudeException("auth", "Not signed in to Gemini. Open Drafty Settings and sign in to Gemini.")
        return Tokens(at, pick("cfb2h"), pick("FdrFJe"), System.currentTimeMillis()).also { tokens = it }
    }

    /** The chat id ("c_...") of the conversation a reply created, from inner[1][0]. */
    private fun chatId(raw: String): String? {
        for (line in raw.lineSequence()) {
            val l = line.trim()
            if (!l.startsWith("[")) continue
            val outer = runCatching { JSONArray(l) }.getOrNull() ?: continue
            for (i in 0 until outer.length()) {
                val part = outer.optJSONArray(i) ?: continue
                if (part.optString(0) != "wrb.fr") continue
                val payload = part.opt(2) as? String ?: continue
                val id = runCatching { JSONArray(payload).getJSONArray(1).getString(0) }.getOrNull()
                if (id != null && id.startsWith("c_")) return id
            }
        }
        return null
    }

    /** Deletes a chat completely (title and messages) from the user's Gemini history. */
    private fun delete(tk: Tokens, id: String): Boolean = runCatching {
        val qs = "rpcids=GzXR5e&bl=${enc(tk.bl)}&_reqid=${100000 + (Math.random() * 800000).toInt()}&rt=c&source-path=%2Fapp" +
            (if (tk.sid.isNotEmpty()) "&f.sid=${enc(tk.sid)}" else "")
        val c = open("https://gemini.google.com/_/BardChatUi/data/batchexecute?$qs")
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
            c.setRequestProperty("X-Same-Domain", "1")
            val req = "[[[\"GzXR5e\"," + org.json.JSONObject.quote("[" + org.json.JSONObject.quote(id) + "]") + ",null,\"generic\"]]]"
            c.outputStream.use { it.write("f.req=${enc(req)}&at=${enc(tk.at)}".toByteArray()) }
            c.responseCode in 200..299 && c.inputStream.bufferedReader().readText().contains("GzXR5e")
        } finally { c.disconnect() }
    }.getOrDefault(false)

    /** Deletes this chat, plus any earlier ones whose delete failed. */
    private fun cleanup(tk: Tokens, id: String?) {
        if (!app.drafty.data.Prefs.cleanup) return
        val pending = app.drafty.data.Prefs.geminiPending.toMutableSet()
        id?.let { pending.add(it) }
        val failed = pending.filterNot { delete(tk, it) }.toSet()
        app.drafty.data.Prefs.geminiPending = failed
    }

    /** Candidate text sits at inner[4][0][1][0] of each "wrb.fr" entry. */
    private fun extract(raw: String): String {
        var best = ""
        for (line in raw.lineSequence()) {
            val l = line.trim()
            if (!l.startsWith("[")) continue
            val outer = runCatching { JSONArray(l) }.getOrNull() ?: continue
            for (i in 0 until outer.length()) {
                val part = outer.optJSONArray(i) ?: continue
                if (part.optString(0) != "wrb.fr") continue
                val payload = part.opt(2) as? String ?: continue
                val t = runCatching {
                    JSONArray(payload).getJSONArray(4).getJSONArray(0).getJSONArray(1).getString(0)
                }.getOrNull() ?: continue
                if (t.length >= best.length) best = t
            }
        }
        return best
    }

    fun generate(prompt: String): Flow<String> = flow {
        var attempt = 0
        while (true) {
            val tk = auth(force = attempt > 0)
            val inner = "[[" + org.json.JSONObject.quote(prompt) + ",0,null,null,null,null,0],null,null]"
            val fReq = "[null," + org.json.JSONObject.quote(inner) + "]"
            val qs = "bl=${enc(tk.bl)}&_reqid=${100000 + (Math.random() * 800000).toInt()}&rt=c" + (if (tk.sid.isNotEmpty()) "&f.sid=${enc(tk.sid)}" else "")
            val c = open("https://gemini.google.com/_/BardChatUi/data/assistant.lamda.BardFrontendService/StreamGenerate?$qs")
            try {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                c.setRequestProperty("X-Same-Domain", "1")
                c.outputStream.use { it.write("f.req=${enc(fReq)}&at=${enc(tk.at)}".toByteArray()) }
                val code = c.responseCode
                if (code in listOf(400, 401, 403) && attempt == 0) { attempt++; tokens = null; continue }
                if (code == 429) throw ClaudeException("rate", "Gemini usage limit reached. Try again later.")
                if (code !in 200..299) throw ClaudeException("auth", "Gemini refused the request (HTTP $code). Sign in to Gemini again in Settings.")
                val raw = StringBuilder()
                var last = ""
                val buf = CharArray(4096)
                c.inputStream.bufferedReader().use { r ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = r.read(buf)
                        if (n < 0) break
                        raw.append(buf, 0, n)
                        val t = extract(raw.toString())
                        if (t.isNotEmpty() && t != last) { last = t; emit(t) }
                    }
                }
                cleanup(tk, chatId(raw.toString()))
                if (last.isEmpty()) throw ClaudeException("driver", "Gemini returned no answer. Try again.")
                return@flow
            } finally {
                c.disconnect()
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
