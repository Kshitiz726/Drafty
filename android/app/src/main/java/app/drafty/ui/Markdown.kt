package app.drafty.ui

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Small markdown renderer: headings, bullets, numbered lists and **bold**. */
object Markdown {
    private data class Line(val text: String, val heading: Boolean)

    private fun lines(md: String): List<Line> = md.replace("\r", "").lines().map { raw ->
        val l = raw.trimEnd()
        when {
            Regex("^#{1,4}\\s+").containsMatchIn(l) -> Line(l.replace(Regex("^#{1,4}\\s+"), ""), true)
            Regex("^\\s*[-*•]\\s+").containsMatchIn(l) -> Line("  •  " + l.replace(Regex("^\\s*[-*•]\\s+"), ""), false)
            else -> Line(l, false)
        }
    }.let { list ->
        // collapse runs of blank lines
        list.filterIndexed { i, ln -> !(ln.text.isBlank() && i > 0 && list[i - 1].text.isBlank()) }
    }

    private val BOLD = Regex("\\*\\*([^*]+)\\*\\*")

    fun annotated(md: String): AnnotatedString = buildAnnotatedString {
        lines(md).forEachIndexed { i, ln ->
            if (i > 0) append('\n')
            if (ln.heading) {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp))
                append(ln.text.replace("**", ""))
                pop()
            } else {
                var last = 0
                BOLD.findAll(ln.text).forEach { m ->
                    append(ln.text.substring(last, m.range.first))
                    pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold))
                    append(m.groupValues[1])
                    pop()
                    last = m.range.last + 1
                }
                append(ln.text.substring(last))
            }
        }
    }

    fun spanned(md: String): Spanned {
        val sb = SpannableStringBuilder()
        lines(md).forEachIndexed { i, ln ->
            if (i > 0) sb.append('\n')
            if (ln.heading) {
                val start = sb.length
                sb.append(ln.text.replace("**", ""))
                sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(RelativeSizeSpan(1.06f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                var last = 0
                BOLD.findAll(ln.text).forEach { m ->
                    sb.append(ln.text.substring(last, m.range.first))
                    val s = sb.length
                    sb.append(m.groupValues[1])
                    sb.setSpan(StyleSpan(Typeface.BOLD), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    last = m.range.last + 1
                }
                sb.append(ln.text.substring(last))
            }
        }
        return sb
    }
}
