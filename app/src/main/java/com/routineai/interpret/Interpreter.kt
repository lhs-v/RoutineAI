package com.routineai.interpret

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
 * 집계 리포트를 Azure OpenAI 에 보내 해석을 받아온다.
 *
 * 이 앱은 규칙 기반 문구를 만들지 않는다. 규칙은 미리 써넣은 만큼만 말할 수 있고
 * 새로 나타난 패턴은 못 잡기 때문이다. 대신 해석 지침서를 시스템 프롬프트로 주고
 * 계산된 수치만 넘긴다.
 *
 * 보내는 것: [com.routineai.analysis.Report] 의 **집계 수치뿐**.
 * 보내지 않는 것: 원본 이벤트, 알림 본문, Wi-Fi 실제 이름(별칭으로 치환됨).
 *
 * 접속 정보는 [AzureConfig] 로 받는다. 엔드포인트·배포 이름·api-version 이 전부
 * 사용자 소유 리소스에 딸린 값이라 앱이 정할 수 없다.
 */
class Interpreter(private val ctx: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    /**
     * assets 에 넣어둔 해석 지침서를 통째로 시스템 프롬프트로 쓴다.
     *
     * 원래 Agent Skill 은 `SKILL.md` 만 먼저 읽고 필요할 때 `references/` 를 여는
     * 구조지만, Chat Completions 에는 파일을 열 수단이 없다. 그래서 전부 미리 잇는다.
     *
     * 이을 때 파일명을 마커로 박는 이유:
     * 지침서 본문이 섹션 구분에 단독 `---` 줄을 38개 쓰고 있어서, 그냥 `---` 로 이으면
     * 파일 경계가 본문의 구분선과 구별되지 않는다. 그러면 SKILL.md 가
     * "`references/metrics.md` 를 따라" 라고 해도 어디부터가 그 파일인지 알 수 없다.
     * 마커의 경로 표기는 SKILL.md 본문의 참조 표기와 정확히 같게 맞춘다.
     */
    private fun skillPrompt(files: List<String> = SKILL_FILES): String = files.joinToString("\n\n") { path ->
        val body = runCatching {
            ctx.assets.open("$SKILL_ROOT/$path").bufferedReader().use { it.readText() }
        }.getOrDefault("")
        "$MARKER $path $MARKER\n\n${stripFrontMatter(body).trim()}"
    }

    /**
     * Agent Skill 포맷의 YAML frontmatter 를 걷어낸다.
     *
     * `name` · `description` 은 "이 스킬을 언제 켤지" 를 고르기 위한 메타데이터다.
     * 항상 켜져 있는 이 앱에서는 판단할 것이 없으므로 토큰만 차지한다.
     */
    private fun stripFrontMatter(text: String): String {
        val lines = text.lines()
        if (lines.firstOrNull()?.trim() != "---") return text
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (end < 0) return text
        return lines.drop(end + 2).joinToString("\n")
    }

    /**
     * 리포트를 보내 루틴 제안 JSON(원문 문자열)을 받아온다.
     *
     * 파싱·검증·병합은 [ProposalEngine] 의 몫이다 — 여기는 전송과
     * "JSON 만 내놓게 만들기"까지만 책임진다. 첫 응답이 JSON 으로 파싱되지
     * 않으면 한 번만 교정 요청을 붙여 재시도한다.
     *
     * @param reportJson [com.routineai.analysis.Report] 를 직렬화한 문자열
     */
    fun propose(reportJson: String, cfg: AzureConfig): Result<String> =
        requestJson(cfg, skillPrompt(), userPrompt(reportJson))

    /**
     * 심층 분석(P3)용 — 도구를 쥔 멀티턴 에이전트 루프.
     *
     * 모델이 tool_calls 를 내면 [execute] 로 로컬 실행해 결과를 돌려주고,
     * 최종 텍스트(JSON)가 나올 때까지 반복한다. 요약 입력만으로 판단이 서지
     * 않을 때 모델이 궁금한 것만 골라 파볼 수 있다 — 모든 순간의 전후를
     * 미리 실어 보낼 수는 없지만, 도구는 그 순간만 조인해 볼 수 있다.
     *
     * 배포가 tools 파라미터를 거부하면 도구 없이 한 방 호출로 낙하한다 —
     * 기능이 죽는 것보다 얕은 분석이 낫다.
     */
    fun refine(
        userPromptText: String,
        cfg: AzureConfig,
        tools: JsonArray? = null,
        execute: ((String, JsonObject) -> String)? = null,
    ): Result<String> {
        val missing = cfg.missing
        if (missing.isNotEmpty()) {
            return Result.failure(
                IllegalStateException("${missing.joinToString(", ")}이(가) 비어 있습니다")
            )
        }
        if (tools == null || execute == null) {
            return requestJson(cfg, skillPrompt(DEEP_FILES), userPromptText)
        }
        val system = skillPrompt(DEEP_FILES)

        return runCatching {
            val history = mutableListOf(
                buildJsonObject { put("role", "user"); put("content", userPromptText) }
            )
            var toolsEnabled = true
            var turns = 0
            while (turns++ < MAX_AGENT_TURNS) {
                val msg = try {
                    exchange(cfg, system, history, if (toolsEnabled) tools else null)
                } catch (e: Exception) {
                    // 배포가 tools 를 모른다 — 도구를 접고 계속한다.
                    if (toolsEnabled && e.message?.contains("tool", ignoreCase = true) == true) {
                        android.util.Log.w("Interpreter", "배포가 tools 를 거부 — 도구 없이 낙하: ${e.message?.take(120)}")
                        toolsEnabled = false
                        exchange(cfg, system, history, null)
                    } else throw e
                }

                val toolCalls = msg["tool_calls"] as? JsonArray
                if (toolCalls.isNullOrEmpty()) {
                    val text = msg["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    return@runCatching extractJson(text)
                        ?: error("응답에서 JSON 을 찾지 못했습니다: ${text.take(300)}")
                }

                // 모델이 보낸 assistant 메시지를 그대로 이력에 되돌려줘야
                // tool 결과와 tool_call_id 가 짝을 이룬다.
                history += msg
                for (tc in toolCalls) {
                    val f = tc.jsonObject["function"]!!.jsonObject
                    val name = f["name"]!!.jsonPrimitive.content
                    val args = runCatching {
                        Json.parseToJsonElement(f["arguments"]!!.jsonPrimitive.content).jsonObject
                    }.getOrDefault(buildJsonObject {})
                    val result = runCatching { execute(name, args) }
                        .getOrElse { "도구 실행 실패: ${it.message}" }
                    android.util.Log.i("Interpreter", "도구 $name($args) → ${result.take(150)}")
                    history += buildJsonObject {
                        put("role", "tool")
                        put("tool_call_id", tc.jsonObject["id"]!!.jsonPrimitive.content)
                        put("content", result.take(MAX_TOOL_RESULT_CHARS))
                    }
                }
            }
            error("도구 호출이 ${MAX_AGENT_TURNS}턴을 넘었습니다 — 지침서의 도구 사용 규칙을 확인하세요")
        }
    }

    /**
     * 전송 + "JSON 만 내놓게 만들기". 첫 응답이 JSON 으로 파싱되지 않으면
     * 한 번만 교정 요청을 붙여 재시도한다.
     */
    private fun requestJson(cfg: AzureConfig, system: String, user: String): Result<String> {
        val missing = cfg.missing
        if (missing.isNotEmpty()) {
            return Result.failure(
                IllegalStateException("${missing.joinToString(", ")}이(가) 비어 있습니다")
            )
        }

        return runCatching {
            var text = call(cfg, system, user)
            if (extractJson(text) == null) {
                text = call(
                    cfg, system,
                    user +
                        "\n\n주의: 직전 응답이 JSON 파싱에 실패했습니다. " +
                        "설명 없이, 코드펜스 없이, 순수 JSON 객체 하나만 출력하세요."
                )
            }
            extractJson(text)
                ?: error("응답에서 JSON 을 찾지 못했습니다: ${text.take(300)}")
        }
    }

    private fun call(cfg: AzureConfig, system: String, user: String): String {
        val history = listOf(
            buildJsonObject { put("role", "user"); put("content", user) }
        )
        var last: Reply? = null
        for ((i, v) in VARIANTS.withIndex()) {
            val r = send(cfg, system, history, v)
            if (r.ok) return r.textOrThrow()
            last = r
            // 배포가 그 파라미터를 안 받는다고 답한 경우에만 다음 조합을 시도한다.
            // 그 외의 400 은 진짜 오류이므로 그대로 올려서 원인을 감추지 않는다.
            if (i == VARIANTS.lastIndex || r.code != 400 || !r.isParamMismatch) break
        }
        return last!!.textOrThrow()
    }

    /** 에이전트 루프의 한 왕복 — 성공하면 assistant 메시지 객체를 돌려준다. */
    private fun exchange(
        cfg: AzureConfig,
        system: String,
        history: List<JsonObject>,
        tools: JsonArray?,
    ): JsonObject {
        var last: Reply? = null
        for ((i, v) in VARIANTS.withIndex()) {
            val r = send(cfg, system, history, v, tools)
            if (r.ok) return r.messageOrThrow()
            last = r
            if (i == VARIANTS.lastIndex || r.code != 400 || !r.isParamMismatch) break
        }
        return last!!.messageOrThrow()
    }

    /** 코드펜스·앞뒤 설명이 섞여도 첫 '{'부터 짝이 맞는 '}'까지를 꺼낸다. */
    private fun extractJson(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                esc -> esc = false
                inStr -> when (c) {
                    '\\' -> esc = true
                    '"' -> inStr = false
                }
                c == '"' -> inStr = true
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    // ------------------------------------------------------------------

    /**
     * 요청 조합.
     *
     * 배포한 모델 계열마다 받는 파라미터가 다르다. 추론 계열은 `max_tokens` 를 거부하고
     * `max_completion_tokens` 를 요구하며, `system` 역할 대신 `developer` 를 쓴다.
     * 어느 계열인지 앱이 알 방법이 없으므로 순서대로 시도하고, 거절 사유가
     * 파라미터 불일치일 때만 다음으로 넘어간다.
     */
    private data class Variant(
        val systemRole: String,
        val tokenKey: String,
        /** 재현성용 저온. 추론 계열은 temperature 를 거부하므로 폴백에는 없다. */
        val temperature: Double? = null,
    )

    private class Reply(val code: Int, val body: String) {
        val ok: Boolean get() = code in 200..299

        /** 이 400 이 "그 파라미터는 못 받는다"는 뜻인가 */
        val isParamMismatch: Boolean
            get() = PARAM_MARKERS.any { body.contains(it, ignoreCase = true) }

        /** 에이전트 루프용 — content 가 비어도(tool_calls 만 있어도) 유효하다. */
        fun messageOrThrow(): JsonObject {
            val root: JsonObject? = runCatching {
                Json.parseToJsonElement(body).jsonObject
            }.getOrNull()
            if (!ok) {
                val msg = runCatching {
                    root!!["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
                }.getOrNull()
                error("HTTP $code: ${msg ?: body.take(400).ifBlank { "(본문 없음)" }}")
            }
            val obj = root ?: error("응답을 JSON 으로 읽을 수 없습니다: ${body.take(400)}")
            return runCatching {
                obj["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject
            }.getOrNull() ?: error("응답에 choices 가 없습니다: ${body.take(400)}")
        }

        fun textOrThrow(): String {
            val root: JsonObject? = runCatching {
                Json.parseToJsonElement(body).jsonObject
            }.getOrNull()

            if (!ok) {
                val msg = runCatching {
                    root!!["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
                }.getOrNull()
                error(buildString {
                    append("HTTP $code")
                    append(
                        when (code) {
                            401, 403 -> " — API 키를 확인하세요"
                            404 -> " — 엔드포인트·배포 이름·API 버전 중 하나가 틀렸을 수 있습니다"
                            429 -> " — 호출 한도에 걸렸습니다. 잠시 뒤 다시 시도하세요"
                            else -> ""
                        }
                    )
                    append(": ")
                    append(msg ?: body.take(400).ifBlank { "(본문 없음)" })
                })
            }

            val obj: JsonObject = root
                ?: error("응답을 JSON 으로 읽을 수 없습니다: ${body.take(400)}")

            val choice = runCatching { obj["choices"]!!.jsonArray[0].jsonObject }.getOrNull()
                ?: error("응답에 choices 가 없습니다: ${body.take(400)}")
            val text = runCatching {
                choice["message"]!!.jsonObject["content"]!!.jsonPrimitive.contentOrNull
            }.getOrNull()

            if (text.isNullOrBlank()) {
                val reason = runCatching {
                    choice["finish_reason"]!!.jsonPrimitive.contentOrNull
                }.getOrNull()
                error(
                    "해석 본문이 비어 있습니다 (finish_reason=$reason). " +
                        "콘텐츠 필터나 토큰 상한을 확인하세요."
                )
            }
            return text
        }
    }

    private fun send(
        cfg: AzureConfig,
        system: String,
        history: List<JsonObject>,
        v: Variant,
        tools: JsonArray? = null,
    ): Reply {
        val payload = buildJsonObject {
            put("messages", buildJsonArray {
                add(buildJsonObject { put("role", v.systemRole); put("content", system) })
                history.forEach { add(it) }
            })
            put(v.tokenKey, MAX_TOKENS)
            v.temperature?.let { put("temperature", it) }
            tools?.let { put("tools", it) }
        }

        val req = Request.Builder()
            .url(cfg.chatCompletionsUrl())
            // Azure 는 x-api-key 가 아니라 api-key 헤더를 쓴다.
            .addHeader("api-key", cfg.apiKey.trim())
            .addHeader("content-type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()

        return client.newCall(req).execute().use { resp ->
            Reply(resp.code, resp.body?.string().orEmpty())
        }
    }

    private fun userPrompt(reportJson: String): String = buildString {
        appendLine("아래는 한 사용자의 스마트폰 사용 로그를 집계한 리포트입니다.")
        appendLine("시스템 프롬프트의 절차(지형도 → 품질 → 후보 발굴 → 맥락 판정 →")
        appendLine("교차 검증 → 제안 생성)를 그대로 따라 루틴 제안을 만드세요.")
        appendLine()
        appendLine("기억할 것:")
        appendLine("- 통계가 문을 열고 서사가 통과시킵니다. 서사가 안 써지면 rejected 로.")
        appendLine("- 개수를 채우지 마세요. 확실한 것만, 전체 최대 5개, 카테고리당 최대 2개.")
        appendLine("- 트리거·액션·삼성 루틴 ID 는 proposal-rules.md 의 허용 목록만 쓰세요.")
        appendLine("- evidence 는 리포트의 필드명·수치를 그대로 인용하세요.")
        appendLine()
        appendLine("출력: proposal-rules.md 의 JSON 스키마 그대로, 설명도 코드펜스도 없이")
        appendLine("순수 JSON 객체 하나만 출력하세요.")
        appendLine()
        appendLine("리포트:")
        appendLine("```json")
        appendLine(reportJson)
        appendLine("```")
    }

    companion object {
        private val JSON_MEDIA = "application/json".toMediaType()
        private const val MAX_TOKENS = 8000

        /** 에이전트 루프 상한 — 이 안에 못 끝내면 지침이 잘못된 것이다. */
        private const val MAX_AGENT_TURNS = 8

        /** 도구 결과 상한(문자). 원본 로그 조인이 폭주하지 않게. */
        private const val MAX_TOOL_RESULT_CHARS = 8_000

        /** 흔한 조합부터. 앞이 성공하면 뒤는 보내지 않는다. */
        private val VARIANTS = listOf(
            Variant("system", "max_tokens", temperature = 0.2),
            Variant("system", "max_completion_tokens"),
            Variant("developer", "max_completion_tokens"),
        )

        private const val SKILL_ROOT = "skill"

        /** 파일 경계 마커. 지침서 본문에 `=` 로 시작하는 줄이 없어 충돌하지 않는다. */
        private const val MARKER = "====="

        /**
         * 경로는 [SKILL_ROOT] 기준 상대 경로다. SKILL.md 본문이 참조를
         * `references/metrics.md` 로 쓰므로 마커에 박히는 문자열도 그와 같아야 한다.
         *
         * `worked-example.md` 는 절차의 어느 단계에도 걸려 있지 않은 유일한 선택 파일이지만,
         * SKILL.md 가 참조 표에서 가리키고 있으므로 빼면 없는 파일을 가리키는 지시가 남는다.
         */
        private val SKILL_FILES = listOf(
            "SKILL.md",
            "references/artifacts.md",
            "references/metrics.md",
            "references/proposal-rules.md",
            "references/cross-analysis.md",
            "references/worked-example.md",
        )

        /** 심층 분석은 지침서 하나로 충분하다 — 입력이 리포트가 아니라 결정 이력이다. */
        private val DEEP_FILES = listOf("deep-analysis.md")
    }
}

/**
 * 400 본문에 이 말이 있으면 파라미터 불일치로 보고 다음 요청 조합을 시도한다.
 * 최상위에 두는 이유는 중첩 클래스에서 그대로 참조하기 위해서다.
 */
private val PARAM_MARKERS = listOf(
    "max_tokens",
    "max_completion_tokens",
    "unsupported_parameter",
    "unsupported parameter",
    "unsupported_value",
    "unsupported value",
    "'system'",
    "developer",
)
