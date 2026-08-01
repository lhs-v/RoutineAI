package com.routineai.watch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.routineai.MainActivity
import com.routineai.collect.Permissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 앱 전환 감지의 주 경로.
 *
 * 접근성 서비스가 더 빠르지만(즉시 vs 1~2초) 삼성 기기에서는 반드시 사용자가
 * 설정 화면에서 손으로 켜야 하고, 앱을 다시 설치할 때마다 꺼진다 — adb 로
 * 켜도 시스템이 되돌린다(악성앱 방지). 데모 중에 조용히 꺼져 있으면
 * 아무것도 동작하지 않으므로, 이미 가진 '사용 정보 접근' 권한만으로
 * 도는 경로를 주 경로로 둔다.
 *
 * 접근성은 있으면 더 빠른 보조이고, 분할화면 토글에는 여전히 필요하다.
 */
class WatchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    private var lastPkg: String? = null
    private var cursor = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, notification())
        isRunning = true
        cursor = System.currentTimeMillis()
        loop = scope.launch {
            while (isActive) {
                runCatching { poll() }.onFailure { Log.w(TAG, "폴링 실패", it) }
                delay(POLL_MS)
            }
        }
        Log.i(TAG, "감시 시작")
    }

    private suspend fun poll() {
        if (!Permissions.hasUsageAccess(applicationContext)) return
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        // 경계에서 이벤트를 흘리지 않도록 조금 겹쳐 읽는다.
        val events = usm.queryEvents(cursor - 1_000, now)
        cursor = now

        val e = UsageEvents.Event()
        var latest: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                val p = e.packageName ?: continue
                if (p != packageName && p !in IGNORED) latest = p
            }
        }
        val pkg = latest ?: return
        if (pkg == lastPkg) return
        lastPkg = pkg
        PatternWatcher.onAppForeground(applicationContext, pkg)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        loop?.cancel()
        isRunning = false
        Log.i(TAG, "감시 중지")
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "패턴 감시", NotificationManager.IMPORTANCE_MIN).apply {
                description = "수락한 루틴을 실시간으로 제안하기 위해 앱 전환을 봅니다"
                setShowBadge(false)
            }
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("패턴 감시 중")
            .setContentText("수락한 루틴을 그 순간에 제안합니다")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        @Volatile
        var isRunning = false
            private set

        private const val TAG = "WatchService"
        private const val CHANNEL = "watch"
        private const val NOTIF_ID = 4201

        /** 1.5초면 "그 순간"으로 느껴지고 배터리 부담도 작다. */
        private const val POLL_MS = 1_500L

        private val IGNORED = setOf(
            "com.android.systemui", "android",
            "com.samsung.android.app.aodservice",
        )

        fun start(ctx: Context) {
            if (!Permissions.hasUsageAccess(ctx)) return
            runCatching {
                ctx.startForegroundService(Intent(ctx, WatchService::class.java))
            }.onFailure { Log.w(TAG, "감시 시작 실패", it) }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, WatchService::class.java)) }
        }
    }
}
