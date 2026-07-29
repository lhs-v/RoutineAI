package com.routineai

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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

class MainActivity : ComponentActivity() {

    private val json = Json { encodeDefaults = true }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Screen() } }
    }

    @Composable
    private fun Screen() {
        val ctx = LocalContext.current
        val prefs = remember { Settings(ctx) }

        var usageOk by remember { mutableStateOf(false) }
        var notifOk by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf("") }
        var reportJson by remember { mutableStateOf<String?>(null) }
        var interpretation by remember { mutableStateOf(prefs.lastInterpretation) }
        var apiKey by remember { mutableStateOf(prefs.apiKey) }
        var busy by remember { mutableStateOf(false) }

        val locationLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { }

        LaunchedEffect(Unit) {
            usageOk = Permissions.hasUsageAccess(ctx)
            notifOk = Permissions.hasNotificationAccess(ctx)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("RoutineAI", style = MaterialTheme.typography.headlineSmall)
            Text(
                "시스템은 사용 이벤트를 며칠치만 보관합니다. 이 앱은 주기적으로 읽어 자체 DB에 " +
                    "누적하므로, 오래 쓸수록 분석 가능한 기간이 길어집니다.",
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider()
            Text("1. 권한", style = MaterialTheme.typography.titleMedium)
            PermRow("사용 정보 접근 (필수)", usageOk) { Permissions.openUsageAccessSettings(ctx) }
            PermRow("알림 접근 (알림 통계)", notifOk) { Permissions.openNotificationAccessSettings(ctx) }
            OutlinedButton(
                enabled = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
                    PackageManager.PERMISSION_GRANTED,
                onClick = { locationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
            ) { Text("위치 권한 (Wi-Fi 이름 기록용)") }

            HorizontalDivider()
            Text("2. 수집", style = MaterialTheme.typography.titleMedium)
            Button(
                enabled = usageOk && !busy,
                onClick = {
                    busy = true
                    lifecycleScope.launch {
                        val r = withContext(Dispatchers.IO) {
                            NetworkCollector(ctx).recordCurrentNetwork()
                            UsageCollector(ctx).collect()
                        }
                        status = "이번 수집 ${r.scanned}건 · 누적 ${r.totalStored}건"
                        busy = false
                    }
                },
            ) { Text("지금 수집") }
            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
            Text("백그라운드 수집은 6시간마다 자동으로 돕니다.", style = MaterialTheme.typography.bodySmall)

            HorizontalDivider()
            Text("3. 분석", style = MaterialTheme.typography.titleMedium)
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    lifecycleScope.launch {
                        val report: Report = withContext(Dispatchers.Default) { Analyzer(ctx).build() }
                        reportJson = json.encodeToString(Report.serializer(), report)
                        status = "리포트 생성 완료 · 온전한 하루 ${report.meta.fullDays.size}일 · " +
                            "이벤트 ${report.meta.eventCount}건"
                        busy = false
                    }
                },
            ) { Text("리포트 생성") }

            val rj = reportJson
            if (rj != null) {
                Text("대시보드", style = MaterialTheme.typography.titleMedium)
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(760.dp),
                    factory = { c ->
                        WebView(c).apply {
                            this.settings.javaScriptEnabled = true
                            this.settings.domStorageEnabled = false
                            loadUrl("file:///android_asset/dashboard.html")
                        }
                    },
                    update = { wv -> wv.evaluateJavascript("window.renderReport($rj);", null) },
                )

                HorizontalDivider()
                Text("4. 해석 (선택)", style = MaterialTheme.typography.titleMedium)
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
                Button(
                    enabled = apiKey.isNotBlank() && !busy,
                    onClick = {
                        busy = true
                        lifecycleScope.launch {
                            val res = withContext(Dispatchers.IO) {
                                Interpreter(ctx).interpret(rj, apiKey)
                            }
                            interpretation = res.getOrElse { "해석 실패: ${it.message}" }
                            prefs.lastInterpretation = interpretation
                            busy = false
                        }
                    },
                ) { Text("해석 요청") }
            }

            if (interpretation.isNotBlank()) {
                HorizontalDivider()
                Text(interpretation, style = MaterialTheme.typography.bodySmall)
            }
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(40.dp))
        }
    }

    @Composable
    private fun PermRow(label: String, granted: Boolean, onClick: () -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (granted) "허용됨" else "필요",
                modifier = Modifier.width(64.dp),
                style = MaterialTheme.typography.labelMedium,
            )
            TextButton(onClick = onClick) { Text(label) }
        }
    }
}
