#!/usr/bin/env bash
# 랭킹 A/B 측정 자동 실행
#
# 규모 3개(1만/5만/10만 유저) x 팔 2개(Redis on/off) = 6회 측정.
#
# 바꾸는 변수는 딱 하나: ranking.redis.enabled
# 코드, DB, 시드 데이터, VU 수, 지속 시간, 엔드포인트 구성은 전부 고정.
#
# 회차마다 서버를 새로 띄운다. ranking.redis.enabled 는 기동 시 주입되는
# 프로퍼티라 한 프로세스에서 두 팔을 섞을 수 없고, 섞더라도 JIT/커넥션풀
# 워밍 상태가 뒤엉켜 비교가 오염된다.
#
# 사전 조건: docker compose up -d / k6 설치 / JDK 21
# 실행: bash bench/run_benchmark.sh

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"

# 앱이 요구하는 값들 (.env 없이 직접 주입)
export DB_USER=reminder
export DB_PASSWORD=reminder
export DB_NAME=reminder
export GITHUB_TOKEN=unused-in-benchmark

source bench/lib.sh   # assert_data_is_measurable / assert_redis_warm / teardown_data / RANK_KEY

RESULT_DIR="bench/results"
mkdir -p "$RESULT_DIR"

MYSQL=(docker exec -i reminder-mysql mysql -ureminder -preminder reminder)
REDIS=(docker exec -i reminder-redis redis-cli)
JAR="server/build/libs/server-0.0.1-SNAPSHOT.jar"

# 측정 중 스케줄러가 끼어들면 100k ZSET 전체를 다시 읽어 지연이 튄다.
# 1월 1일 정각으로 밀어 사실상 실행되지 않게 한다.
FAR_CRON="0 0 0 1 1 ?"
# 워밍업 단계에서만 빠르게 돌린다.
FAST_CRON="*/5 * * * * ?"

start_server() {  # $1=redis_enabled  $2=cron  $3=logfile
  RANKING_REDIS_ENABLED="$1" RANKING_SYNC_CRON="$2" \
    java -jar "$JAR" > "$3" 2>&1 &
  SERVER_PID=$!
}

stop_server() {
  [ -n "${SERVER_PID:-}" ] || return 0
  kill "$SERVER_PID" 2>/dev/null || true
  wait "$SERVER_PID" 2>/dev/null || true
  SERVER_PID=""
  sleep 2
}
trap stop_server EXIT

wait_for_server() {
  for _ in $(seq 1 90); do
    curl -fsS "http://localhost:8080/rank" >/dev/null 2>&1 && return 0
    sleep 1
  done
  echo "서버가 뜨지 않았습니다" >&2; return 1
}

# Redis 팔이 정말 Redis 를 타게 하려면 랭킹이 미리 채워져 있어야 한다.
# 비어 있으면 getTop30() 이 조용히 DB 폴백을 타서 두 팔이 같은 걸 재게 된다.
# 앱의 실제 스케줄러 경로를 그대로 쓰되, 다 채워지면 서버를 내려
# 측정 중에는 스케줄러가 돌지 않도록 분리한다. (Redis 컨테이너는 그대로 유지)
warm_redis() {  # $1=expected_users
  echo "  Redis 워밍업 (스케줄러 1회 실행)..."
  start_server true "$FAST_CRON" "$RESULT_DIR/warmup_$1.log"
  wait_for_server
  for _ in $(seq 1 60); do
    local card
    card=$("${REDIS[@]}" ZCARD "$RANK_KEY" | tr -d '\r')
    [ "$card" = "$1" ] && { echo "  워밍업 완료 (ZCARD=$card)"; stop_server; return 0; }
    sleep 2
  done
  echo "  워밍업 실패: ZCARD가 $1 에 도달하지 못함" >&2
  stop_server; return 1
}

run_one() {  # $1=users  $2=arm  $3=redis_enabled
  local tag="$1_$2"
  echo "── 유저 $1명 / $2 ──"
  start_server "$3" "$FAR_CRON" "$RESULT_DIR/server_${tag}.log"
  wait_for_server

  # 조용히 0건을 재는 것을 막는 관문.
  # 데이터가 이번 달 밖이면 HTTP 200 에 빈 배열이 나갈 뿐 아무것도 실패하지 않는다.
  assert_data_is_measurable || { stop_server; return 1; }

  k6 run -e USERS="$1" -e ARM="$2" \
      --summary-export="$RESULT_DIR/${tag}.json" \
      bench/rank_ab.js > "$RESULT_DIR/${tag}.txt" 2>&1

  stop_server
}

echo "jar 빌드..."
./server/gradlew -p server bootJar --quiet

for users in 10000 50000 100000; do
  echo ""
  echo "########## 유저 ${users}명 ##########"
  "${REDIS[@]}" FLUSHALL >/dev/null
  { echo "SET @target_users = ${users};"; cat bench/seed_scale.sql; } | "${MYSQL[@]}"

  warm_redis "$users"
  assert_redis_warm "$users"

  # db 조건은 Redis 를 아예 쳐다보지 않으므로(RankServiceFallbackTest 로 검증됨)
  # 워밍된 Redis 가 남아 있어도 결과에 영향이 없다.
  run_one "$users" "db"    "false"
  run_one "$users" "redis" "true"
done

# 데이터를 남기지 않는다. 이번 달 커밋으로 시드된 데이터는 다음 달이 되면
# "지난달 데이터"가 되어, 다시 돌릴 때 조용히 0건 측정을 유발한다.
teardown_data

echo ""
echo "완료. 결과: $RESULT_DIR/"
