package app.drafty.data

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences("drafty", Context.MODE_PRIVATE)
    }

    private fun str(key: String, def: String) = sp.getString(key, def) ?: def
    private fun put(key: String, v: String) = sp.edit().putString(key, v).apply()

    var provider: String
        get() = str("provider", "claude")
        set(v) = put("provider", v)

    var myName: String
        get() = str("myName", "")
        set(v) = put("myName", v)

    var aboutMe: String
        get() = str("aboutMe", "")
        set(v) = put("aboutMe", v)

    var replyLang: String
        get() = str("replyLang", "Auto")
        set(v) = put("replyLang", v)

    var translateTo: String
        get() = str("translateTo", "English")
        set(v) = put("translateTo", v)

    var cleanup: Boolean
        get() = sp.getBoolean("cleanup", true)
        set(v) = sp.edit().putBoolean("cleanup", v).apply()

    var autoInsert: Boolean
        get() = sp.getBoolean("autoInsert", true)
        set(v) = sp.edit().putBoolean("autoInsert", v).apply()

    var showBubble: Boolean
        get() = sp.getBoolean("showButton", true)
        set(v) = sp.edit().putBoolean("showButton", v).apply()

    var buttonRight: Boolean
        get() = sp.getBoolean("buttonRight", true)
        set(v) = sp.edit().putBoolean("buttonRight", v).apply()

    var buttonY: Float
        get() = sp.getFloat("buttonY", 0.62f)
        set(v) = sp.edit().putFloat("buttonY", v).apply()

    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) = sp.edit().putBoolean("onboarded", v).apply()

    /** "system", "light" or "dark" */
    var theme: String
        get() = str("theme", "system")
        set(v) = put("theme", v)

    /** Draft as soon as a chat reply box is opened, without tapping the button. */
    var autoDraft: Boolean
        get() = sp.getBoolean("autoDraft", false)
        set(v) = sp.edit().putBoolean("autoDraft", v).apply()

    /** Send drafted chat replies automatically, including from notifications. */
    var autoSend: Boolean
        get() = sp.getBoolean("autoSend", false)
        set(v) = sp.edit().putBoolean("autoSend", v).apply()

    var commentLimit: Int
        get() = sp.getInt("commentLimit", 30)
        set(v) = sp.edit().putInt("commentLimit", v).apply()

    /** Auto reply style: "me" (reply as me), "away" (say I am away), "fixed" (send a fixed message). */
    var arMode: String
        get() = str("arMode", "me")
        set(v) = put("arMode", v)

    /** Extra instructions for auto replies, e.g. "Keep it short. Do not agree to plans." */
    var arNote: String
        get() = str("arNote", "")
        set(v) = put("arNote", v)

    var arFixed: String
        get() = str("arFixed", "Hey! Can't reply right now, I'll get back to you soon.")
        set(v) = put("arFixed", v)

    /** Answer new messages from notifications while the phone is locked or the screen is off. */
    var autoReply: Boolean
        get() = sp.getBoolean("autoReply", false)
        set(v) = sp.edit().putBoolean("autoReply", v).apply()

    /** Only auto reply while the screen is off or locked. */
    var arOnlyAway: Boolean
        get() = sp.getBoolean("arOnlyAway", true)
        set(v) = sp.edit().putBoolean("arOnlyAway", v).apply()

    /** Apps auto reply is allowed in (empty = all supported apps). */
    var arApps: Set<String>
        get() = sp.getStringSet("arApps", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("arApps", v).apply()

    /** Gemini chat ids still to delete (a delete failed earlier). */
    var geminiPending: Set<String>
        get() = sp.getStringSet("geminiPending", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("geminiPending", v).apply()

    var autoSentCount: Int
        get() = sp.getInt("autoSentCount", 0)
        set(v) = sp.edit().putInt("autoSentCount", v).apply()

    var budgetTokens: Int
        get() = sp.getInt("budgetTokens", 3000)
        set(v) = sp.edit().putInt("budgetTokens", v).apply()

    var halfLife: Int
        get() = sp.getInt("halfLife", 10)
        set(v) = sp.edit().putInt("halfLife", v).apply()

    val LANGS = listOf(
        "English", "Hindi", "Hinglish", "Nepali", "Bengali", "Urdu", "Tamil", "Telugu", "Marathi",
        "Spanish", "French", "German", "Portuguese", "Italian", "Arabic", "Chinese", "Japanese", "Korean", "Russian", "Indonesian",
    )
}
