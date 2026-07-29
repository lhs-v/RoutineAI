package com.routineai

import android.app.Application
import com.routineai.collect.CollectWorker

class RoutineApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CollectWorker.schedule(this)
    }
}
