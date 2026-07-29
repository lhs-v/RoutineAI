package com.routineai.interpret

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 집계 리포트를 LLM 에 보내 해석을 받아온다.
 *
 * 이 앱은 규칙 기반 문구를 만들지 않는다. 규칙은 미리 써넣은 만큼만 말할 수 있고
 * 새로 나타난 패턴은 못 잡기 때문이다. 대신 해석 지침서를 시스템 프롬프트로 주고
 * 계산된 수치만 넘긴다.
 *
 * 보내는 것: [com.routineai.analysis.Report] 의 **집계 수치뿐**.
 * 보내지 않는 것: 원본 이벤트, 알림 본문, Wi-Fi 실제 이름(별칭으로 치환됨).
 */
class Interpreter(private val ctx: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /** assets 에 넣어둔 해석 지침서를 통째로 시스템 프롬프트로 쓴다. */
    private fun skillPrompt(): String = SKILL_FILES.joinToString("\n\n---\n\n") { path ->
        runCatching { ctx.assets.open(path).bufferedReader().use { it.readText() } }
            .getOrDefault("")
    }

    /**
     * @param reportJson [com.routineai.analysis.Report] 를 직렬화한 문자열
     * @return 마크다운 해석문
     */
    fun interpret(
        reportJson: String,
        apiKey: String,
        model: String = DEFAULT_MODEL,
    ): Result<String> {
        if (apiKey.isBlank()) {
            return Result.failure(IllegalStateException("API 키가 설정되지 않았습니다"))
        }

        val payload = buildJsonObject {
            put("model", model)
            put("max_tokens", 8000)
            put("system", skillPrompt())
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", userPrompt(reportJson))
                })
            })
        }

        val req = Request.Builder()
            .url(ENDPOINT)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()

        return runCatching {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                check(resp.isSuccessful) { "HTTP ${resp.code}: ${text.take(400)}" }
                json.parseToJsonElement(text).jsonObject["content"]!!.jsonArray
                    .joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }
            }
        }
    }

    private fun userPrompt(reportJson: String): String = buildString {
        appendLine("아래는 한 사용자의 스마트폰 사용 로그를 집계한 리포트입니다.")
        appendLine("시스템 프롬프트의 절차를 그대로 따라 해석해 주세요.")
        appendLine()
        appendLine("특히 지켜야 할 것:")
        appendLine("- meta.fullDays 와 meta.partialDays 를 먼저 확인하고, 모든 평균은 fullDays 기준임을 전제하세요.")
        appendLine("- quality 객체에 데이터 품질 정보가 있습니다. warnings 를 반드시 반영하세요.")
        appendLine("- quality.reconciliationDelta 가 음수면 집계 오류이므로 그 사실을 먼저 알리세요.")
        appendLine("- 관측일수가 적은 지표는 결론 등급을 낮추세요.")
        appendLine("- 장소는 'Wi-Fi A' 같은 별칭으로만 주어집니다. 실제 장소를 추정하지 마세요.")
        appendLine("- coUse 는 전환이 아니라 분할화면 동시 사용입니다. 혼동하지 마세요.")
        appendLine()
        appendLine("출력 형식: 마크다운. 순서는")
        appendLine("1) 데이터 범위 한 문단")
        appendLine("2) 발견 — 등급 순, 각각 주장/근거/판정 근거/한계")
        appendLine("3) 자동화 제안 — 우선순위 순, 안드로이드에서 실제로 설정 가능한 것만")
        appendLine("4) 이번에 확인할 수 없었던 것")
        appendLine()
        appendLine("리포트:")
        appendLine("```json")
        appendLine(reportJson)
        appendLine("```")
    }

    companion object {
        private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
        private const val DEFAULT_MODEL = "claude-sonnet-4-5"
        private val JSON_MEDIA = "application/json".toMediaType()

        private val SKILL_FILES = listOf(
            "skill/SKILL.md",
            "skill/references/artifacts.md",
            "skill/references/metrics.md",
            "skill/references/cross-analysis.md",
            "skill/references/output-rules.md",
        )
    }
}
