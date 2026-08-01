package com.routineai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.routineai.analysis.Analyzer
import com.routineai.analysis.Report
import androidx.compose.foundation.clickable
import androidx.health.connect.client.PermissionController
import com.routineai.collect.HealthCollector
import com.routineai.data.ProposalRow
import com.routineai.interpret.ProposalEngine
import com.routineai.watch.Applier
import com.routineai.watch.SuggestionOverlay
import com.routineai.watch.WatchService
import com.routineai.watch.WatchStatus
import com.routineai.collect.NetworkCollector
import com.routineai.collect.Permissions
import com.routineai.collect.UsageCollector
import com.routineai.data.Db
import com.routineai.interpret.AzureConfig
import com.routineai.interpret.Interpreter
import com.routineai.interpret.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val OK = Color(0xFF0CA30C)
private val NEED = Color(0xFFD03B3B)
private val WARN = Color(0xFFEDA100)

private val CATEGORY_LABELS = mapOf(
    "trigger_routine" to "자동 실행",
    "app_pair" to "앱페어",
    "app_mode" to "앱 모드",
    "notif_cleanup" to "알림 정리",
    "time_shortcut" to "시간 습관",
)

/**
 * 화면 구성.
 *
 * 이 앱의 값어치는 "며칠치가 쌓였는가"에 있다. 그래서 누적 상태를 탭 위에 상시로 두고,
 * 목적에 해당하는 대시보드·해석을 앞 탭에, 한 번 맞춰두면 다시 안 보는 권한·수집·해석
 * 연결은 설정 탭 하나로 몰았다.
 */
class MainActivity : ComponentActivity() {

    private val json = Json { encodeDefaults = true }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Root() } }
    }

    override fun onStart() {
        super.onStart()
        // 포그라운드 서비스는 앱이 보일 때만 시작할 수 있다. 한 번 뜨면
        // START_STICKY 로 유지되므로 앱을 닫아도 감시는 계속된다.
        WatchService.start(this)
    }

    /** 탭 위에 상시로 띄우는 누적 상태 */
    private data class CollectStatus(
        val storedEvents: Int,
        val oldestTs: Long?,
        val newestTs: Long?,
        val lastCollectTs: Long?,
    ) {
        /** 관측 기간(일). 첫 기록과 마지막 기록 사이를 센다. */
        val spanDays: Int
            get() = if (oldestTs != null && newestTs != null)
                ((newestTs - oldestTs) / 86_400_000L + 1).toInt() else 0
    }

    private suspend fun loadStatus(ctx: Context): CollectStatus {
        val dao = Db.get(ctx).dao()
        return CollectStatus(
            storedEvents = dao.eventCount(),
            oldestTs = dao.firstEventTs(),
            newestTs = dao.lastEventTs(),
            lastCollectTs = dao.get(UsageCollector.KEY_LAST_SYNC)?.toLongOrNull(),
        )
    }

    // ------------------------------------------------------------------

    @Composable
    private fun Root() {
        val ctx = LocalContext.current
        val prefs = remember { Settings(ctx) }

        // 설정 화면에 다녀오면 권한이 바뀌어 있을 수 있으므로 화면이 돌아올 때마다 다시 본다.
        var permTick by remember { mutableIntStateOf(0) }
        val owner = LocalLifecycleOwner.current
        DisposableEffect(owner) {
            val obs = LifecycleEventObserver { _, e ->
                if (e == Lifecycle.Event.ON_RESUME) permTick++
            }
            owner.lifecycle.addObserver(obs)
            onDispose { owner.lifecycle.removeObserver(obs) }
        }

        val usageOk = remember(permTick) { Permissions.hasUsageAccess(ctx) }
        val notifOk = remember(permTick) { Permissions.hasNotificationAccess(ctx) }
        val locOk = remember(permTick) {
            ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
        val overlayOk = remember(permTick) { Applier.hasOverlay(ctx) }
        val a11yOk = remember(permTick) { Applier.hasAccessibility(ctx) }
        val writeOk = remember(permTick) { android.provider.Settings.System.canWrite(ctx) }
        val btOk = remember(permTick) {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        }
        // Health Connect 권한은 동기 조회가 없어 상태로 들고 갱신한다.
        var healthOk by remember { mutableStateOf(false) }
        LaunchedEffect(permTick) {
            healthOk = withContext(Dispatchers.IO) { HealthCollector.grantedAll(ctx) }
        }

        var statusTick by remember { mutableIntStateOf(0) }
        var status by remember { mutableStateOf<CollectStatus?>(null) }
        LaunchedEffect(statusTick, permTick) {
            status = withContext(Dispatchers.IO) { loadStatus(ctx) }
        }

        var tab by remember { mutableIntStateOf(0) }
        var busy by remember { mutableStateOf(false) }
        var step by remember { mutableStateOf("") }
        var collectMsg by remember { mutableStateOf("") }

        // 마지막 리포트를 들고 시작한다. 회전하거나 앱을 다시 켜도 대시보드가 비지 않는다.
        var reportJson by remember { mutableStateOf(prefs.lastReport.ifBlank { null }) }
        var reportAt by remember { mutableStateOf(prefs.lastReportAt) }
        var reportMsg by remember { mutableStateOf("") }
        var azure by remember { mutableStateOf(prefs.azureConfig()) }
        var proposalsTick by remember { mutableIntStateOf(0) }
        // 에셋이 없는 빌드(저장소를 클론해서 빌드한 경우)에서는 데모를 켤 수 없다.
        // 이전에 켜둔 상태로 저장돼 있어도 여기서 내려야 첫 질의에서 안 터진다.
        val demoAvailable = remember { Db.hasDemoAsset(ctx) }
        var demo by remember { mutableStateOf(prefs.demoMode && demoAvailable) }

        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                StatusHeader(status, usageOk, demo)

                TabRow(selectedTabIndex = tab) {
                    listOf("대시보드", "제안", "설정").forEachIndexed { i, t ->
                        Tab(
                            selected = tab == i,
                            onClick = { tab = i },
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(t)
                                    // 설정 탭에만 표시한다. 필수 권한이 꺼졌으면 빨강,
                                    // 해석 연결이 비었으면 노랑.
                                    if (i == 2 && (!usageOk || !azure.isComplete)) {
                                        Spacer(Modifier.size(5.dp))
                                        Dot(if (!usageOk) NEED else WARN)
                                    }
                                }
                            },
                        )
                    }
                }

                if (busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (step.isNotBlank()) {
                        Text(
                            step,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(16.dp, 6.dp, 16.dp, 0.dp),
                        )
                    }
                }

                Box(Modifier.fillMaxSize()) {
                    when (tab) {
                        0 -> DashboardTab(
                            usageOk = usageOk || demo, busy = busy, msg = reportMsg,
                            reportJson = reportJson, reportAt = reportAt, demo = demo,
                            onGoSettings = { tab = 2 },
                            onBuild = {
                                busy = true; step = "시작하는 중"
                                lifecycleScope.launch {
                                    val report: Report = withContext(Dispatchers.Default) {
                                        Analyzer(ctx, demo).build { s ->
                                            lifecycleScope.launch { step = s }
                                        }
                                    }
                                    val encoded =
                                        json.encodeToString(Report.serializer(), report)
                                    reportJson = encoded
                                    reportAt = System.currentTimeMillis()
                                    prefs.lastReport = encoded
                                    prefs.lastReportAt = reportAt
                                    val d = report.diagnostics
                                    reportMsg = buildString {
                                        appendLine("이벤트 ${d.storedEvents}건 · 세션 ${d.sessionsBuilt}개")
                                        appendLine(
                                            "온전한 하루 ${report.meta.fullDays.size}일 · " +
                                                "부분일 ${report.meta.partialDays.size}일"
                                        )
                                        if (report.quality.warnings.isNotEmpty()) {
                                            append("경고 ${report.quality.warnings.size}건 — 대시보드 위쪽 확인")
                                        }
                                    }
                                    busy = false; step = ""
                                }
                            },
                        )

                        1 -> ProposalsTab(
                            busy = busy, azure = azure, prefs = prefs,
                            refreshTick = proposalsTick,
                            onGoSettings = { tab = 2 },
                            onChanged = { proposalsTick++ },
                            onAnalyze = {
                                busy = true; step = "분석 시작"
                                lifecycleScope.launch {
                                    val res = withContext(Dispatchers.Default) {
                                        ProposalEngine(ctx).analyze(azure, demo) { s ->
                                            lifecycleScope.launch { step = s }
                                        }
                                    }
                                    res.onSuccess { o ->
                                        prefs.lastAnalysisAt = System.currentTimeMillis()
                                        prefs.lastAnalysisNote = ProposalEngine.encodeNote(
                                            ProposalEngine.StoredNote(
                                                o.analysisNote, o.rejected, prefs.lastAnalysisAt
                                            )
                                        )
                                    }.onFailure { e ->
                                        prefs.lastAnalysisNote = ProposalEngine.encodeNote(
                                            ProposalEngine.StoredNote(
                                                "분석 실패: ${e.message}", emptyList(),
                                                System.currentTimeMillis()
                                            )
                                        )
                                    }
                                    proposalsTick++
                                    busy = false; step = ""
                                }
                            },
                        )

                        else -> SettingsTab(
                            usageOk = usageOk, notifOk = notifOk, locOk = locOk,
                            btOk = btOk, healthOk = healthOk, prefs = prefs,
                            overlayOk = overlayOk, a11yOk = a11yOk, writeOk = writeOk,
                            busy = busy, collectMsg = collectMsg, azure = azure,
                            demo = demo, demoAvailable = demoAvailable,
                            onDemoChange = {
                                demo = it
                                prefs.demoMode = it
                                // 이전 리포트는 반대쪽 데이터로 만든 것이라 그대로 두면
                                // 어느 쪽 결과인지 알 수 없다. 버리고 다시 만들게 한다.
                                reportJson = null
                                reportMsg = ""
                                reportAt = 0L
                                prefs.lastReport = ""
                                prefs.lastReportAt = 0L
                            },
                            onPermChanged = { permTick++ },
                            onChanged = {
                                proposalsTick++
                                reportJson = prefs.lastReport.ifBlank { null }
                                reportAt = prefs.lastReportAt
                                reportMsg = ""
                            },
                            onAzureChange = {
                                azure = it
                                prefs.azureEndpoint = it.endpoint
                                prefs.azureApiKey = it.apiKey
                                prefs.azureApiVersion = it.apiVersion
                                prefs.azureDeployment = it.deployment
                            },
                            onCollect = {
                                busy = true; step = "이벤트 읽는 중"
                                lifecycleScope.launch {
                                    val r = withContext(Dispatchers.IO) {
                                        NetworkCollector(ctx).recordCurrentNetwork()
                                        UsageCollector(ctx).collect()
                                    }
                                    collectMsg = buildString {
                                        appendLine("이번에 읽은 이벤트 ${r.scanned}건")
                                        appendLine("DB 누적 ${r.totalStored}건")
                                        r.oldestStored?.let {
                                            appendLine("가장 오래된 기록 ${fmt(it)}")
                                        }
                                        append("조회 구간 ${fmt(r.windowFrom)} ~ ${fmt(r.windowTo)}")
                                    }
                                    busy = false; step = ""
                                    statusTick++
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------

    /**
     * 상시 상태 바.
     *
     * 이 앱은 오래 쓸수록 값어치가 커지는 구조라, 지금 며칠치가 쌓였고 마지막 수집이
     * 언제였는지가 항상 보여야 한다. 예전에는 수집 탭에 들어가야만 보였다.
     */
    @Composable
    private fun StatusHeader(status: CollectStatus?, usageOk: Boolean, demo: Boolean) {
        Column(Modifier.fillMaxWidth().padding(16.dp, 14.dp, 16.dp, 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("RoutineAI", style = MaterialTheme.typography.titleLarge)
                if (demo) {
                    Spacer(Modifier.size(8.dp))
                    Chip("데모 데이터", WARN)
                }
            }
            Spacer(Modifier.height(8.dp))

            if (demo) {
                Text(
                    "리포트를 데모 로그로 만듭니다. 아래 누적 수치는 이 기기의 실제 기록입니다.",
                    style = MaterialTheme.typography.labelSmall,
                    color = WARN,
                )
                Spacer(Modifier.height(6.dp))
            }

            if (status == null) {
                Text("상태 확인 중", style = MaterialTheme.typography.labelSmall)
            } else if (status.storedEvents == 0) {
                Text(
                    if (usageOk) "아직 수집된 기록이 없습니다 — 설정 탭에서 '지금 수집'"
                    else "'사용 정보 접근'이 꺼져 있습니다 — 설정 탭에서 켜주세요",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (usageOk) WARN else NEED,
                )
            } else {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Chip("누적 ${status.storedEvents.formatted()}건")
                    Chip("${status.spanDays}일치")
                    status.oldestTs?.let { o ->
                        status.newestTs?.let { n -> Chip("${fmtDay(o)} ~ ${fmtDay(n)}") }
                    }
                    Chip(
                        status.lastCollectTs?.let { "수집 ${ago(it)}" } ?: "수집 기록 없음",
                        if (status.lastCollectTs.isStale()) WARN else Color.Unspecified,
                    )
                    if (!usageOk) Chip("권한 꺼짐", NEED)
                }
            }
        }
    }

    @Composable
    private fun Chip(text: String, tint: Color = Color.Unspecified) {
        Surface(
            shape = RoundedCornerShape(99.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                color = if (tint == Color.Unspecified)
                    MaterialTheme.colorScheme.onSurfaceVariant else tint,
                modifier = Modifier.padding(9.dp, 4.dp),
            )
        }
    }

    // ------------------------------------------------------------------

    /**
     * 대시보드 탭.
     *
     * WebView 를 verticalScroll 안에 넣으면 안 된다. 바깥 스크롤이 드래그를 먼저
     * 먹어버려서 WebView 내부가 움직이지 않고, 첫 화면 아래로는 볼 수가 없다.
     * 그래서 컨트롤은 위에 고정하고 WebView 가 남은 높이를 전부 차지하게 둔다.
     */
    @Composable
    private fun DashboardTab(
        usageOk: Boolean, busy: Boolean, msg: String, reportJson: String?,
        reportAt: Long, demo: Boolean, onBuild: () -> Unit, onGoSettings: () -> Unit,
    ) {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.padding(16.dp, 12.dp, 16.dp, 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!usageOk) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = NEED.copy(alpha = .10f)
                        )
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "'사용 정보 접근'이 꺼져 있어 리포트가 전부 0으로 나옵니다.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            OutlinedButton(onClick = onGoSettings) { Text("설정 탭으로") }
                        }
                    }
                }
                Button(enabled = !busy, onClick = onBuild) {
                    Text(
                        (if (demo) "데모 " else "") +
                            (if (reportJson == null) "리포트 생성" else "리포트 다시 생성")
                    )
                }
                when {
                    msg.isNotBlank() ->
                        Text(msg, style = MaterialTheme.typography.bodySmall)
                    // 이전에 만들어둔 리포트를 복원한 경우
                    reportJson != null && reportAt > 0L -> Text(
                        "${fmt(reportAt)}에 만든 리포트입니다. 새 기록을 반영하려면 다시 생성하세요.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    !busy -> Text(
                        "리포트를 만들면 여기에 대시보드가 나타납니다. 아래 영역은 좌우·상하로 스크롤됩니다.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            HorizontalDivider()
            DashboardWebView(
                reportJson = reportJson,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }

    /**
     * 페이지 로드가 끝난 뒤에 렌더한다.
     *
     * 이전 버전은 factory 직후 update 에서 바로 호출해서, 첫 번째 누름은 아직
     * 페이지가 안 떠 있어 조용히 실패했다. 그래서 두 번 눌러야 보였다.
     */
    @Composable
    private fun DashboardWebView(reportJson: String?, modifier: Modifier) {
        val holder = remember { WebHolder() }
        AndroidView(
            modifier = modifier,
            factory = { c ->
                WebView(c).apply {
                    this.settings.javaScriptEnabled = true
                    this.settings.domStorageEnabled = false
                    this.settings.builtInZoomControls = true
                    this.settings.displayZoomControls = false
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            holder.loaded = true
                            holder.json?.let { render(view, it) }
                        }
                    }
                    holder.web = this
                    loadUrl("file:///android_asset/dashboard.html")
                }
            },
            update = { wv ->
                holder.json = reportJson
                if (holder.loaded && reportJson != null) render(wv, reportJson)
            },
        )
    }

    private class WebHolder {
        var web: WebView? = null
        var loaded = false
        var json: String? = null
    }

    private fun render(wv: WebView, json: String) {
        wv.evaluateJavascript("window.renderReport($json);", null)
    }

    // ------------------------------------------------------------------

    /**
     * 제안 탭 — 루틴 제안의 수명주기를 보여주는 화면.
     *
     * [감시 중] 카드에서 바로 수락·거절할 수 있다(P2 의 실시간 팝업이 생기면
     * 그쪽이 주 경로가 되고 여기는 목록·이력 뷰가 된다). 분석 버튼은 데모
     * 시연용이자 디버그용 — 평상시 갱신은 자동 분석(설정 탭 토글)이 맡는다.
     */
    @Composable
    private fun ProposalsTab(
        busy: Boolean, azure: AzureConfig, prefs: Settings, refreshTick: Int,
        onAnalyze: () -> Unit, onChanged: () -> Unit, onGoSettings: () -> Unit,
    ) {
        val ctx = LocalContext.current
        var proposals by remember { mutableStateOf<List<ProposalRow>>(emptyList()) }
        LaunchedEffect(refreshTick) {
            proposals = withContext(Dispatchers.IO) { Db.get(ctx).dao().proposals() }
        }
        val note = remember(refreshTick) { ProposalEngine.decodeNote(prefs.lastAnalysisNote) }
        var watch by remember { mutableStateOf<WatchStatus.Snapshot?>(null) }
        LaunchedEffect(refreshTick) {
            watch = withContext(Dispatchers.IO) { WatchStatus.read(ctx) }
        }

        fun decide(p: ProposalRow, state: String, eventKind: String) {
            lifecycleScope.launch(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                val dao = Db.get(ctx).dao()
                dao.setProposalState(p.signature, state, now)
                dao.logProposalEvent(
                    com.routineai.data.ProposalEventRow(
                        ts = now, proposalSignature = p.signature, kind = eventKind
                    )
                )
                withContext(Dispatchers.Main) { onChanged() }
            }
        }

        Column(
            Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!azure.isComplete) {
                Notice(WARN, "분석 연결이 설정되지 않았습니다: ${azure.missing.joinToString(", ")}") {
                    OutlinedButton(onClick = onGoSettings) { Text("설정 탭으로") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = azure.isComplete && !busy, onClick = onAnalyze) {
                    Text(if (proposals.isEmpty()) "패턴 분석" else "다시 분석")
                }
                Spacer(Modifier.size(10.dp))
                if (prefs.lastAnalysisAt > 0L) {
                    Text(
                        "마지막 분석 ${fmt(prefs.lastAnalysisAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                "집계 수치만 전송합니다. 원본 이벤트·알림 본문·Wi-Fi 실제 이름은 보내지 않습니다.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val watching = proposals.filter { it.state in setOf("candidate", "snoozed", "dormant") }
            val accepted = proposals.filter { it.state == "accepted" }
            val dismissed = proposals.filter { it.state == "dismissed" }

            if (proposals.isEmpty() && !busy) {
                Text(
                    "아직 제안이 없습니다. 분석을 실행하면 사용 패턴에서 루틴 후보를 찾습니다.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // 앱페어가 있는데 접근성이 꺼져 있으면 순차 실행으로 떨어진다.
            // 실패로 보이기 쉬우므로 미리 알린다.
            if (proposals.any { it.actionType == "app_pair" } && !Applier.hasAccessibility(ctx)) {
                Notice(
                    WARN,
                    "접근성이 꺼져 있어 앱페어가 분할화면으로 갈라지지 않고 순차 실행됩니다. " +
                        "설정 탭에서 켜주세요."
                ) {
                    OutlinedButton(onClick = onGoSettings) { Text("설정 탭으로") }
                }
            }

            if (watching.isNotEmpty()) {
                Section("감시 중 ${watching.size}", "패턴이 실시간으로 감지되면 제안됩니다.")
                watching.forEach { p ->
                    ProposalCard(p,
                        // 수락은 곧 의도 확인이다 — 그 자리에서 한 번 실행해 보여주고,
                        // 이후 같은 트리거가 오면 PatternWatcher 가 말없이 실행한다.
                        primary = "수락하고 지금 적용" to {
                            decide(p, "accepted", "accepted")
                            val r = Applier.apply(ctx, p)
                            android.widget.Toast.makeText(
                                ctx, r.message, android.widget.Toast.LENGTH_SHORT
                            ).show()
                        },
                        secondary = "보지 않기" to { decide(p, "dismissed", "dismissed") })
                }
            }
            if (accepted.isNotEmpty()) {
                Section(
                    "수락됨 ${accepted.size}",
                    "패턴이 감지되면 원탭으로 제안합니다. 자동 실행으로 바꾸면 묻지 않고 실행합니다.",
                )
                accepted.forEach { p ->
                    ProposalCard(p, autoRunToggle = { on ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            Db.get(ctx).dao().upsertProposal(p.copy(autoRun = on))
                            withContext(Dispatchers.Main) { onChanged() }
                        }
                    })
                }
            }
            if (dismissed.isNotEmpty()) {
                Section("보지 않기로 함 ${dismissed.size}", "다시 제안되지 않습니다.")
                dismissed.forEach { p -> ProposalCard(p, dimmed = true) }
            }

            // 억제 규칙이 여럿이라 화면에 이유가 없으면 고장인지 설계인지 알 수 없다.
            watch?.let { w ->
                if (w.lines.isNotEmpty()) {
                    HorizontalDivider()
                    var open by remember { mutableStateOf(false) }
                    OutlinedButton(onClick = { open = !open }) {
                        Text(if (open) "감시 상태 접기" else "감시 상태 보기")
                    }
                    if (open) {
                        Card(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                w.lines.forEach { line ->
                                    Row {
                                        Text(
                                            line.label,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.weight(1f),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            line.value,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (line.warn) WARN
                                            else MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (note.analysisNote.isNotBlank() || note.rejected.isNotEmpty()) {
                HorizontalDivider()
                var open by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { open = !open }) {
                    Text(if (open) "분석 노트 접기" else "분석 노트 · 제안하지 않은 것 보기")
                }
                if (open) {
                    if (note.analysisNote.isNotBlank()) {
                        Text(note.analysisNote, style = MaterialTheme.typography.bodySmall)
                    }
                    note.rejected.forEach { r ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(r.candidate, fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.bodySmall)
                                Text(r.reason, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    @Composable
    private fun ProposalCard(
        p: ProposalRow,
        primary: Pair<String, () -> Unit>? = null,
        secondary: Pair<String, () -> Unit>? = null,
        dimmed: Boolean = false,
        autoRunToggle: ((Boolean) -> Unit)? = null,
    ) {
        val ctx = LocalContext.current
        val evidence = remember(p.evidenceJson) {
            runCatching { Json.decodeFromString<List<String>>(p.evidenceJson) }
                .getOrDefault(emptyList())
        }
        var expanded by remember { mutableStateOf(false) }
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(14.dp).let { if (dimmed) it else it },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Chip(CATEGORY_LABELS[p.category] ?: p.category)
                    Spacer(Modifier.size(6.dp))
                    Chip(p.confidence, if (p.confidence == "확인됨") OK else WARN)
                }
                Text(p.oneLine, fontWeight = FontWeight.SemiBold)
                Text(p.narrative, style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (evidence.isNotEmpty()) {
                        Text(
                            if (expanded) "근거 접기" else "근거 ${evidence.size}개 보기",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable { expanded = !expanded },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    // 데모 리허설용. 실제 트리거를 기다리지 않고 팝업을 확인한다.
                    Text(
                        "팝업 미리보기",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable {
                            if (!Applier.hasOverlay(ctx)) {
                                android.widget.Toast.makeText(
                                    ctx, "다른 앱 위에 표시 권한이 필요합니다",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                moveTaskToBack(true)
                                lifecycleScope.launch {
                                    kotlinx.coroutines.delay(600)
                                    SuggestionOverlay.show(
                                        ctx, p,
                                        shortcut = p.state == "accepted" && !p.autoRun,
                                        anchorPkg = null,
                                    )
                                }
                            }
                        },
                    )
                }
                if (expanded && evidence.isNotEmpty()) {
                    Text(
                        evidence.joinToString("\n") { "· $it" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (autoRunToggle != null) {
                    var auto by remember(p.signature) { mutableStateOf(p.autoRun) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("자동 실행", style = MaterialTheme.typography.bodySmall)
                            Text(
                                if (auto) "묻지 않고 바로 실행합니다"
                                else "매번 원탭으로 여쭤봅니다",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = auto, onCheckedChange = { auto = it; autoRunToggle(it) })
                    }
                }
                if (primary != null || secondary != null) {
                    Row {
                        primary?.let { (label, act) ->
                            Button(onClick = act) { Text(label) }
                        }
                        Spacer(Modifier.size(8.dp))
                        secondary?.let { (label, act) ->
                            OutlinedButton(onClick = act) { Text(label) }
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------

    /**
     * 설정 탭.
     *
     * 권한 → 수집 → 해석 연결 순서로 둔다. 위에서부터 채우면 앱이 동작하도록
     * 하려는 것이고, 앞의 것이 안 되어 있으면 뒤의 것은 소용이 없다는 뜻이기도 하다.
     */
    @Composable
    private fun SettingsTab(
        usageOk: Boolean, notifOk: Boolean, locOk: Boolean,
        btOk: Boolean, healthOk: Boolean, busy: Boolean, prefs: Settings,
        overlayOk: Boolean, a11yOk: Boolean, writeOk: Boolean,
        collectMsg: String, azure: AzureConfig, demo: Boolean, demoAvailable: Boolean,
        onPermChanged: () -> Unit, onAzureChange: (AzureConfig) -> Unit,
        onDemoChange: (Boolean) -> Unit, onCollect: () -> Unit,
        onChanged: () -> Unit,
    ) {
        val ctx = LocalContext.current
        val locLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { onPermChanged() }
        val btLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { onPermChanged() }
        val healthLauncher = rememberLauncherForActivityResult(
            PermissionController.createRequestPermissionResultContract()
        ) { onPermChanged() }
        val healthAvailable = remember { HealthCollector.isAvailable(ctx) }

        Column(
            Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ---- 0. 데모 ----
            //
            // 맨 위에 둔다. 권한을 하나도 안 켠 새 기기에서도 이것만 켜면
            // 대시보드와 해석이 바로 돌아가는 것이 데모의 목적이다.
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("데모 데이터", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (demoAvailable)
                                "앱에 들어 있는 예시 로그로 리포트를 만듭니다. 권한도 수집도 " +
                                    "필요 없습니다. 이 기기의 실제 기록은 그대로 쌓이고, 끄면 돌아옵니다."
                            else
                                "이 빌드에는 데모 로그가 들어 있지 않습니다. 실제 사용 기록이라 " +
                                    "저장소에 올리지 않기 때문입니다. 쓰려면 " +
                                    "app/src/main/assets/demo/routine.db 에 파일을 넣고 다시 빌드하세요.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.size(10.dp))
                    Switch(
                        checked = demo,
                        enabled = demoAvailable,
                        onCheckedChange = onDemoChange,
                    )
                }
            }
            if (demo) {
                Notice(
                    WARN,
                    "데모 모드입니다. 대시보드·해석 탭의 결과는 예시 로그로 만든 것이며 " +
                        "이 기기의 사용 기록이 아닙니다."
                )
            }

            // ---- 1. 권한 ----
            Section("1. 권한", "설치만으로는 아무것도 켜지지 않습니다. 직접 허용해야 합니다.")
            if (!usageOk) {
                Notice(
                    NEED,
                    "'사용 정보 접근'이 꺼져 있으면 데이터가 하나도 들어오지 않습니다. " +
                        "리포트를 만들어도 전부 0으로 나옵니다."
                )
            }
            PermCard(
                title = "사용 정보 접근",
                required = true,
                granted = usageOk,
                what = "앱 실행·화면 켜짐·잠금 해제 이벤트를 읽습니다. 이 앱의 모든 통계가 여기서 나옵니다.",
                without = "리포트가 전부 0이 됩니다.",
                where = "설정 → 특별한 앱 접근 → 사용 정보 접근 → RoutineAI 허용",
                onClick = { Permissions.openUsageAccessSettings(ctx) },
            )
            PermCard(
                title = "알림 접근",
                required = false,
                granted = notifOk,
                what = "알림이 도착한 시각과 발신 앱을 기록합니다. 본문은 읽지 않습니다.",
                without = "알림 지표만 비고 나머지는 정상 동작합니다.",
                where = "설정 → 알림 → 고급 설정 → 알림 접근 → RoutineAI 허용",
                onClick = { Permissions.openNotificationAccessSettings(ctx) },
            )
            PermCard(
                title = "위치 권한",
                required = false,
                granted = locOk,
                what = "접속한 Wi-Fi 이름을 읽어 장소 축을 만듭니다. 위치 좌표는 쓰지 않습니다.",
                without = "장소가 'wifi'로만 뭉뚱그려집니다.",
                where = "아래 버튼을 누르면 시스템 대화상자가 뜹니다",
                onClick = { locLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
            )
            PermCard(
                title = "블루투스 연결 기록",
                required = false,
                granted = btOk,
                what = "버즈·워치·차량 같은 기기와 연결되는 순간을 기록합니다. " +
                    "\"버즈 연결 → 음악 앱\" 같은 이벤트 연쇄의 재료입니다.",
                without = "BT 기반 연쇄·맥락 지표가 빕니다.",
                where = "아래 버튼을 누르면 시스템 대화상자가 뜹니다",
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        btLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    }
                },
            )
            if (healthAvailable) {
                PermCard(
                    title = "Health Connect (운동·수면)",
                    required = false,
                    granted = healthOk,
                    what = "워치가 삼성 헬스에 남긴 운동·수면 세션을 읽습니다. " +
                        "행동의 상태 맥락이자, 화면 공백 기반 수면 추정의 교차 검증 소스입니다.",
                    without = "운동 연쇄가 비고 수면은 화면 공백 추정만 남습니다.",
                    where = "아래 버튼을 누르면 Health Connect 권한 화면이 뜹니다",
                    onClick = { healthLauncher.launch(HealthCollector.PERMISSIONS) },
                )
            } else {
                Notice(WARN, "이 기기에서 Health Connect 를 쓸 수 없어 운동·수면 수집이 꺼집니다.")
            }
            Text(
                "권한을 켜고 돌아오면 이 화면이 자동으로 갱신됩니다.",
                style = MaterialTheme.typography.bodySmall,
            )

            // ---- 1.5 실시간 제안 ----
            Section(
                "2. 실시간 제안",
                "수락한 루틴을 그 순간에 실행하고, 후보 패턴이 감지되면 팝업으로 물어봅니다. " +
                    "셋 다 꺼도 대시보드·제안 목록은 정상 동작합니다.",
            )
            PermCard(
                title = "다른 앱 위에 표시",
                required = false,
                granted = overlayOk,
                what = "패턴이 감지된 그 순간 제안 카드를 띄웁니다. 백그라운드에서 앱을 " +
                    "여는 것도 이 권한이 있어야 허용됩니다.",
                without = "실시간 팝업이 뜨지 않습니다.",
                where = "아래 버튼 → RoutineAI 허용",
                onClick = { Applier.overlaySettings(ctx) },
            )
            PermCard(
                title = "접근성 (선택) — 더 빠른 감지·분할화면",
                required = false,
                granted = a11yOk,
                what = "앱 전환을 즉시 감지하고, 분할화면 앱페어를 실제로 가릅니다. " +
                    "없어도 '사용 정보 접근'으로 1~2초 안에 감지합니다. 화면 내용은 읽지 않습니다.",
                without = "감지가 1~2초 늦고, 앱페어는 순차 실행으로 떨어집니다.",
                where = "설정 → 접근성 → 설치된 앱 → RoutineAI (직접 켜야 합니다)",
                onClick = { Applier.accessibilitySettings(ctx) },
            )
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("제안 강조색", fontWeight = FontWeight.SemiBold)
                    Text(
                        "팝업 테두리 발광과 버튼에 쓰입니다.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    var accent by remember { mutableStateOf(prefs.accent) }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Settings.ACCENTS.forEach { (key, argb) ->
                            val selected = key == accent
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable { accent = key; prefs.accent = key },
                            ) {
                                Box(
                                    Modifier.size(if (selected) 34.dp else 28.dp)
                                        .clip(CircleShape)
                                        .background(Color(argb))
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    Settings.ACCENT_LABELS[key] ?: key,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected) Color(argb)
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            PermCard(
                title = "시스템 설정 변경",
                required = false,
                granted = writeOk,
                what = "앱 맥락 모드가 자동 회전 같은 설정을 바꿉니다. 그 앱에서 나오면 " +
                    "곧바로 원래대로 되돌립니다.",
                without = "모드 제안이 안내로만 남습니다.",
                where = "아래 버튼 → RoutineAI 허용",
                onClick = { Applier.writeSettings(ctx) },
            )

            // ---- 2. 수집 ----
            Section(
                "2. 수집",
                "안드로이드는 사용 이벤트를 며칠치만 보관하다 지웁니다. 이 앱은 주기적으로 " +
                    "읽어 자체 DB에 누적하므로, 오래 쓸수록 분석 가능한 기간이 길어집니다.",
            )
            Button(enabled = usageOk && !busy, onClick = onCollect) { Text("지금 수집") }
            if (collectMsg.isNotBlank()) {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        collectMsg,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            Text(
                "자동 수집은 6시간마다 백그라운드에서 돕니다. 시스템이 이벤트를 며칠은 " +
                    "들고 있어서 이 주기로도 유실되지 않습니다.\n\n" +
                    "첫 수집에는 시스템이 갖고 있던 며칠치만 들어옵니다. 기간이 늘어나는 건 " +
                    "며칠 지난 뒤부터입니다.",
                style = MaterialTheme.typography.bodySmall,
            )

            // ---- 3. 분석 연결 ----
            Section(
                "3. 분석 연결 (선택)",
                "비워두면 앱은 완전히 오프라인으로 동작합니다. 채우면 제안 탭에서 " +
                    "집계 결과를 Azure OpenAI 에 보내 루틴 제안을 받을 수 있습니다.",
            )
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("자동 분석", fontWeight = FontWeight.SemiBold)
                        Text(
                            "켜면 백그라운드 수집 후 하루 1회 제안을 자동 갱신합니다. " +
                                "API 호출 비용이 들어 기본은 꺼짐입니다.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.size(10.dp))
                    var auto by remember { mutableStateOf(prefs.autoAnalyze) }
                    Switch(checked = auto, onCheckedChange = {
                        auto = it; prefs.autoAnalyze = it
                    })
                }
            }
            ConfigField(
                label = "AZURE_OPENAI_ENDPOINT",
                value = azure.endpoint,
                placeholder = "https://<리소스>.openai.azure.com",
                onChange = { onAzureChange(azure.copy(endpoint = it)) },
            )
            ConfigField(
                label = "AZURE_OPENAI_API_KEY",
                value = azure.apiKey,
                placeholder = "Azure 포털 → 키 및 엔드포인트",
                onChange = { onAzureChange(azure.copy(apiKey = it)) },
            )
            ConfigField(
                label = "AZURE_OPENAI_API_VERSION",
                value = azure.apiVersion,
                placeholder = Settings.DEFAULT_API_VERSION,
                onChange = { onAzureChange(azure.copy(apiVersion = it)) },
            )
            ConfigField(
                label = "AZURE_OPENAI_DEPLOYMENT",
                value = azure.deployment,
                placeholder = "배포 이름",
                note = "모델 이름이 아니라 배포에 붙인 이름입니다.",
                onChange = { onAzureChange(azure.copy(deployment = it)) },
            )
            // 404 가 나면 대개 이 주소가 틀린 것이다. 눈으로 확인할 수 있게 그대로 띄운다.
            // API 키는 헤더로 가므로 이 문자열에는 들어가지 않는다.
            if (azure.endpoint.isNotBlank() && azure.deployment.isNotBlank()) {
                Text(
                    "요청 주소\n${azure.chatCompletionsUrl()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (azure.missing.isNotEmpty()) {
                Text(
                    "비어 있는 항목: ${azure.missing.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = WARN,
                )
            }

            // ---- 4. 데모 초기화 ----
            //
            // 리허설을 처음부터 다시 하려면 어디까지 되돌릴지 고를 수 있어야 한다.
            // 수집한 로그까지 지우면 다시 며칠 기다려야 하므로 단계를 나눈다.
            Section(
                "4. 데모 초기화",
                "리허설을 처음 상태로 되돌립니다. 수집한 로그는 건드리지 않습니다.",
            )
            var resetMsg by remember { mutableStateOf("") }
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { WatchStatus.resetProposals(ctx) }
                        resetMsg = "제안과 결정 이력을 지웠습니다. 제안 탭에서 다시 분석하세요."
                        onChanged()
                    }
                },
            ) { Text("제안·결정 이력 지우기") }
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { WatchStatus.resetProposals(ctx) }
                        prefs.clearReportAndAnalysis()
                        resetMsg = "리포트까지 지웠습니다. 대시보드 → 리포트 생성부터 시작하세요."
                        onChanged()
                    }
                },
            ) { Text("리포트까지 지우기 (완전 초기화)") }
            if (resetMsg.isNotBlank()) {
                Notice(OK, resetMsg)
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    // ------------------------------------------------------------------

    @Composable
    private fun Section(title: String, subtitle: String) {
        Spacer(Modifier.height(6.dp))
        HorizontalDivider()
        Spacer(Modifier.height(4.dp))
        Text(title, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall)
    }

    @Composable
    private fun Notice(tint: Color, text: String, action: @Composable (() -> Unit)? = null) {
        Card(colors = CardDefaults.cardColors(containerColor = tint.copy(alpha = .12f))) {
            Column(Modifier.padding(12.dp)) {
                Text(text, style = MaterialTheme.typography.bodySmall)
                action?.invoke()
            }
        }
    }

    @Composable
    private fun PermCard(
        title: String, required: Boolean, granted: Boolean,
        what: String, without: String, where: String, onClick: () -> Unit,
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(if (granted) OK else if (required) NEED else WARN)
                    Spacer(Modifier.size(8.dp))
                    Text(title, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (granted) "허용됨" else if (required) "필수 · 꺼짐" else "선택 · 꺼짐",
                        color = if (granted) OK else if (required) NEED else WARN,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Text(what, style = MaterialTheme.typography.bodySmall)
                if (!granted) {
                    Text("끄면: $without", style = MaterialTheme.typography.bodySmall)
                    Text(where, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onClick) { Text("설정 열기") }
                }
            }
        }
    }

    @Composable
    private fun ConfigField(
        label: String, value: String, placeholder: String,
        note: String? = null, onChange: (String) -> Unit,
    ) {
        val support: (@Composable () -> Unit)? = note?.let { n ->
            { Text(n, style = MaterialTheme.typography.labelSmall) }
        }
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
            placeholder = {
                Text(placeholder, style = MaterialTheme.typography.bodySmall)
            },
            supportingText = support,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    @Composable
    private fun Dot(color: Color) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(color))
    }

    // ------------------------------------------------------------------

    private fun fmt(ts: Long): String =
        SimpleDateFormat("MM/dd HH:mm", Locale.KOREAN).format(Date(ts))

    private fun fmtDay(ts: Long): String =
        SimpleDateFormat("MM/dd", Locale.KOREAN).format(Date(ts))

    private fun ago(ts: Long): String {
        val d = System.currentTimeMillis() - ts
        return when {
            d < 60_000 -> "방금"
            d < 3_600_000 -> "${d / 60_000}분 전"
            d < 86_400_000 -> "${d / 3_600_000}시간 전"
            else -> "${d / 86_400_000}일 전"
        }
    }

    /** 자동 수집이 6시간 주기라, 그 두 배가 넘게 조용하면 눈에 띄게 한다. */
    private fun Long?.isStale(): Boolean =
        this == null || System.currentTimeMillis() - this > 12L * 60 * 60 * 1000

    private fun Int.formatted(): String = String.format(Locale.KOREAN, "%,d", this)
}
