package com.routineai.interpret

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 개인 맥락 메모리의 입구.
 *
 * 사전의 본체(정리·요약·수명 관리)는 외부 프로세스가 맡을 수 있도록,
 * 저장을 단순한 파일(files/memory.md, 한 줄 항목)로 둔다. 앱이 제공하는
 * 것은 입구 둘뿐이다 — 넣기(에이전트의 remember 도구)와 읽기(분석
 * 프롬프트에 자동 포함).
 *
 * 의존성이 아니다: 파일이 없으면 모든 분석이 메모리 없이 그대로 돈다.
 * 외부에서 넣고 빼는 법: `adb shell run-as com.routineai cat files/memory.md`
 * 로 꺼내고, 정리한 내용을 같은 형식으로 되돌려 놓으면 앱은 그대로 읽는다.
 */
object Memory {

    private const val FILE = "memory.md"

    /** 상한. 넘으면 오래된 줄부터 버린다 — 정리는 외부 프로세스의 몫이다. */
    private const val MAX_LINES = 120

    fun read(ctx: Context): String = runCatching {
        File(ctx.filesDir, FILE).readText().trim()
    }.getOrDefault("")

    /**
     * 한 줄 항목으로 덧붙인다. 같은 내용이 이미 있으면 넣지 않는다 —
     * 중복 판정은 날짜를 뺀 본문 기준이다.
     */
    fun append(ctx: Context, entry: String): String {
        val body = entry.trim().replace('\n', ' ').take(200)
        if (body.isBlank()) return "빈 내용은 기억하지 않습니다"
        val f = File(ctx.filesDir, FILE)
        val existing = runCatching { f.readLines() }.getOrDefault(emptyList())
        if (existing.any { it.substringAfter("] ", it) == body }) {
            return "이미 같은 내용이 메모리에 있습니다"
        }
        val date = SimpleDateFormat("MM/dd", Locale.KOREAN).format(Date())
        val all = (existing + "- [$date] $body").takeLast(MAX_LINES)
        return runCatching {
            f.writeText(all.joinToString("\n") + "\n")
            "기억했습니다: $body"
        }.getOrElse { "메모리 쓰기 실패: ${it.message}" }
    }

    /** 데모 완전 초기화용 — 이력과 함께 기억도 지워야 근거 없는 기억이 남지 않는다. */
    fun clear(ctx: Context) {
        runCatching { File(ctx.filesDir, FILE).delete() }
    }
}
