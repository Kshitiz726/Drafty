package app.drafty.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.drafty.claude.Ai
import app.drafty.claude.ClaudeBridge
import app.drafty.claude.Provider
import app.drafty.claude.ProviderBridge

/** Signs in to the chosen AI provider. Cookies are shared with Drafty's background WebViews. */
class LoginActivity : ComponentActivity() {
    private var finished = false
    private lateinit var provider: Provider

    companion object {
        fun open(context: Context, p: Provider) =
            context.startActivity(Intent(context, LoginActivity::class.java).putExtra("provider", p.id))
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        provider = Provider.from(intent.getStringExtra("provider") ?: "claude")
        val hint = when (provider) {
            Provider.CLAUDE -> "Use Continue with email. Google sign-in isn't available inside apps."
            Provider.CHATGPT -> "Log in with your email. Tap Done when you see the chat."
            Provider.GEMINI -> "Sign in with your Google account. Tap Done when Gemini opens."
            Provider.GROK -> "Sign in with email or X. Tap Done when Grok opens."
        }
        setContent {
            DraftyTheme {
                var loading by remember { mutableStateOf(true) }
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                        BrandMark(28)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Connect ${provider.label}", style = MaterialTheme.typography.titleMedium)
                            Text(hint, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (provider != Provider.CLAUDE) TextButton(onClick = { done() }) { Text("Done") }
                    }
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                if (provider != Provider.CLAUDE) settings.userAgentString = ProviderBridge.browserUserAgent(ctx)
                                CookieManager.getInstance().setAcceptCookie(true)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { loading = true }
                                    override fun onPageFinished(view: WebView, url: String) { loading = false; onUrl(url) }
                                    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) { onUrl(url) }
                                }
                                loadUrl(provider.loginUrl)
                            }
                        },
                    )
                }
            }
        }
    }

    private fun onUrl(url: String) {
        if (finished) return
        val ok = when (provider) {
            Provider.CLAUDE -> Regex("^https://claude\\.ai/(new|chats?|recents|project)").containsMatchIn(url)
            else -> Ai.hasSession(provider) && url.startsWith(provider.homeUrl.substringBefore("/?").trimEnd('/'))
        }
        if (ok) done()
    }

    private fun done() {
        if (finished) return
        finished = true
        CookieManager.getInstance().flush()
        if (provider == Provider.CLAUDE) ClaudeBridge.reload()
        Toast.makeText(this, "${provider.label} connected", Toast.LENGTH_SHORT).show()
        finish()
    }
}
