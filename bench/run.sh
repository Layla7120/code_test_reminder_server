#!/usr/bin/env bash
# 랭킹 A/B 측정 — ranking.redis.enabled 하나만 바꾼다.
#
# 규모 3개(유저 1만/5만/10만) x 조건 2개(redis/db).
# 조건마다 두 가지를 잰다:
#   동시성 1     순수 쿼리 비용 (커넥션 풀 경합이 없는 조건)
#   VU 30, 60초  실제 부하에서의 처리량과 실패율
#
# db 조건은 벤치마크용 별도 구현이 아니라 운영의 Redis 장애 폴백 경로다.
# 그래서 이 측정 하나가 "Redis가 죽으면 어떻게 되나"도 함께 답한다.
#
# 사전 조건: docker compose up -d / k6 설치 / JDK 21
# 실행: bash bench/run.sh                          (약 25분)
#      SCALES="10000" DURATION=20s bash bench/run.sh   (빠른 확인용)

set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null \
  || echo /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home)}"
export PATH="$JAVA_HOME/bin:$PATH"
export DB_USER=reminder DB_PASSWORD=reminder DB_NAME=reminder GITHUB_TOKEN=unused

OUT=bench/results; mkdir -p "$OUT"
JAR=server/build/libs/server-0.0.1-SNAPSHOT.jar
# 비밀번호를 인자로 넘기면 mysql 이 경고를 stderr 로 뱉는다. MYSQL_PWD 로 전달한다.
MYSQL=(docker exec -i -e MYSQL_PWD=reminder reminder-mysql mysql -ureminder reminder)
REDIS=(docker exec -i reminder-redis redis-cli)
KEY="rank:commit:$(TZ=Asia/Seoul date +%Y%m)"

# 측정 중 스케줄러가 끼어들면 10만 ZSET 전체를 다시 읽어 지연이 튄다. 1월 1일로 밀어둔다.
FAR="0 0 0 1 1 ?"
FAST="*/5 * * * * ?"   # 워밍업 때만

SERVER_LOG=/tmp/bench_server.log

start() {  # $1=redis_enabled  $2=cron
  # 8080 을 이미 누가 잡고 있으면 새로 띄운 서버는 바로 죽는데,
  # curl 은 "남의 서버"에서 200 을 받아 성공한 것처럼 보인다.
  # 그러면 의도한 조건이 아닌 서버를 상대로 측정하게 되므로 먼저 막는다.
  if lsof -nP -iTCP:8080 -sTCP:LISTEN >/dev/null 2>&1; then
    echo "✗ 포트 8080 을 이미 누가 쓰고 있다. 기존 서버를 내리고 다시 실행할 것." >&2
    exit 1
  fi

  RANKING_REDIS_ENABLED=$1 RANKING_SYNC_CRON=$2 java -jar "$JAR" > "$SERVER_LOG" 2>&1 &
  PID=$!
  for _ in $(seq 1 90); do
    # 응답만 보지 말고 내가 띄운 프로세스가 살아 있는지도 같이 본다
    kill -0 "$PID" 2>/dev/null || {
      echo "✗ 서버가 기동 중 죽었다:" >&2; tail -5 "$SERVER_LOG" >&2; exit 1; }
    curl -fsS localhost:8080/rank >/dev/null 2>&1 && return
    sleep 1
  done
  echo "✗ 서버가 90초 안에 뜨지 않았다:" >&2; tail -5 "$SERVER_LOG" >&2; exit 1
}
stop() { [ -n "${PID:-}" ] || return 0; kill "$PID" 2>/dev/null || true; wait "$PID" 2>/dev/null || true; PID=; sleep 2; }
trap stop EXIT

# 시드가 이번 달 밖이면 랭킹이 빈 배열인데도 HTTP 200 이 나간다.
# 그대로 재면 두 조건 모두 0 을 재게 되므로 여기서 멈춘다.
verify() {
  local top
  top=$(curl -fsS localhost:8080/rank | sed -n 's/.*"commitCount":\([0-9]*\).*/\1/p' | head -1)
  [ -n "$top" ] && [ "$top" -gt 0 ] 2>/dev/null && return
  echo "✗ 랭킹이 비어 있다. 시드가 이번 달($(TZ=Asia/Seoul date +%Y%m)) 밖일 수 있다." >&2
  exit 1
}

median_ms() {  # 워밍 5회 후 10회 측정, 중앙값
  for _ in $(seq 1 5);  do curl -s -o /dev/null "$1"; done
  for _ in $(seq 1 10); do curl -s -o /dev/null -w "%{time_total}\n" "$1"; done \
    | sort -n | awk '{a[NR]=$1} END {printf "%.1f", ((a[5]+a[6])/2)*1000}'
}

echo "jar 빌드..."
./server/gradlew -p server bootJar --quiet

SUMMARY="$OUT/summary.txt"
printf "%-8s %-6s %10s %10s %11s %8s\n" 유저 조건 "rank(ms)" "user(ms)" "req/s" 실패율 | tee "$SUMMARY"

for users in ${SCALES:-10000 50000 100000}; do
  "${REDIS[@]}" FLUSHALL >/dev/null
  { echo "SET @target_users=$users;"; cat bench/seed.sql; } | "${MYSQL[@]}" >/dev/null

  # Redis 조건이 정말 Redis 를 타려면 랭킹이 채워져 있어야 한다.
  # 비어 있으면 getTop30() 이 에러 없이 DB 폴백을 타서 두 조건이 같은 걸 재게 된다.
  # 앱의 실제 스케줄러로 채운 뒤 서버를 내려, 측정 중에는 스케줄러가 돌지 않게 한다.
  start true "$FAST"
  for _ in $(seq 1 60); do
    [ "$("${REDIS[@]}" ZCARD "$KEY" | tr -d '\r')" = "$users" ] && break
    sleep 2
  done
  [ "$("${REDIS[@]}" ZCARD "$KEY" | tr -d '\r')" = "$users" ] \
    || { echo "✗ Redis 워밍 실패 (ZCARD != $users)" >&2; exit 1; }
  stop

  for arm in redis db; do
    [ "$arm" = redis ] && enabled=true || enabled=false
    start "$enabled" "$FAR"
    verify

    rank_ms=$(median_ms "http://localhost:8080/rank")
    user_ms=$(median_ms "http://localhost:8080/rank/users?userId=7")

    txt="$OUT/${users}_${arm}.txt"
    k6 run -e MAX_USER_ID="$users" -e DURATION="${DURATION:-60s}" --summary-export="$OUT/${users}_${arm}.json" \
        bench/rank_ab.js > "$txt" 2>&1
    stop

    flat=$(sed 's/\.\{2,\}/ /g' "$txt")
    rps=$(awk '/^ *http_reqs /       {print $NF}' <<<"$flat" | tr -d '/s')
    fail=$(awk '/^ *http_req_failed /{print $3}'  <<<"$flat")
    printf "%-8s %-6s %10s %10s %11s %8s\n" "$users" "$arm" "$rank_ms" "$user_ms" "$rps" "$fail" \
      | tee -a "$SUMMARY"
  done
done

# 이번 달 커밋으로 만든 데이터는 다음 달이면 "지난달"이 되어 조용히 0건 측정을 유발한다.
"${REDIS[@]}" FLUSHALL >/dev/null
"${MYSQL[@]}" >/dev/null <<'SQL'
SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE participate; TRUNCATE commits; TRUNCATE `groups`; TRUNCATE users;
SET FOREIGN_KEY_CHECKS = 1;
SQL

echo
echo "완료 → $SUMMARY"
