#!/usr/bin/env bash
# 벤치마크 공통 함수
#
# 핵심은 assert_data_is_measurable() 이다.
# 시드 데이터는 "이번 달" 커밋이어야 랭킹에 잡히는데, 여기가 어긋나도
# HTTP 200 에 빈 배열이 나갈 뿐 에러가 나지 않는다. 그러면 두 조건 모두
# 0건을 재게 되어 측정이 조용히 무의미해진다.
#
# 실제로 겪었다: MySQL 컨테이너(UTC)와 앱(Asia/Seoul)의 "이번 달"이 갈려
# 어제 시드한 데이터가 오늘 전부 지난달로 분류됐다.
# 그때 아무것도 실패하지 않았다는 게 문제였다.

RANK_KEY_MONTH="$(TZ=Asia/Seoul date +%Y%m)"
RANK_KEY="rank:commit:${RANK_KEY_MONTH}"

# 측정 가능한 상태인지 확인하고, 아니면 즉시 중단한다.
# 조용히 0을 재느니 시끄럽게 죽는 편이 낫다.
assert_data_is_measurable() {
  local body top_count
  body=$(curl -fsS "http://localhost:8080/rank" 2>/dev/null) || {
    echo "✗ /rank 호출 실패 — 서버가 준비되지 않았습니다" >&2; return 1; }

  if [ "$body" = "[]" ]; then
    echo "✗ /rank 가 빈 배열입니다." >&2
    echo "  시드 데이터가 앱 기준 '이번 달'(${RANK_KEY_MONTH}) 밖에 있을 수 있습니다." >&2
    echo "  bench/seed_scale.sql 을 다시 실행하세요." >&2
    return 1
  fi

  # 1등의 커밋 수가 0이면 데이터는 있으나 이번 달 집계가 0건이라는 뜻
  top_count=$(printf '%s' "$body" | sed -n 's/.*"commitCount":\([0-9]*\).*/\1/p' | head -1)
  if [ -z "$top_count" ] || [ "$top_count" -eq 0 ] 2>/dev/null; then
    echo "✗ 1등의 이번 달 커밋 수가 0입니다 — 집계할 데이터가 없습니다." >&2
    echo "  시드를 다시 하세요 (bench/seed_scale.sql)." >&2
    return 1
  fi

  echo "  ✓ 측정 가능 (1등 커밋 수: ${top_count})"
}

# Redis 조건이 정말 Redis 를 타는지 확인.
# 비어 있으면 getTop30() 이 에러 없이 DB 폴백을 타서 두 조건이 같은 걸 재게 된다.
assert_redis_warm() {  # $1=expected_users
  local card
  card=$(docker exec -i reminder-redis redis-cli ZCARD "$RANK_KEY" 2>/dev/null | tr -d '\r')
  if [ "${card:-0}" != "$1" ]; then
    echo "✗ Redis 랭킹이 채워지지 않았습니다 (ZCARD=${card:-0}, 기대=$1)" >&2
    echo "  이 상태로 재면 Redis 조건이 조용히 DB 폴백을 타서 측정이 무의미해집니다." >&2
    return 1
  fi
  echo "  ✓ Redis 워밍 확인 (ZCARD=$card)"
}

# 측정이 끝나면 데이터를 치운다.
# 남겨두면 다음 달에 "지난달 데이터"가 되어 조용히 0건 측정을 유발한다.
teardown_data() {
  echo "데이터 정리..."
  docker exec -i reminder-redis redis-cli FLUSHALL >/dev/null 2>&1 || true
  docker exec -i reminder-mysql mysql -ureminder -preminder reminder 2>/dev/null <<'SQL' || true
SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE participate;
TRUNCATE TABLE commits;
TRUNCATE TABLE `groups`;
TRUNCATE TABLE users;
SET FOREIGN_KEY_CHECKS = 1;
SQL
  echo "  ✓ 정리 완료"
}
