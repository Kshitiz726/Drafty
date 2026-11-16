package app.drafty.service

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import app.drafty.claude.ContextMath
import app.drafty.claude.Kind
import app.drafty.claude.Msg
import app.drafty.data.Prefs

data class ScreenItem(val text: String, val bounds: Rect, val who: String)

/** One piece of text on screen. [page] is how many scrolls down it was found (0 = as the user sees it). */
data class RawItem(val text: String, val rect: Rect, val page: Int = 0)

data class ScreenContext(
    val kind: Kind,
    val title: String,
    val transcript: String,
    val omitted: Int,
    val lastIsMe: Boolean,
    val stats: String,
    val plain: String,
    val items: Int = 0,
    /** On-screen bounds of "[Photo N]" images, index N-1, for describing them. */
    val images: List<Rect> = emptyList(),
    /** Every chat message read from the screen, oldest first (for chat memory). */
    val msgs: List<Msg> = emptyList(),
)

/** What the focused text box is for, from its hint and view id. */
enum class FieldKind { CHAT, COMMENT, UNKNOWN }

/**
 * Reads another app's screen through the accessibility tree.
 * Chat bubbles are classified by geometry: right-aligned = me, left-aligned = them.
 */
object ScreenReader {
    val CHAT_APPS = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thoughtcrime.securesms",
        "com.facebook.orca", "com.google.android.apps.messaging", "com.samsung.android.messaging",
        "com.discord", "com.Slack", "com.viber.voip", "jp.naver.line.android", "com.snapchat.android",
        "com.microsoft.teams", "com.instagram.barcelona.direct",
    )
    val EMAIL_APPS = setOf(
        "com.google.android.gm", "com.microsoft.office.outlook", "com.yahoo.mobile.client.android.mail",
        "ch.protonmail.android", "com.samsung.android.email.provider",
    )
    private val NOISE = Regex(
        "^(seen|delivered|sent|read|edited|online|typing\\.*|today|yesterday|reply|replies|like|likes|share|send|more|back|" +
            "see translation|view replies|hide replies|view \\d+ more repl(y|ies)|translate|pinned|author|follow|following|" +
            "navigate up|more options|\\d{1,2}[:.]\\d{2}(\\s?[ap]\\.?m\\.?)?|\\d+[smhdw]|\\d+ (min|mins|hours?|days?|weeks?) ago|\\d+ likes?)$",
        RegexOption.IGNORE_CASE,
    )
    private val NOISE_CD = Regex(
        "^(like|unlike|reply|profile picture|double tap|go to|view|more options|send|share|save|tap to|button|play|pause|mute|unmute|close)\\b",
        RegexOption.IGNORE_CASE,
    )
    const val IMG = "⁣IMG:"
    private val IMAGE_CD = Regex("photo|image|picture|video|gif|sticker|thumbnail|reel", RegexOption.IGNORE_CASE)

    private val COMMENT_HINT = Regex("comment|reply to|add a reply|join the conversation|write a reply|respond|reply as|post your reply|add reply", RegexOption.IGNORE_CASE)
    private val CHAT_HINT = Regex("message|type a|text|chat|imessage|send a|^aa$|write to|signal", RegexOption.IGNORE_CASE)

    fun fieldKind(field: AccessibilityNodeInfo?): FieldKind {
        field ?: return FieldKind.UNKNOWN
        val hint = buildString {
            if (Build.VERSION.SDK_INT >= 26) append(field.hintText ?: "")
            if (Build.VERSION.SDK_INT >= 26 && field.isShowingHintText) append(" ").append(field.text ?: "")
            append(" ").append(field.contentDescription ?: "")
        }.trim()
        val id = field.viewIdResourceName?.lowercase() ?: ""
        return when {
            COMMENT_HINT.containsMatchIn(hint) || id.contains("comment") -> FieldKind.COMMENT
            id.contains("message") || id.contains("entry") || id.contains("chat") || id.contains("compose") ||
                (hint.isNotBlank() && CHAT_HINT.containsMatchIn(hint)) -> FieldKind.CHAT
            else -> FieldKind.UNKNOWN
        }
    }

    fun read(root: AccessibilityNodeInfo?, target: AccessibilityNodeInfo?, pkg: String, screenW: Int, screenH: Int, full: Boolean = false): ScreenContext? {
        root ?: return null
        return build(collectRaw(root), target, pkg, screenW, screenH, full, deep = false)
    }

    fun collectRaw(root: AccessibilityNodeInfo, page: Int = 0): List<RawItem> {
        val out = ArrayList<RawItem>()
        collect(root, out, 0, page)
        return out
    }

    fun build(
        raw: List<RawItem>, target: AccessibilityNodeInfo?, pkg: String, screenW: Int, screenH: Int,
        full: Boolean, deep: Boolean,
    ): ScreenContext? {
        val targetRect = Rect().also { runCatching { target?.getBoundsInScreen(it) } }
        val hasTarget = target != null && !targetRect.isEmpty
        val topBar = (screenH * 0.13).toInt()
        val title = raw.firstOrNull { it.page == 0 && it.rect.bottom in 1..topBar && it.text.length in 2..60 && !NOISE.matches(it.text) }?.text ?: ""

        val seen = HashSet<String>()
        var items = raw.asSequence()
            .filter { it.rect.top >= topBar - 4 || it.rect.bottom > topBar + 8 || it.page > 0 }
            // Email: the message being answered can sit below the reply box (quoted text), so keep it.
            .filter { !hasTarget || pkg in EMAIL_APPS || it.rect.top < targetRect.top }
            .filter { !NOISE.matches(it.text.trim()) }
            .filter { it.text.startsWith(IMG) || seen.add(it.text.trim()) || it.text.length < 4 }
            .sortedWith(compareBy({ it.page }, { it.rect.top }, { it.rect.left }))
            .map { r ->
                // Sent bubbles hug the right edge, received ones the left. Compare the two gaps,
                // so long messages that span most of the width are still attributed correctly.
                val left = r.rect.left
                val right = screenW - r.rect.right
                val margin = screenW * 0.035
                val who = when {
                    left - right > margin -> "me"
                    right - left > margin -> "them"
                    else -> "system"
                }
                ScreenItem(r.text.trim(), r.rect, who)
            }
            .filter { it.text.isNotEmpty() }
            .toList()

        // A row's description often repeats its own texts; keep the longer one.
        items = items.filterIndexed { i, a ->
            a.text.startsWith(IMG) ||
                items.withIndex().none { (j, b) -> j != i && !b.text.startsWith(IMG) && b.text.length > a.text.length + 2 && b.text.contains(a.text) && Rect.intersects(a.bounds, b.bounds) }
        }
        // Images: drop nested duplicates, then number the ones worth describing (most recent in chats, largest on posts).
        val imgs = items.filter { it.text.startsWith(IMG) }
        val keepImg = imgs.filter { a -> imgs.none { b -> b !== a && b.bounds.contains(a.bounds) && b.bounds != a.bounds } }.toMutableSet()
        val fk0 = fieldKind(target)
        val chatLike = pkg in CHAT_APPS || fk0 == FieldKind.CHAT
        val ranked = if (chatLike) keepImg.sortedBy { it.bounds.top }.takeLast(3) else keepImg.sortedByDescending { it.bounds.width() * it.bounds.height() }.take(2)
        val numbered = ranked.sortedBy { it.bounds.top }
        val imageRects = numbered.map { Rect(it.bounds) }
        items = items.mapNotNull { it ->
            if (!it.text.startsWith(IMG)) it
            else if (it !in keepImg) null
            else {
                val n = numbered.indexOf(it)
                val label = it.text.removePrefix(IMG).trim().takeIf { l -> l.isNotEmpty() && !IMAGE_CD.matches(l) }
                val tag = "[Photo]"
                it.copy(text = if (label != null) "$tag (${label})" else tag)
            }
        }
        if (items.isEmpty()) return null

        val fk = fieldKind(target)
        val hasMe = items.any { it.who == "me" }
        val hasThem = items.any { it.who == "them" }
        val kind = when {
            pkg in EMAIL_APPS -> Kind.EMAIL
            fk == FieldKind.COMMENT -> Kind.POST
            pkg in CHAT_APPS -> Kind.CHAT
            fk == FieldKind.CHAT && hasThem -> Kind.CHAT
            hasMe && hasThem && items.count { it.who != "system" } >= 3 -> Kind.CHAT
            else -> Kind.POST
        }

        val plain = items.joinToString("\n") { it.text }
        return when (kind) {
            Kind.CHAT -> {
                val msgs = items.filter { it.who != "system" || it.text.length > 25 || it.text.startsWith("[Photo") }.map { Msg(if (it.who == "system" && it.text.startsWith("[Photo")) "them" else it.who, it.text) }
                val sel = if (full) ContextMath.select(msgs, 12_000, Prefs.halfLife, coverage = 1.01, minMessages = msgs.size, maxMsgChars = 3000)
                    else ContextMath.select(msgs, Prefs.budgetTokens, Prefs.halfLife)
                ScreenContext(
                    kind, title, ContextMath.format(sel.selected, title), sel.omitted,
                    msgs.lastOrNull()?.who == "me",
                    "${sel.selected.size}/${sel.total} msgs · ~${sel.tokens} tok · ${(sel.coverage * 100).toInt()}%",
                    plain, items.size, imageRects, msgs,
                )
            }
            else -> {
                val budget = when {
                    full -> 48_000
                    deep -> maxOf(Prefs.budgetTokens * 4, Prefs.commentLimit * 260)
                    else -> Prefs.budgetTokens * 4
                }
                val text = ContextMath.clip(plain, budget)
                ScreenContext(kind, title, text, 0, false, "${items.size} items · ~${ContextMath.estimateTokens(text)} tok", plain, items.size, imageRects)
            }
        }
    }

    private val DOWN = android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
    private val UP = android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id

    fun scrollDownId(n: AccessibilityNodeInfo) =
        if (n.actionList.any { it.id == DOWN }) DOWN else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD

    fun scrollUpId(n: AccessibilityNodeInfo) =
        if (n.actionList.any { it.id == UP }) UP else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD

    private fun textCount(n: AccessibilityNodeInfo, depth: Int = 0, budget: IntArray = intArrayOf(400)): Int {
        if (depth > 25 || budget[0]-- <= 0) return 0
        var c = if (!n.text.isNullOrBlank()) 1 else 0
        for (i in 0 until n.childCount) n.getChild(i)?.let { c += textCount(it, depth + 1, budget) }
        return c
    }

    /** A pager shows one full-size page at a time (reels, stories, swipeable posts). Never scroll those. */
    private fun isPager(n: AccessibilityNodeInfo, r: Rect): Boolean {
        val cls = n.className?.toString() ?: ""
        if (cls.contains("ViewPager", ignoreCase = true)) return true
        val ci = n.collectionInfo
        if (ci != null && ci.columnCount > 1 && ci.rowCount <= 1) return true
        val kids = (0 until n.childCount).mapNotNull { n.getChild(it) }.filter { it.isVisibleToUser }
        if (kids.size in 1..2) {
            val tallest = kids.maxOf { Rect().also { k -> it.getBoundsInScreen(k) }.height() }
            if (tallest >= r.height() * 0.7) return true
        }
        return false
    }

    /**
     * The comment list on screen, for reading comments beyond what is visible.
     * Only vertical lists with real text; horizontal carousels and full-screen pagers like Reels are skipped,
     * so the post or video the user is on never moves.
     */
    fun findScroller(root: AccessibilityNodeInfo, above: Rect?, screenH: Int): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>().apply { add(root to 0) }
        while (stack.isNotEmpty()) {
            val (n, d) = stack.removeLast()
            if (d > 50 || !n.isVisibleToUser) continue
            val actions = n.actionList.map { it.id }
            val canDown = DOWN in actions || AccessibilityNodeInfo.ACTION_SCROLL_FORWARD in actions
            val horizontalOnly = (android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id in actions ||
                android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id in actions) && DOWN !in actions && UP !in actions
            if (n.isScrollable && canDown && !horizontalOnly) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                val vertical = r.height() > r.width() * 0.3
                // The list must sit above the comment box and must not contain it (a feed post with an inline box).
                val ok = above == null || above.isEmpty || (r.top < above.top && !r.contains(above.centerX(), above.centerY()))
                if (vertical && ok && !isPager(n, r)) {
                    val texts = textCount(n)
                    if (texts >= 4 && texts > bestScore) { best = n; bestScore = texts }
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it to d + 1) }
        }
        return best
    }

    private fun collect(node: AccessibilityNodeInfo, out: MutableList<RawItem>, depth: Int, page: Int) {
        if (depth > 60) return
        if (!node.isVisibleToUser) return
        if (node.isEditable) return
        val r = Rect().also { node.getBoundsInScreen(it) }
        val text = node.text?.toString()?.takeIf { it.isNotBlank() }
        val cls = node.className?.toString() ?: ""
        val cdText = node.contentDescription?.toString() ?: ""
        if (page == 0 && text == null && r.width() >= 180 && r.height() >= 150 &&
            (cls.contains("Image") || IMAGE_CD.containsMatchIn(cdText))) {
            out.add(RawItem(IMG + cdText.take(80), r, page))
        }
        if (text != null && !cls.endsWith("Button") && !cls.endsWith("EditText")) {
            out.add(RawItem(text, r, page))
        } else if (text == null) {
            val cd = node.contentDescription?.toString()?.trim()
            if (cd != null && !NOISE_CD.containsMatchIn(cd) &&
                ((node.childCount == 0 && cd.length >= 3 && !node.isClickable) || cd.length >= 15)
            ) out.add(RawItem(cd, r, page))
        }
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            collect(c, out, depth + 1, page)
        }
    }
}
