# 랭킹 A/B 측정

**바꾸는 변수는 하나뿐: `ranking.redis.enabled`**

| 조건 | 랭킹 조회 경로 |
|---|---|
| `redis` | Redis ZSET `ZREVRANGE` + score→denseRank HASH `HGET` |
| `db` | MySQL `DENSE_RANK()` — 전체 유저 집계 후 정렬 |

코드·DB·시드 데이터·VU 수·지속 시간·엔드포인트 구성·커넥션 풀은 **전부 고정**한다.
절대값이 아니라 **규모를 바꿔가며 기울기**를 본다 — 유저 1만 / 5만 / 10만.

`db` 조건은 벤치마크용 별도 구현이 아니라 **운영에 이미 있는 Redis 장애 폴백 경로**
(`RankService.getTop30FromDb()`)를 강제로 태우는 것이다.
그래서 이 측정 하나가 "Redis가 죽으면 어떻게 되나"도 함께 답한다.

**결과와 해석 → [`../docs/기록.md`](../docs/기록.md)** · 원본 → [`results/`](results/)

---

## 실행

```bash
docker compose up -d
brew install k6

bash bench/run.sh                                  # 약 25분
SCALES="10000" DURATION=20s bash bench/run.sh      # 빠른 확인용
```

규모마다 **시드 → Redis 워밍 → 사전 검증 → 측정 → 뒷정리**를 알아서 한다.
데이터가 이번 달 밖이거나 Redis가 비어 있으면 조용히 0을 재지 않고 **즉시 중단**한다.

| 파일 | 역할 |
|---|---|
| `run.sh` | 전체 실행. 조건마다 동시성 1 측정과 k6 부하 측정을 연달아 한다 |
| `seed.sql` | 규모별 시드. 순수 SQL(재귀 CTE) — Python 의존성 없음 |
| `rank_ab.js` | k6 스크립트. `GET /rank` 70% + `GET /rank/users` 30%, VU 30 |
| `results/summary.txt` | 6행 요약표 |
| `results/*.json`, `*.txt` | k6 원본 |

### 왜 한 스크립트인가

처음에는 부하 측정과 동시성 1 측정을 파일 두 개로 나눠 뒀는데,
둘 다 규모마다 시드하고 Redis를 워밍해서 **같은 일을 두 번씩** 했다.

```
이전  규모당 서버 기동 6회, 시드 2회, Redis 워밍 2회   (397줄, 5개 파일)
지금  규모당 서버 기동 3회, 시드 1회, Redis 워밍 1회   (208줄, 3개 파일)
```

---

## 직접 손으로 해보기

읽는 것과 실제로 기다려보는 건 다르다. 터미널 2개를 띄우고 순서대로.

### 0. 준비

저장소 루트에서:

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || echo /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home)"
export DB_USER=reminder DB_PASSWORD=reminder DB_NAME=reminder GITHUB_TOKEN=unused
docker compose up -d
cd server && ./gradlew bootJar --quiet && cd ..
```

**데이터는 매번 새로 만든다.** 이번 달 커밋으로 만든 데이터는 다음 달이 되면
"지난달 데이터"가 되어 랭킹이 조용히 0건이 되기 때문이다.

```bash
{ echo "SET @target_users = 100000;"; cat bench/seed.sql; } \
  | docker exec -i -e MYSQL_PWD=reminder reminder-mysql mysql -ureminder reminder
```

### Redis 워밍업 (Redis 조건을 쓰려면 필수)

터미널 A — 앱의 실제 스케줄러로 랭킹을 채운다:

```bash
docker exec -i reminder-redis redis-cli FLUSHALL
RANKING_REDIS_ENABLED=true RANKING_SYNC_CRON="*/5 * * * * ?" \
  java -jar server/build/libs/server-0.0.1-SNAPSHOT.jar
```

터미널 B에서 유저 수만큼 찰 때까지 확인:

```bash
docker exec -i reminder-redis redis-cli ZCARD rank:commit:$(TZ=Asia/Seoul date +%Y%m)
```

`100000`이 나오면 터미널 A를 `Ctrl+C`.

### 1. DB 조건으로 눌러보기

터미널 A:
```bash
RANKING_REDIS_ENABLED=false RANKING_SYNC_CRON="0 0 0 1 1 ?" \
  java -jar server/build/libs/server-0.0.1-SNAPSHOT.jar
```

터미널 B:
```bash
curl -s -o /dev/null -w "%{time_total}초\n" http://localhost:8080/rank
```

**약 1초.** 동시 요청이 하나도 없는데 이렇다.

> `RANKING_SYNC_CRON="0 0 0 1 1 ?"`는 자가치유 스케줄러를 1월 1일로 밀어
> 측정 중 끼어들지 못하게 하는 것이다.

### 2. Redis 조건으로 바꿔보기

터미널 A를 `Ctrl+C` 하고 `RANKING_REDIS_ENABLED=true`로 재시작 → 같은 curl.

**약 0.004초.**

> Redis 조건인데도 느리면 Redis가 비어 있는 것이다. `getTop30()`은 Redis가 비면
> **에러 없이** DB 폴백을 탄다. 위 `ZCARD`로 확인할 것.

### 3. 동시 30명 (여기가 제일 볼만하다)

DB 조건으로 서버를 켜고:

```bash
k6 run -e USERS=100000 bench/rank_ab.js
```

60초 동안 처리한 요청이 300건 아래에서 멈추고, 실패율이 20%대까지 오른다.
터미널 A에는 이게 쌓인다.

```
HikariPool-1 - Connection is not available, request timed out after 3005ms
             (total=20, active=20, idle=0, waiting=9)
```

쿼리 하나가 1초씩 커넥션을 붙잡으니 20개 풀이 포화되고,
뒤에 온 요청은 **쿼리를 시작해보지도 못하고** 3초 뒤 5xx로 떨어진다.

Redis 조건으로 바꿔 같은 명령 → **30만 건 처리, 실패 0%.**

### 4. 규모를 바꿔 기울기 느끼기

0번을 `@target_users = 10000`으로 다시 하고 1~3을 반복.
`GET /rank` 단일 요청이 **990ms → 84ms**로 줄어든다. Redis 쪽은 그대로다.

### 끝나면 치우기

`run.sh`는 알아서 치우지만, 손으로 했다면:

```bash
docker exec -i reminder-redis redis-cli FLUSHALL
docker exec -i -e MYSQL_PWD=reminder reminder-mysql mysql -ureminder reminder <<'SQL'
SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE participate; TRUNCATE commits; TRUNCATE `groups`; TRUNCATE users;
SET FOREIGN_KEY_CHECKS = 1;
SQL
```

**남겨두지 마세요.** 다음 달에 "지난달 데이터"가 되어 조용히 0건 측정을 유발한다.

---

## 함정 두 개 (실제로 겪음)

### 타임존

MySQL 컨테이너는 UTC, 앱은 `Asia/Seoul`이다.
KST 기준 매월 1일 0~9시 사이에는 두 시계의 "이번 달"이 다르다.
`seed.sql`에 `SET time_zone = '+09:00';`이 있는 이유다.
없으면 시드한 커밋이 전부 "지난달"로 분류되어 **랭킹이 조용히 0건**이 된다.

에러가 안 나고 빈 결과가 나오는 형태라 더 위험하다.
그래서 `run.sh`의 `verify()`가 측정 직전에 이걸 막는다 — 1등의 커밋 수가 0이면 즉시 중단한다.

### 시드 지우기

200만 행 규모에서 `DELETE`는 10분을 넘긴다(행 단위 undo 로그).
`seed.sql`은 `TRUNCATE`를 쓴다.
