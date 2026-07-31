#!/usr/bin/env bash
# 동시성 1에서의 순수 쿼리 비용 측정 (보조 측정)
#
# 왜 필요한가:
#   본 측정(VU 30)에서 db 팔은 50k/100k 규모부터 HikariCP 풀(20개)이 포화되어
#   대부분의 요청이 3초 커넥션 타임아웃으로 실패했다. 그래서 p95가
#   "느린 성공"과 "빠른 실패"의 혼합값이 되어 지연 지표로 쓸 수 없다.
#
#   "그건 풀 크기 문제지 쿼리 문제가 아니지 않냐"는 반론을 막으려면
#   경합이 없는 상태에서 쿼리 자체의 비용을 따로 재야 한다.
#   VU 1, 순차 요청 → 풀 대기가 발생할 수 없다.
#
# 실행: bash bench/single_request.sh

set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"
export DB_USER=reminder DB_PASSWORD=reminder DB_NAME=reminder GITHUB_TOKEN=unused

MYSQL=(docker exec -i reminder-mysql mysql -ureminder -preminder reminder)
REDIS=(docker exec -i reminder-redis redis-cli)
JAR="server/build/libs/server-0.0.1-SNAPSHOT.jar"
RANK_KEY="rank:commit:$(date +%Y%m)"
FAR_CRON="0 0 0 1 1 ?"
FAST_CRON="*/5 * * * * ?"
OUT="bench/results/single_request.txt"

start_server() { RANKING_REDIS_ENABLED="$1" RANKING_SYNC_CRON="$2" java -jar "$JAR" >/dev/null 2>&1 & SERVER_PID=$!; }
stop_server()  { [ -n "${SERVER_PID:-}" ] || return 0; kill "$SERVER_PID" 2>/dev/null || true; wait "$SERVER_PID" 2>/dev/null || true; SERVER_PID=""; sleep 2; }
trap stop_server EXIT
wait_for_server() { for _ in $(seq 1 90); do curl -fsS localhost:8080/rank >/dev/null 2>&1 && return 0; sleep 1; done; return 1; }

# 워밍업 5회 후 10회를 재서 중앙값 — JIT/캐시 편차를 줄인다
median_ms() {  # $1=url
  for _ in $(seq 1 5); do curl -s -o /dev/null "$1"; done
  for _ in $(seq 1 10); do curl -s -o /dev/null -w "%{time_total}\n" "$1"; done \
    | sort -n | awk '{a[NR]=$1} END {printf "%.1f", ((a[5]+a[6])/2)*1000}'
}

: > "$OUT"
printf "%-10s %-7s %12s %12s\n" "유저수" "팔" "top30(ms)" "user_rank(ms)" | tee -a "$OUT"

for users in 10000 50000 100000; do
  "${REDIS[@]}" FLUSHALL >/dev/null
  { echo "SET @target_users = ${users};"; cat bench/seed_scale.sql; } | "${MYSQL[@]}" >/dev/null

  # Redis 팔을 위한 워밍 (앱의 실제 스케줄러 경로)
  start_server true "$FAST_CRON"; wait_for_server
  for _ in $(seq 1 60); do
    [ "$("${REDIS[@]}" ZCARD "$RANK_KEY" | tr -d '\r')" = "$users" ] && break
    sleep 2
  done
  stop_server

  for arm in db redis; do
    [ "$arm" = "db" ] && enabled=false || enabled=true
    start_server "$enabled" "$FAR_CRON"; wait_for_server
    t30=$(median_ms "http://localhost:8080/rank")
    tur=$(median_ms "http://localhost:8080/rank/users?userId=7")
    stop_server
    printf "%-10s %-7s %12s %12s\n" "$users" "$arm" "$t30" "$tur" | tee -a "$OUT"
  done
done

echo "" && echo "결과: $OUT"
