package com.example.notify

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Настройки приёмника и короткий журнал последних событий. */
class Cfg private constructor(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("notify", Context.MODE_PRIVATE)

    companion object {
        private const val LOG_LINES = 20

        @Volatile
        private var inst: Cfg? = null

        fun get(ctx: Context): Cfg =
            inst ?: synchronized(this) { inst ?: Cfg(ctx.applicationContext).also { inst = it } }
    }

    /** Секретный код связи (имя темы ntfy): тот же, что в ProfiMonitor. */
    var code: String
        get() = sp.getString("code", "") ?: ""
        set(v) = sp.edit().putString("code", v.trim()).apply()

    var server: String
        get() = (sp.getString("server", "") ?: "").trim().trimEnd('/').ifBlank { Link.DEFAULT_SERVER }
        set(v) = sp.edit().putString("server", v.trim()).apply()

    /** Приёмник должен работать (включается кнопкой, переживает перезагрузку). */
    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    /** Принимать любые https-ссылки, а не только profi.ru. */
    var anyHttps: Boolean
        get() = sp.getBoolean("any_https", false)
        set(v) = sp.edit().putBoolean("any_https", v).apply()

    private val timeFmt = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault())

    @Synchronized
    fun log(line: String) {
        val old = (sp.getString("log", "") ?: "").split("\n").filter { it.isNotBlank() }
        val all = (listOf(timeFmt.format(Date()) + "  " + line) + old).take(LOG_LINES)
        sp.edit().putString("log", all.joinToString("\n")).apply()
    }

    @Synchronized
    fun logText(): String = sp.getString("log", "") ?: ""
}
