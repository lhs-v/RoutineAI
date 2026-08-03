package com.routineai.interpret

import android.content.Context
import com.routineai.data.Db
import com.routineai.data.ProposalEventRow
import com.routineai.data.ProposalRow
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.random.Random

/**
 * 데모 시연용 심기 — 증권 페어(영웅문↔토스) 제안과 일주일치 결정 이력.
 *
 * 목적은 심층 분석의 시연이다. 이 페어는 분석마다 거의 항상 나오는 안정
 * 제안이라 데모의 닻으로 쓰기 좋은데, 심층 분석이 보여줄 것(결정 맥락을
 * 읽고 조건을 좁히는 것)은 이력이 일주일쯤 쌓여야 생긴다. 그래서 갈림이
 * 선명한 이력 — 평일 아침 9시엔 수락, 평일 저녁과 주말엔 미룸 — 을 심어
 * 두면, 심층 분석을 바로 돌려 "평일 오전으로 좁힐까요" 류의 정제·실험이
 * 나오는 것을 기다림 없이 보여줄 수 있다.
 *
 * 실 DB 에 쓴다 — 제안·결정 이력의 수명주기는 데모 로그가 아니라 앱
 * 상태다(데모 모드 여부와 무관하게 심층 분석이 읽는 곳). 재실행하면 이
 * 시그니처의 이력을 지우고 다시 심으므로 멱등이다.
 */
object DemoSeed {

    private const val HERO = "com.kiwoom.heromts"
    private const val TOSS = "viva.republica.toss"

    /** 1차 분석이 실제로 내는 시그니처와 같게 — 재분석이 중복 카드를 만들지 않게 한다. */
    const val SIGNATURE = "app_launch:$HERO>app_pair:$HERO,$TOSS"

    suspend fun plantHeroTossStory(ctx: Context): String {
        val dao = Db.get(ctx).dao()
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()

        // 이미 분석이 만든 카드가 있으면 그 내용을 살리고 상태만 수락으로.
        val existing = dao.proposal(SIGNATURE)
        dao.upsertProposal(
            (existing ?: ProposalRow(
                signature = SIGNATURE,
                category = "app_pair",
                oneLine = "영웅문S#와 토스를 분할화면 앱페어로 묶을까요?",
                narrative = "커뮤니티의 종목 반응 확인과 거래·시세 확인이 한 활동으로 오간다.",
                triggerType = "app_launch",
                triggerParam = HERO,
                actionType = "app_pair",
                actionParams = """["$HERO","$TOSS"]""",
                samsungCondition = null,
                samsungAction = null,
                evidenceJson = """["appPairs: 왕복 37회 · 갈아타기 중앙 ~0초 · 반복 5일"]""",
                confidence = "확인됨",
                state = "accepted",
                createdAt = now - 9L * 24 * 3600 * 1000,
                updatedAt = now,
            )).copy(state = "accepted", updatedAt = now)
        )

        // 이 시그니처의 이력만 걷고 다시 심는다 — 버튼을 두 번 눌러도 한 벌.
        dao.deleteProposalEvents(SIGNATURE)

        val rng = Random(42)
        var mornings = 0; var afternoons = 0; var evenings = 0; var weekends = 0

        fun row(ts: Long, h: Int, weekday: Int, kind: String, net: String,
                ringer: String, charging: Boolean, battery: Int) = ProposalEventRow(
            ts = ts,
            proposalSignature = SIGNATURE,
            kind = kind,
            hour = h,
            weekday = weekday,
            foregroundPkg = HERO,
            network = net,
            btDevice = null,
            choice = null,
            ringer = ringer,
            charging = charging,
            batteryPct = battery,
        )

        // 노출과 결정 한 쌍 — 결정은 노출 40초 뒤(카드를 읽는 시간).
        suspend fun pair(day: LocalDate, h: Int, m: Int, decision: String, net: String,
                         ringer: String, charging: Boolean, battery: Int) {
            val ts = LocalDateTime.of(day, LocalTime.of(h, m))
                .atZone(zone).toInstant().toEpochMilli()
            val wd = day.dayOfWeek.value
            dao.logProposalEvent(row(ts, h, wd, "surfaced", net, ringer, charging, battery))
            dao.logProposalEvent(row(ts + 40_000, h, wd, decision, net, ringer, charging, battery))
        }

        // 오늘을 뺀 지난 9일 — 평일 5일 이상과 주말 2일이 반드시 들어온다.
        for (back in 9 downTo 1) {
            val day = LocalDate.now().minusDays(back.toLong())
            val weekend = day.dayOfWeek == DayOfWeek.SATURDAY ||
                day.dayOfWeek == DayOfWeek.SUNDAY
            if (!weekend) {
                // 장 시작 무렵 — 영웅문을 열자 팝업이 떴고, 바로 수락.
                val net = if (rng.nextInt(3) == 0) "cellular" else "wifi"
                pair(day, 9, rng.nextInt(0, 14), "accepted", net, "sound", false, 75 + rng.nextInt(21))
                mornings++
                // 평일 절반쯤은 오후 3시 반 무렵에도 수락 — 시각 자체는 원시
                // 값일 뿐이고, 9시·15:30 이 장 시작·마감이라는 해석은 심층
                // 분석의 세계지식이 붙이는 것이다(그게 이 데모의 핵심 장면).
                if (rng.nextInt(2) == 0) {
                    pair(day, 15, 24 + rng.nextInt(12), "accepted", "wifi", "vibrate", false, 40 + rng.nextInt(31))
                    afternoons++
                }
                // 평일 사흘쯤은 저녁에도 열었지만, 그때는 미뤘다.
                if (rng.nextInt(5) < 3) {
                    val h = 19 + rng.nextInt(3)
                    pair(day, h, rng.nextInt(0, 59), "not_now", "wifi", "vibrate", h >= 21, 30 + rng.nextInt(31))
                    evenings++
                }
            } else {
                // 주말 낮 — 열긴 하지만 미룬다.
                pair(day, 10 + rng.nextInt(6), rng.nextInt(0, 59), "not_now", "wifi", "sound", false, 50 + rng.nextInt(41))
                weekends++
            }
        }

        return "심었습니다 — 평일 아침 수락 ${mornings}회 · 오후 3시 반 수락 ${afternoons}회 · " +
            "평일 저녁 미룸 ${evenings}회 · 주말 미룸 ${weekends}회. 루틴 탭에서 심층 분석을 돌려보세요."
    }
}
