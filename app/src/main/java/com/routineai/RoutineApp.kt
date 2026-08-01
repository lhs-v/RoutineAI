package com.routineai

import android.app.Application
import com.routineai.collect.CollectWorker

class RoutineApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CollectWorker.schedule(this)
        // 감시 서비스는 여기서 시작할 수 없다 — Application.onCreate 는 아직
        // 포그라운드가 아니라 startForegroundService 가 거부된다.
        // MainActivity 가 보일 때 시작하고, 그 뒤로는 START_STICKY 로 유지된다.
    }
}
