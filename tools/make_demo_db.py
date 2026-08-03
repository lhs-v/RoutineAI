# 데모 DB 생성 도구.
#
# 연결된 폰에서 앱의 실제 DB 를 뽑아 데모 에셋(app/src/main/assets/demo/routine.db)을
# 만들고, 폰의 netstats 원본(dumpsys)에서 SSID 이력을 앱 수집 포맷(net_change)으로
# 변환해 소급 구간을 채운다.
#
# 원칙: 값을 지어내지 않는다. 모든 행은 폰이 실제로 기록한 측정값에서만 나온다.
#  - net_bucket / usage_event / notif_event: 앱 DB 를 그대로 복사
#  - net_change 소급분: dumpsys netstats 의 SSID 별 버킷에서 "그 시간에 어느 망이
#    우세했나" 를 계산해 접속 변화 시점으로 변환. 앱이 그 시점에 설치되어 있었다면
#    recordCurrentNetwork() 가 남겼을 행과 같은 의미다.
#
# 사용:
#   python tools/make_demo_db.py            # adb 로 뽑기 + 백필 + 검증까지 전부
#   python tools/make_demo_db.py --no-pull  # 이미 있는 에셋에 백필만 다시
#
# 데모 에셋은 실제 사용 기록이라 저장소에 올리지 않는다(.gitignore). BUILD.md 참고.

import argparse
import datetime
import os
import re
import sqlite3
import subprocess
import sys
import tempfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSET = os.path.join(REPO, "app", "src", "main", "assets", "demo", "routine.db")
KST = datetime.timezone(datetime.timedelta(hours=9))
PKG = "com.routineai"


def kst(ms):
    return datetime.datetime.fromtimestamp(ms / 1000, KST)


def adb():
    for c in (
        os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk",
                     "platform-tools", "adb.exe"),
        "adb",
    ):
        try:
            subprocess.run([c, "version"], capture_output=True, check=True)
            return c
        except Exception:
            continue
    sys.exit("adb 를 찾을 수 없습니다")


def pull_db(adb_path):
    """앱 DB 를 WAL 포함해 뽑아 단일 파일로 정리한 뒤 에셋 자리에 둔다."""
    tmp = ASSET + ".pull"
    for suffix in ("", "-wal", "-shm"):
        out = subprocess.run(
            [adb_path, "exec-out", "run-as", PKG, "cat", f"databases/routine.db{suffix}"],
            capture_output=True)
        if out.returncode != 0 and suffix == "":
            sys.exit("DB 를 뽑지 못했습니다: " + out.stderr.decode(errors="replace"))
        with open(tmp + suffix, "wb") as f:
            f.write(out.stdout)
    con = sqlite3.connect(tmp)
    con.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    os.makedirs(os.path.dirname(ASSET), exist_ok=True)
    if os.path.exists(ASSET):
        os.remove(ASSET)
    con.execute("VACUUM INTO ?", (ASSET.replace(os.sep, "/"),))
    con.close()
    # sqlite 가 연결을 닫으며 -wal/-shm 을 스스로 지울 수 있다.
    for suffix in ("", "-wal", "-shm"):
        if os.path.exists(tmp + suffix):
            os.remove(tmp + suffix)
    print(f"에셋 갱신: {os.path.getsize(ASSET):,} 바이트")


def dump_netstats(adb_path, path):
    out = subprocess.run([adb_path, "shell", "dumpsys", "netstats", "--full"],
                         capture_output=True)
    if out.returncode != 0 or len(out.stdout) < 1000:
        sys.exit("dumpsys netstats 실패: " + out.stderr.decode(errors="replace"))
    with open(path, "wb") as f:
        f.write(out.stdout)
    print(f"netstats 덤프: {len(out.stdout):,} 바이트 -> {path}")


def parse_netstats(path):
    """
    dumpsys netstats --full 에서 (버킷 시작 ms, 망 라벨) -> 바이트 를 만든다.
    망 라벨은 SSID(와이파이) 또는 "cellular".

    이 기기(Android 16 / One UI)의 Xt stats 형식:
      ident=[{type=1, ratType=COMBINED, wifiNetworkKey="SSID"wpa2-psk, ...}] uid=-1 ...
      ident=[{type=0, ratType=-2, subscriberId=..., ...}] uid=-1 ...
      이어지는 히스토리 줄:  st=<epoch초> rb=<수신> rp=<패킷> tb=<송신> tp=<패킷> op=0
    type=1 이 Wi-Fi(SSID 는 wifiNetworkKey), type=0 이 모바일이다.
    uid=-1(망별 합계)만 존재함을 확인했다 — per-uid 이력이 섞이면 중복 합산되므로,
    형식이 바뀌면 이 함수만 고치면 된다. 아래 단계는 형식과 무관하다.
    """
    buckets = {}  # (bucket_ms, label) -> bytes
    label = None
    ident_re = re.compile(r"ident=\[\{type=(\d+)")
    key_re = re.compile(r'wifiNetworkKey="([^"]*)"')
    hist_re = re.compile(r"^\s*st=(\d+)\s+rb=(\d+)\s+rp=\d+\s+tb=(\d+)")
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            m = ident_re.search(line)
            if m:
                key = key_re.search(line)
                if m.group(1) == "1" and key:
                    label = key.group(1)        # Wi-Fi: SSID 그대로
                elif m.group(1) == "0":
                    label = "cellular"
                else:
                    label = None                # 기타(이더넷 등)는 제외
                continue
            m = hist_re.match(line)
            if m and label is not None:
                st, rb, tb = int(m.group(1)), int(m.group(2)), int(m.group(3))
                ms = st * 1000 if st < 10**12 else st  # 초/밀리초 양쪽 대응
                buckets[(ms, label)] = buckets.get((ms, label), 0) + rb + tb
    return buckets


def backfill(buckets):
    """버킷별 우세 망 -> 접속 변화 행. 앱이 실제로 기록하기 시작한 시점 이전만 채운다.

    경계는 net_change 의 최소 ts 가 아니라 kv.first_sync(설치 시각)다.
    전자로 잡으면 이전 실행이 넣은 소급 행이 경계가 되어 재실행이 불가능해진다.
    recordCurrentNetwork() 가 first_sync 기록보다 몇 초 먼저 돌므로 여유를 둔다.
    """
    con = sqlite3.connect(ASSET)
    row = con.execute("SELECT v FROM kv WHERE k='first_sync'").fetchone()
    first_real = (int(row[0]) - 600_000) if row else float("inf")

    per_start = {}  # bucket_ms -> {label: bytes}
    for (ms, label), b in buckets.items():
        per_start.setdefault(ms, {})[label] = per_start.setdefault(ms, {}).get(label, 0) + b

    rows, cur = [], None
    for ms in sorted(per_start):
        if ms >= first_real:
            break
        label = max(per_start[ms], key=per_start[ms].get)
        state = ("cellular", None) if label == "cellular" else ("wifi", label)
        if state != cur:
            rows.append((ms, state[0], state[1]))
            cur = state

    # 소급분을 다시 만들 수 있도록 이전 소급분은 지우고 넣는다 (실제 수집분은 보존).
    deleted = con.execute("DELETE FROM net_change WHERE ts < ?", (first_real,)).rowcount
    con.executemany("INSERT INTO net_change (ts, kind, ssid) VALUES (?,?,?)", rows)
    con.commit()
    print(f"net_change 백필: {deleted}행 제거, {len(rows)}행 삽입"
          + (f" ({kst(rows[0][0]):%m-%d} ~ {kst(rows[-1][0]):%m-%d})" if rows else ""))
    return con


# 데모에서 빼기로 한 앱. 사용 이벤트·알림·통신량 어디에도 안 나오게 지운다.
# 도구가 지우므로 재추출해도 다시 적용된다.
EXCLUDE_PKGS = [
    "com.example.browser",
    "com.example.social",
]

# 삭제된 앱은 pm 으로 uid 를 되돌릴 수 없다. 기기 화면의 앱별 통신량과 DB 의
# uid 별 합계를 대조해 확정한 값 (앱 A=10001, 앱 B=10002 — 이웃
# uid 들도 전부 화면 값과 일치함을 확인).
EXCLUDE_UIDS = [10001, 10002]


def exclude_pkgs(adb_path):
    """데모 제외 앱의 흔적을 모든 테이블에서 지운다. 통신량은 uid 로 지운다."""
    con = sqlite3.connect(ASSET)
    uids = list(EXCLUDE_UIDS)
    out = subprocess.run([adb_path, "shell", "pm", "list", "packages", "-U"],
                         capture_output=True)
    for line in out.stdout.decode(errors="replace").splitlines():
        m = re.match(r"package:(\S+) uid:(\d+)", line.strip())
        if m and m.group(1) in EXCLUDE_PKGS:
            uids.append(int(m.group(2)))

    qmarks = ",".join("?" * len(EXCLUDE_PKGS))
    a = con.execute(f"DELETE FROM usage_event WHERE pkg IN ({qmarks})", EXCLUDE_PKGS).rowcount
    b = con.execute(f"DELETE FROM notif_event WHERE pkg IN ({qmarks})", EXCLUDE_PKGS).rowcount
    c = 0
    if uids:
        c = con.execute(
            "DELETE FROM net_bucket WHERE uid IN (%s)" % ",".join("?" * len(uids)),
            uids).rowcount
    con.commit(); con.close()
    print(f"데모 제외 앱 정리: usage {a}, notif {b}, net {c}행 "
          f"({', '.join(p.split('.')[-1] for p in EXCLUDE_PKGS)})")


def backfill_bt(adb_path):
    """
    dumpsys bluetooth_manager 의 연결 이력을 bt_event 로 변환한다.

    시스템 링버퍼라 대개 하루 이틀치만 남아 있다 — 있는 만큼만 넣는다.
    같은 순간의 connectionStateChanged 가 프로필 로그마다(A2DP·HFP·LE)
    중복 출력되므로 (초, 기기, 상태) 로 접는다. MAC 꼬리는 본딩 목록의
    이름으로 되돌리고 MAC 자체는 저장하지 않는다.
    """
    out = subprocess.run([adb_path, "shell", "dumpsys", "bluetooth_manager"],
                         capture_output=True)
    text = out.stdout.decode(errors="replace")
    if out.returncode != 0 or len(text) < 1000:
        print("bluetooth_manager 덤프 실패 — BT 백필 건너뜀")
        return

    # 본딩 목록에서 MAC 꼬리(뒤 5자) -> 이름
    names = {}
    # 이름은 줄 마지막 ']' 뒤에 온다 — 탐욕 매칭으로 마지막 ']' 까지 소비해야
    # "[DUAL] [ACL ...] 이름" 에서 브래킷 노이즈가 이름에 섞이지 않는다.
    for m in re.finditer(
        r"XX:XX:XX:XX:([0-9A-F]{2}:[0-9A-F]{2})\(Public \)[^\n]*\]\s+([^\n|]+?)\s*$",
        text, re.M,
    ):
        name = m.group(2).strip()
        if name and not name.startswith("XX:"):
            names.setdefault(m.group(1), name)

    year = datetime.datetime.now(KST).year
    rows = {}
    for m in re.finditer(
        r"(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})\.\d+ connectionStateChanged: "
        r"device: XX:XX:XX:XX:([0-9A-F]{2}:[0-9A-F]{2}).*toState: STATE_(CONNECTED|DISCONNECTED)\b",
        text,
    ):
        mo, d, h, mi, s, tail, state = m.groups()
        dt = datetime.datetime(year, int(mo), int(d), int(h), int(mi), int(s), tzinfo=KST)
        ts = int(dt.timestamp() * 1000)
        action = "connect" if state == "CONNECTED" else "disconnect"
        rows[(ts // 1000, tail, action)] = (
            ts, action, names.get(tail, "기기(" + tail[-2:] + ")"), 0)

    con = sqlite3.connect(ASSET)
    # 재실행 대비: 이미 있는 (ts, action, name) 은 다시 넣지 않는다.
    seen = set(con.execute("SELECT ts, action, name FROM bt_event").fetchall())
    fresh = [r for r in sorted(rows.values()) if (r[0], r[1], r[2]) not in seen]
    existing = len(seen)
    con.executemany(
        "INSERT INTO bt_event (ts, action, name, majorClass) VALUES (?,?,?,?)", fresh)
    con.commit(); con.close()
    if rows:
        lo = min(r[0] for r in rows.values()); hi = max(r[0] for r in rows.values())
        print(f"BT 백필: 새로 {len(fresh)}행 / 덤프에 {len(rows)}행 "
              f"({kst(lo):%m-%d %H:%M} ~ {kst(hi):%m-%d %H:%M}, 기존 {existing}행) "
              f"— 시스템 버퍼가 짧아 하루 안팎만 남는다")
    else:
        print("BT 이력 없음")


def synth_shopping():
    """
    쇼핑 페어 로그를 합성한다. --synth-shopping 으로만 실행된다.

    - 평일 저녁: 쿠팡 ↔ 네이버 쇼핑 (비교 쇼핑 — 한쪽을 오래 보고 곧장 건너감)
    - 주말 오후: 당근 ↔ 번개장터 (중고 탐색)

    목적: ① 갈아타기(medianSwitchSeconds) 지표의 실증 — 체류가 길어 왕복
    주기(60초)로는 문턱을 넘지만 갈아타기 몇 초로 살아나는 페어 형태,
    ② 같은 '쇼핑' 행동이 요일 축으로 갈리는 두 페어 — 심층 분석의 조건
    발견 재료. 시드 고정이라 재실행해도 같은 행(유니크 인덱스로 멱등).
    """
    import random
    from datetime import datetime
    rng = random.Random(42)
    RESUMED, SCREEN_ON, SCREEN_OFF = 1, 15, 16
    LAUNCHER = "com.sec.android.app.launcher"
    BOUTS = [
        ("2026-07-21", 19, 48, "com.coupang.mobile", "com.nhn.android.shoppingn"),
        ("2026-07-22", 20, 12, "com.coupang.mobile", "com.nhn.android.shoppingn"),
        ("2026-07-23", 19, 55, "com.nhn.android.shoppingn", "com.coupang.mobile"),
        ("2026-07-28", 20, 31, "com.coupang.mobile", "com.nhn.android.shoppingn"),
        ("2026-07-30", 19, 42, "com.coupang.mobile", "com.nhn.android.shoppingn"),
        ("2026-07-25", 14, 20, "com.towneers.www", "kr.co.quicket"),
        ("2026-07-26", 15, 5, "kr.co.quicket", "com.towneers.www"),
        ("2026-08-01", 14, 47, "com.towneers.www", "kr.co.quicket"),
    ]
    con = sqlite3.connect(ASSET)
    cur = con.cursor()

    def free_slot(ts, span_ms):
        for _ in range(8):
            n = cur.execute(
                "SELECT COUNT(*) FROM usage_event WHERE ts BETWEEN ? AND ?",
                (ts - 120_000, ts + span_ms + 120_000)).fetchone()[0]
            if n == 0:
                return ts
            ts += 15 * 60_000
        return ts

    ins = 0
    for date, h, m, a, b_ in BOUTS:
        t = free_slot(int(datetime.fromisoformat(
            f"{date}T{h:02d}:{m:02d}:00").timestamp() * 1000), 12 * 60_000)
        rows = [(t, SCREEN_ON, LAUNCHER)]
        t += rng.randint(1500, 3000)
        for i in range(rng.randint(5, 7)):
            rows.append((t, RESUMED, [a, b_][i % 2]))
            t += rng.randint(40_000, 95_000)      # 상품을 훑는 긴 체류
            rows.append((t, RESUMED, LAUNCHER))    # 홈은 3~8초 — 통로일 뿐
            t += rng.randint(3_000, 8_000)
        rows.append((t + rng.randint(1000, 3000), SCREEN_OFF, LAUNCHER))
        for ts, typ, pkg in rows:
            cur.execute("INSERT OR IGNORE INTO usage_event(ts, type, pkg, cls) "
                        "VALUES(?,?,?,NULL)", (ts, typ, pkg))
            ins += cur.rowcount
    con.commit(); con.close()
    print(f"쇼핑 합성 {ins}행")


def synth_bt():
    """
    사용자가 밝힌 습관("유튜브 뮤직 전엔 보통 이어폰을 연결, 유튜브는 종종")을
    데모 BT 데이터로 합성한다. --synth-bt 로만 실행된다.

    시각을 지어내지 않는다 — 데모 DB 에 실제로 있는 두 앱의 실행 시각에 앵커해
    그 직전(8~90초)에 연결을 심는다. 연결 상태를 시뮬레이션해서, 이미 연결 중이면
    connect 를 다시 만들지 않는다(실제 리시버가 보게 될 모습과 같게).
    합성 행은 majorClass=-1 로 표시한다 — 실측과 항상 구분되고, 재실행 시
    이 표시로 지우고 다시 만들므로 멱등이다. 실측 BT가 있는 날(당일 덤프)은
    건드리지 않는다.
    """
    import random
    rng = random.Random(42)
    MUSIC = "com.google.android.apps.youtube.music"
    TUBE = "com.google.android.youtube"
    NAME = "Galaxy Buds3 Pro"
    BOUT_GAP = 45 * 60 * 1000

    con = sqlite3.connect(ASSET)
    real_days = {kst(ts).date() for (ts,) in
                 con.execute("SELECT ts FROM bt_event WHERE majorClass != -1")}
    # 연쇄는 "연결 뒤 처음 연 앱"과 짝지어지므로, 연결과 앵커 앱 사이에
    # 다른 앱 실행이 끼면 안 된다. 낄 자리 검사는 Chains 와 같은 필터를 써야
    # 한다 — 런처·시스템·키보드를 포함하면 "홈 → 뮤직" 흐름의 앵커가
    # 전부 "직전에 실행 있음"으로 퇴짜맞는다.
    all_ts = [ts for ts, pkg in con.execute(
        "SELECT ts, pkg FROM usage_event WHERE type=1 ORDER BY ts")
        if pkg not in ("com.sec.android.app.launcher", "android",
                       "com.android.systemui", "com.android.intentresolver",
                       "com.android.permissioncontroller",
                       "com.google.android.permissioncontroller")
        and "inputmethod" not in pkg and "honeyboard" not in pkg]
    import bisect

    def bouts_of(pkg):
        out = []
        for (ts,) in con.execute(
            "SELECT ts FROM usage_event WHERE type=1 AND pkg=? ORDER BY ts", (pkg,)):
            if out and ts - out[-1][1] <= BOUT_GAP:
                out[-1][1] = ts
            else:
                out.append([ts, ts])
        return out

    # 앱별로 구간을 따로 잡는다 — 합치면 뮤직 실행이 유튜브 사용 중에 섞여
    # "뮤직이 첫 앱인 구간"이 드물어지고, 사용자가 말한 습관과 반대로 나온다.
    # 확률은 사용자가 밝힌 습관의 기울기다: 뮤직 "보통", 유튜브 "종종".
    # 유튜브는 사용 빈도가 4배라 확률을 낮게 둬야 연쇄 횟수·조건부 비율
    # 양쪽에서 뮤직이 우세해진다 — 0.4 로 두면 횟수가 유튜브로 쏠려
    # "종종"이 "보통"을 덮는다 (실제로 그랬다).
    anchors = sorted(
        [(s, e, MUSIC, 0.85) for s, e in bouts_of(MUSIC)] +
        [(s, e, TUBE, 0.18) for s, e in bouts_of(TUBE)]
    )

    rows = []
    open_until = None
    for start, end, pkg, p in anchors:
        if kst(start).date() in real_days:
            continue
        if open_until is not None and start <= open_until:
            # 이미 연결 중 — connect 없이 연결만 연장 (실제 리시버와 같은 모습)
            open_until = max(open_until, end + rng.randint(3, 10) * 60_000)
            continue
        if rng.random() >= p:
            continue
        # 직전의 다른 앱 실행보다 뒤에 connect 를 둬야 이 앱과 짝지어진다.
        i = bisect.bisect_left(all_ts, start)
        prev_ts = all_ts[i - 1] if i > 0 else 0
        max_gap = min(45_000, start - prev_ts - 2_000)
        if max_gap < 3_000:
            continue    # 직전까지 폰을 쓰고 있던 구간 — 앵커 불가, 건너뜀
        # 이어폰을 끼고 곧장 앱을 여는 습관이므로 간격은 몇 초대에 몰린다.
        # 삼각분포(최빈 6초, 꼬리 ~45초): 폰을 꺼내 잠금 해제하는 시간 정도.
        connect = start - min(int(rng.triangular(3_000, 45_000, 6_000)), int(max_gap))
        if open_until is not None:
            rows.append((min(open_until, connect - rng.randint(60, 300) * 1000),
                         "disconnect", NAME, -1))
        rows.append((connect, "connect", NAME, -1))
        # 청취가 끝나면 곧 벗는다. 오래 끌수록 이후의 무관한 앱 실행들이
        # "연결 중"으로 잡혀 조건부 비율이 물타기된다.
        open_until = end + rng.randint(3, 10) * 60_000
    if open_until is not None:
        rows.append((open_until, "disconnect", NAME, -1))

    deleted = con.execute("DELETE FROM bt_event WHERE majorClass = -1").rowcount
    con.executemany(
        "INSERT INTO bt_event (ts, action, name, majorClass) VALUES (?,?,?,?)",
        sorted(rows))
    con.commit(); con.close()
    n_con = sum(1 for r in rows if r[1] == "connect")
    print(f"BT 합성(--synth-bt): 연결 {n_con}건 포함 {len(rows)}행 "
          f"(이전 합성 {deleted}행 교체) — 실제 실행 시각 앵커, majorClass=-1 표시")


def trim_to_events(con):
    """모든 축의 시작점을 사용 이벤트의 시작점으로 통일한다.

    통신량·SSID 는 몇 달 소급되지만 사용 이벤트는 며칠뿐이라, 그대로 두면
    지표마다 관측 창이 달라진다. 데모에서는 "모든 정보가 다 있는 구간"만 남겨
    시작점을 하나로 맞춘다. 긴 소급을 보고 싶으면 --keep-history 로 건너뛴다.
    """
    start = con.execute("SELECT MIN(ts) FROM usage_event").fetchone()[0]
    if start is None:
        return
    # 경계 직전의 마지막 상태를 경계 시점으로 이월한다. 그냥 지우면
    # 창 시작부터 첫 변화까지의 접속 상태가 비어 버린다.
    carry = con.execute(
        "SELECT kind, ssid FROM net_change WHERE ts < ? ORDER BY ts DESC LIMIT 1",
        (start,)).fetchone()
    a = con.execute("DELETE FROM net_bucket WHERE bucketStart < ?", (start,)).rowcount
    b = con.execute("DELETE FROM net_change WHERE ts < ?", (start,)).rowcount
    c = con.execute("DELETE FROM notif_event WHERE ts < ?", (start,)).rowcount
    # 새로 붙는 소스도 같은 시작점을 지킨다. 특히 Health Connect 는 권한 승인 시
    # 30일을 소급하므로, 자르지 않으면 사용 이벤트보다 앞선 구간이 데모에 샌다.
    d = con.execute("DELETE FROM bt_event WHERE ts < ?", (start,)).rowcount
    e = con.execute("DELETE FROM health_session WHERE tsStart < ?", (start,)).rowcount
    if carry:
        con.execute("INSERT INTO net_change (ts, kind, ssid) VALUES (?,?,?)",
                    (start, carry[0], carry[1]))
    con.commit()
    con.execute("VACUUM")
    print(f"시작점 통일: {kst(start):%m-%d %H:%M} 이전 제거 "
          f"(net_bucket {a}, net_change {b}, notif {c}, bt {d}, health {e}행)")


def verify(con):
    """places()/외출 지표가 쓰는 방식으로 재집계해 눈으로 검증할 요약을 낸다."""
    rows = con.execute("SELECT ts, kind, ssid FROM net_change ORDER BY ts").fetchall()
    hours, nights = {}, {}
    for i, (ts, kind, ssid) in enumerate(rows):
        end = rows[i + 1][0] if i + 1 < len(rows) else ts
        key = ssid if kind == "wifi" and ssid else kind
        hours[key] = hours.get(key, 0) + (end - ts) / 3_600_000
        t = ts
        while t < end:
            if kind == "wifi" and 0 <= kst(t).hour <= 5:
                nights.setdefault(key, set()).add(kst(t).date())
            t += 3_600_000
    print("검증 — 별칭별 체류 (앱 places() 와 같은 계산):")
    for k in sorted(hours, key=hours.get, reverse=True):
        print(f"  {k:24s} {hours[k]:7.0f} h   밤 {len(nights.get(k, [])):3d}일")
    con.close()


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--no-pull", action="store_true", help="에셋 갱신 없이 백필만")
    ap.add_argument("--dump", help="이미 받아둔 dumpsys netstats 파일 사용")
    ap.add_argument("--keep-history", action="store_true",
                    help="시작점 통일(사용 이벤트 이전 구간 제거)을 건너뛴다")
    ap.add_argument("--synth-bt", action="store_true",
                    help="사용자가 밝힌 이어폰 습관을 실행 시각에 앵커해 합성 (majorClass=-1 표시)")
    ap.add_argument("--synth-shopping", action="store_true",
                    help="쇼핑 페어(평일 저녁 쿠팡↔네이버쇼핑, 주말 당근↔번개장터) 합성")
    args = ap.parse_args()

    a = None if (args.no_pull and args.dump) else adb()
    if not args.no_pull:
        pull_db(a)
    # 덤프를 assets 안에 두면 APK 에 패키징된다. 반드시 밖에 둔다.
    dump_path = args.dump or os.path.join(tempfile.gettempdir(), "routineai-netstats.txt")
    if not args.dump:
        dump_netstats(a, dump_path)
    b = parse_netstats(dump_path)
    if not b:
        sys.exit("덤프에서 버킷을 하나도 읽지 못했습니다 — parse_netstats() 의 "
                 "정규식을 이 기기의 출력 형식에 맞춰 조정하세요: " + dump_path)
    print(f"버킷 {len(b):,}개, 망 {len(set(k[1] for k in b)):d}종")
    con = backfill(b)
    if not args.keep_history:
        trim_to_events(con)
    verify(con)
    exclude_pkgs(a or adb())
    backfill_bt(a or adb())
    if args.synth_bt:
        synth_bt()
    if args.synth_shopping:
        synth_shopping()
