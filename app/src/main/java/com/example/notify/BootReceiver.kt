package com.example.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Поднимает приёмник после перезагрузки телефона и после обновления приложения. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cfg = Cfg.get(context)
        if (!cfg.enabled || !Link.validCode(cfg.code)) return
        cfg.log("Телефон перезагружен или приложение обновлено: запускаю приём")
        ReceiverService.start(context)
    }
}
