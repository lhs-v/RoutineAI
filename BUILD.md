# 빌드 & 설치

## 준비물

- Android Studio (Ladybug 이상 권장) 또는 Gradle 8.11+
- JDK 17 이상 — Android Studio 를 쓰면 번들 JBR 이 자동으로 잡힌다
- 폰: 개발자 옵션 → USB 디버깅 켜기, 케이블 연결 후 "이 컴퓨터를 허용" 승인

## Gradle Wrapper 가 없는 이유

`gradle-wrapper.jar` 는 바이너리라 저장소에 넣지 않았다. 아래 중 하나로 만든다.

```bash
# Gradle 이 설치되어 있으면
gradle wrapper --gradle-version 8.11.1
```

Android Studio 로 프로젝트를 열면 Sync 과정에서 자동으로 생성된다.

## 빌드

```bash
./gradlew :app:assembleDebug          # macOS / Linux
.\gradlew.bat :app:assembleDebug      # Windows
```

산출물: `app/build/outputs/apk/debug/app-debug.apk`

## 설치

```bash
adb devices -l                        # 기기가 잡히는지 먼저 확인
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Windows 라면 `build_install.ps1` 이 위 과정을 한 번에 처리한다.

## 데모 데이터 (선택)

설정 탭의 **데모 데이터** 토글을 켜면 권한도 수집도 없이 대시보드와 해석을 볼 수 있다.
그러려면 데모 로그가 빌드에 들어 있어야 한다.

```
app/src/main/assets/demo/routine.db
```

이 파일은 **저장소에 없다.** 실제 기기에서 뽑은 사용 기록(앱 목록·시각·Wi-Fi 이름)이라
공개 저장소에 올리지 않는다. 파일을 따로 받아 위 경로에 두고 다시 빌드하면 된다.
없으면 토글이 잠긴 채 이유가 화면에 표시되고, 나머지 기능은 정상 동작한다.

직접 만들려면 앱을 얼마간 쓴 기기를 USB 로 연결하고 도구를 돌린다.

```bash
python tools/make_demo_db.py
```

앱 DB 를 뽑아(WAL 체크포인트 포함) 에셋 자리에 두고, `dumpsys netstats` 의
SSID 별 이력을 앱 수집 포맷(`net_change`)으로 변환해 설치 전 구간을 소급해 채운다.
값을 지어내는 단계는 없다 — 모든 행이 폰이 실제로 기록한 측정값에서 나온다.

> 데모 폰에 그냥 보여주기만 할 거라면 APK 를 통째로 건네는 편이 간단하다.
> 데모 로그는 APK 안에 들어가고, APK 는 저장소에 올라가지 않는다(`*.apk` 무시).

### 데모 DB 를 넣는 에이전트/개발자를 위한 체크리스트

파일을 전달받아 넣는 작업은 아래만 지키면 의존성·충돌이 없다.

**1. 위치와 이름은 고정이다.** 정확히 `app/src/main/assets/demo/routine.db`.
경로·이름은 `data/Db.kt` 의 `DEMO_ASSET_DIR`/`DEMO_ASSET_FILE` 상수와 묶여
있다 — 다른 이름으로 두면 앱이 못 찾고, **`.gitignore` 는 이 정확한
경로만 막고 있어서** `routine2.db` 같은 변형은 실수로 커밋된다. 이름을
바꾸지 마라.

**2. 스키마 버전은 "코드 이하"여야 한다.** Room 은 에셋의 `user_version` 이
현재 코드의 `Db.version` 보다 **낮으면** 자동 마이그레이션 체인으로
승격하지만, **높으면**(더 새 앱에서 뽑은 DB) 다운그레이드 불가로 죽는다.
넣기 전에 확인:

```bash
python -c "import sqlite3; print(sqlite3.connect('routine.db').execute('PRAGMA user_version').fetchone())"
```

이 값이 `data/Db.kt` 의 `version` 이하면 된다. 아니면 지금 코드로 빌드된
앱을 쓴 기기에서 `tools/make_demo_db.py` 로 다시 뽑는다.

**3. 단일 파일이어야 한다.** `-wal`/`-shm` 없이 `.db` 하나.
`make_demo_db.py` 는 체크포인트를 처리하지만, 기기에서 손으로 뽑았다면
WAL 이 병합됐는지 확인하라 — WAL 이 남은 파일은 최근 기록이 잘린 채
복사된다.

**4. 넣은 뒤 검증.** 재빌드·재설치 후 ① 설정 탭의 데모 토글이 활성인지,
② 켜고 대시보드에서 "데모 리포트 다시 생성"이 수치를 채우는지. 앱은 열
때마다 기기 사본을 지우고 에셋에서 다시 복사하므로(`Db.demo()`), 에셋을
교체했다면 재빌드·재설치만 하면 되고 기기에 남은 옛 사본은 걱정할 것 없다.

**5. 없어도 아무것도 안 깨진다.** 파일이 없으면 토글이 잠기고 이유가
표시될 뿐, 수집·감지·제안 등 나머지 전부가 정상이다 — 데모 DB 는
의존성이 아니라 선택 에셋이다. 제안·결정 이력은 어느 모드든 실 DB 에
쌓이므로 데모 DB 안의 proposal 계열 테이블 내용은 무시된다.

**6. 절대 커밋하지 마라.** 실사용 기록이다. `git status` 에 이 파일이
보이면 뭔가 잘못된 것이다(정상이라면 `.gitignore` 가 숨긴다).

**7. `make_demo_db.py` 재실행은 pull 이 에셋을 덮는다는 것을 기억하라.**
`--no-pull` 없이 돌리면 기기 실 DB 복사본으로 **통째 교체**되므로, 이전에
심어둔 합성(BT `majorClass=-1` · 쇼핑 `cls='synth'`)이 함께 사라진다.
실측: `--synth-shopping` 만 주고 돌렸다가 합성 BT 가 날아가 "버즈 연결 →
뮤직" 연쇄가 리포트에서 사라졌다. 지금은 스크립트가 pull 전에 흔적을
감지해 해당 `--synth-*` 를 자동으로 켜지만, 합성을 새로 추가하면 그
감지 목록에도 넣어야 한다. 합성만 다시 심을 때는 pull 없이 모듈 호출이
안전하다: `python -c "import make_demo_db; make_demo_db.synth_bt()"`.

## 설치 후

앱은 설치만으로 아무것도 수집하지 않는다. 앱을 열고 순서대로 켜야 한다.

1. **사용 정보 접근** — 필수. 이게 없으면 아무 데이터도 안 들어온다.
2. **알림 접근** — 알림 통계용. 없으면 알림 지표가 빈다.
3. **위치 권한** — Wi-Fi 이름 기록용. 없으면 장소 축이 "wifi" 로만 남는다.

그다음 `지금 수집` → `리포트 생성` 순서로 누르면 대시보드가 뜬다.

첫 수집에는 시스템이 들고 있던 며칠치만 들어온다. 기간이 늘어나는 건
백그라운드 수집(6시간 주기)이 며칠 돌고 난 뒤부터다.
