"""
부하 테스트용 더미 데이터 생성 스크립트
=========================================
목적: GitHub API Rate Limit 없이 DB 커넥션 풀 고갈 시나리오를 재현하기 위한
      실제 수준의 데이터를 사전 구축합니다.

사용법:
  python seed_data.py            # 기본 (100명 유저, ~50,000건 커밋)
  python seed_data.py --clear    # 기존 더미 데이터 삭제 후 재생성

부하 테스트 타겟 엔드포인트:
  GET /rank/          → _get_top_30_commits_query() : window function + full scan
  GET /group/info     → count_commits_for_current_month() : 그룹 멤버 전체 집계

커넥션 풀 고갈 재현 조건:
  - Commit.commit_date 컬럼에 인덱스 없음 (models.py 확인)
  - 10만 건 이상의 커밋 데이터 → Full Scan 유발
  - k6로 VU 50+ 동시 접속 → pool_size(5) + max_overflow(10) = 15개 커넥션 포화
"""

import argparse
import random
import hashlib
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

from run import app
from app.db import db
from app.models import User, Commit, Group, Participate

KST = ZoneInfo("Asia/Seoul")
TODAY = datetime.now(KST)

# ────────────────────────────────────────────────
# 설정값
# ────────────────────────────────────────────────
NUM_USERS = 100
NUM_GROUPS = 5
COMMITS_PER_USER_CURRENT_MONTH = 400   # 유저당 이번달 커밋 수 → 총 ~40,000건
COMMITS_PER_USER_LAST_MONTH = 100      # 지난달 커밋 (last_month_commit_count 계산용)
HEAVY_GROUP_INDEX = 0                   # 모든 유저가 몰리는 "고부하 그룹" 인덱스

# 백준 문제 레벨 (commit message 패턴: [level] Title: ..., Time: ..., Memory: ... -)
BOJ_LEVELS = [
    "Bronze V", "Bronze IV", "Bronze III", "Bronze II", "Bronze I",
    "Silver V", "Silver IV", "Silver III", "Silver II", "Silver I",
    "Gold V", "Gold IV", "Gold III", "Gold II", "Gold I",
    "Platinum V", "Platinum IV", "Platinum III",
]

BOJ_PROBLEMS = [
    "두 수의 합", "피보나치 수", "소수 판별", "DFS와 BFS", "최단경로",
    "동적 계획법", "이진 탐색", "투 포인터", "유니온 파인드", "세그먼트 트리",
    "트리의 지름", "위상 정렬", "최소 신장 트리", "플로이드 워셜", "벨만 포드",
    "조합론", "확률과 통계", "문자열 처리", "KMP 알고리즘", "트라이",
]

SEED_MARKER = "seed_user"  # 더미 데이터 구분자 (삭제 시 활용)


# ────────────────────────────────────────────────
# 헬퍼 함수
# ────────────────────────────────────────────────
def _fake_sha(user_idx: int, commit_idx: int) -> str:
    """중복 없는 가짜 SHA 생성"""
    raw = f"fake-{user_idx}-{commit_idx}-{random.random()}"
    return hashlib.sha1(raw.encode()).hexdigest()


def _fake_commit_message(level: str, title: str) -> str:
    """커밋 메시지 패턴: [level] Title: title, Time: Xms, Memory: XMB -"""
    time_ms = random.randint(4, 2000)
    mem_mb = random.randint(32, 512)
    return f"[{level}] Title: {title}, Time: {time_ms}ms, Memory: {mem_mb}MB -"


def _random_commit_date(year: int, month: int) -> datetime:
    """지정된 연/월 내의 랜덤 날짜·시각 반환"""
    # 해당 월의 마지막 날 계산
    if month == 12:
        next_month = datetime(year + 1, 1, 1, tzinfo=KST)
    else:
        next_month = datetime(year, month + 1, 1, tzinfo=KST)
    first_day = datetime(year, month, 1, tzinfo=KST)
    delta = next_month - first_day
    return first_day + timedelta(
        days=random.randint(0, delta.days - 1),
        hours=random.randint(0, 23),
        minutes=random.randint(0, 59),
    )


# ────────────────────────────────────────────────
# 데이터 생성
# ────────────────────────────────────────────────
def seed_users(num_users: int) -> list[User]:
    print(f"[1/4] 유저 {num_users}명 생성 중...")
    users = []
    for i in range(1, num_users + 1):
        user = User(
            github_id=f"{SEED_MARKER}_{i:04d}",
            nick_name=f"테스터_{i:04d}",
            repository_name="algorithm-study",
            active="y",
        )
        db.session.add(user)
        users.append(user)
    db.session.flush()  # user_id 확보
    print(f"  → {len(users)}명 생성 완료")
    return users


def seed_groups(users: list[User], num_groups: int) -> list[Group]:
    print(f"[2/4] 그룹 {num_groups}개 생성 중...")
    groups = []
    for i in range(num_groups):
        group = Group(
            group_name=f"부하테스트_그룹_{i + 1}",
            group_pw=None,
            member_maxCnt=len(users) + 1,
            member_counter=0,
            owner=users[i].user_id,
        )
        db.session.add(group)
        groups.append(group)
    db.session.flush()

    # 참여 데이터: 고부하 그룹(index 0)에는 모든 유저, 나머지는 20명씩
    heavy_group = groups[HEAVY_GROUP_INDEX]
    for user in users:
        db.session.add(Participate(group_id=heavy_group.group_id, user_id=user.user_id))
        heavy_group.member_counter += 1

    for idx, group in enumerate(groups[1:], start=1):
        subset = users[idx * 20: idx * 20 + 20] if idx * 20 < len(users) else users[:20]
        for user in subset:
            db.session.add(Participate(group_id=group.group_id, user_id=user.user_id))
            group.member_counter += 1

    db.session.flush()
    print(f"  → {len(groups)}개 그룹, 고부하 그룹({heavy_group.group_name})에 {heavy_group.member_counter}명 배치")
    return groups


def seed_commits(users: list[User]) -> int:
    print(f"[3/4] 커밋 데이터 생성 중 (유저당 이번달 {COMMITS_PER_USER_CURRENT_MONTH}건 + 지난달 {COMMITS_PER_USER_LAST_MONTH}건)...")
    current_year = TODAY.year
    current_month = TODAY.month
    previous_month = current_month - 1 if current_month > 1 else 12
    previous_year = current_year if current_month > 1 else current_year - 1

    total = 0
    BATCH_SIZE = 1000

    for user_idx, user in enumerate(users):
        commit_batch = []

        # 이번달 커밋
        for j in range(COMMITS_PER_USER_CURRENT_MONTH):
            level = random.choice(BOJ_LEVELS)
            title = random.choice(BOJ_PROBLEMS)
            commit_date = _random_commit_date(current_year, current_month)
            sha = _fake_sha(user.user_id, j)
            commit_batch.append({
                "user_id": user.user_id,
                "commit_date": commit_date,
                "commit_url": f"https://github.com/{user.github_id}/algorithm-study/commit/{sha}",
                "title": title,
                "level": level,
                "sha": sha,
            })

        # 지난달 커밋 (rank 쿼리의 last_month_commit_count 계산용)
        for j in range(COMMITS_PER_USER_LAST_MONTH):
            level = random.choice(BOJ_LEVELS)
            title = random.choice(BOJ_PROBLEMS)
            commit_date = _random_commit_date(previous_year, previous_month)
            sha = _fake_sha(user.user_id, COMMITS_PER_USER_CURRENT_MONTH + j)
            commit_batch.append({
                "user_id": user.user_id,
                "commit_date": commit_date,
                "commit_url": f"https://github.com/{user.github_id}/algorithm-study/commit/{sha}",
                "title": title,
                "level": level,
                "sha": sha,
            })

        # 배치 insert
        for start in range(0, len(commit_batch), BATCH_SIZE):
            db.session.bulk_insert_mappings(Commit, commit_batch[start:start + BATCH_SIZE])

        total += len(commit_batch)

        if (user_idx + 1) % 10 == 0:
            db.session.flush()
            print(f"  → {user_idx + 1}/{len(users)} 유저 처리 완료 (누적 {total:,}건)")

    print(f"  → 총 {total:,}건 커밋 생성 완료")
    return total


def clear_seed_data():
    print("[삭제] 기존 더미 데이터 삭제 중...")
    seed_users_q = User.query.filter(User.github_id.like(f"{SEED_MARKER}%")).all()
    if not seed_users_q:
        print("  → 삭제할 더미 데이터 없음")
        return

    user_ids = [u.user_id for u in seed_users_q]

    # CASCADE로 Commit, Participate 자동 삭제됨
    deleted = User.query.filter(User.user_id.in_(user_ids)).delete(synchronize_session=False)

    # 더미 그룹 삭제 (owner가 더미 유저인 경우)
    Group.query.filter(Group.group_name.like("부하테스트_그룹_%")).delete(synchronize_session=False)

    db.session.commit()
    print(f"  → {deleted}명 유저 및 관련 데이터 삭제 완료")


# ────────────────────────────────────────────────
# 메인
# ────────────────────────────────────────────────
def main(clear: bool = False):
    with app.app_context():
        if clear:
            clear_seed_data()
            return

        print("=" * 55)
        print("  부하 테스트용 더미 데이터 생성 시작")
        print(f"  기준 날짜: {TODAY.strftime('%Y-%m-%d')} (KST)")
        print("=" * 55)

        try:
            users = seed_users(NUM_USERS)
            groups = seed_groups(users, NUM_GROUPS)
            total_commits = seed_commits(users)

            print("[4/4] DB 커밋 중...")
            db.session.commit()

            print()
            print("=" * 55)
            print("  생성 완료 요약")
            print(f"  유저:   {len(users):>8,} 명")
            print(f"  그룹:   {len(groups):>8,} 개")
            print(f"  커밋:   {total_commits:>8,} 건")
            print()
            print("  [부하 테스트 방법]")
            print("  1. k6 스크립트로 GET /rank/ 를 VU 50+ 동시 요청")
            print("  2. SQLAlchemy 기본 pool_size=5 → 커넥션 고갈 재현")
            print("  3. SQLALCHEMY_POOL_SIZE=20 설정 후 재테스트 → 개선 확인")
            print("  4. Commit.commit_date 인덱스 추가 후 재테스트 → 쿼리 최적화 확인")
            print("=" * 55)

        except Exception as e:
            db.session.rollback()
            print(f"[오류] 데이터 생성 실패: {e}")
            raise


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="부하 테스트용 더미 데이터 seeder")
    parser.add_argument("--clear", action="store_true", help="더미 데이터 삭제")
    args = parser.parse_args()
    main(clear=args.clear)
