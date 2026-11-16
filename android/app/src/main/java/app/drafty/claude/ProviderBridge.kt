package app.drafty.claude

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Drives ChatGPT, Gemini or Grok through their own web apps in a hidden WebView,
 * using whichever account the user signed in with. No API keys.
 */
object ProviderBridge {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var appContext: Context
    private var webView: WebView? = null
    private var pending: Pending? = null

    private class Pending(val id: String, val provider: Provider, val prompt: String) { var started = false }

    private interface Listener {
        fun onText(text: String)
        fun onDone()
        fun onError(code: String, message: String)
    }

    private val listeners = ConcurrentHashMap<String, Listener>()

    fun init(context: Context) { appContext = context.applicationContext }

    /** A browser-like user agent so sign-in pages treat the WebView like Chrome. */
    fun browserUserAgent(context: Context): String =
        WebSettings.getDefaultUserAgent(context).replace("; wv", "").replace(Regex("Version/\\S+\\s"), "")

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(): WebView {
        webView?.let { return it }
        CookieManager.getInstance().setAcceptCookie(true)
        if (appContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) WebView.setWebContentsDebuggingEnabled(true)
        val wv = WebView(appContext)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.userAgentString = browserUserAgent(appContext)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.addJavascriptInterface(JsApi(), "DraftyDrv")
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) { startPending(view) }
        }
        wv.layout(0, 0, 1080, 2000)
        webView = wv
        return wv
    }

    /** Lets the accessibility service host the WebView in an invisible window so pages render normally. */
    fun attachTo(container: ViewGroup) {
        main.post {
            val wv = ensureWebView()
            (wv.parent as? ViewGroup)?.removeView(wv)
            container.addView(wv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    fun detach() {
        main.post { (webView?.parent as? ViewGroup)?.removeView(webView) }
    }

    private fun startPending(view: WebView) {
        val p = pending ?: return
        if (p.started) return
        p.started = true
        view.evaluateJavascript(
            DRIVER + ";draftyDrive(${JSONObject.quote(p.provider.id)}, ${JSONObject.quote(p.prompt)}, ${JSONObject.quote(p.id)});",
            null,
        )
    }

    fun generate(provider: Provider, prompt: String): Flow<String> = callbackFlow {
        val id = UUID.randomUUID().toString()
        listeners[id] = object : Listener {
            override fun onText(text: String) { trySend(text) }
            override fun onDone() { close() }
            override fun onError(code: String, message: String) { close(ClaudeException(code, message)) }
        }
        main.post {
            val wv = ensureWebView()
            pending = Pending(id, provider, prompt)
            wv.loadUrl(provider.homeUrl)
            // SPA pages sometimes skip onPageFinished; start anyway after a grace period.
            main.postDelayed({ if (pending?.id == id) startPending(wv) }, 9000)
        }
        awaitClose {
            listeners.remove(id)
            main.post {
                if (pending?.id == id) pending = null
                webView?.evaluateJavascript("window.__draftyCancel && window.__draftyCancel(${JSONObject.quote(id)})", null)
            }
        }
    }

    private class JsApi {
        @JavascriptInterface fun onText(id: String, text: String) { listeners[id]?.onText(text) }
        @JavascriptInterface fun onDone(id: String) { listeners.remove(id)?.onDone() }
        @JavascriptInterface fun onError(id: String, code: String, message: String) { listeners.remove(id)?.onError(code, message) }
    }

    private const val DRIVER = """
window.draftyDrive = function(provider, prompt, id){
  var S = {
    chatgpt: { composer:['#prompt-textarea','div.ProseMirror[contenteditable="true"]','textarea[name="prompt-textarea"]','textarea[name="prompt"]','#mobile-composer-prompt'],
      send:['button[data-testid="send-button"]','button#composer-submit-button','button[aria-label="Send message"]','button[aria-label*="Send" i]'],
      stop:['button[data-testid="stop-button"]','button[aria-label*="Stop" i]'],
      answer:['[data-message-author-role="assistant"]','[data-message-role="assistant"] [data-assistant-markdown-root]','[data-message-role="assistant"]'], login:['button[data-testid="login-button"]','a[href*="/auth/login"]'] },
    gemini: { composer:['rich-textarea .ql-editor','div.ql-editor[contenteditable="true"]','[contenteditable="true"][role="textbox"]'],
      send:['button.send-button','button[aria-label*="Send" i]'], stop:['button[aria-label*="Stop" i]'],
      answer:['model-response message-content','model-response .markdown','model-response'], login:['a[href*="ServiceLogin"]','a[href*="accounts.google.com"]'] },
    grok: { composer:['textarea[aria-label]','div.ProseMirror[contenteditable="true"]','div[contenteditable="true"]','textarea'],
      send:['button[type="submit"]','button[aria-label*="Submit" i]','button[aria-label*="Send" i]'], stop:['button[aria-label*="Stop" i]'],
      answer:['.message-bubble','[class*="message-bubble"]'], login:['a[href*="sign-in"]','a[href*="/login"]'] }
  }[provider];
  var cancelled = false;
  window.__draftyCancel = function(x){ if (x === id) cancelled = true; };
  function sleep(ms){ return new Promise(function(r){ setTimeout(r, ms); }); }
  function q(l){ for (var i=0;i<l.length;i++){ var e=document.querySelector(l[i]); if (e) return e; } return null; }
  function qa(l){ for (var i=0;i<l.length;i++){ var n=document.querySelectorAll(l[i]); if (n.length) return Array.prototype.slice.call(n); } return []; }
  function esc(s){ return s.replace(/[&<>]/g, function(c){ return {'&':'&amp;','<':'&lt;','>':'&gt;'}[c]; }); }
  (async function(){
    var composer = null;
    for (var i=0;i<60 && !composer;i++){ composer = q(S.composer); if (!composer) await sleep(500); }
    if (!composer){ DraftyDrv.onError(id, q(S.login)?'auth':'driver', q(S.login)?('Sign in to '+provider+' in Drafty settings.'):'Could not open the chat. Try again.'); return; }
    var before = qa(S.answer).length;
    composer.focus();
    if (composer.tagName === 'TEXTAREA'){
      Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set.call(composer, prompt);
      composer.dispatchEvent(new Event('input', {bubbles:true}));
    } else {
      function selectAll(){ var sel = getSelection(), r = document.createRange(); r.selectNodeContents(composer); sel.removeAllRanges(); sel.addRange(r); }
      function filled(){ return (composer.textContent || '').trim().length > 0; }
      selectAll();
      try { document.execCommand('insertText', false, prompt); } catch(e) {}
      await sleep(250);
      if (!filled()){
        selectAll();
        var dt = new DataTransfer(); dt.setData('text/plain', prompt);
        composer.dispatchEvent(new ClipboardEvent('paste', {clipboardData: dt, bubbles:true, cancelable:true}));
        await sleep(250);
      }
      if (!filled()){
        // Pages with Trusted Types block innerHTML; build the paragraphs as DOM nodes instead.
        while (composer.firstChild) composer.removeChild(composer.firstChild);
        prompt.split('\n').forEach(function(l){
          var p = document.createElement('p');
          if (l) p.textContent = l; else p.appendChild(document.createElement('br'));
          composer.appendChild(p);
        });
        composer.dispatchEvent(new InputEvent('input', {bubbles:true, inputType:'insertText', data: prompt}));
        await sleep(200);
      }
    }
    var btn = null;
    for (var j=0;j<20;j++){ btn = q(S.send); if (btn && !btn.disabled && btn.getAttribute('aria-disabled')!=='true') break; await sleep(200); }
    if (btn && !btn.disabled) btn.click();
    else composer.dispatchEvent(new KeyboardEvent('keydown', {key:'Enter', code:'Enter', keyCode:13, bubbles:true}));
    var t0 = Date.now(), last = '', changedAt = Date.now();
    for (;;){
      if (cancelled) return;
      await sleep(400);
      var list = qa(S.answer);
      var node = list.length > before ? list[list.length-1] : null;
      var text = node ? (node.innerText || '').trim() : '';
      if (text && text.slice(0,200) === prompt.trim().slice(0,200)) text = '';
      if (text && text !== last){ last = text; changedAt = Date.now(); DraftyDrv.onText(id, text); }
      var quiet = Date.now() - changedAt, streaming = !!q(S.stop);
      if (last && ((!streaming && quiet > 1800) || quiet > 9000)){ DraftyDrv.onDone(id); return; }
      if (!last && Date.now() - t0 > 60000){ DraftyDrv.onError(id, 'driver', 'No answer from '+provider+'. Check that you are signed in.'); return; }
      if (Date.now() - t0 > 180000){ DraftyDrv.onDone(id); return; }
    }
  })().catch(function(e){ DraftyDrv.onError(id, 'driver', String(e && e.message || e)); });
};
"""
}
