package com.example.notify

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var cfg: Cfg
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var etCode: EditText
    private lateinit var etServer: EditText
    private lateinit var cbAny: CheckBox
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 2000)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Cfg.get(this)
        title = "Notify"

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(16), dp(16), dp(24))

        fun label(text: String, size: Float = 14f, bold: Boolean = false): TextView {
            val t = TextView(this)
            t.text = text
            t.textSize = size
            if (bold) t.setTypeface(t.typeface, android.graphics.Typeface.BOLD)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(10)
            root.addView(t, lp)
            return t
        }

        fun button(text: String, onClick: () -> Unit): Button {
            val b = Button(this)
            b.text = text
            b.isAllCaps = false
            b.setOnClickListener { onClick() }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(6)
            root.addView(b, lp)
            return b
        }

        label("Notify", 24f, true)
        label("Открывает в Chrome ссылки, которые вы отправляете кнопкой «Открыть на втором телефоне» в ProfiMonitor.")

        tvStatus = label("")

        label("Код связи", 14f, true)
        etCode = EditText(this)
        etCode.setSingleLine(true)
        etCode.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        etCode.hint = "Скопируйте код из ProfiMonitor"
        etCode.setText(cfg.code)
        root.addView(etCode, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        button("Вставить код из буфера") { pasteCode() }

        label("Сервер ntfy", 14f, true)
        etServer = EditText(this)
        etServer.setSingleLine(true)
        etServer.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        etServer.setText(cfg.server)
        root.addView(etServer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        cbAny = CheckBox(this)
        cbAny.text = "Принимать любые https-ссылки (по умолчанию только profi.ru)"
        cbAny.isChecked = cfg.anyHttps
        root.addView(cbAny, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        button("Сохранить и запустить приём") { startReceiving() }
        button("Остановить приём") {
            cfg.enabled = false
            ReceiverService.stop(this)
            cfg.log("Приём остановлен")
            ui.postDelayed({ refresh() }, 400)
        }
        button("Проверить: отправить себе ссылку") {
            saveFields()
            thread {
                val err = Link.sendTest(cfg)
                runOnUiThread {
                    toast(err ?: "Отправлено. Через пару секунд должен открыться Chrome")
                }
            }
        }

        label("Разрешения на этом телефоне", 14f, true)
        button("Поверх других приложений") {
            startSafe(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        button("Не ограничивать батарею") {
            startSafe(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        button("Показ на весь экран (Android 14+)") {
            if (Build.VERSION.SDK_INT >= 34) {
                startSafe(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
            } else {
                toast("На этой версии Android отдельное разрешение не нужно")
            }
        }

        label("Журнал", 14f, true)
        tvLog = label("")
        tvLog.textSize = 12f
        tvLog.setTextIsSelectable(true)

        setContentView(ScrollView(this).apply { addView(root) })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        ui.post(tick)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        super.onPause()
    }

    private fun saveFields() {
        cfg.code = etCode.text.toString().trim()
        cfg.server = etServer.text.toString().trim().ifBlank { Link.DEFAULT_SERVER }
        cfg.anyHttps = cbAny.isChecked
    }

    private fun startReceiving() {
        saveFields()
        if (!Link.validCode(cfg.code)) {
            toast("Код: 16–64 символа, только латинские буквы, цифры, - и _")
            return
        }
        cfg.enabled = true
        cfg.log("Приём запущен")
        ReceiverService.start(this)
        ui.postDelayed({ refresh() }, 500)
        if (!Settings.canDrawOverlays(this)) {
            toast("Разрешите «Поверх других приложений», иначе Chrome может не открыться сам")
        }
    }

    private fun pasteCode() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) toast("Буфер обмена пуст") else etCode.setText(text)
    }

    private fun startSafe(i: Intent) {
        try {
            startActivity(i)
        } catch (e: Exception) {
            toast("Не удалось открыть настройки. Откройте их вручную в настройках приложения")
        }
    }

    private fun refresh() {
        val pm = getSystemService(PowerManager::class.java)
        val notifOk = getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        val sb = StringBuilder()
        sb.append(if (ReceiverService.running) "✅ Приём работает" else "⏹ Приём остановлен").append("\n")
        sb.append(if (Settings.canDrawOverlays(this)) "✅" else "⚠️").append(" Поверх других приложений\n")
        sb.append(if (pm.isIgnoringBatteryOptimizations(packageName)) "✅" else "⚠️").append(" Батарея не ограничена\n")
        sb.append(if (notifOk) "✅" else "⚠️").append(" Уведомления разрешены")
        tvStatus.text = sb.toString()
        tvLog.text = cfg.logText().ifBlank { "Пока пусто" }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
