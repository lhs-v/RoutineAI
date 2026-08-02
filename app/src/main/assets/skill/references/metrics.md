# 리포트 필드 사전 — 무엇을 보고, 무엇을 의심하고, 무엇과 대조하는가

리포트 JSON 의 필드별 독법. 표기: **필드** — 정의 / 의심할 것 / 대조할 것.

## 제안의 직접 근거가 되는 필드

**eventChains** — 비앱 이벤트(BT 연결/해제, Wi-Fi 연결, 모바일 전환, 운동
시작/종료) 직후 5분 안에 연 앱. 횟수·gap 분포(min/중앙/max)·반복일수.
루틴의 형태(조건→행동) 그대로라 trigger_routine 의 원천이다.
/ 중앙 gap 이 짧을수록(수십 초 이내) 의식(ritual)에 가깝고, 길수록 우연이 섞인다.
/ appContext 의 조건부 비율과 대조 — 연쇄가 있는데 조건부 비율이 배경 수준이면 우연.

**appContext** — 앱 실행 순간의 상태 프로파일: 이동통신 중 %(cellularPct),
BT 연결 중 %(btConnectedPct, 최다 기기), 알림 주도 %(notifLedPct, 실행 30초 안
같은 앱 알림 선행), 몰리는 시간대(topHours). -1 은 해당 소스 기록 없음.
/ 조건부 비율은 **다른 앱과의 기울기**로 읽는다 — 73%가 의미 있는 건 배경이
20%대일 때다. / 출퇴근·이동·운동 같은 비가시 상태의 유추 재료. 유추임을 유지.

**appPairs** — 두 앱의 양방향 전환 합(roundTrips), 방향비(abPct, 50 근처면 왕복),
중앙 간격, 홈 경유율(viaLauncherPct), 분할화면 동시 사용(coUseCount), 반복일수.
app_pair 의 원천. / 간격 수 초 + 홈 경유 낮음 + 분할 실측 = "한 세트" 서사 강함.
간격 수십 초 + 홈 경유 높음 = 병행 작업일 가능성 — 서사 없이 제안하지 않는다.

**timeFixed** — (앱, 시간대)별 "관측일 N일 중 며칠 열었나" + 하루 실행 횟수.
time_shortcut 의 원천. / 자주 쓰는 앱은 우연히도 값이 높다 — launchesPerDay 로
보정. 7/8일처럼 높고 실행 횟수 대비 특정 시간대 쏠림이 뚜렷할 때만 습관.

**notifByAppInterrupt** — 시스템 알림 이벤트 기준 발신 앱별 건/일(소급됨).
notif_cleanup 의 원천. 앱 이름이 성격을 말한다 — 시스템·연동성(PC 연결, 시스템 UI,
루틴 앱)은 사람이 응답할 일이 없는 알림이다. / notifByApp(리스너 도착)과 정의·
커버리지가 다르다. 건수보다 "누가 보내는가"가 중요.

**sleep** — 화면 공백 기반 수면 추정(밤 시작 공백만). "잤다"가 아니라 "폰을 안
봤다"다. app_mode(수면 창 시청 모드)의 창 계산에 쓴다. / health 의
sleep 세션이 있으면 교차 검증 — 둘이 맞으면 confidence 를 올릴 수 있다.

**health** — Health Connect 세션 요약(워치→삼성 헬스 동기화). kind 는 "sleep"
또는 "exercise:걷기" 형태, 세션 수·평균 길이·마지막 시작 시각. sleep(화면 공백
추정)과 달리 **실측 기록**이다 — sleep 교차 검증과 trigger_routine(운동 시작·
수면 종료 트리거)의 근거. eventChains 에 "운동 시작:걷기"·"수면 종료" 트리거가
여기서 나온다. 세션이 적으면(관측 며칠) 존재 확인까지만 — 습관 판정은 이르다.

## 서사의 가중치가 되는 필드 (페르소나)

**days / hourly** — 날짜별·시간대별 화면과 알림. 하루 리듬의 봉우리·공백.
**outingByWeekday / outingByHour** — 낮 이동통신 우세율. 요일 패턴과 하루 안
출입 경계. 기기의 망 상태이지 사람의 위치가 아니다.
**places** — SSID 별칭별 체류(밤 수·낮 비율). 별칭 그대로 쓰고 실제 장소를
추정하지 않는다.
**apps** — 앱별 사용시간의 평균·중앙값·편차·범위·사용일수·dailyMinutes(원본
궤적). 평균과 중앙값이 크게 다르면 습관이 아니라 사건이다 — dailyMinutes 로
직접 확인. **secondsPerLaunch** 가 짧고 실행이 잦으면 확인 습관형 앱.
**firstApps / lastApps / morningFirstApps** — 세션의 진입·이탈 앱, 아침 진입점.
**netApps** — 앱별 통신량·모바일 비율. 통신이지 사용이 아니다(artifacts S1).
**transitions / coUse** — 방향별 전환과 분할화면 원본. appPairs 의 재료이며
세부 확인용.

## 품질·진단 필드

**quality** — 검산(reconciliationDelta), 처리량, 표본 수(weekday/weekendCount),
warnings. **diagnostics** — 저장량, 커버리지 시점(notifListenerSince), 이벤트
종류 분포, 세션 구성 방식, notifRepostsCollapsed. 관측 창이 짧은 소스의 판별 근거.
