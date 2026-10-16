package com.example.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import okhttp3.Request
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Держит соединение с темой ntfy и открывает пришедшие ссылки в Chrome.
 * Чтобы Android разрешил открыть Chrome из фона, нужно разрешение «Поверх других приложений».
 */
class ReceiverService : Service() {

    companion object {
        private const val CH_RX = "notify_rx"
        private const val CH_OPEN = "notify_open"
        private const val NOTIF_RX = 7201
        private const val NOTIF_OPEN = 7202
        private const val MAX_AGE_SEC = 120L

        @Volatile
        var running = false

        /** Открыто ли сейчас соединение с сервером и когда пришёл последний сигнал (время сервера не нужно, берём часы телефона только для показа). */
        @Volatile
        var connected = false

        @Volatile
        var lastEventAt = 0L

        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, ReceiverService::class.java))
            } catch (e: Exception) {
                Cfg.get(ctx).log("Не удалось запустить приём: ${e.message}")
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, ReceiverService::class.java))
        }
    }

    @Volatile
    private var generation = 0
    private var everConnected = false
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var cfg: Cfg

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val cfgLocal = Cfg.get(this)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                cfgLocal.log("СБОЙ приложения: ${e.javaClass.simpleName}: ${e.message}")
            } catch (x: Exception) {
                // ignore
            }
            prev?.uncaughtException(t, e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        cfg = Cfg.get(this)
        createChannels()
        try {
            val n = ongoing()
            if (Build.VERSION.SDK_INT >= 34) {
                ServiceCompat.startForeground(this, NOTIF_RX, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_RX, n)
            }
        } catch (e: Exception) {
            // Android 12+ не даёт запускать такую службу из фона (например, при перезапуске системой)
            cfg.log("Android не дал запустить приём (${e.javaClass.simpleName}). Откройте Notify: приём запустится заново")
            running = false
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        acquireLock()
        val gen = ++generation
        thread(name = "notify-rx", isDaemon = true) { loop(gen) }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        connected = false
        generation++ // старый поток сам завершится
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            // ignore
        }
        super.onDestroy()
    }

    private fun acquireLock() {
        try {
            if (wakeLock?.isHeld == true) return
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "notify:rx").also {
                it.setReferenceCounted(false)
                it.acquire()
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    /**
     * Часы телефона не используются для отбора сообщений: если они уходят вперёд или назад на пару минут,
     * сообщения иначе молча отбрасывались бы. Возраст сообщения считается по времени сервера (событие open).
     */
    private fun loop(gen: Int) {
        var lastId = ""
        var lastServerTime = 0L
        var backoff = 3_000L
        var hadError = false
        while (gen == generation) {
            val code = cfg.code
            if (!Link.validCode(code)) {
                try {
                    Thread.sleep(5_000)
                } catch (e: InterruptedException) {
                    return
                }
                continue
            }
            try {
                // без since сервер присылает только новые сообщения; после обрыва догоняем пропущенное
                val since = when {
                    lastId.isNotEmpty() -> "?since=$lastId"
                    lastServerTime > 0 -> "?since=$lastServerTime"
                    else -> ""
                }
                val req = Request.Builder().url("${cfg.server}/$code/json$since").build()
                Link.streamClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw RuntimeException("сервер ответил ${resp.code}")
                    val src = resp.body?.source() ?: throw RuntimeException("пустой ответ")
                    var openTime = 0L
                    while (gen == generation) {
                        val line = src.readUtf8Line() ?: break
                        if (line.isBlank()) continue
                        val j = try {
                            JSONObject(line)
                        } catch (e: Exception) {
                            continue
                        }
                        lastEventAt = System.currentTimeMillis()
                        val t = j.optLong("time", 0L)
                        if (t > 0) lastServerTime = t
                        when (j.optString("event")) {
                            "open" -> {
                                openTime = t
                                connected = true
                                backoff = 3_000L
                                if (hadError || lastId.isEmpty() && openTime > 0 && !everConnected) {
                                    cfg.log("Подключено к серверу ${cfg.server}")
                                }
                                everConnected = true
                                hadError = false
                            }
                            "message" -> {
                                lastId = j.optString("id", lastId)
                                if (openTime > 0 && t > 0 && openTime - t > MAX_AGE_SEC) {
                                    cfg.log("Старое сообщение пропущено")
                                } else {
                                    handle(j.optString("message").trim(), j.optString("title"))
                                }
                            }
                            else -> { /* keepalive */ }
                        }
                    }
                }
                connected = false
            } catch (e: Exception) {
                connected = false
                if (gen != generation) return
                hadError = true
                cfg.log("Нет связи (${e.message ?: e.javaClass.simpleName}), повтор через ${backoff / 1000} с")
            }
            if (gen != generation) return
            try {
                Thread.sleep(backoff)
            } catch (e: InterruptedException) {
                return
            }
            backoff = (backoff * 2).coerceAtMost(30_000L)
        }
    }

    private fun handle(url: String, title: String) {
        if (!Link.allowed(url, cfg.anyHttps)) {
            cfg.log("Пришла недопустимая ссылка, пропущена")
            return
        }
        cfg.log("Получено: $url")
        wakeScreen()
        var opened = false
        try {
            startActivity(viewIntent(url, chrome = true))
            opened = true
        } catch (e: Exception) {
            try {
                startActivity(viewIntent(url, chrome = false))
                opened = true
            } catch (e2: Exception) {
                // остаётся только уведомление
            }
        }
        if (opened) cfg.log("Открыто в браузере")
        // запасной путь: если система не дала открыть из фона, уведомление откроет ссылку нажатием
        // (при выключенном экране покажется на весь экран)
        showOpenNotification(url, title, fullScreen = !opened || !Settings.canDrawOverlays(this))
    }

    private fun viewIntent(url: String, chrome: Boolean): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (chrome) setPackage("com.android.chrome")
        }

    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "notify:screen"
            ).acquire(5_000L)
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun showOpenNotification(url: String, title: String, fullScreen: Boolean) {
        try {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pi = PendingIntent.getActivity(this, url.hashCode(), viewIntent(url, chrome = true), flags)
            val b = NotificationCompat.Builder(this, CH_OPEN)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title.ifBlank { "Ссылка с первого телефона" })
                .setContentText("Нажмите, чтобы открыть в Chrome")
                .setStyle(NotificationCompat.BigTextStyle().bigText(url))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(pi)
            if (fullScreen) b.setFullScreenIntent(pi, true)
            getSystemService(NotificationManager::class.java).notify(NOTIF_OPEN, b.build())
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun createChannels() {
        val m = getSystemService(NotificationManager::class.java)
        m.createNotificationChannel(
            NotificationChannel(CH_RX, "Приём ссылок", NotificationManager.IMPORTANCE_LOW)
        )
        m.createNotificationChannel(
            NotificationChannel(CH_OPEN, "Полученная ссылка", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun ongoing(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CH_RX)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Notify: приём ссылок включён")
            .setContentText("Присланные ссылки открываются в Chrome")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .build()
    }
}
