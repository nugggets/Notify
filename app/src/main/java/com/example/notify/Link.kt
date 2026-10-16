package com.example.notify

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Протокол совпадает с ProfiMonitor 4.5: тема ntfy = код связи, в сообщении ссылка. */
object Link {
    const val DEFAULT_SERVER = "https://ntfy.sh"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** Соединение держится открытым, ntfy шлёт «пульс» примерно раз в 45 секунд. */
    val streamClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun validCode(code: String): Boolean = Regex("^[A-Za-z0-9_-]{16,64}$").matches(code)

    fun allowed(url: String, anyHttps: Boolean): Boolean =
        if (anyHttps) url.startsWith("https://")
        else Regex("^https://([a-z0-9-]+\\.)?profi\\.ru(/|\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(url)

    /** Проверка связи: отправляет ссылку самому себе. Возвращает null при успехе или текст ошибки. */
    fun sendTest(cfg: Cfg): String? {
        if (!validCode(cfg.code)) return "Сначала сохраните код связи"
        return try {
            val body = JSONObject()
                .put("topic", cfg.code)
                .put("title", "Проверка Notify")
                .put("message", "https://profi.ru/")
                .put("click", "https://profi.ru/")
                .put("priority", 4)
            val req = Request.Builder()
                .url(cfg.server)
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) null else "Сервер ответил ${resp.code}"
            }
        } catch (e: Exception) {
            "Нет связи с сервером: ${e.message ?: e.javaClass.simpleName}"
        }
    }
}
