package app.drafty.service

import android.accessibilityservice.AccessibilityService
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.drafty.R
import app.drafty.claude.Action
import app.drafty.claude.Ai
import app.drafty.claude.ClaudeBridge
import app.drafty.claude.Kind
import app.drafty.claude.Provider
import app.drafty.claude.ProviderBridge
import app.drafty.claude.Prompts
import app.drafty.data.Prefs
import app.drafty.ui.Markdown
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlin.math.hypot

/**
 * One floating Drafty button, docked to the screen edge. Everything lives inside it:
 * - Tap in an empty chat or email box: the reply is written straight into the box (never sent).
 * - Tap otherwise: the Drafty hub opens with refine, reply, catch up, analyze, summarize,
 *   translate, ideas, ask, and on posts the comment tools (TL;DR, summarize comments, opinions...).
 */
class DraftyAccessibilityService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var wm: WindowManager
    private lateinit var ctx: Context
    private lateinit var ui: OverlayUi

    private var button: View? = null
    private var buttonCircle: View? = null
    private var buttonIcon: ImageView? = null
    private var ring: Ring? = null
    private var spin: ObjectAnimator? = null
    private var pulse: ObjectAnimator? = null
    private var card: View? = null
    private var cardClosedAt = 0L
    private var ignoreOutsideUntil = 0L
    private var host: FrameLayout? = null

    private var target: AccessibilityNodeInfo? = null
    private var targetPkg = ""
    private var screenPkg = ""
    private var job: Job? = null
    private var busy = false

    private var themeApplied = ""
    private var lastDraft = ""
    private var lastAutoSig = ""

    private fun systemNight(): Int {
        val um = getSystemService(Context.UI_MODE_SERVICE) as android.app.UiModeManager
        return when (um.nightMode) {
            android.app.UiModeManager.MODE_NIGHT_YES -> android.content.res.Configuration.UI_MODE_NIGHT_YES
            android.app.UiModeManager.MODE_NIGHT_NO -> android.content.res.Configuration.UI_MODE_NIGHT_NO
            else -> applicationContext.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        }
    }

    /** Overlay views follow the app theme setting (system, light or dark). */
    private fun applyTheme() {
        val sysNight = systemNight()
        val mode = Prefs.theme + ":" + sysNight
        if (mode == themeApplied && ::ui.isInitialized) return
        themeApplied = mode
        val cfg = android.content.res.Configuration(resources.configuration)
        val night = when (Prefs.theme) {
            "dark" -> android.content.res.Configuration.UI_MODE_NIGHT_YES
            "light" -> android.content.res.Configuration.UI_MODE_NIGHT_NO
            else -> sysNight
        }
        cfg.uiMode = (cfg.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        ctx = ContextThemeWrapper(createConfigurationContext(cfg), R.style.Theme_Drafty_Overlay)
        ui = OverlayUi(ctx)
    }

    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        applyTheme()
        ClaudeBridge.init(this)
        ProviderBridge.init(this)
        showButton()
        main.post(watchdog)
        if (!Prefs.onboarded) runCatching {
            startActivity(android.content.Intent(this, app.drafty.ui.MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        scope.cancel()
        button?.let { runCatching { wm.removeView(it) } }
        closeCard(animate = false)
        host?.let { runCatching { wm.removeView(it) } }
        ProviderBridge.detach()
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    // ---------------- events ----------------
    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        // A keyboard opening or closing: keep the card clear of it.
        if (e.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) { if (card != null) main.postDelayed({ fitAboveIme() }, 120); return }
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui" || isIme(pkg)) return
        if ((e.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) &&
            (pkg in ScreenReader.CHAT_APPS || pkg in ScreenReader.EMAIL_APPS || pkg in AutoReplyService.APPS)) {
            main.removeCallbacks(capture)
            main.postDelayed(capture, 1200)
        }
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            screenPkg = pkg
            if (pkg != targetPkg && card == null && !busy) { target = null; targetPkg = "" }
        }
        when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                val src = e.source ?: return
                if (src.isEditable && !src.isPassword) {
                    target = src; targetPkg = pkg
                    if (Prefs.autoDraft && (e.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED || e.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED)) {
                        main.removeCallbacks(autoDraft)
                        main.postDelayed(autoDraft, 450)
                    }
                }
            }
        }
        if (Prefs.showBubble && button == null) showButton()
        if (!Prefs.showBubble && button != null) { runCatching { wm.removeView(button) }; button = null }
    }

    /** Drafts by itself when a chat reply box is opened (setting: Draft automatically). Never for comments. */
    private val autoDraft = Runnable {
        if (busy || card != null || !Prefs.autoDraft) return@Runnable
        val t = liveTarget() ?: return@Runnable
        if (fieldText(t).isNotBlank()) return@Runnable
        val s = readScreen(false) ?: return@Runnable
        if (s.kind != Kind.CHAT || s.lastIsMe) return@Runnable
        val sig = targetPkg + "|" + s.title + "|" + s.transcript.takeLast(300).hashCode()
        if (sig == lastAutoSig) return@Runnable
        lastAutoSig = sig
        write(screen = s)
    }

    private fun isIme(pkg: String) = pkg.contains("inputmethod") || pkg.contains("keyboard") || pkg == "com.touchtype.swiftkey"

    private val watchdog = object : Runnable {
        override fun run() {
            val t = target
            if (card == null && !busy && t != null && !runCatching { t.refresh() && t.isVisibleToUser }.getOrDefault(false)) target = null
            main.postDelayed(this, 1000)
        }
    }

    // ---------------- helpers ----------------
    private fun liveTarget(): AccessibilityNodeInfo? {
        val t = target ?: return null
        val ok = runCatching { t.refresh() && t.isVisibleToUser && t.isEditable }.getOrDefault(false)
        val active = rootInActiveWindow?.packageName?.toString()
        return if (ok && (active == null || active == targetPkg || active == packageName)) t else null
    }

    /** Placeholders some apps report as real text (WhatsApp, Instagram, Messenger...). */
    private val HINTISH = Regex(
        "^(message|messages|type a message|text message|write a message|send a message|aa|add a comment|add a reply|reply|comment|write a comment|chat|message\\s.{0,30}(…|\\.\\.\\.)|(add|write|type|send) a .{0,30}(…|\\.\\.\\.))[.…]*$",
        RegexOption.IGNORE_CASE,
    )

    private fun fieldText(n: AccessibilityNodeInfo?): String {
        n ?: return ""
        runCatching { n.refresh() }
        if (Build.VERSION.SDK_INT >= 26 && n.isShowingHintText) return ""
        val t = n.text?.toString() ?: return ""
        if (Build.VERSION.SDK_INT >= 26 && n.hintText?.toString()?.trim()?.equals(t.trim(), ignoreCase = true) == true) return ""
        if (t.trim() != lastDraft.trim() && HINTISH.matches(t.trim())) return ""
        return t
    }

    /** Top edge of the on-screen keyboard, or the screen height when no keyboard is showing. */
    private fun imeTop(): Int {
        val h = resources.displayMetrics.heightPixels
        return runCatching {
            windows.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                .map { Rect().also { r -> it.getBoundsInScreen(r) }.top }.filter { it in 1 until h }.minOrNull()
        }.getOrNull() ?: h
    }

    private fun selectedText(n: AccessibilityNodeInfo?): String {
        val text = fieldText(n)
        val s = n?.textSelectionStart ?: -1
        val e = n?.textSelectionEnd ?: -1
        return if (s in 0 until e && e <= text.length) text.substring(s, e) else ""
    }

    private fun setField(n: AccessibilityNodeInfo?, text: String): Boolean = n != null && runCatching {
        n.refresh()
        n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        })
    }.getOrDefault(false)

    private fun appRoot(): AccessibilityNodeInfo? {
        val pkg = targetPkg.ifBlank { screenPkg }
        runCatching {
            for (w in windows) {
                val r = w.root ?: continue
                if (r.packageName?.toString() == pkg) return r
            }
        }
        return rootInActiveWindow?.takeIf { it.packageName?.toString() != packageName }
    }

    private fun readScreen(full: Boolean): ScreenContext? {
        val dm = resources.displayMetrics
        return runCatching { ScreenReader.read(appRoot(), liveTarget() ?: target, targetPkg.ifBlank { screenPkg }, dm.widthPixels, dm.heightPixels, full) }.getOrNull()
    }

    /**
     * The reply box of the open chat or comment thread when none is focused: the lowest visible
     * editable field in the lower part of the screen. Search bars at the top are never picked.
     */
    private fun findInput(): AccessibilityNodeInfo? {
        val root = appRoot() ?: return null
        val h = resources.displayMetrics.heightPixels
        var best: AccessibilityNodeInfo? = null
        var bestBottom = -1
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>().apply { add(root to 0) }
        while (stack.isNotEmpty()) {
            val (n, d) = stack.removeLast()
            if (d > 50 || !n.isVisibleToUser) continue
            if (n.isEditable && !n.isPassword) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                if (r.centerY() > h * 0.4 && r.bottom > bestBottom) { best = n; bestBottom = r.bottom }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it to d + 1) }
        }
        best?.let { target = it; targetPkg = root.packageName?.toString() ?: targetPkg }
        return best
    }

    private val COMPOSE_SIG = Regex("emoji|sticker|attach|voice|record|camera|gif|microphone|mic\\b|compose|send", RegexOption.IGNORE_CASE)

    private val SEND = Regex("^(send|reply|submit|post|enviar|envoyer|senden|invia|отправить|भेजें|পাঠান|kirim|gönder)(\\s.{0,40})?$", RegexOption.IGNORE_CASE)
    private val NOT_SEND = Regex("voice|record|audio|sticker|gif|attach|money|pay|camera|photo|location|schedule|later", RegexOption.IGNORE_CASE)

    /** Taps the app's own send button next to the reply box. */
    private fun clickSend(field: AccessibilityNodeInfo): Boolean {
        val root = appRoot() ?: return false
        val fr = Rect().also { runCatching { field.refresh(); field.getBoundsInScreen(it) } }
        var best: AccessibilityNodeInfo? = null
        var bestScore = Int.MAX_VALUE
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>().apply { add(root to 0) }
        while (stack.isNotEmpty()) {
            val (n, d) = stack.removeLast()
            if (d > 50 || !n.isVisibleToUser) continue
            val id = n.viewIdResourceName?.lowercase() ?: ""
            val label = (n.contentDescription ?: n.text ?: "").toString().trim()
            val isSend = !n.isEditable && (id.endsWith(":id/send") || id.contains("send_button") || id.contains("sendbutton") ||
                id.endsWith("/send_btn") || SEND.matches(label)) && !NOT_SEND.containsMatchIn(label)
            if (isSend) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                val score = kotlin.math.abs(r.centerY() - fr.centerY()) + (if (r.left >= fr.left) 0 else 400)
                if (score < bestScore) { best = n; bestScore = score }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it to d + 1) }
        }
        var n = best ?: return false
        while (!n.isClickable) n = n.parent ?: return false
        return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /**
     * For posts: reads past what is visible by scrolling the list, up to the comment limit in Settings,
     * then scrolls back to where the user was.
     */
    private suspend fun readDeep(full: Boolean, onProgress: (String) -> Unit = {}): ScreenContext? {
        val first = readScreen(full) ?: return null
        if (first.kind != Kind.POST) return first
        val root = appRoot() ?: return first
        // Only scroll when there is a comment box outside the list (a comments sheet or page).
        // Feeds with the box inside a post are never scrolled, so the post you're on doesn't move.
        val field = liveTarget() ?: findInput() ?: return first
        val fr = Rect().also { runCatching { field.getBoundsInScreen(it) } }
        if (fr.isEmpty) return first
        val scroller = ScreenReader.findScroller(root, fr, resources.displayMetrics.heightPixels) ?: return first
        val down = ScreenReader.scrollDownId(scroller)
        val up = ScreenReader.scrollUpId(scroller)
        val dm = resources.displayMetrics
        val limit = Prefs.commentLimit
        val raw = ArrayList(ScreenReader.collectRaw(root))
        val seen = raw.mapTo(HashSet()) { it.text }
        var scrolls = 0
        val maxScrolls = (limit / 4).coerceIn(1, 30)
        onProgress("Reading comments...")
        while (scrolls < maxScrolls && raw.size / 2 < limit) {
            if (!runCatching { scroller.refresh(); scroller.performAction(down) }.getOrDefault(false)) break
            scrolls++
            kotlinx.coroutines.delay(420)
            val r2 = appRoot() ?: break
            val fresh = ScreenReader.collectRaw(r2, scrolls).filter { seen.add(it.text) }
            if (fresh.isEmpty()) break
            raw.addAll(fresh)
            onProgress("Reading comments... ${raw.size / 2}")
        }
        repeat(scrolls) {
            runCatching { scroller.refresh(); scroller.performAction(up) }
            kotlinx.coroutines.delay(90)
        }
        return runCatching {
            ScreenReader.build(raw, field, targetPkg.ifBlank { screenPkg }, dm.widthPixels, dm.heightPixels, full, deep = true)
        }.getOrNull() ?: first
    }

    private fun appName(): String {
        val pkg = rootInActiveWindow?.packageName?.toString() ?: targetPkg.ifBlank { screenPkg }
        return runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }
            .getOrDefault("this app")
    }

    private var pill: View? = null

    /** A short message in a pill next to the Drafty button (more reliable than a system toast). */
    private fun toast(msg: String) {
        if (!::ui.isInitialized || button == null) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); return }
        pill?.let { runCatching { wm.removeView(it) } }
        val dm = resources.displayMetrics
        val tv = ui.text(msg, 13.5f, 0xFFFFFFFF.toInt()).apply {
            background = ui.rounded(0xF0202124.toInt(), 999f)
            setPadding(ui.dp(16f), ui.dp(10f), ui.dp(16f), ui.dp(10f))
            maxWidth = (dm.widthPixels * 0.78).toInt()
            elevation = ui.dp(6f).toFloat()
        }
        val wrap = FrameLayout(ctx).apply { setPadding(ui.dp(8f), ui.dp(8f), ui.dp(8f), ui.dp(8f)); addView(tv) }
        wrap.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.AT_MOST), View.MeasureSpec.UNSPECIFIED)
        val blp = button?.layoutParams as? WindowManager.LayoutParams
        val bw = button?.width?.takeIf { it > 0 } ?: ui.dp(72f)
        val lp = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, false, touchable = false).apply {
            x = if (Prefs.buttonRight) (dm.widthPixels - bw - wrap.measuredWidth).coerceAtLeast(0) else bw
            y = ((blp?.y ?: dm.heightPixels / 2) + bw / 2 - wrap.measuredHeight / 2).coerceAtLeast(ui.dp(28f))
        }
        if (runCatching { wm.addView(wrap, lp) }.isFailure) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); return }
        pill = wrap
        tv.alpha = 0f; tv.translationX = ui.dp(if (Prefs.buttonRight) 12f else -12f).toFloat()
        tv.animate().alpha(1f).translationX(0f).setDuration(180).start()
        main.postDelayed({
            tv.animate().alpha(0f).setDuration(200).withEndAction { runCatching { wm.removeView(wrap) }; if (pill === wrap) pill = null }.start()
        }, 2600)
    }

    private fun copy(text: String) {
        if (text.isBlank()) return
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Drafty", text))
        toast("Copied")
    }

    private fun overlayParams(w: Int, h: Int, focusable: Boolean, touchable: Boolean = true) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    /** Hosts the ChatGPT, Gemini or Grok WebView in an invisible window so the page renders normally. */
    fun ensureHost() {
        if (Ai.provider == Provider.CLAUDE || Ai.provider == Provider.GEMINI || host != null) return
        val f = FrameLayout(this)
        val lp = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, focusable = false, touchable = false)
            .apply { alpha = 0f }
        if (runCatching { wm.addView(f, lp) }.isSuccess) {
            host = f
            ProviderBridge.attachTo(f)
        }
    }

    // ---------------- the one button ----------------
    private class Ring(context: Context, color: Int, private val stroke: Float) : View(context) {
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = stroke; this.color = color; alpha = 60
        }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = stroke; this.color = color; strokeCap = Paint.Cap.ROUND
        }
        private val box = RectF()
        override fun onDraw(canvas: Canvas) {
            val s = stroke / 2
            box.set(s, s, width - s, height - s)
            canvas.drawOval(box, track)
            canvas.drawArc(box, -90f, 95f, false, paint)
        }
    }

    private var halo: View? = null
    private var haloAnim: android.animation.AnimatorSet? = null
    private val idle = Runnable { if (!busy) buttonCircle?.animate()?.alpha(0.86f)?.setDuration(400)?.start() }

    private fun wake() {
        main.removeCallbacks(idle)
        buttonCircle?.animate()?.alpha(1f)?.setDuration(120)?.start()
        main.postDelayed(idle, 3500)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showButton() {
        if (button != null || !Prefs.showBubble) return
        val size = ui.dp(50f)
        val pad = ui.dp(11f)
        val circle = FrameLayout(ctx).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR, intArrayOf(0xFFE8906F.toInt(), 0xFFD97757.toInt(), 0xFFC25F3E.toInt()),
            ).apply { shape = GradientDrawable.OVAL; setStroke(ui.dp(1f), 0x40FFFFFF) }
            elevation = ui.dp(8f).toFloat()
            outlineAmbientShadowColor(this)
            contentDescription = "Drafty"
        }
        val icon = ImageView(ctx).apply { setImageResource(R.drawable.ic_drafty) }
        val r = Ring(ctx, 0xFFFFFFFF.toInt(), ui.dp(2.2f).toFloat()).apply { visibility = View.INVISIBLE }
        circle.addView(icon, FrameLayout.LayoutParams(ui.dp(23f), ui.dp(23f), Gravity.CENTER))
        circle.addView(r, FrameLayout.LayoutParams(size - ui.dp(9f), size - ui.dp(9f), Gravity.CENTER))
        val h = View(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ui.accent) }
            alpha = 0f
        }
        val wrap = FrameLayout(ctx).apply {
            setPadding(pad, pad, pad, pad)
            clipToPadding = false
            clipChildren = false
            addView(h, FrameLayout.LayoutParams(size, size))
            addView(circle, FrameLayout.LayoutParams(size, size))
        }
        halo = h
        val win = size + pad * 2
        val dm = resources.displayMetrics
        val lp = overlayParams(win, win, false).apply {
            x = if (Prefs.buttonRight) dm.widthPixels - win else 0
            y = (Prefs.buttonY * dm.heightPixels).toInt().coerceIn(ui.dp(60f), dm.heightPixels - win - ui.dp(40f))
        }
        val added = runCatching { wm.addView(wrap, lp) }
        if (added.isFailure) return
        button = wrap; buttonCircle = circle; buttonIcon = icon; ring = r

        val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var dragging = false; var longFired = false
        val longPress = Runnable { longFired = true; circle.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); openHub(force = true) }
        circle.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    wake()
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y
                    dragging = false; longFired = false
                    main.postDelayed(longPress, 450)
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) { dragging = true; main.removeCallbacks(longPress) }
                    if (dragging) {
                        lp.x = (startX + dx).toInt(); lp.y = (startY + dy).toInt()
                        runCatching { wm.updateViewLayout(wrap, lp) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                    v.animate().scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(OvershootInterpolator(2f)).start()
                    if (dragging) snap(wrap, lp, win)
                    else if (!longFired && e.actionMasked == MotionEvent.ACTION_UP) onTap()
                }
            }
            true
        }
        circle.scaleX = 0.3f; circle.scaleY = 0.3f; circle.alpha = 0f
        circle.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(260).setInterpolator(OvershootInterpolator(1.8f))
            .withEndAction { main.postDelayed(idle, 3500) }.start()
    }

    private fun outlineAmbientShadowColor(v: View) {
        if (Build.VERSION.SDK_INT >= 28) { v.outlineAmbientShadowColor = 0xFFC25F3E.toInt(); v.outlineSpotShadowColor = 0xFFC25F3E.toInt() }
    }

    private fun snap(wrap: View, lp: WindowManager.LayoutParams, win: Int) {
        val dm = resources.displayMetrics
        val right = lp.x + win / 2 > dm.widthPixels / 2
        val to = if (right) dm.widthPixels - win else 0
        lp.y = lp.y.coerceIn(ui.dp(40f), dm.heightPixels - win - ui.dp(40f))
        ValueAnimator.ofInt(lp.x, to).apply {
            duration = 240
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { lp.x = it.animatedValue as Int; runCatching { wm.updateViewLayout(wrap, lp) } }
            start()
        }
        Prefs.buttonRight = right
        Prefs.buttonY = lp.y.toFloat() / dm.heightPixels
    }

    private fun setBusy(on: Boolean) {
        busy = on
        val r = ring ?: return
        val i = buttonIcon ?: return
        if (on) {
            wake(); main.removeCallbacks(idle)
            r.visibility = View.VISIBLE
            r.alpha = 0f; r.animate().alpha(1f).setDuration(200).start()
            spin = ObjectAnimator.ofFloat(r, View.ROTATION, 0f, 360f).apply {
                duration = 900; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator(); start()
            }
            pulse = ObjectAnimator.ofPropertyValuesHolder(
                i,
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 0.84f),
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 0.84f),
            ).apply {
                duration = 700; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
                interpolator = android.view.animation.AccelerateDecelerateInterpolator(); start()
            }
            halo?.let { h ->
                val sx = ObjectAnimator.ofFloat(h, View.SCALE_X, 1f, 1.42f).apply { repeatCount = ValueAnimator.INFINITE }
                val sy = ObjectAnimator.ofFloat(h, View.SCALE_Y, 1f, 1.42f).apply { repeatCount = ValueAnimator.INFINITE }
                val a = ObjectAnimator.ofFloat(h, View.ALPHA, 0.35f, 0f).apply { repeatCount = ValueAnimator.INFINITE }
                haloAnim = android.animation.AnimatorSet().apply {
                    playTogether(sx, sy, a); duration = 1300; interpolator = DecelerateInterpolator(); start()
                }
            }
        } else {
            spin?.cancel(); pulse?.cancel(); haloAnim?.cancel()
            halo?.alpha = 0f
            r.animate().alpha(0f).setDuration(160).withEndAction { r.visibility = View.INVISIBLE }.start()
            i.scaleX = 1f; i.scaleY = 1f
            main.postDelayed(idle, 3500)
        }
    }

    private fun flashDone() {
        val i = buttonIcon ?: return
        buttonCircle?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        i.setImageResource(R.drawable.ic_check)
        buttonCircle?.animate()?.scaleX(1.12f)?.scaleY(1.12f)?.setDuration(140)?.withEndAction {
            buttonCircle?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(180)?.start()
        }?.start()
        main.postDelayed({ buttonIcon?.setImageResource(R.drawable.ic_drafty) }, 1300)
    }

    private fun onTap() {
        if (busy) { job?.cancel(); setBusy(false); toast("Stopped"); return }
        if (card != null) { closeCard(); return }
        if (SystemClock.uptimeMillis() - cardClosedAt < 350) return
        // Tap = write, always. Chats: the reply box is found even if it isn't focused.
        // Comment boxes: writes a comment. No chat or comment box open: says so and does nothing else.
        // Long-press opens every other option.
        val first = readScreen(false)
        val h = resources.displayMetrics.heightPixels
        // A focused box near the top is a search bar, not a reply box.
        val t = (liveTarget() ?: findInput())?.takeIf { n -> Rect().also { n.getBoundsInScreen(it) }.centerY() > h * 0.25 }
        if (t != null) return tapWith(t, first)
        if (first == null || first.kind == Kind.POST) return toast(NO_CHAT)
        scope.launch {
            val f = locateInput()
            if (f == null) toast(NO_CHAT) else tapWith(f, first)
        }
    }

    private val NO_CHAT = "No chat open. Open a chat or a comment box first. Long-press for more."

    private fun tapWith(t: AccessibilityNodeInfo, first: ScreenContext?) {
        val s = withEmail(readScreen(false) ?: first) ?: return toast(NO_CHAT)
        val cur = fieldText(t).trim()
        if (cur.isEmpty() || cur == lastDraft.trim()) write(screen = s)
        // You started typing: finish and polish it instead of throwing it away.
        else write("Finish and polish what I started typing into my reply. Keep my meaning, words and tone.", cur, "", s)
    }

    /**
     * Finds the open chat's reply box. Some apps (Google Messages) only expose it once focused, so Drafty
     * taps the message bar first, but only when the screen really is a conversation: the bar must sit next
     * to compose controls (emoji, attach, mic, camera, send). On a chat list nothing is tapped.
     */
    private suspend fun locateInput(): AccessibilityNodeInfo? {
        findInput()?.let { return it }
        val root = appRoot() ?: return null
        val dm = resources.displayMetrics
        val bars = ArrayList<Rect>()
        val controls = ArrayList<Rect>()
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>().apply { add(root to 0) }
        while (stack.isNotEmpty()) {
            val (n, d) = stack.removeLast()
            if (d > 50 || !n.isVisibleToUser) continue
            val r = Rect().also { n.getBoundsInScreen(it) }
            if (r.top > dm.heightPixels * 0.7) {
                val label = (n.contentDescription?.toString() ?: "") + " " + (n.viewIdResourceName ?: "")
                if (COMPOSE_SIG.containsMatchIn(label) && r.width() < dm.widthPixels * 0.3) controls.add(r)
                if (r.width() > dm.widthPixels * 0.4 && r.width() < dm.widthPixels * 0.98 && r.height() in ui.dp(32f)..ui.dp(160f) &&
                    n.text.isNullOrBlank() && !n.isClickable) bars.add(r)
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it to d + 1) }
        }
        // A message bar: on the same row as at least two compose controls.
        val b = bars.filter { bar -> controls.count { c -> c.centerY() in bar.top..bar.bottom } >= 2 }
            .maxByOrNull { it.bottom }
        if (b == null) return null
        val inside = controls.filter { Rect.intersects(it, b) }
        val leftEdge = inside.filter { it.centerX() < b.centerX() }.maxOfOrNull { it.right } ?: b.left
        val x = (leftEdge + ui.dp(24f)).toFloat().coerceAtMost(b.right - ui.dp(24f).toFloat())
        val y = b.exactCenterY()
        val path = android.graphics.Path().apply { moveTo(x, y) }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 50)).build()
        if (!dispatchGesture(gesture, null, null)) return null
        repeat(8) {
            kotlinx.coroutines.delay(150)
            liveTarget()?.let { return it }
            findInput()?.let { return it }
        }
        return null
    }

    // ---------------- writing ----------------
    private fun write(instruction: String = "", previous: String = "", selection: String = "", screen: ScreenContext? = null) {
        val t = liveTarget() ?: target ?: return toast("Tap the text box you want to reply in first.")
        val s0 = withEmail(screen ?: readScreen(false)) ?: return toast("Couldn't read this screen. Open the chat and try again.")
        val insert = Prefs.autoInsert || Prefs.autoSend
        closeCard()
        ensureHost()
        job?.cancel()
        setBusy(true)
        job = scope.launch {
            var text = ""
            var pushed = ""
            var lastPush = 0L
            val initial = fieldText(t).trim()
            try {
                // Comments: read further down the thread, up to the limit in Settings.
                val s = withMemory(if (s0.kind == Kind.POST) readDeep(false) ?: s0 else s0)
                val prompt = Prompts.reply(s.kind, appName(), s.title, s.transcript, s.omitted, s.lastIsMe, instruction, previous, selection)
                Ai.generate(prompt).collect { full ->
                    text = Prompts.clean(full)
                    // Never let "I can't read this..." style output reach the text box.
                    if (Prompts.isMeta(text)) throw IllegalStateException(META_ERR)
                    if (insert && text.length >= 12 && SystemClock.uptimeMillis() - lastPush > 300) {
                        // Stop if the user starts typing their own reply.
                        val cur = fieldText(t).trim()
                        val ours = pushed.trim()
                        val typing = cur.isNotEmpty() && cur != ours && cur != initial && cur != previous.trim() &&
                            !(ours.isNotEmpty() && (cur.startsWith(ours.take(12)) || ours.startsWith(cur.take(12))))
                        if (typing) {
                            toast("Stopped, you're typing")
                            throw CancellationException("user typing")
                        }
                        setField(t, text); pushed = text; lastPush = SystemClock.uptimeMillis()
                    }
                }
                if (text.isBlank()) { toast("Got an empty reply. Tap to try again."); return@launch }
                if (Prompts.isMeta(text)) throw IllegalStateException(META_ERR)
                lastDraft = text
                // Comments are always on the clipboard too, for apps that block typing in (YouTube).
                if (s.kind == Kind.POST) copyQuiet(text)
                if (insert) {
                    if (setField(t, text)) {
                        runCatching { t.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
                        if (Prefs.autoSend && s.kind == Kind.CHAT) {
                            kotlinx.coroutines.delay(350)
                            if (clickSend(t)) { lastDraft = ""; flashDone() }
                            else toast("Drafted. Couldn't find the send button, tap send yourself.")
                        } else {
                            flashDone()
                            if (s.kind == Kind.POST) toast("Comment ready. Also copied.")
                        }
                    } else { copyQuiet(text); toast("This app blocked typing in. Copied, just paste it.") }
                } else openDraft(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Put back whatever was in the box before.
                if (pushed.isNotEmpty()) setField(t, initial)
                toast(e.message ?: "Something went wrong.")
            } finally {
                setBusy(false)
            }
        }
    }

    private val META_ERR = "Couldn't write a good reply here. Scroll so the messages are visible, then tap again."

    private fun copyQuiet(text: String) {
        if (text.isBlank()) return
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Drafty", text))
    }

    /** Adds earlier messages Drafty saw in this chat, so replies use the whole conversation. */
    private fun withMemory(s: ScreenContext): ScreenContext {
        if (s.kind != Kind.CHAT || s.msgs.isEmpty() || s.title.isBlank()) return s
        val all = app.drafty.data.ChatMemory.merge(targetPkg.ifBlank { screenPkg }, s.title, s.msgs)
        if (all.size <= s.msgs.size) return s
        val sel = app.drafty.claude.ContextMath.select(all, Prefs.budgetTokens, Prefs.halfLife)
        return s.copy(transcript = app.drafty.claude.ContextMath.format(sel.selected, s.title), omitted = sel.omitted, lastIsMe = all.last().who == "me")
    }

    /** Email apps: the reply screen hides the original email, so use the one you just read. */
    private val emailSeen = HashMap<String, Pair<Long, ScreenContext>>()

    private fun withEmail(s: ScreenContext?): ScreenContext? {
        val pkg = targetPkg.ifBlank { screenPkg }
        if (pkg !in ScreenReader.EMAIL_APPS) return s
        val seen = emailSeen[pkg]?.takeIf { System.currentTimeMillis() - it.first < 30 * 60_000 }?.second ?: return s
        if (s == null || s.items < 6) return seen.copy(kind = Kind.EMAIL)
        if (s.transcript.contains(seen.transcript.take(200))) return s
        return s.copy(kind = Kind.EMAIL, transcript = seen.transcript + "\n\n" + s.transcript)
    }

    /** Remembers what is on screen in chats and emails (debounced), on this device only. */
    private val capture = Runnable {
        val pkg = screenPkg
        if (card != null || busy) return@Runnable
        val focusedEditable = liveTarget() != null && fieldText(liveTarget()).isNotBlank()
        val s = readScreen(false) ?: return@Runnable
        when {
            pkg in ScreenReader.EMAIL_APPS && !focusedEditable && s.items >= 6 -> emailSeen[pkg] = System.currentTimeMillis() to s
            s.kind == Kind.CHAT && s.title.isNotBlank() && s.msgs.size >= 2 ->
                app.drafty.data.ChatMemory.merge(pkg, s.title, s.msgs)
        }
    }

    private fun openDraft(text: String) {
        applyTheme()
        val c = ui.card()
        c.addView(ui.header("Draft", "via ${Ai.provider.label}") { closeCard() })
        c.addView(ui.spacer(10f))
        val edit = EditText(ctx).apply {
            setText(text)
            textSize = 15f
            setTextColor(ui.fg)
            background = ui.rounded(ui.bg2, 16f)
            setPadding(ui.dp(14f), ui.dp(12f), ui.dp(14f), ui.dp(12f))
            maxLines = 6
        }
        focusOnTouch(edit)
        c.addView(edit)
        c.addView(ui.spacer(10f))
        c.addView(ui.row(listOf(
            ui.primaryButton("Insert") {
                val v = edit.text.toString().trim()
                if (setField(target, v)) { closeCard(); flashDone() } else { copy(v); toast("This app blocked insert. Copied, paste it in.") }
            },
            ui.chip("Regenerate") { write() },
            ui.chip("Copy") { copy(edit.text.toString()) },
        ), 8f, scroll = false))
        showCard(c)
    }

    // ---------------- hub ----------------
    private fun tile(iconRes: Int, label: String, onClick: () -> Unit) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = ui.ripple(ui.rounded(ui.bg2, 16f))
        setPadding(ui.dp(12f), ui.dp(11f), ui.dp(10f), ui.dp(11f))
        addView(ImageView(ctx).apply { setImageResource(iconRes) }, LinearLayout.LayoutParams(ui.dp(20f), ui.dp(20f)))
        addView(ui.text(label, 12.5f, ui.fg, bold = true).apply { setPadding(0, ui.dp(7f), 0, 0); maxLines = 2 })
        setOnClickListener { onClick() }
    }

    private fun grid(tiles: List<View>, cols: Int = 3): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        tiles.chunked(cols).forEachIndexed { r, chunk ->
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until cols) {
                val v = chunk.getOrNull(i) ?: View(ctx)
                row.addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    if (i > 0) marginStart = ui.dp(8f)
                })
            }
            addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (r > 0) topMargin = ui.dp(8f)
            })
        }
    }

    private fun openHub(screen: ScreenContext? = null, force: Boolean = false) {
        if (busy && !force) return
        applyTheme()
        val s = screen ?: readScreen(false)
        val kind = s?.kind ?: Kind.POST
        val t = liveTarget() ?: findInput()
        val c = ui.card()
        c.addView(ui.header("Drafty", "${appName()} · via ${Ai.provider.label}") { closeCard() })
        c.addView(ui.spacer(12f))

        val home = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val result = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }

        if (t != null) {
            val current = fieldText(t)
            val selection = selectedText(t)
            if (current.isNotBlank()) {
                val input = ui.input(if (selection.isNotBlank()) "What should change in the selection?" else "What should change?")
                focusOnTouch(input)
                val send = ui.icon(R.drawable.ic_send, "Apply", sizeDp = 44f, filled = true) {
                    val v = input.text.toString().trim()
                    if (v.isNotEmpty()) write(v, current, selection, s)
                }
                input.setOnEditorActionListener { _, id, _ ->
                    if (id == EditorInfo.IME_ACTION_SEND || id == EditorInfo.IME_ACTION_DONE) { send.performClick(); true } else false
                }
                home.addView(ui.row(listOf(input, send), 8f, scroll = false).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                })
                home.addView(ui.spacer(10f))
                val chips = mutableListOf<View>(ui.chip("Regenerate", primary = true) { write(screen = s) })
                Prompts.MODS.forEach { (label, mod) -> chips.add(ui.chip(label) { write(mod, current, selection, s) }) }
                chips.add(ui.chip("Translate") { write(Prompts.translateMod(), current, selection, s) })
                home.addView(ui.row(chips))
            } else {
                home.addView(ui.primaryButton(if (kind == Kind.POST) "Write a comment" else "Write reply") { write(screen = s) }.apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                })
            }
            home.addView(ui.spacer(12f))
        }

        val run: (Action) -> Unit = { a -> runAction(a, home, result, s) }
        val tiles = if (kind == Kind.POST) listOf(
            tile(R.drawable.t_doc, "TL;DR") { run(Action.TLDR) },
            tile(R.drawable.t_comments, "Summarize comments") { run(Action.COMMENTS) },
            tile(R.drawable.t_scale, "Opinions") { run(Action.OPINIONS) },
            tile(R.drawable.t_star, "Best comments") { run(Action.BEST) },
            tile(R.drawable.t_pen, "Write a comment") { run(Action.WRITE_COMMENT) },
            tile(R.drawable.t_bulb, "Explain simply") { run(Action.EXPLAIN) },
            tile(R.drawable.t_globe, "Translate") { run(Action.TRANSLATE) },
            tile(R.drawable.t_search, "Find") { run(Action.FIND) },
            tile(R.drawable.t_chat, "Ask") { run(Action.ASK) },
        ) else listOf(
            tile(R.drawable.t_clock, "Catch up") { run(Action.CATCH_UP) },
            tile(R.drawable.t_chart, "Analyze chat") { run(Action.ANALYZE) },
            tile(R.drawable.t_list, "Summarize") { run(Action.SUMMARIZE) },
            tile(R.drawable.t_globe, "Translate") { run(Action.TRANSLATE) },
            tile(R.drawable.t_ideas, "Reply ideas") { run(Action.IDEAS) },
            tile(R.drawable.t_chat, "Ask") { run(Action.ASK) },
        )
        home.addView(grid(tiles))
        c.addView(home)
        c.addView(result)
        showCard(c)
    }

    private class MaxHeightScroll(context: Context, private val maxH: Int) : ScrollView(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
        }
    }

    private fun resultHeader(a: Action, home: View, result: LinearLayout) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(ui.icon(R.drawable.ic_back, "Back", sizeDp = 32f) {
            job?.cancel()
            result.visibility = View.GONE
            result.removeAllViews()
            home.visibility = View.VISIBLE
        })
        addView(ui.text(a.label, 14.5f, ui.fg, bold = true).apply { setPadding(ui.dp(10f), 0, 0, 0) })
    }

    private fun runAction(a: Action, home: View, result: LinearLayout, screen: ScreenContext?, question: String = "") {
        home.visibility = View.GONE
        result.visibility = View.VISIBLE
        result.removeAllViews()
        result.addView(resultHeader(a, home, result))
        result.addView(ui.spacer(10f))

        if (a.needsInput && question.isBlank()) {
            val input = ui.input(if (a == Action.FIND) "Describe what to find, e.g. people who disagree" else "Ask anything about this screen")
            focusOnTouch(input)
            val send = ui.icon(R.drawable.ic_send, "Go", sizeDp = 44f, filled = true) {
                val q = input.text.toString().trim()
                if (q.isNotEmpty()) runAction(a, home, result, screen, q)
            }
            input.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { send.performClick(); true } else false }
            result.addView(ui.row(listOf(input, send), 8f, scroll = false).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            main.postDelayed({ enableFocus(); input.requestFocus(); showIme(input) }, 120)
            return
        }

        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val out = ui.text("Thinking...", 14.5f, ui.muted)
        body.addView(out)
        result.addView(MaxHeightScroll(ctx, (resources.displayMetrics.heightPixels * 0.42).toInt()).apply {
            isVerticalScrollBarEnabled = false
            addView(body)
        })
        result.addView(ui.spacer(10f))
        var text = ""
        val footer = ui.row(listOf(
            ui.chip("Copy") { copy(text) },
            ui.chip("Redo") { runAction(a, home, result, screen, question) },
        ), 8f, scroll = false).apply { visibility = View.INVISIBLE }
        result.addView(footer)

        ensureHost()
        job?.cancel()
        job = scope.launch {
            try {
                val quick = screen ?: readScreen(false)
                val s = (if (quick?.kind == Kind.POST || quick == null) readDeep(a.full) { out.text = it } else null)
                    ?: (if (a.full) readScreen(true) else null) ?: quick
                    ?: run { out.setTextColor(0xFFD1453B.toInt()); out.text = "Couldn't read this screen."; return@launch }
                val s2 = s
                out.text = "Thinking..."
                val prompt = Prompts.action(a, s2.kind, appName(), s2.title, s2.transcript, s2.omitted, question)
                Ai.generate(prompt).collect { full ->
                    text = Prompts.clean(full)
                    out.setTextColor(ui.fg)
                    out.text = if (a.ideas) text else Markdown.spanned(text)
                }
                if (a.ideas) showIdeas(body, out, text)
                footer.visibility = View.VISIBLE
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                out.setTextColor(0xFFD1453B.toInt())
                out.text = e.message ?: "Something went wrong."
                footer.visibility = View.VISIBLE
            }
        }
    }

    private fun showIdeas(body: LinearLayout, placeholder: TextView, text: String) {
        val ideas = Prompts.parseIdeas(text)
        if (ideas.isEmpty()) return
        body.removeView(placeholder)
        val t = liveTarget() ?: target
        ideas.forEachIndexed { i, idea ->
            val item = ui.text(idea, 14.5f).apply {
                background = ui.ripple(ui.rounded(ui.bg2, 16f))
                setPadding(ui.dp(14f), ui.dp(11f), ui.dp(14f), ui.dp(11f))
                setOnClickListener { if (t != null && setField(t, idea)) { closeCard(); flashDone() } else copy(idea) }
            }
            body.addView(item, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (i > 0) topMargin = ui.dp(8f)
            })
        }
        body.addView(ui.text(if (t != null) "Tap one to put it in the text box" else "Tap one to copy it", 11.5f, ui.muted).apply {
            setPadding(ui.dp(4f), ui.dp(8f), 0, 0)
        })
    }

    // ---------------- card window ----------------
    @SuppressLint("ClickableViewAccessibility")
    private fun focusOnTouch(input: EditText) {
        input.setOnTouchListener { v, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) { enableFocus(); v.post { v.requestFocus(); showIme(v) } }
            false
        }
    }

    private fun showIme(v: View) = (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(v, 0)

    /** Cards start non-focusable (the app keeps its keyboard); typing in a card makes it focusable. */
    private fun enableFocus() {
        val c = card ?: return
        val lp = c.layoutParams as WindowManager.LayoutParams
        if (lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0) return
        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        ignoreOutsideUntil = SystemClock.uptimeMillis() + 600
        runCatching { wm.updateViewLayout(c, lp) }
        main.postDelayed({ fitAboveIme() }, 350)
        main.postDelayed({ fitAboveIme() }, 700)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showCard(view: View) {
        closeCard(animate = false)
        val dm = resources.displayMetrics
        val width = minOf(dm.widthPixels - ui.dp(24f), ui.dp(360f))
        val pad = ui.dp(12f)
        val wrapper = object : FrameLayout(ctx) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) { closeCard(); return true }
                return super.dispatchKeyEvent(event)
            }
        }
        wrapper.setPadding(pad, pad, pad, pad)
        wrapper.clipToPadding = false
        wrapper.addView(view, FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT))
        wrapper.setOnTouchListener { _, ev ->
            if (ev.action != MotionEvent.ACTION_OUTSIDE) return@setOnTouchListener false
            if (SystemClock.uptimeMillis() < ignoreOutsideUntil) return@setOnTouchListener true
            // Only close for touches that are really outside the card.
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            val inside = ev.rawX >= loc[0] && ev.rawX <= loc[0] + view.width && ev.rawY >= loc[1] && ev.rawY <= loc[1] + view.height
            if (!inside || (ev.rawX == 0f && ev.rawY == 0f)) closeCard()
            true
        }

        // Drag handle on top; the handle and header move the card anywhere on screen.
        (view as? LinearLayout)?.let { card ->
            val handle = View(ctx).apply { background = ui.rounded(ui.line, 999f) }
            card.addView(handle, 0, LinearLayout.LayoutParams(ui.dp(36f), ui.dp(4f)).apply {
                gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = ui.dp(8f)
            })
        }

        val totalW = width + pad * 2
        wrapper.measure(
            View.MeasureSpec.makeMeasureSpec(totalW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.AT_MOST),
        )
        val ch = wrapper.measuredHeight
        // Open next to the button: above it when it sits low on the screen, below it otherwise.
        val blp = button?.layoutParams as? WindowManager.LayoutParams
        val bTop = blp?.y ?: (dm.heightPixels / 2)
        val bH = button?.height?.takeIf { it > 0 } ?: ui.dp(72f)
        val right = Prefs.buttonRight
        val lp = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, false).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            gravity = Gravity.TOP or Gravity.START
            x = if (totalW >= dm.widthPixels - ui.dp(16f)) (dm.widthPixels - totalW) / 2
                else if (right) dm.widthPixels - totalW - ui.dp(4f) else ui.dp(4f)
            y = if (bTop + bH / 2 > dm.heightPixels / 2) bTop - ch + pad * 2 else bTop + bH - pad
        }
        clampCard(lp, ch)
        val res = runCatching { wm.addView(wrapper, lp) }
        if (res.isFailure) return
        card = wrapper
        makeDraggable(wrapper, view, lp)
        wrapper.post { fitAboveIme() }
        view.alpha = 0f
        view.translationY = ui.dp(12f).toFloat()
        view.scaleX = 0.96f; view.scaleY = 0.96f
        view.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(210).setInterpolator(DecelerateInterpolator(1.8f)).start()
    }

    /** Keeps the card fully on screen and above any keyboard. */
    private fun clampCard(lp: WindowManager.LayoutParams, h: Int) {
        val dm = resources.displayMetrics
        val bottomLimit = minOf(dm.heightPixels - ui.dp(16f), imeTop() - ui.dp(4f))
        lp.y = lp.y.coerceAtMost(bottomLimit - h).coerceAtLeast(ui.dp(28f))
        lp.x = lp.x.coerceIn(0, maxOf(0, dm.widthPixels - (card?.width?.takeIf { it > 0 } ?: 0)))
    }

    /** Moves the card up when a keyboard opens beneath it (the app's or the card's own). */
    private fun fitAboveIme() {
        val c = card ?: return
        val lp = c.layoutParams as? WindowManager.LayoutParams ?: return
        val before = lp.y
        clampCard(lp, c.height.takeIf { it > 0 } ?: c.measuredHeight)
        if (lp.y != before) {
            ValueAnimator.ofInt(before, lp.y).apply {
                duration = 180
                interpolator = DecelerateInterpolator()
                addUpdateListener { lp.y = it.animatedValue as Int; runCatching { wm.updateViewLayout(c, lp) } }
                start()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeDraggable(wrapper: View, view: View, lp: WindowManager.LayoutParams) {
        val group = view as? ViewGroup ?: return
        val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        var dx = 0f; var dy = 0f; var sx = 0; var sy = 0; var drag = false
        val listener = View.OnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; sx = lp.x; sy = lp.y; drag = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val mx = e.rawX - dx; val my = e.rawY - dy
                    if (!drag && hypot(mx, my) > slop) drag = true
                    if (drag) {
                        val dm = resources.displayMetrics
                        lp.x = (sx + mx).toInt().coerceIn(-wrapper.width / 2, dm.widthPixels - wrapper.width / 2)
                        lp.y = (sy + my).toInt().coerceIn(0, dm.heightPixels - ui.dp(80f))
                        runCatching { wm.updateViewLayout(wrapper, lp) }
                    }
                    true
                }
                else -> true
            }
        }
        // Handle (index 0) and header (index 1).
        group.getChildAt(0)?.let { h -> h.setOnTouchListener(listener); (h.layoutParams as? LinearLayout.LayoutParams)?.let { it.height = ui.dp(4f) } }
        group.getChildAt(1)?.setOnTouchListener(listener)
    }

    private fun closeCard(animate: Boolean = true) {
        val c = card ?: return
        card = null
        cardClosedAt = SystemClock.uptimeMillis()
        val inner = (c as? FrameLayout)?.getChildAt(0)
        if (!animate || inner == null) { runCatching { wm.removeView(c) }; return }
        inner.animate().alpha(0f).translationY(ui.dp(10f).toFloat()).scaleX(0.97f).scaleY(0.97f).setDuration(130)
            .withEndAction { runCatching { wm.removeView(c) } }.start()
    }


    companion object {
        @Volatile var instance: DraftyAccessibilityService? = null

        fun isEnabled(context: Context): Boolean {
            val enabled = android.provider.Settings.Secure.getString(
                context.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':').any { it.startsWith(context.packageName + "/") }
        }
    }
}
