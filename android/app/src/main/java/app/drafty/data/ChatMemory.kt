package app.drafty.data

import android.content.Context
import android.content.SharedPreferences
import app.drafty.claude.Msg
import org.json.JSONArray
import org.json.JSONObject

/**
 * Remembers recent messages per conversation, on this device only, so replies sent while the phone
 * is locked use the whole conversation and not just the one message in the notification.
 * Filled from chats you open and from incoming notifications.
 */
object ChatMemory {
    private const val MAX = 80
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences("chat_memory", Context.MODE_PRIVATE)
    }

    private fun key(pkg: String, title: String) = pkg + "|" + title.trim().lowercase()

    fun get(pkg: String, title: String): List<Msg> {
        if (!::sp.isInitialized || title.isBlank()) return emptyList()
        val raw = sp.getString(key(pkg, title), null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Msg(o.getString("w"), o.getString("t"), o.optString("n").takeIf { it.isNotEmpty() })
            }
        }.getOrDefault(emptyList())
    }

    /** Adds what is visible now to what was seen before, joining them where they overlap. */
    fun merge(pkg: String, title: String, visible: List<Msg>): List<Msg> {
        if (!::sp.isInitialized || title.isBlank() || visible.isEmpty()) return visible
        val merged = join(get(pkg, title), visible).takeLast(MAX)
        val a = JSONArray()
        merged.forEach { m ->
            a.put(JSONObject().put("w", m.who).put("t", m.text.take(1500)).put("n", m.name ?: ""))
        }
        sp.edit().putString(key(pkg, title), a.toString()).apply()
        return merged
    }

    private fun same(a: Msg, b: Msg) = a.text.trim() == b.text.trim() && (a.who == b.who || a.who == "system" || b.who == "system")

    /** Longest overlap between the end of [old] and the start of [new]; the rest of [new] is appended. */
    fun join(old: List<Msg>, new: List<Msg>): List<Msg> {
        if (old.isEmpty()) return new
        if (new.isEmpty()) return old
        // The new window may start anywhere inside the old history.
        val firstIdx = old.indexOfLast { same(it, new.first()) }
        if (firstIdx >= 0) {
            var i = firstIdx
            var j = 0
            while (i < old.size && j < new.size && same(old[i], new[j])) { i++; j++ }
            if (i == old.size) return old + new.drop(j)
        }
        // New messages that are already the tail of the history: nothing to add.
        val tail = old.takeLast(new.size)
        if (tail.size == new.size && tail.indices.all { same(tail[it], new[it]) }) return old
        // No overlap found: keep both, newest last.
        val known = old.map { it.who + "|" + it.text.trim() }.toHashSet()
        return old + new.filter { (it.who + "|" + it.text.trim()) !in known }
    }
}
