package app.drafty

import android.app.Application
import app.drafty.claude.ClaudeBridge
import app.drafty.claude.ProviderBridge
import app.drafty.data.Prefs

class DraftyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        app.drafty.data.ChatMemory.init(this)
        ClaudeBridge.init(this)
        ProviderBridge.init(this)
        app.drafty.claude.GeminiClient.init(this)
    }
}
