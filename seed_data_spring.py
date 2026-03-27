"""
Spring Boot 스키마용 부하 테스트 더미 데이터 생성
=================================================
사전 조건:
  docker compose up -d  (MySQL 실행 중)
  pip install pymysql python-dotenv

사용법:
  python seed_data_spring.py            # 생성 (100명, ~50,000건)
  python seed_data_spring.py --clear    # 더미 데이터만 삭제

k6 실행:
  k6 run k6/k6_spring.js
"""

import argparse
import hashlib
import os
import random
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

import pymysql
from dotenv import load_dotenv

load_dotenv()

KST = ZoneInfo("Asia/Seoul")
TODAY = datetime.now(KST)

NUM_USERS = 100
NUM_GROUPS = 5
COMMITS_CURRENT_MONTH = 400
COMMITS_LAST_MONTH = 100
SEED_MARKER = "seed_user"

BOJ_LEVELS = [
    "BRONZE", "BRONZE", "BRONZE",
    "SILVER", "SILVER", "SILVER",
    "GOLD", "GOLD", "GOLD", "GOLD",
    "PLATINUM", "PLATINUM",
    "DIAMOND", "UNRATED",
]

BOJ_PROBLEMS = [
    "두 수의 합", "피보나치 수", "소수 판별", "DFS와 BFS", "최단경로",
    "동적 계획법", "이진 탐색", "투 포인터", "유니온 파인드", "세그먼트 트리",
    "트리의 지름", "위상 정렬", "최소 신장 트리", "플로이드 워셜", "벨만 포드",
]


def connect():
    return pymysql.connect(
        host=os.getenv("DB_HOST", "localhost"),
        port=int(os.getenv("DB_PORT", 3306)),
        user=os.getenv("DB_USER", "reminder"),
        password=os.getenv("DB_PASSWORD", "reminder"),
        database=os.getenv("DB_NAME", "reminder"),
        charset="utf8mb4",
    )


def fake_sha(user_idx: int, commit_idx: int) -> str:
    raw = f"seed-{user_idx}-{commit_idx}-{random.random()}"
    return hashlib.sha1(raw.encode()).hexdigest()


def random_date(year: int, month: int) -> datetime:
    if month == 12:
        end = datetime(year + 1, 1, 1, tzinfo=KST)
    else:
        end = datetime(year, month + 1, 1, tzinfo=KST)
    start = datetime(year, month, 1, tzinfo=KST)
    delta = int((end - start).total_seconds())
    return start + timedelta(seconds=random.randint(0, delta - 1))


def seed(conn):
    cur = conn.cursor()
    now = datetime.now(KST).strftime("%Y-%m-%d %H:%M:%S.%f")

    # ── 유저 생성 ─────────────────────────────────────────────
    print(f"[1/4] 유저 {NUM_USERS}명 생성 중...")
    cur.executemany(
        """INSERT INTO users (github_id, nickname, repository_name, active, created_at, updated_at)
           VALUES (%s, %s, %s, TRUE, %s, %s)""",
        [(f"{SEED_MARKER}_{i:04d}", f"테스터_{i:04d}", "algorithm-study", now, now)
         for i in range(1, NUM_USERS + 1)],
    )
    conn.commit()

    cur.execute(f"SELECT user_id FROM users WHERE github_id LIKE '{SEED_MARKER}%' ORDER BY user_id")
    user_ids = [row[0] for row in cur.fetchall()]
    print(f"  → {len(user_ids)}명 완료 (user_id: {user_ids[0]}~{user_ids[-1]})")

    # ── 그룹 생성 ─────────────────────────────────────────────
    print(f"[2/4] 그룹 {NUM_GROUPS}개 생성 중...")
    cur.executemany(
        """INSERT INTO `groups` (group_name, group_pw, member_max_count, owner_id, member_counter, created_at, updated_at)
           VALUES (%s, NULL, %s, %s, 0, %s, %s)""",
        [(f"부하테스트_그룹_{i + 1}", NUM_USERS + 1, user_ids[i], now, now)
         for i in range(NUM_GROUPS)],
    )
    conn.commit()

    cur.execute("SELECT group_id FROM `groups` WHERE group_name LIKE '부하테스트_그룹_%' ORDER BY group_id")
    group_ids = [row[0] for row in cur.fetchall()]

    # 고부하 그룹: 전체 유저 참가
    heavy_group_id = group_ids[0]
    cur.executemany(
        "INSERT INTO participate (group_id, user_id, created_at, updated_at) VALUES (%s, %s, %s, %s)",
        [(heavy_group_id, uid, now, now) for uid in user_ids],
    )
    cur.execute(
        "UPDATE `groups` SET member_counter = %s WHERE group_id = %s",
        (len(user_ids), heavy_group_id),
    )

    # 나머지 그룹: 20명씩
    for idx, gid in enumerate(group_ids[1:], start=1):
        subset = user_ids[idx * 20: idx * 20 + 20] or user_ids[:20]
        cur.executemany(
            "INSERT INTO participate (group_id, user_id, created_at, updated_at) VALUES (%s, %s, %s, %s)",
            [(gid, uid, now, now) for uid in subset],
        )
        cur.execute("UPDATE `groups` SET member_counter = %s WHERE group_id = %s", (len(subset), gid))

    conn.commit()
    print(f"  → {len(group_ids)}개 그룹, 고부하 그룹에 {len(user_ids)}명 배치")

    # ── 커밋 생성 ─────────────────────────────────────────────
    cur_year = TODAY.year
    cur_month = TODAY.month
    prev_month = cur_month - 1 if cur_month > 1 else 12
    prev_year = cur_year if cur_month > 1 else cur_year - 1

    print(f"[3/4] 커밋 생성 중 (유저당 이번달 {COMMITS_CURRENT_MONTH}건 + 지난달 {COMMITS_LAST_MONTH}건)...")
    total = 0
    BATCH = 1000

    for i, uid in enumerate(user_ids):
        rows = []
        for j in range(COMMITS_CURRENT_MONTH):
            sha = fake_sha(uid, j)
            d = random_date(cur_year, cur_month).strftime("%Y-%m-%d %H:%M:%S")
            rows.append((uid, d, f"https://github.com/seed/algorithm/commit/{sha}",
                         random.choice(BOJ_PROBLEMS), random.choice(BOJ_LEVELS), sha))

        for j in range(COMMITS_LAST_MONTH):
            sha = fake_sha(uid, COMMITS_CURRENT_MONTH + j)
            d = random_date(prev_year, prev_month).strftime("%Y-%m-%d %H:%M:%S")
            rows.append((uid, d, f"https://github.com/seed/algorithm/commit/{sha}",
                         random.choice(BOJ_PROBLEMS), random.choice(BOJ_LEVELS), sha))

        for start in range(0, len(rows), BATCH):
            cur.executemany(
                """INSERT INTO commits (user_id, commit_date, commit_url, title, level, sha)
                   VALUES (%s, %s, %s, %s, %s, %s)
                   ON DUPLICATE KEY UPDATE sha = sha""",
                rows[start:start + BATCH],
            )

        total += len(rows)
        if (i + 1) % 10 == 0:
            conn.commit()
            print(f"  → {i + 1}/{len(user_ids)} 유저 처리 완료 (누적 {total:,}건)")

    conn.commit()
    print(f"  → 총 {total:,}건 완료")

    # ── k6 ID 범위 출력 ───────────────────────────────────────
    print()
    print("=" * 50)
    print("  생성 완료")
    print(f"  유저:   {len(user_ids):>8,} 명")
    print(f"  그룹:   {len(group_ids):>8,} 개")
    print(f"  커밋:   {total:>8,} 건")
    print()
    print("  k6 실행:")
    print(f"  k6 run -e USER_ID_START={user_ids[0]} -e USER_ID_END={user_ids[-1]} k6/k6_spring.js")
    print("=" * 50)

    cur.close()


def clear(conn):
    cur = conn.cursor()
    print("[삭제] 더미 데이터 삭제 중...")

    cur.execute(f"SELECT user_id FROM users WHERE github_id LIKE '{SEED_MARKER}%'")
    user_ids = [row[0] for row in cur.fetchall()]
    if not user_ids:
        print("  → 삭제할 데이터 없음")
        return

    ids = ",".join(str(i) for i in user_ids)
    cur.execute(f"DELETE FROM commits WHERE user_id IN ({ids})")
    cur.execute(f"DELETE FROM participate WHERE user_id IN ({ids})")
    cur.execute("DELETE FROM `groups` WHERE group_name LIKE '부하테스트_그룹_%'")
    cur.execute(f"DELETE FROM users WHERE user_id IN ({ids})")
    conn.commit()

    print(f"  → {len(user_ids)}명 및 관련 데이터 삭제 완료")
    cur.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--clear", action="store_true")
    args = parser.parse_args()

    conn = connect()
    try:
        if args.clear:
            clear(conn)
        else:
            seed(conn)
    finally:
        conn.close()
