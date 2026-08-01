package com.routineai.interpret

import android.content.Context

/**
 * Azure OpenAI 접속 정보 보관.
 *
 * 개인용 사이드로드 기준이라 앱 전용 저장소의 SharedPreferences 를 쓴다.
 * 배포용으로 만들 거라면 EncryptedSharedPreferences 나 Keystore 로 바꿔야 한다.
 *
 * 네 값은 환경변수 `AZURE_OPENAI_ENDPOINT` / `_API_KEY` / `_API_VERSION` / `_DEPLOYMENT`
 * 와 1:1 로 대응한다. 폰에는 환경변수가 없으므로 해석 탭에서 직접 입력받는다.
 */
class Settings(ctx: Context) {
    private val sp = ctx.getSharedPreferences("routine", Context.MODE_PRIVATE)

    /** AZURE_OPENAI_ENDPOINT — 예: https://my-resource.openai.azure.com */
    var azureEndpoint: String
        get() = sp.getString(K_ENDPOINT, "").orEmpty()
        set(v) = sp.edit().putString(K_ENDPOINT, v).apply()

    /** AZURE_OPENAI_API_KEY */
    var azureApiKey: String
        get() = sp.getString(K_API_KEY, "").orEmpty()
        set(v) = sp.edit().putString(K_API_KEY, v).apply()

    /** AZURE_OPENAI_API_VERSION — 배포가 지원하는 값으로 바꿔도 된다. */
    var azureApiVersion: String
        get() = sp.getString(K_API_VERSION, DEFAULT_API_VERSION).orEmpty()
            .ifBlank { DEFAULT_API_VERSION }
        set(v) = sp.edit().putString(K_API_VERSION, v).apply()

    /** AZURE_OPENAI_DEPLOYMENT — 모델 이름이 아니라 **배포 이름**이다. */
    var azureDeployment: String
        get() = sp.getString(K_DEPLOYMENT, "").orEmpty()
        set(v) = sp.edit().putString(K_DEPLOYMENT, v).apply()

    /**
     * 자동 분석. 켜면 수집 워커 완료 후 하루 1회 백그라운드로 제안을 갱신한다.
     * 기본 꺼짐 — API 비용이 드는 동작이라 사용자가 명시적으로 켜야 한다.
     */
    var autoAnalyze: Boolean
        get() = sp.getBoolean("auto_analyze", false)
        set(v) = sp.edit().putBoolean("auto_analyze", v).apply()

    var lastAnalysisAt: Long
        get() = sp.getLong("last_analysis_at", 0L)
        set(v) = sp.edit().putLong("last_analysis_at", v).apply()

    /** 마지막 분석의 analysisNote + rejected(JSON). 제안 탭 하단에 접혀 보인다. */
    var lastAnalysisNote: String
        get() = sp.getString("last_analysis_note", "").orEmpty()
        set(v) = sp.edit().putString("last_analysis_note", v).apply()

    /**
     * 마지막으로 만든 리포트 JSON.
     *
     * 화면 회전이나 앱 재시작으로 대시보드가 비는 걸 막는다. 리포트는 관측 기간에
     * 비례해 수십 KB 까지 커지지만, 앱 전용 저장소라 나가지 않는다.
     */
    var lastReport: String
        get() = sp.getString("last_report", "").orEmpty()
        set(v) = sp.edit().putString("last_report", v).apply()

    /** [lastReport] 를 만든 시각. 화면에 "언제 만든 리포트인지" 를 알리는 용도. */
    var lastReportAt: Long
        get() = sp.getLong("last_report_at", 0L)
        set(v) = sp.edit().putLong("last_report_at", v).apply()

    /**
     * 심층 분석이 만든 Now Brief 문단. 루틴 탭 상단에 보인다.
     * 비어 있으면 로컬 집계로 만든 요약이 대신 나간다.
     */
    var lastBrief: String
        get() = sp.getString("last_brief", "").orEmpty()
        set(v) = sp.edit().putString("last_brief", v).apply()

    var lastBriefAt: Long
        get() = sp.getLong("last_brief_at", 0L)
        set(v) = sp.edit().putLong("last_brief_at", v).apply()

    /** 심층 분석의 analysisNote — 루틴 탭 하단에 접혀 보인다. */
    var lastRefineNote: String
        get() = sp.getString("last_refine_note", "").orEmpty()
        set(v) = sp.edit().putString("last_refine_note", v).apply()

    /**
     * 데모 모드.
     *
     * 켜면 리포트를 이 기기의 기록이 아니라 assets 에 넣어둔 데모 로그로 만든다.
     * 수집은 영향을 받지 않는다 — 실제 기록은 그대로 쌓인다.
     */
    var demoMode: Boolean
        get() = sp.getBoolean("demo_mode", false)
        set(v) = sp.edit().putBoolean("demo_mode", v).apply()

    /** 제안 팝업의 강조색 키. [ACCENTS] 의 항목 중 하나. */
    var accent: String
        get() = sp.getString("accent", DEFAULT_ACCENT).orEmpty().ifBlank { DEFAULT_ACCENT }
        set(v) = sp.edit().putString("accent", v).apply()

    fun accentColor(): Int = ACCENTS[accent] ?: ACCENTS.getValue(DEFAULT_ACCENT)

    /** 데모를 처음부터 다시 보여줄 때 화면 상태까지 되돌린다. */
    fun clearReportAndAnalysis() {
        lastReport = ""
        lastReportAt = 0L
        lastAnalysisAt = 0L
        lastAnalysisNote = ""
        lastBrief = ""
        lastBriefAt = 0L
        lastRefineNote = ""
    }

    fun azureConfig(): AzureConfig =
        AzureConfig(azureEndpoint, azureApiKey, azureApiVersion, azureDeployment)

    companion object {
        /** 채팅 완성이 GA 인 버전. 배포가 더 새 버전을 요구하면 화면에서 바꾼다. */
        const val DEFAULT_API_VERSION = "2024-10-21"

        /**
         * 제안 팝업 강조색 후보.
         * 기본이 틸인 이유: 시스템 UI 느낌이면서 경고(빨강)·성공(초록)과
         * 겹치지 않아 "이건 알림이 아니라 제안"으로 읽힌다.
         */
        const val DEFAULT_ACCENT = "teal"
        val ACCENTS: Map<String, Int> = linkedMapOf(
            "teal" to 0xFF1D9E75.toInt(),
            "blue" to 0xFF378ADD.toInt(),
            "coral" to 0xFFD85A30.toInt(),
            "lime" to 0xFF639922.toInt(),
            "mono" to 0xFF8A8A93.toInt(),
        )
        val ACCENT_LABELS: Map<String, String> = mapOf(
            "teal" to "틸", "blue" to "블루", "coral" to "코랄",
            "lime" to "라임", "mono" to "무채",
        )

        private const val K_ENDPOINT = "azure_endpoint"
        private const val K_API_KEY = "azure_api_key"
        private const val K_API_VERSION = "azure_api_version"
        private const val K_DEPLOYMENT = "azure_deployment"
    }
}

/**
 * 해석 요청 한 번에 필요한 접속 정보 묶음.
 *
 * 값 검증을 여기서 하는 이유: 네 값 중 하나만 비어도 Azure 는 404 나 401 로만 답하고
 * 무엇이 빠졌는지 알려주지 않는다. 요청을 보내기 전에 이름을 대며 막는다.
 */
data class AzureConfig(
    val endpoint: String,
    val apiKey: String,
    val apiVersion: String,
    val deployment: String,
) {
    /** 비어 있는 항목의 사람이 읽는 이름. 비어 있으면 요청을 보낼 수 있다. */
    val missing: List<String>
        get() = buildList {
            if (endpoint.isBlank()) add("엔드포인트")
            if (apiKey.isBlank()) add("API 키")
            if (apiVersion.isBlank()) add("API 버전")
            if (deployment.isBlank()) add("배포 이름")
        }

    val isComplete: Boolean get() = missing.isEmpty()

    /**
     * `{엔드포인트}/openai/deployments/{배포}/chat/completions?api-version={버전}`
     *
     * 끝의 `/` 와 프로토콜 누락은 흔한 입력 실수라 여기서 흡수한다.
     * 화면에도 이 값을 그대로 띄워 사용자가 눈으로 확인할 수 있게 한다.
     */
    fun chatCompletionsUrl(): String {
        val trimmed = endpoint.trim().trimEnd('/')
        val root = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
        else "https://$trimmed"
        return "$root/openai/deployments/${deployment.trim()}/chat/completions" +
            "?api-version=${apiVersion.trim()}"
    }
}
