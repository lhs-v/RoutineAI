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
import com.routineai.collect.NetworkCollector
import com.routineai.collect.Permissions
import com.routineai.data.BtEventRow
import com.routineai.data.Db
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
    private var btConnected: Set<String> = emptySet()
    private var btInitialized = false
    /** "wifi:<SSID>" | "cellular" | "none". null 이면 아직 첫 폴링 전 */
    private var netState: String? = null
    /** 마지막 Wi-Fi 이탈 (state 문자열, 시각) — 짧은 재접속을 재진입으로 안 치기 위해 */
    private var lastWifiDrop: Pair<String, Long>? = null
    private var lastMinute: String? = null
    private var lastExercisePoll = 0L
    private val firedExercise = HashSet<Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, notification())
        isRunning = true
        cursor = System.currentTimeMillis()
        loop = scope.launch {
            // 프로세스가 죽으면 메모리의 모드 추적이 사라진다 — 지난 세션이
            // 바꿔둔 설정이 있으면 남의 설정을 바꿔놓은 채가 되므로 먼저 되돌린다.
            runCatching { PatternWatcher.restoreModes(applicationContext) }
                .onFailure { Log.w(TAG, "모드 복구 실패", it) }
            while (isActive) {
                runCatching { poll() }.onFailure { Log.w(TAG, "폴링 실패", it) }
                runCatching { pollBluetooth() }.onFailure { Log.w(TAG, "BT 폴링 실패", it) }
                runCatching { pollNetwork() }.onFailure { Log.w(TAG, "네트워크 폴링 실패", it) }
                runCatching { pollTime() }.onFailure { Log.w(TAG, "시간 트리거 실패", it) }
                runCatching { pollExercise() }.onFailure { Log.w(TAG, "운동 폴링 실패", it) }
                runCatching { PatternWatcher.evaluateOutcomes(applicationContext) }
                    .onFailure { Log.w(TAG, "실행 결과 판정 실패", it) }
                runCatching { pollExperiments() }
                    .onFailure { Log.w(TAG, "실험 만료 확인 실패", it) }
                delay(POLL_MS)
            }
        }
        Log.i(TAG, "감시 시작")
    }

    private suspend fun poll() {
        if (!Permissions.hasUsageAccess(applicationContext)) return
        // 접근성이 붙어 있으면 앱 전환은 그쪽이 즉시 보고한다. 같은 신호를
        // 1.5초 늦게 또 만들 이유가 없으니 조회를 쉰다 — 단, 커서는
        // 전진시켜서 접근성이 꺼지는 순간 밀린 옛 이벤트를 재생하지 않는다.
        if (PatternAccessibilityService.isConnected()) {
            cursor = System.currentTimeMillis()
            return
        }
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

    /**
     * BT 연결 상태를 상태 변화로 환산한다. 여러 기기가 붙어 있을 수 있으므로
     * 집합으로 다뤄, 새로 붙은 기기마다 트리거한다 — 워치가 상시 연결돼 있어도
     * 이어폰 연결을 놓치지 않는다(실측: 워치만 잡혀 버즈가 가려졌다).
     *
     * ACL_CONNECTED 는 연결되는 **순간**에만 오는 브로드캐스트라, 앱을 설치하기
     * 전부터 이어폰이 연결돼 있으면 영원히 오지 않는다(실측으로 확인: 리시버가
     * 한 번도 동작하지 않았고 bt_event 가 비어 있었다). 폴링이 그 공백을 메운다.
     *
     * 서비스가 막 떴을 때 이미 연결돼 있던 것은 트리거로 보지 않는다 —
     * 그건 "방금 연결했다"가 아니라 "원래 연결돼 있었다"이기 때문이다.
     */
    private suspend fun pollBluetooth() {
        val now = connectedDevices()

        // 첫 폴링은 **빈 상태여도** 초기화로 친다. 상태 변화가 있을 때만
        // 초기화하면, 아무것도 없이 시작한 뒤의 첫 연결이 "원래 연결돼
        // 있었다"로 오인되어 트리거가 삼켜진다(연결이 상시인 워치가 있어야만
        // 통과하던 버그).
        if (!btInitialized) {
            btInitialized = true
            btConnected = now
            now.forEach { BtState.update(it, true) }
            Log.i(TAG, "BT 초기 상태: ${now.joinToString().ifEmpty { "연결 없음" }}")
            return
        }
        if (now == btConnected) return
        val added = now - btConnected
        val removed = btConnected - now
        btConnected = now

        // 수집도 여기서 한다. 기록은 원래 BtReceiver 전담이었는데, 이 기기에서
        // ACL 브로드캐스트가 한 번도 오지 않아(위 주석의 실측) 트리거는 폴링으로
        // 살았지만 bt_event 는 영원히 비어 있었다 — 리포트의 BT 축이 백필 없이는
        // 0 이던 원인. 리시버가 사는 기기에서는 몇 초 차 이중 기록이 생길 수
        // 있으나, 연쇄 계산(연결→앱)에는 무해하다.
        val dao = Db.get(applicationContext).dao()
        val now2 = System.currentTimeMillis()
        for (name in added) {
            Log.i(TAG, "BT 연결 감지: $name")
            dao.insertBt(BtEventRow(ts = now2, action = "connect", name = name, majorClass = 0))
            PatternWatcher.onBluetooth(applicationContext, true, name)
        }
        for (name in removed) {
            Log.i(TAG, "BT 해제 감지: $name")
            dao.insertBt(BtEventRow(ts = now2, action = "disconnect", name = name, majorClass = 0))
            PatternWatcher.onBluetooth(applicationContext, false, name)
        }
    }

    /**
     * Wi-Fi 접속 변화를 트리거로 환산한다. 별칭(Wi-Fi A, B…)은 리포트를
     * 만드는 [com.routineai.analysis.Analyzer] 와 같은 규칙 — net_change
     * 등장 순서 — 로 계산해야 제안의 triggerParam 과 맞아떨어진다.
     */
    private suspend fun pollNetwork() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE)
            as android.net.ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val kind = when {
            caps == null -> "none"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }
        val ssid = if (kind == "wifi") NetworkCollector(applicationContext).currentSsid() else null
        // wifi 인 채 SSID 만 null 이 되는 것은 재협상 순간의 판독 실패지 다른
        // 네트워크가 아니다 — 상태 변화로 치면 "연결→?→연결" 트리거가 분
        // 단위로 반복된다(실측 8/2). 직전이 wifi 면 상태를 유지한다.
        if (kind == "wifi" && ssid == null && netState?.startsWith("wifi:") == true) return
        val state = if (kind == "wifi") "wifi:${ssid.orEmpty()}" else kind

        if (netState == null) { netState = state; return }   // 시작 상태는 트리거 아님
        if (state == netState) return
        val prev = netState!!
        netState = state

        // 실시간으로 잡았으니 장소 축 로그도 그 자리에서 남긴다.
        NetworkCollector(applicationContext).recordCurrentNetwork()

        if (state.startsWith("wifi:")) {
            // 방금 떠났던 Wi-Fi 로의 빠른 복귀는 재진입이 아니라 핸드오버
            // 낑김이다(실측: 10~19초 만에 복귀). 기록은 위에서 남겼고
            // 트리거만 삼킨다.
            val drop = lastWifiDrop
            if (drop != null && drop.first == state &&
                System.currentTimeMillis() - drop.second < WIFI_REJOIN_MS
            ) {
                Log.i(TAG, "Wi-Fi 재접속 접음(핸드오버): $state")
                return
            }
            val alias = wifiAlias(ssid)
            Log.i(TAG, "Wi-Fi 연결 감지: $alias")
            PatternWatcher.onNetwork(applicationContext, true, alias)
        } else if (prev.startsWith("wifi:")) {
            lastWifiDrop = prev to System.currentTimeMillis()
            val alias = wifiAlias(prev.removePrefix("wifi:").ifBlank { null })
            Log.i(TAG, "Wi-Fi 해제 감지: $alias")
            PatternWatcher.onNetwork(applicationContext, false, alias)
        }
    }

    /** SSID → 리포트와 같은 별칭. 변화 순간에만 불려 전체 조회 비용을 감수한다. */
    private suspend fun wifiAlias(ssid: String?): String {
        val changes = Db.get(applicationContext).dao().netChanges(0, System.currentTimeMillis())
        val alias = LinkedHashMap<String, String>()
        changes.filter { it.kind == "wifi" }.forEach { r ->
            alias.getOrPut(r.ssid ?: "wifi-unknown") { "Wi-Fi " + ('A' + alias.size) }
        }
        return alias[ssid ?: "wifi-unknown"] ?: "Wi-Fi"
    }

    /** 분이 바뀌는 순간 시간 트리거를 확인한다. triggerParam 형식은 "HH:mm". */
    private suspend fun pollTime() {
        val t = java.time.LocalTime.now()
        // 데모 시계가 켜져 있으면 '시'만 바꿔 본다 — 조건 판정(capture)과
        // 같은 세계여야 시간 트리거 제안도 시연에서 함께 산다.
        val demoHour = com.routineai.interpret.Settings(applicationContext)
            .demoHour.takeIf { it in 0..23 }
        val hhmm = "%02d:%02d".format(demoHour ?: t.hour, t.minute)
        if (hhmm == lastMinute) return
        val first = lastMinute == null
        lastMinute = hhmm
        if (first) return   // 서비스가 뜬 순간의 분은 "그 시각이 됐다"가 아니다
        PatternWatcher.onTime(applicationContext, hhmm)
    }

    /**
     * 운동 시작을 트리거로 환산한다. Health Connect 에는 실시간 콜백이 없어
     * 주기 조회로 근사한다 — 세션이 동기화되는 시차만큼 늦게 잡히는 한계는
     * 플랫폼의 것이다. 권한이 없으면 collect 가 조용히 아무것도 안 한다.
     */
    private suspend fun pollExercise() {
        val now = System.currentTimeMillis()
        if (now - lastExercisePoll < EXERCISE_POLL_MS) return
        lastExercisePoll = now
        val lookback = now - 2 * EXERCISE_POLL_MS
        runCatching {
            com.routineai.collect.HealthCollector(applicationContext).collect(lookback, now)
        }
        val dao = Db.get(applicationContext).dao()
        for (s in dao.healthSessions(lookback, now)) {
            if (!s.kind.startsWith("exercise:")) continue
            if (!firedExercise.add(s.tsStart)) continue
            val name = s.kind.removePrefix("exercise:")
            Log.i(TAG, "운동 시작 감지: $name")
            PatternWatcher.onExercise(applicationContext, name)
        }
    }

    private var lastExperimentPoll = 0L

    /** 만료된 실험의 조건을 되돌린다. 실험이 없으면 60초에 한 번의 조회뿐이다. */
    private suspend fun pollExperiments() {
        val now = System.currentTimeMillis()
        if (now - lastExperimentPoll < EXPERIMENT_POLL_MS) return
        lastExperimentPoll = now
        val n = com.routineai.interpret.Experiments.rollbackExpired(applicationContext)
        if (n > 0) Log.i(TAG, "실험 만료 롤백: ${n}건")
    }

    /** 지금 연결된 기기 이름들. 본딩 목록에서 실제 연결 여부를 묻는다. */
    private fun connectedDevices(): Set<String> = runCatching {
        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
        val adapter = bm.adapter ?: return emptySet()
        if (!adapter.isEnabled) return emptySet()
        val names = HashSet<String>()
        bm.getConnectedDevices(android.bluetooth.BluetoothProfile.GATT)
            .mapNotNullTo(names) { it.name }
        // GATT 는 LE 기기만 잡는다. 클래식 오디오(이어버즈)는 본딩 목록에
        // isConnected 를 물어야 보인다 — 공개 API 가 없어 리플렉션을 쓴다.
        adapter.bondedDevices?.forEach { d ->
            val connected = runCatching {
                d.javaClass.getMethod("isConnected").invoke(d) as? Boolean == true
            }.getOrDefault(false)
            if (connected) d.name?.let { names.add(it) }
        }
        names
    }.getOrDefault(emptySet())

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

        /** Health Connect 조회는 무거워서 별도 주기로 돈다. */
        private const val EXERCISE_POLL_MS = 60_000L

        /** 실험 만료는 분 단위면 충분하다. */
        private const val EXPERIMENT_POLL_MS = 60_000L

        /** 이 시간 안에 같은 Wi-Fi 로 돌아오면 재진입이 아니라 핸드오버 낑김 */
        private const val WIFI_REJOIN_MS = 3L * 60 * 1000

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
