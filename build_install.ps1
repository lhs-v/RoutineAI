# ============================================
#  RoutineAI 빌드 + 폰 설치
# ============================================
$ErrorActionPreference = "Stop"
$proj = $PSScriptRoot
Set-Location $proj

function Say($m, $c = "Gray") { Write-Host $m -ForegroundColor $c }

Say ""
Say "=== [1/5] JDK 찾는 중 ===" Cyan
$jdk = $null
foreach ($c in @(
    "$env:ProgramFiles\Android\Android Studio\jbr",
    "${env:ProgramFiles(x86)}\Android\Android Studio\jbr",
    "$env:LOCALAPPDATA\Programs\Android Studio\jbr",
    $env:JAVA_HOME)) {
    if ($c -and (Test-Path "$c\bin\java.exe")) { $jdk = $c; break }
}
if (-not $jdk) {
    $j = Get-Command java -ErrorAction SilentlyContinue
    if ($j) { $jdk = Split-Path (Split-Path $j.Source) }
}
if (-not $jdk) {
    Say "JDK 를 못 찾았습니다. Android Studio 를 설치하거나 JAVA_HOME 을 설정하세요." Red
    return
}
$env:JAVA_HOME = $jdk
Say "  JAVA_HOME = $jdk" Green
& "$jdk\bin\java.exe" -version 2>&1 | Select-Object -First 1 | ForEach-Object { Say "  $_" }

Say ""
Say "=== [2/5] Gradle Wrapper 확인 ===" Cyan
if (-not (Test-Path "$proj\gradlew.bat")) {
    Say "  wrapper 가 없습니다. 생성을 시도합니다..." Yellow
    $g = Get-Command gradle -ErrorAction SilentlyContinue
    if ($g) {
        & gradle wrapper --gradle-version 8.11.1
    } else {
        Say ""
        Say "  Gradle 이 설치되어 있지 않습니다." Red
        Say "  Android Studio 로 이 폴더를 열면 Sync 과정에서 wrapper 가 만들어집니다." Yellow
        Say "  경로: $proj" Yellow
        return
    }
}
Say "  gradlew.bat 준비됨" Green

Say ""
Say "=== [3/5] 빌드 (첫 실행은 의존성 내려받느라 몇 분 걸립니다) ===" Cyan
& "$proj\gradlew.bat" :app:assembleDebug --console=plain
if ($LASTEXITCODE -ne 0) {
    Say ""
    Say "빌드 실패. 위 오류를 알려주시면 고치겠습니다." Red
    return
}
$apk = "$proj\app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $apk)) { Say "APK 를 찾을 수 없습니다: $apk" Red; return }
Say "  APK: $apk  ($([math]::Round((Get-Item $apk).Length/1MB,1)) MB)" Green

Say ""
Say "=== [4/5] 기기 확인 ===" Cyan
$adb = $null
$g = Get-Command adb -ErrorAction SilentlyContinue
if ($g) { $adb = $g.Source }
if (-not $adb) {
    foreach ($c in @(
        "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
        "$env:USERPROFILE\Downloads\platform-tools\adb.exe",
        "C:\Android\platform-tools\adb.exe")) {
        if (Test-Path $c) { $adb = $c; break }
    }
}
if (-not $adb) { Say "adb 를 못 찾았습니다." Red; return }

$dev = & $adb devices -l 2>&1
$dev | ForEach-Object { Say "  $_" }
if (-not ($dev | Where-Object { $_ -match "\sdevice(\s|$)" })) {
    Say ""
    Say "연결된 기기가 없습니다. USB 디버깅과 폰 화면의 허용 팝업을 확인하세요." Red
    return
}

Say ""
Say "=== [5/5] 설치 ===" Cyan
& $adb install -r $apk
if ($LASTEXITCODE -ne 0) { Say "설치 실패" Red; return }

Say ""
Say "설치 완료. 폰에서 RoutineAI 를 열고 아래 순서로 켜주세요." Green
Say "  1) 사용 정보 접근  (필수 - 없으면 데이터가 전혀 안 들어옵니다)" Yellow
Say "  2) 알림 접근       (알림 통계용)" Yellow
Say "  3) 위치 권한       (Wi-Fi 이름 기록용)" Yellow
Say "  그다음 '지금 수집' -> '리포트 생성'" Yellow
Say ""
Say "앱을 바로 실행하려면:" Gray
Say "  $adb shell am start -n com.routineai/.MainActivity" Gray
