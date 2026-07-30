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

직접 만들려면 앱을 얼마간 쓴 기기에서 뽑는다.

```bash
adb exec-out run-as com.routineai cat databases/routine.db > demo.db
```

WAL 이 남아 있으면 최근 기록이 빠지므로, 한 번 열어 체크포인트한 뒤 쓰는 편이 안전하다.

> 데모 폰에 그냥 보여주기만 할 거라면 APK 를 통째로 건네는 편이 간단하다.
> 데모 로그는 APK 안에 들어가고, APK 는 저장소에 올라가지 않는다(`*.apk` 무시).

## 설치 후

앱은 설치만으로 아무것도 수집하지 않는다. 앱을 열고 순서대로 켜야 한다.

1. **사용 정보 접근** — 필수. 이게 없으면 아무 데이터도 안 들어온다.
2. **알림 접근** — 알림 통계용. 없으면 알림 지표가 빈다.
3. **위치 권한** — Wi-Fi 이름 기록용. 없으면 장소 축이 "wifi" 로만 남는다.

그다음 `지금 수집` → `리포트 생성` 순서로 누르면 대시보드가 뜬다.

첫 수집에는 시스템이 들고 있던 며칠치만 들어온다. 기간이 늘어나는 건
백그라운드 수집(6시간 주기)이 며칠 돌고 난 뒤부터다.
