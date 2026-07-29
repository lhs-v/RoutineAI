package com.rountineai

import android.app.Application
import com.rountineai.collect.CollectWorker

class RountineApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CollectWorker.schedule(this)
    }
}
