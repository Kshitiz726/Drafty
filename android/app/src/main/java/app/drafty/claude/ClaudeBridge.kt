package app.drafty.claude

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import app.drafty.data.Prefs
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

class ClaudeException(val code: String, message: String) : Exception(message)

data class ClaudeStatus(val connected: Boolean, val plan: String = "", val orgName: String = "", val code: String = "", val message: String = "")

/**
 * Talks to claude.ai with the user's own signed-in session.
 * A hidden WebView sits on the claude.ai origin, so requests carry the session cookies exactly like the website.
 */
object ClaudeBridge {
    private val main = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var loaded = false
    private val pending = mutableListOf<String>()

    private interface Listener {
        fun onDelta(text: String)
        fun onDone()
        fun onError(code: String, message: String)
    }

    private val streams = ConcurrentHashMap<String, Listener>()
    private val statusWaiters = ConcurrentHashMap<String, (String) -> Unit>()

    @SuppressLint("SetJavaScriptEnabled")
    fun init(context: Context) {
        main.post {
            if (webView != null) return@post
            CookieManager.getInstance().setAcceptCookie(true)
            val wv = WebView(context.applicationContext)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.addJavascriptInterface(JsApi(), "DraftyBridge")
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    if (!url.startsWith("https://claude.ai")) return
                    loaded = true
                    view.evaluateJavascript(JS, null)
                    val queued = pending.toList()
                    pending.clear()
                    queued.forEach { view.evaluateJavascript(JS + ";" + it, null) }
                }
            }
            webView = wv
            wv.loadUrl(HOME)
        }
    }

    /** Call after signing in or out so the bridge picks up fresh cookies. */
    fun reload() {
        main.post {
            loaded = false
            webView?.loadUrl(HOME)
        }
    }

    fun signOut(done: () -> Unit) {
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            reload()
            done()
        }
    }

    private fun run(js: String) {
        main.post {
            val wv = webView
            if (wv == null || !loaded) pending.add(js) else wv.evaluateJavascript(JS + ";" + js, null)
        }
    }

    private fun q(s: String) = JSONObject.quote(s)

    suspend fun status(): ClaudeStatus {
        val json: String = withTimeoutOrNull<String>(25_000) {
            suspendCancellableCoroutine<String> { cont ->
                val id = UUID.randomUUID().toString()
                statusWaiters[id] = { if (cont.isActive) cont.resume(it) }
                cont.invokeOnCancellation { statusWaiters.remove(id) }
                run("window.drafty.status(${q(id)})")
            }
        } ?: return ClaudeStatus(false, code = "timeout", message = "Could not reach claude.ai. Check your connection.")
        val o = JSONObject(json)
        return if (o.optBoolean("ok")) {
            val org = o.getJSONObject("org")
            ClaudeStatus(true, org.optString("plan"), org.optString("name"))
        } else {
            ClaudeStatus(false, code = o.optString("code"), message = o.optString("message"))
        }
    }

    /** Streams the reply text chunk by chunk. */
    fun generate(prompt: String): Flow<String> = callbackFlow {
        val id = UUID.randomUUID().toString()
        streams[id] = object : Listener {
            override fun onDelta(text: String) { trySend(text) }
            override fun onDone() { close() }
            override fun onError(code: String, message: String) { close(ClaudeException(code, message)) }
        }
        run("window.drafty.generate(${q(id)}, ${q(prompt)}, ${Prefs.cleanup})")
        awaitClose {
            streams.remove(id)
            run("window.drafty && window.drafty.cancel(${q(id)})")
        }
    }

    private class JsApi {
        @JavascriptInterface fun onDelta(id: String, text: String) { streams[id]?.onDelta(text) }
        @JavascriptInterface fun onDone(id: String) { streams.remove(id)?.onDone() }
        @JavascriptInterface fun onError(id: String, code: String, message: String) { streams.remove(id)?.onError(code, message) }
        @JavascriptInterface fun onStatus(id: String, json: String) { statusWaiters.remove(id)?.invoke(json) }
    }

    private const val HOME = "https://claude.ai/robots.txt"

    private val JS = """
(function(){
if (window.drafty) return;
var ROOT='00000000-0000-4000-8000-000000000000';
var ctrls={};
var org=null;
function E(code,msg){var e=new Error(msg);e.code=code;return e;}
function api(path,o){o=o||{};return fetch('/api'+path,{method:o.method||'GET',credentials:'include',signal:o.signal,headers:{'content-type':'application/json',accept:o.accept||'application/json'},body:o.body?JSON.stringify(o.body):undefined});}
async function fail(r){
  if(r.status===401||r.status===403){org=null;throw E('auth','Not connected. Sign in to Claude in the Drafty app.');}
  if(r.status===429) throw E('rate','Claude usage limit reached. It resets in a few hours.');
  throw E('http','Claude request failed ('+r.status+').');
}
async function getOrg(force){
  if(org&&!force) return org;
  var r=await api('/organizations'); if(!r.ok) await fail(r);
  var os=await r.json(); if(!Array.isArray(os)||!os.length) throw E('auth','No Claude account found.');
  var o=os.find(function(x){return (x.capabilities||[]).indexOf('chat')>=0;})||os[0];
  var c=o.capabilities||[];
  org={uuid:o.uuid,name:o.name,plan:c.indexOf('claude_max')>=0?'Max':c.indexOf('claude_pro')>=0?'Pro':c.indexOf('raven')>=0?'Team':'Free'};
  return org;
}
async function remove(o,conv){
  for(var t=0;t<2;t++){
    try{ var r=await api('/organizations/'+o.uuid+'/chat_conversations/'+conv,{method:'DELETE'}); if(r.ok||r.status===404) return; }catch(e){}
    await new Promise(function(res){setTimeout(res,800);});
  }
}
// Leftovers from cancelled or interrupted replies: Drafty's chats are always named "Drafty".
async function sweep(o){
  try{
    var r=await api('/organizations/'+o.uuid+'/chat_conversations?limit=40'); if(!r.ok) return;
    var list=await r.json(); var now=Date.now();
    for(var i=0;i<list.length;i++){
      var c=list[i];
      if(c.name==='Drafty' && now-Date.parse(c.updated_at||c.created_at||0)>60000) await remove(o,c.uuid);
    }
  }catch(e){}
}
window.drafty={
  status: async function(id){
    try{var o=await getOrg(true);DraftyBridge.onStatus(id,JSON.stringify({ok:true,org:o}));}
    catch(e){DraftyBridge.onStatus(id,JSON.stringify({ok:false,code:e.code||'net',message:e.message||String(e)}));}
  },
  cancel: function(id){ if(ctrls[id]) ctrls[id].abort(); },
  generate: async function(id,prompt,cleanup){
    var c=new AbortController(); ctrls[id]=c; var conv=null,o=null;
    try{
      o=await getOrg(false); conv=crypto.randomUUID();
      if(cleanup) sweep(o);
      var r=await api('/organizations/'+o.uuid+'/chat_conversations',{method:'POST',signal:c.signal,body:{uuid:conv,name:'Drafty'}});
      if(!r.ok) await fail(r);
      r=await api('/organizations/'+o.uuid+'/chat_conversations/'+conv+'/completion',{method:'POST',signal:c.signal,accept:'text/event-stream',
        body:{prompt:prompt,parent_message_uuid:ROOT,timezone:Intl.DateTimeFormat().resolvedOptions().timeZone,locale:'en-US',attachments:[],files:[],sync_sources:[],rendering_mode:'messages'}});
      if(!r.ok) await fail(r);
      var rd=r.body.getReader(),dec=new TextDecoder(),buf='';
      for(;;){
        var x=await rd.read(); if(x.done) break;
        buf+=dec.decode(x.value,{stream:true});
        var i;
        while((i=buf.indexOf('\n'))>=0){
          var line=buf.slice(0,i).trim(); buf=buf.slice(i+1);
          if(line.indexOf('data:')!==0) continue;
          var raw=line.slice(5).trim(); if(!raw) continue;
          var d; try{d=JSON.parse(raw);}catch(e){continue;}
          if(d.type==='content_block_delta'&&d.delta&&d.delta.type==='text_delta') DraftyBridge.onDelta(id,d.delta.text);
          else if(d.type==='completion'&&d.completion) DraftyBridge.onDelta(id,d.completion);
          else if(d.type==='error') throw E('api',(d.error&&d.error.message)||'Claude returned an error.');
        }
      }
      // Delete the chat completely before reporting done, so nothing is left in the account.
      if(cleanup&&conv&&o){ var cv=conv; conv=null; await remove(o,cv); }
      DraftyBridge.onDone(id);
    }catch(e){ if(!c.signal.aborted) DraftyBridge.onError(id,e.code||'net',e.message||String(e)); }
    finally{ delete ctrls[id]; if(cleanup&&conv&&o) await remove(o,conv); }
  }
};
})();
"""
}
