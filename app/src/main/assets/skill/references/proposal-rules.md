# 제안 규칙 — 카테고리, 문턱, 출력 스키마

## 카테고리 5종과 통계 문턱

문턱은 후보의 **입장 조건**이다. 통과해도 맥락 판정(4단계)을 거쳐야 제안이 된다.

| category | 무엇 | 입장 문턱 | 필요한 서사 |
|---|---|---|---|
| `trigger_routine` | 조건 발생 시 앱 실행 | eventChains: 횟수≥3 · 반복≥3일 · 중앙 gap≤60초 | 트리거가 그 앱의 목적을 시작시킨다 |
| `app_pair` | 두 앱을 분할화면 세트로 | appPairs: 왕복≥10 · 중앙 간격≤60초, 또는 coUseCount≥3 | 두 앱의 목적이 하나의 활동으로 이어진다 |
| `app_mode` | 앱 실행 중에만 기기 설정 변경(종료 시 원복) | apps: 사용일≥관측일 60% 이상인 앱 + 설정이 그 앱 경험을 개선한다는 근거(시간대·수면 창 겹침 등) | 그 앱을 쓰는 동안 이 설정이 나은 이유 |
| `notif_cleanup` | 시스템성 다량 알림 끄기 | notifByAppInterrupt: ≥20건/일 | 사람이 응답할 일이 없는 알림이다 |
| `time_shortcut` | 고정 시각 습관의 진입 마찰 제거 | timeFixed: daysHit ≥ 관측일 70% | 그 시각의 실행이 의도적 습관이다 |

app_mode 는 행동 로그(회전·밝기 조작)를 수집하지 않으므로 confidence 상한이
"유력"이다. 수면 창과의 겹침(sleep × 실행 시각)이 있으면 그것이 주 근거다.

## 출력 — 이 JSON 만, 코드펜스 없이

```
{
  "proposals": [
    {
      "id": "짧은-슬러그",
      "category": "trigger_routine | app_pair | app_mode | notif_cleanup | time_shortcut",
      "oneLine": "사용자에게 보일 한 줄 제안 (…할까요? 형)",
      "narrative": "왜 이 패턴이 이 목적으로 읽히는가 — 한 문장 서사",
      "trigger": { "type": "<아래 목록>", "param": "패키지명/기기명/SSID별칭/HH:mm 등" },
      "action":  { "type": "<아래 목록>", "params": ["패키지명", ...] },
      "samsungCondition": "<삼성 루틴 조건 ID 또는 null>",
      "samsungAction": "<삼성 루틴 액션 ID 또는 null>",
      "evidence": ["필드명과 수치를 그대로 인용한 근거 문자열", ...],
      "confidence": "확인됨 | 유력 | 가설"
    }
  ],
  "rejected": [
    { "candidate": "무엇", "reason": "왜 — 서사 기반 사유 (통계는 강하나 …)" }
  ],
  "analysisNote": "관측 창·품질 경고·전반 요약 몇 문장"
}
```

### trigger.type 허용 목록 (앱이 실시간 감지할 수 있는 것만)

`bt_connect` `bt_disconnect` (param=기기 이름) · `wifi_connect` `wifi_disconnect`
(param=SSID 별칭) · `app_launch` (param=패키지명) · `time` (param="HH:mm") ·
`exercise_start` (param=운동 이름)

### action.type 허용 목록

`launch_app` (params=[패키지명]) · `app_pair` (params=[패키지1, 패키지2]) ·
`mode_rotation` `mode_eye_comfort` `mode_dnd` (params=[적용 중인 앱 패키지]) ·
`notif_channel_off` (params=[패키지명])

### 삼성 루틴 ID 허용 목록 (이 기기에서 실존 확인된 것)

조건: `specify_bluetooth_v3` `connected_headset_v3` `specify_wifi_v3` `time_v3`
`specific_time_v3` `launch_app_v3` `plugin_gps_location_v3`
`samsung_health_during_exercise_v3`
액션: `launch_app_v3` `dnd_mode_on_v3` `rotation_screen_v3` `dark_mode_v3`
`brightness_v3` `sound_sdk3` `custom_notification_v3`
목록에 없는 매핑이 필요하면 null 로 둔다 — 지어내지 않는다.

## 개수·표현 정책

- 전체 최대 5개, 카테고리당 최대 2개. confidence(확인됨>유력>가설) → 반복일수 순.
- **개수를 채우지 않는다.** 확실한 것이 적으면 적게 낸다.
- evidence 는 리포트의 필드명·수치를 그대로 인용한다. 수치를 반올림해도 되지만
  지어내지 않는다.
- oneLine 은 명령이 아니라 제안형("…할까요?"). 사용자의 패턴을 바꾸라는 제안
  (사용 줄이기, 일찍 자기)은 내지 않는다 — 패턴을 편하게 만드는 제안만.
- rejected 는 사용자가 "왜 이건 제안 안 했지"라고 물을 만한 것 위주로 2~4개.
