package app.drafty.claude

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

data class Msg(val who: String, val text: String, val name: String? = null, val ts: Long? = null)

data class Selection(
    val selected: List<Msg>,
    val total: Int,
    val tokens: Int,
    val coverage: Double,
    val unanswered: Int,
) {
    val omitted get() = total - selected.size
}

/**
 * Relevance w_i = 2^(-age/h) * rho^(session breaks). Sends the shortest recent stretch that
 * includes every unanswered message, has at least [minMessages], holds [coverage] of the total
 * weight and fits [budgetTokens].
 */
object ContextMath {
    fun estimateTokens(text: String): Int {
        var ascii = 0
        var other = 0
        text.forEach { if (it.code < 128) ascii++ else other++ }
        return ceil(ascii / 4.0 + other / 1.5).toInt() + 4
    }

    fun clip(text: String, max: Int): String {
        if (text.length <= max) return text
        val half = (max - 7) / 2
        return text.take(half) + " [...] " + text.takeLast(half)
    }

    fun select(
        messages: List<Msg>,
        budgetTokens: Int = 3000,
        halfLife: Int = 10,
        coverage: Double = 0.95,
        minMessages: Int = 8,
        sessionGapMs: Long = 3 * 3600_000L,
        sessionDecay: Double = 0.35,
        maxMsgChars: Int = 1500,
    ): Selection {
        val n = messages.size
        if (n == 0) return Selection(emptyList(), 0, 0, 1.0, 0)
        // A longer memory (half-life up to 100 messages) gets a proportionally larger budget and minimum.
        val budgetTokens = maxOf(budgetTokens, minOf(16_000, halfLife * 90))
        val minMessages = maxOf(minMessages, minOf(halfLife, 60))
        val lastMe = messages.indexOfLast { it.who == "me" }
        val unanswered = n - 1 - lastMe

        val breaks = IntArray(n)
        var b = 0
        for (i in n - 1 downTo 0) {
            breaks[i] = b
            val t1 = messages[i].ts
            val t0 = if (i > 0) messages[i - 1].ts else null
            if (t1 != null && t0 != null && t1 - t0 > sessionGapMs) b++
        }
        val lambda = ln(2.0) / halfLife
        val w = DoubleArray(n) { i -> exp(-lambda * (n - 1 - i)) * sessionDecay.pow(breaks[i]) }
        val total = w.sum()
        val minL = minOf(n, maxOf(minMessages, unanswered + 1))

        val out = ArrayList<Msg>()
        var cum = 0.0
        var tokens = 0
        for (i in n - 1 downTo 0) {
            val m = messages[i]
            val text = clip(m.text, if (i == n - 1) maxMsgChars * 3 else maxMsgChars)
            val t = estimateTokens(text)
            val required = (n - 1 - i) < unanswered && tokens < budgetTokens * 2
            if (!required) {
                if (out.size >= minL && cum / total >= coverage) break
                if (tokens + t > budgetTokens) break
            }
            out.add(m.copy(text = text))
            cum += w[i]
            tokens += t
        }
        out.reverse()
        return Selection(out, n, tokens, cum / total, unanswered)
    }


    /** Speaker label for the other person: their name (from the chat title) when known. */
    fun themLabel(title: String): String {
        val t = title.trim().replace("[", "").replace("]", "")
        return if (t.isEmpty() || t.length > 40 || t.equals("me", true)) "Them" else t
    }

    /** One line per message: "[Me] ..." for me, "[Name] ..." for the other person. */
    fun format(selected: List<Msg>, title: String): String {
        val them = themLabel(title)
        return selected.joinToString("\n") { m ->
            val who = when (m.who) {
                "me" -> "[Me]"
                "system" -> "[notice]"
                else -> "[" + (m.name?.takeIf { it.isNotBlank() && it != title }?.let { "$them / $it" } ?: them) + "]"
            }
            "$who ${m.text.replace("\n", "\n    ")}"
        }
    }
}
