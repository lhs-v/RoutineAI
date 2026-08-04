# 리포트 필드 사전 — 무엇을 보고, 무엇을 의심하고, 무엇과 대조하는가

리포트 JSON 의 필드별 독법. 표기: **필드** — 정의 / 의심할 것 / 대조할 것.

## 제안의 직접 근거가 되는 필드

**eventChains** — 비앱 이벤트(BT 연결/해제, Wi-Fi 연결, 모바일 전환, 운동
시작/종료) 직후 5분 안에 **전면에 온** 앱. 횟수·gap 분포(min/중앙/max)·
반복일수·topHours(연쇄가 몰리는 시간대)·resumedPct.
루틴의 형태(조건→행동) 그대로라 trigger_routine 의 원천이다.
/ "전면에 온"이지 "새로 연"이 아니다 — **resumedPct** 가 그 구분이다:
트리거 직전에도 같은 앱이 전면이었던 비율로, 높으면 트리거가 행동을
시작시킨 게 아니라 하던 행동 중에 일어난 사건이다(영상 보다 이어폰을 뺀
"BT해제→YouTube"). 낮아야 트리거→행동 서사가 선다.
/ 중앙 gap 이 짧을수록(수십 초 이내) 의식(ritual)에 가깝고, 길수록 우연이 섞인다.
/ appContext 의 조건부 비율과 대조 — 연쇄가 있는데 조건부 비율이 배경 수준이면 우연.
/ **BT 기기 이름이 둘로 갈릴 수 있다** — 같은 이어버즈가 클래식·LE 이중
본딩으로 두 이름(개명된 실명, 기본 이름+MAC 꼬리)이 되어 거의 같은 초에
쌍으로 붙고 떨어진다. 두 이름의 연쇄는 한 기기로 병합해 읽고, 횟수도
나뉘어 있을 수 있음을 감안하라.

**appContext** — 앱 실행 순간의 상태 프로파일: 이동통신 중 %(cellularPct),
BT 연결 중 %(btConnectedPct, 최다 기기), 알림 주도 %(notifLedPct, 실행 30초 안
같은 앱 알림 선행), 몰리는 시간대(topHours). -1 은 해당 소스 기록 없음.
/ 조건부 비율은 **다른 앱과의 기울기**로 읽는다 — 73%가 의미 있는 건 배경이
20%대일 때다. / 출퇴근·이동·운동 같은 비가시 상태의 유추 재료. 유추임을 유지.

**appPairs** — 두 앱의 양방향 전환 합(roundTrips), 방향비(abPct, 50 근처면 왕복),
시간 축 둘: **왕복 주기**(medianCycleSeconds — 한쪽을 열고 반대쪽을 열기까지,
확인 리듬)와 **갈아타기**(medianSwitchSeconds — 한쪽을 떠나 반대쪽을 열기까지
= 홈에 머문 시간). 홈 경유율(viaLauncherPct), 분할화면 동시(coUseCount), 반복일수.
app_pair 의 원천. / 갈아타기 **중앙값은 판별력이 없다** — 실측상 어느
페어든 1~5초다(다들 홈을 빨리 지나간다). 갈리는 것은 꼬리, 즉
**switchLongPct(30초 초과 비율)**: 한 흐름 페어는 0~5%, 홈에서 딴 일을
보다 돌아오는 병행은 15%+ (실측: 쇼핑 페어 0% vs 설정↔YouTube 17%,
YouTube↔카톡 24%). 낮은 switchLongPct 는 필요조건이지 충분조건이 아니다 —
한 흐름인지의 최종 판별은 목적 연결(서사)이 한다. 서사 없이 제안하지 않는다.
viaLauncherPct 와 coUseCount 는 **제외 근거로 쓰지 않는다** — 분할화면을
안 쓰는 사용자가 빠르게 번갈아 보면 홈 경유율은 필연적으로 100%가 되고
coUse 는 항상 0이다. 그건 병행의 증거가 아니라 app_pair 가 제거하려는
마찰(홈 왕복) 그 자체다. coUse≥3 은 "이미 나란히 쓰고 있다"는 확증으로
입장을 열어주는 대안 경로일 뿐 필요조건이 아니다. 병행/한 흐름의 통계
판별은 switchLongPct 만 쓰고, 제외하려면 서사로 제외한다.

**timeFixed** — (앱, 시간대)별 "관측일 N일 중 며칠 열었나" + 하루 실행 횟수.
time_shortcut 의 원천. / 자주 쓰는 앱은 우연히도 값이 높다 — launchesPerDay 로
보정. 7/8일처럼 높고 실행 횟수 대비 특정 시간대 쏠림이 뚜렷할 때만 습관.

**notifByAppInterrupt** — 시스템 알림 이벤트 기준 발신 앱별 건/일(소급됨).
notif_cleanup 의 원천. 앱 이름이 성격을 말한다 — 시스템·연동성(PC 연결, 시스템 UI,
루틴 앱)은 사람이 응답할 일이 없는 알림이다. / notifByApp(리스너 도착)과 정의·
커버리지가 다르다. 건수보다 "누가 보내는가"가 중요.

**notifResponse** — 발신 앱별 알림 응답 행동: 어떻게 사라졌는가.
clickPct(눌러서 열었다 = 응답 가치) · swipePct(개별 스와이프 = 보고 무시) ·
clearAllPct(모두 지우기에 쓸림 = 쌓아놓는 무관심) · appCancelPct(앱 자체
취소 = 앱 안에서 처리) · medianShelfMinutes(방치 중앙값).
notif_cleanup 의 **가장 강한 근거** — 건수는 양이지만 이건 행동이다.
클릭률 낮고 스와이프·몰아 지우기가 높으면 행동으로 증명된 무응답 알림.
/ 반대 방향도 성립: clickPct 가 높으면 건수가 많아도 끄면 안 된다.
/ 수집 시작(v10) 이후만 쌓여 관측 창이 다른 지표보다 짧을 수 있다 —
removed 표본 수를 함께 보라.

**health** — Health Connect 세션(수면·운동) 종류별 요약: sessions ·
meanMinutes · lastStart(동기화 생존 증거) · topHours(시작 시간대 상위 —
"언제 하는 운동인가"). / 운동 직후의 앱은 eventChains(운동 시작/종료
트리거)에 따로 실린다.

**sleep** — 화면 공백 기반 수면 추정(밤 시작 공백만). "잤다"가 아니라 "폰을 안
봤다"다. app_mode(수면 창 시청 모드)의 창 계산에 쓴다. / health 의
sleep 세션이 있으면 교차 검증 — 둘이 맞으면 confidence 를 올릴 수 있다.

**nightHabit** — 수면 직전 60분과 심야(22~02시)의 앱 습관. preSleepSharePct
(직전 60분 점유율)·preSleepNights(등장 밤 수)·lateNightMinutesPerNight.
app_mode 의 주 근거: "잠들기 전 마지막 화면의 주인"이 누구인지가 여기 있다.
점유율이 높고 밤 수가 관측 밤의 절반을 넘으면 그 앱의 심야 경험 개선
(편안하게 보기·방해 금지·회전 잠금)을 검토할 근거가 된다.

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
**category** 는 개발자가 선언한 분류(게임·오디오·영상 등, null 흔함) —
세계지식이 안 닿는 앱의 목적을 정의할 때의 보조 단서다.
**firstApps / lastApps / morningFirstApps** — 세션의 진입·이탈 앱, 아침 진입점.
**netApps** — 앱별 통신량·모바일 비율. 통신이지 사용이 아니다(artifacts S1).
**transitions / coUse** — 방향별 전환과 분할화면 원본. appPairs 의 재료이며
세부 확인용.

## 품질·진단 필드

**quality** — 검산(reconciliationDelta), 처리량, 표본 수(weekday/weekendCount),
warnings. **diagnostics** — 저장량, 커버리지 시점(notifListenerSince), 이벤트
종류 분포, 세션 구성 방식, notifRepostsCollapsed. 관측 창이 짧은 소스의 판별 근거.
