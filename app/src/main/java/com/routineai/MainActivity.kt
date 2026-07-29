package com.routineai

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.routineai.collect.NetworkCollector
import com.routineai.collect.Permissions
import com.routineai.collect.UsageCollector
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

class MainActivity : ComponentActivity() {

    private val json = Json { encodeDefaults = true }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Root() } }
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

        var tab by remember { mutableIntStateOf(0) }
        var busy by remember { mutableStateOf(false) }
        var step by remember { mutableStateOf("") }
        var collectMsg by remember { mutableStateOf("") }
        var reportJson by remember { mutableStateOf<String?>(null) }
        var reportMsg by remember { mutableStateOf("") }
        var interpretation by remember { mutableStateOf(prefs.lastInterpretation) }

        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Text(
                    "RoutineAI",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(16.dp, 14.dp, 16.dp, 8.dp),
                )
                TabRow(selectedTabIndex = tab) {
                    listOf("권한", "수집", "리포트").forEachIndexed { i, t ->
                        Tab(
                            selected = tab == i,
                            onClick = { tab = i },
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(t)
                                    val bad = when (i) {
                                        0 -> !usageOk
                                        1 -> usageOk && collectMsg.isBlank()
                                        else -> false
                                    }
                                    if (bad) {
                                        Spacer(Modifier.size(5.dp))
                                        Dot(if (i == 0) NEED else WARN)
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
                        0 -> PermissionTab(usageOk, notifOk, locOk) { permTick++ }
                        1 -> CollectTab(usageOk, busy, collectMsg,
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
                                        r.oldestStored?.let { appendLine("가장 오래된 기록 ${fmt(it)}") }
                                        append("조회 구간 ${fmt(r.windowFrom)} ~ ${fmt(r.windowTo)}")
                                    }
                                    busy = false; step = ""
                                }
                            },
                            onGoPerm = { tab = 0 })

                        else -> ReportTab(
                            busy = busy, msg = reportMsg, reportJson = reportJson,
                            interpretation = interpretation, prefs = prefs,
                            onBuild = {
                                busy = true; step = "시작하는 중"
                                lifecycleScope.launch {
                                    val report: Report = withContext(Dispatchers.Default) {
                                        Analyzer(ctx).build { s ->
                                            lifecycleScope.launch { step = s }
                                        }
                                    }
                                    reportJson = json.encodeToString(Report.serializer(), report)
                                    val d = report.diagnostics
                                    reportMsg = buildString {
                                        appendLine("이벤트 ${d.storedEvents}건 · 세션 ${d.sessionsBuilt}개")
                                        appendLine("온전한 하루 ${report.meta.fullDays.size}일 · 부분일 ${report.meta.partialDays.size}일")
                                        if (report.quality.warnings.isNotEmpty()) {
                                            append("경고 ${report.quality.warnings.size}건 — 대시보드 위쪽 확인")
                                        }
                                    }
                                    busy = false; step = ""
                                }
                            },
                            onInterpret = { key ->
                                busy = true; step = "해석 요청 중 (최대 2분)"
                                lifecycleScope.launch {
                                    val rj = reportJson ?: return@launch
                                    val res = withContext(Dispatchers.IO) {
                                        Interpreter(ctx).interpret(rj, key)
                                    }
                                    interpretation = res.getOrElse { "해석 실패: ${it.message}" }
                                    prefs.lastInterpretation = interpretation
                                    busy = false; step = ""
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------

    @Composable
    private fun PermissionTab(
        usageOk: Boolean, notifOk: Boolean, locOk: Boolean, onChanged: () -> Unit,
    ) {
        val ctx = LocalContext.current
        val locLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { onChanged() }

        Column(
            Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!usageOk) {
                Card(colors = CardDefaults.cardColors(containerColor = NEED.copy(alpha = .10f))) {
                    Text(
                        "'사용 정보 접근'이 꺼져 있으면 데이터가 하나도 들어오지 않습니다. " +
                            "리포트를 만들어도 전부 0으로 나옵니다.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
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

            HorizontalDivider()
            Text(
                "권한을 켜고 돌아오면 이 화면이 자동으로 갱신됩니다.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(40.dp))
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
    private fun CollectTab(
        usageOk: Boolean, busy: Boolean, msg: String,
        onCollect: () -> Unit, onGoPerm: () -> Unit,
    ) {
        Column(
            Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "안드로이드는 사용 이벤트를 며칠치만 보관하다 지웁니다. 이 앱은 주기적으로 읽어 " +
                    "자체 DB에 누적하므로, 오래 쓸수록 분석 가능한 기간이 길어집니다.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!usageOk) {
                Card(colors = CardDefaults.cardColors(containerColor = NEED.copy(alpha = .10f))) {
                    Column(Modifier.padding(12.dp)) {
                        Text("'사용 정보 접근'이 꺼져 있어 수집할 수 없습니다.",
                            style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = onGoPerm) { Text("권한 탭으로") }
                    }
                }
            }
            Button(enabled = usageOk && !busy, onClick = onCollect) { Text("지금 수집") }
            if (msg.isNotBlank()) {
                Card(Modifier.fillMaxWidth()) {
                    Text(msg, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp))
                }
            }
            HorizontalDivider()
            Text("자동 수집", fontWeight = FontWeight.SemiBold)
            Text(
                "6시간마다 백그라운드에서 돕니다. 시스템이 이벤트를 며칠은 들고 있어서 " +
                    "이 주기로도 유실되지 않습니다.\n\n" +
                    "첫 수집에는 시스템이 갖고 있던 며칠치만 들어옵니다. 기간이 늘어나는 건 " +
                    "며칠 지난 뒤부터입니다.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(40.dp))
        }
    }

    @Composable
    private fun ReportTab(
        busy: Boolean, msg: String, reportJson: String?, interpretation: String,
        prefs: Settings, onBuild: () -> Unit, onInterpret: (String) -> Unit,
    ) {
        var apiKey by remember { mutableStateOf(prefs.apiKey) }
        Column(
            Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(enabled = !busy, onClick = onBuild) { Text("리포트 생성") }
            if (msg.isNotBlank()) {
                Card(Modifier.fillMaxWidth()) {
                    Text(msg, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp))
                }
            }

            if (reportJson != null) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth().height(780.dp),
                    factory = { c ->
                        WebView(c).apply {
                            this.settings.javaScriptEnabled = true
                            this.settings.domStorageEnabled = false
                            loadUrl("file:///android_asset/dashboard.html")
                        }
                    },
                    update = { wv ->
                        wv.evaluateJavascript("window.renderReport($reportJson);", null)
                    },
                )

                HorizontalDivider()
                Text("해석 (선택)", fontWeight = FontWeight.SemiBold)
                Text(
                    "집계 수치만 전송합니다. 원본 이벤트·알림 본문·Wi-Fi 실제 이름은 보내지 않습니다.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it; prefs.apiKey = it },
                    label = { Text("Anthropic API 키") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(enabled = apiKey.isNotBlank() && !busy, onClick = { onInterpret(apiKey) }) {
                    Text("해석 요청")
                }
            }

            if (interpretation.isNotBlank()) {
                HorizontalDivider()
                Text(interpretation, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    @Composable
    private fun Dot(color: Color) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(color))
    }

    private fun fmt(ts: Long): String =
        SimpleDateFormat("MM/dd HH:mm", Locale.KOREAN).format(Date(ts))
}
