# RountineAI

스마트폰 사용 로그를 **기기 안에서** 집계하고, 그 결과를 대시보드로 보여주는 개인용 앱.

## 왜 만드는가

안드로이드는 사용 이벤트를 며칠치만 보관하다 지운다. `dumpsys usagestats`로 뽑아도
이벤트 섹션은 "Last 24 hour events"로 하드코딩되어 하루치만 나오고,
`--checkin`으로 저장된 일별 파일을 긁어야 며칠치가 겨우 나온다.

이 앱은 주기적으로 이벤트를 읽어 **자체 DB에 누적**한다. 한 달 쓰면 한 달치,
1년 쓰면 1년치가 초 단위 해상도로 쌓인다. 보존 기간 문제가 사라지는 것이 존재 이유다.

## 설계 원칙

**계산과 해석을 분리한다.**

- 앱은 **집계만** 한다. `Report` 객체에는 해석 문장이 한 줄도 들어가지 않는다.
- 해석은 `assets/skill/`의 지침서를 시스템 프롬프트로 준 LLM이 만든다.
- 규칙 기반 문구를 쓰지 않는 이유: 규칙은 미리 써넣은 만큼만 말할 수 있고,
  새로 나타난 패턴을 못 잡는다.

**데이터 품질을 리포트에 포함한다.**

`Quality` 객체에 검산 결과·표본 수·경고가 들어간다. 해석하는 쪽이 이걸 보고
결론의 신뢰 등급을 정할 수 있어야 한다.

## 계측 함정 처리

집계에서 다음을 처리한다. 빼면 통계가 **조용히** 틀린다.

| 함정 | 처리 | 위치 |
|---|---|---|
| 화면 꺼진 뒤 마지막 앱에 시간이 계속 쌓임 | 화면 ON 구간으로만 집계 + 1시간 상한 | `Sessionizer` |
| 같은 초에 두 앱 동시 실행을 전환으로 오인 | 분할화면으로 분류해 전환에서 제외, 별도 지표화 | `Sessionizer` |
| 화면·잠금 이벤트가 사용자 프로필마다 중복 기록 | (종류, 초)로 중복 제거 | `Sessionizer` |
| 첫날·마지막날이 부분 기록 | 커버리지 검사 후 평균에서 제외 | `Analyzer` |
| 시간대 변환 오류 | epoch → `ZoneId.systemDefault()` 로만 변환 | `Analyzer` |
| 앱 시간 합계가 화면 시간을 넘음 | 검산해서 `Quality.reconciliationDelta` 로 노출 | `Analyzer` |

## 구조

```
data/        Room 엔티티 · DAO · DB
collect/     UsageStatsManager, NetworkStatsManager, NotificationListener, WorkManager
analysis/    Sessionizer(함정 처리) → Analyzer(집계) → Report(직렬화)
interpret/   집계 JSON + 해석 지침서 → LLM
assets/      dashboard.html (WebView), skill/ (해석 지침서)
```

## 권한

| 권한 | 용도 | 켜는 법 |
|---|---|---|
| `PACKAGE_USAGE_STATS` | 사용 이벤트 (필수) | 설정 → 특별한 앱 접근 → 사용 정보 접근 |
| 알림 접근 | 알림 통계 | 설정 → 알림 → 알림 접근 |
| `ACCESS_FINE_LOCATION` | Wi-Fi 이름 기록 | 앱 안에서 요청 |
| `READ_PHONE_STATE` | 모바일 통신량 | 앱 안에서 요청 |
| `INTERNET` | LLM 해석 (선택) | 해석 기능을 끄면 완전히 오프라인 |

앱을 설치해도 권한은 켜지지 않는다. 사용자가 설정에서 직접 허용해야 한다.

## 알려진 한계

- **과거 Wi-Fi 이력은 못 가져온다.** 시스템이 앱에 주지 않는다.
  `NetworkStatsManager`로 얻는 건 Wi-Fi/모바일 두 갈래까지다.
  장소 축은 앱 설치 시점부터 `NetworkChangeRow`로 직접 쌓는다.
- **배터리 히스토리는 접근 불가.** 시스템 앱 전용이다.
- **블루투스 연결 이력도 과거분은 없다.** 필요하면 지금부터 기록하는 코드를 추가해야 한다.
- 첫 실행 시 과거 데이터는 시스템이 들고 있는 만큼만 들어온다. 기기마다 다르다.

## 빌드

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Android Studio에서 열어도 된다. `minSdk 29`, `compileSdk 35`, Kotlin 2.0 / Compose.

> 이 스캐폴딩은 아직 실제 기기에서 빌드·실행 검증을 하지 않았다.
> 첫 빌드에서 의존성 버전이나 API 시그니처 조정이 필요할 수 있다.

## 개인정보

- 알림 **본문은 읽지도 저장하지도 않는다.** 발신 앱·채널·시각·방해 여부만 기록한다.
- Wi-Fi 이름은 로컬 DB에만 있고, 리포트에는 `Wi-Fi A` 같은 별칭으로만 나간다.
- LLM 해석을 켰을 때 전송되는 것은 `Report` 객체(집계 수치)뿐이다.
  원본 이벤트는 기기 밖으로 나가지 않는다.
- 백업 제외(`allowBackup=false`).
