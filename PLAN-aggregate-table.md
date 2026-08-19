# 집계 테이블 이관 계획

랭킹의 진실 원천을 `commits` 하나로 두고, 집계 결과를 `user_monthly_score` 행에 저장한다.
Redis 랭킹 경로를 걷어낸다.

이 문서는 다른 에이전트가 그대로 실행하기 위한 것이다. 추측하지 말고 여기 적힌 것만 한다.

---

## 0. 시작 전 반드시 읽을 것

### 0-1. 손대면 안 되는 것

| 대상 | 이유 |
|---|---|
| `docs/기록.md` | **사용자 본인이 쓴 글이다. 한 글자도 고치지 않는다.** 문서에 반영할 내용이 생기면 보고만 한다 |
| `app/`, `migrations/` | Flask 원본. 대조용으로 남겨둔 것이라 건드리지 않는다 |
| 커밋 수집 분산 락 | `CommitService` 의 Redis SETNX 락, `CommitFetchLockReleaseEvent/Listener` — 랭킹과 무관하다 |

### 0-2. Redis 의존성은 남는다

**`spring-boot-starter-data-redis` 를 지우지 않는다.** Testcontainers 의 Redis 컨테이너도 남긴다.
랭킹만 걷어내는 것이고, 커밋 수집 락이 여전히 Redis 를 쓴다.
`IntegrationTest.clearStores()` 의 `flushDb()` 도 그대로 둔다.

### 0-3. 워킹트리에 미커밋 작업이 있다

브랜치 `trim-comments` 에 주석 정리 21개 파일이 커밋 안 된 채로 있고, **테스트를 아직 못 돌렸다**
(작업 당시 Docker 미기동). 이 계획을 시작하기 전에 사용자에게 확인한다:

```bash
docker compose up -d
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
cd server && ./gradlew test
```

초록불이면 커밋하고 새 브랜치를 판다. 빨간불이면 **여기서 멈추고 보고한다** —
주석만 바꾼 작업이 테스트를 깨뜨렸다면 그건 이 계획과 별개의 문제다.

```bash
git checkout -b aggregate-table
```

### 0-4. 검증 명령 (모든 단계 끝에 실행)

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
cd server && ./gradlew test
```

`test` 뒤에 `verifyEndpointCoverage` 가 자동으로 이어 돈다 (`build.gradle.kts:140`).
Docker 가 안 떠 있으면 Testcontainers 가 못 뜨므로 **"못 돌렸다"고 명시하고 멈춘다.
통과했다고 쓰지 않는다.**

---

## 1. 목표와 비목표

**목표**
- 랭킹 조회가 매 요청 `commits` 전체를 집계하지 않게 한다
- 랭킹 점수의 진실 원천을 하나로 만든다 → 재수집해도 점수가 부풀 수 없는 구조
- Redis 랭킹 경로, 자가 치유 스케줄러, 이벤트 리스너를 제거한다

**비목표 (하지 않는다)**
- API 응답 모양 변경
- 그룹 멤버 랭킹(`findMemberCommits`) 변경 — 6절 참고, 이번 범위 밖
- 성능 최적화 자체. **목적은 단순화이고 성능은 부수 효과다**
- 새 기술 스택 추가. MySQL 테이블 하나만 는다

---

## 2. 불변조건 — 하나라도 깨지면 실패

| # | 불변조건 | 확인 방법 |
|---|---|---|
| I1 | `GET /rank` 응답은 `[{userId, commitCount, rank}]` 그대로 | `RankApiTest` |
| I2 | `GET /rank/users` 응답은 `{"rank": n\|null}` 그대로 | `RankApiTest` |
| I3 | 동순위는 dense — 1등 두 명이면 다음이 **2등** (1,1,2) | `RankApiTest` + `toDenseRankEntries` 단위 검증 |
| I4 | 이번 달 커밋이 **0건인 유저는 랭킹에 안 나온다** (기존 INNER JOIN 의미) | 신규 테스트 |
| I5 | 비활성 유저(`users.active = false`)는 랭킹에 안 나온다 — **읽기 필터가 아니라 행이 없어서** (3-6) | 신규 테스트 |
| I6 | 동점자 표시 순서는 `userId` 오름차순 | `toDenseRankEntries` 가 이미 보장 |
| I7 | **같은 커밋을 재수집해도 점수가 오르지 않는다** (버그 A 재발 금지) | 신규 멱등성 테스트 |
| I8 | 엔드포인트 19개 유지, HTTP 테스트 커버리지 게이트 통과 | `verifyEndpointCoverage` |

I7 이 이 작업의 핵심이다. 지금은 "증분을 정확하게 계산해서" 막고 있고,
바뀐 뒤에는 **절대값으로 다시 세므로 틀릴 방법이 없어야** 한다.

---

## 3. 설계

### 3-1. 스키마

~~`infra/init.sql` 에 추가한다.~~ **2026-08-17 갱신**: Flyway 도입으로 `infra/init.sql` 은
삭제됐다. 스키마는 `server/src/main/resources/db/migration/` 이 진실 원천이고, 이 테이블은
이미 `V1__baseline.sql` 에 들어 있다 (Phase 1 완료분). 앞으로 스키마를 바꿀 때는
`V<yyyyMMddHHmmss>__<설명>.sql` 을 새로 추가한다 — 적용된 파일은 수정하지 않는다.

```sql
CREATE TABLE IF NOT EXISTS user_monthly_score (
    user_id     BIGINT   NOT NULL,
    score_month CHAR(6)  NOT NULL,          -- 'yyyyMM'
    score       INT      NOT NULL,
    PRIMARY KEY (user_id, score_month),
    INDEX idx_rank (score_month, score DESC),
    CONSTRAINT fk_ums_user FOREIGN KEY (user_id) REFERENCES users (user_id)
);
```

> ⚠️ **컬럼명을 `year_month` 로 하지 말 것.** `YEAR_MONTH` 는 MySQL 예약어라
> (`INTERVAL ... YEAR_MONTH`) 백틱 없이는 문법 오류가 난다. `score_month` 를 쓴다.

`IntegrationTest.CLEANUP_ORDER` 에도 추가한다 — FK 때문에 `users` 보다 **먼저** 지워야 한다.

### 3-2. 쓰기 — 증분이 아니라 절대값

**이 설계의 전부다.** 지금 버그가 났던 이유는 `+= n` 이라는 증분을 썼기 때문이고,
증분은 한 번 틀리면 복구가 안 돼서 자가 치유 스케줄러가 필요했다.

`commits` 에서 다시 센 값을 그대로 덮어쓴다:

```sql
INSERT INTO user_monthly_score (user_id, score_month, score)
SELECT :userId, :scoreMonth, COUNT(*)
FROM commits
WHERE user_id = :userId
  AND commit_date >= :from
  AND commit_date <  :to
ON DUPLICATE KEY UPDATE score = VALUES(score)
```

몇 번을 실행해도 결과가 같다. 이게 I7 을 **구조적으로** 보장한다.

호출 지점은 `CommitService.fetchAndSaveCommits()` 안, `bulkUpsert` **바로 다음, 같은 트랜잭션**.
이벤트도 `AFTER_COMMIT` 도 쓰지 않는다 — 원자적으로 같이 커밋되거나 같이 롤백된다.

재계산할 달은 `newCommits` 가 아니라 **`dtos` 에 등장하는 모든 달**로 잡는다.
절대값이라 안 바뀐 달을 다시 써도 무해하고, 이쪽이 더 안전하다.

```kotlin
dtos.map { YearMonth.from(it.commitDate) }.distinct()
    .forEach { ym -> userMonthlyScoreRepository.recompute(userId, ym) }
```

### 3-3. 읽기 — Top 30

인덱스가 이미 `(score_month, score DESC)` 순이라 정렬 없이 30건이 나온다.
**DENSE_RANK 를 SQL 에서 돌리지 않는다.**

```sql
SELECT s.user_id AS userId, s.score AS score
FROM user_monthly_score s
WHERE s.score_month = :scoreMonth
  AND s.score > 0            -- I4
ORDER BY s.score DESC, s.user_id
LIMIT 30
```

> ⚠️ **`users` 를 조인하지 않는다.** I5(비활성 유저 제외)는 3-6 에서 쓰기 시점에 보장한다.
> 조인을 넣으면 옵티마이저가 `idx_rank` 를 버리고 `users` 를 풀스캔한다. 실측(5만 행):
> **조인 있음 81ms / 없음 0.47ms.** 조인이 있으면 인덱스가 아예 안 쓰인다.
>
> `ORDER BY ... s.user_id` 는 표시 순서 때문이 아니다(그건 앱이 다시 정렬한다).
> **LIMIT 30 경계에서 동점자 중 누가 잘리는지를 고정**하기 위한 것이다.
> 그리고 이 방향(`score DESC, user_id ASC`)이라야 정렬이 안 붙는다 —
> InnoDB 가 보조 인덱스에 PK(`user_id`)를 오름차순으로 덧붙이기 때문이다.
> `user_id DESC` 로 뒤집으면 실측 23ms 로 Sort 가 붙는다.

동순위 계산은 **기존 `RankEntry.toDenseRankEntries()` 를 그대로 재사용한다.**
`List<Pair<userId, score>>` 를 받아 dense rank 를 매기는 함수이고, I3·I6 을 이미 보장한다.
이 함수는 삭제 대상이 아니다.

### 3-4. 읽기 — 개인 순위

dense rank 의 정의는 "나보다 높은 점수가 몇 **종류**인가 + 1" 이다.

> 주의: MySQL 은 여기서 loose index scan 을 쓰지 않는다. **distinct 개수가 아니라
> 조건에 맞는 행을 전부 훑는다** (실측 5만 행 중 45,083행 스캔, 20.4ms).
> covering index 라 랜덤 IO 가 없어서 싼 것이지 `O(distinct)` 가 아니다.
> "값의 종류가 적어서 싸다"고 적지 말 것 — 재보면 사실이 아니다.

한 쿼리로 묶지 말고 **두 단계로 나눈다.** null 의미를 정확히 유지하기 위해서다.

```kotlin
fun getUserRank(userId: Long): Long? {
    val myScore = repo.findScore(userId, ym) ?: return null   // 행 없음 → 순위 없음
    if (myScore <= 0) return null                             // I4
    return repo.countHigherDistinctScores(ym, myScore) + 1
}
```

```sql
-- countHigherDistinctScores — 여기도 users 를 조인하지 않는다
SELECT COUNT(DISTINCT s.score)
FROM user_monthly_score s
WHERE s.score_month = :scoreMonth
  AND s.score > :myScore
```

실측(5만 행): 조인 있음 72.6ms / 없음 20.4ms.

### 3-5. 백필

기존 `commits` 행에 대한 초기 점수를 만든다. 마이그레이션 도구가 없으므로(문서 446절)
`infra/init.sql` 에는 넣지 않고 **일회성 SQL 로 실행하고 명령을 보고서에 남긴다.**

```sql
INSERT INTO user_monthly_score (user_id, score_month, score)
SELECT c.user_id, DATE_FORMAT(c.commit_date, '%Y%m'), COUNT(*)
FROM commits c
JOIN users u ON u.user_id = c.user_id AND u.active = TRUE   -- 3-6: 비활성 유저는 행을 만들지 않는다
GROUP BY c.user_id, DATE_FORMAT(c.commit_date, '%Y%m')
ON DUPLICATE KEY UPDATE score = VALUES(score);
```

여기서는 조인해도 된다. 일회성이라 매 요청 비용이 아니다.

### 3-6. `active` 는 읽을 때가 아니라 쓸 때 거른다

`active` 는 `users` 에만 있으므로, 읽기에서 거르려면 조인이 필요하다.
그런데 **`active` 는 유저당 평생 한 번 바뀔까 말까 한 값이고 랭킹은 계속 읽힌다.**
거의 안 변하는 값을 매 요청 확인하는 게 위의 81ms 다.

이 테이블의 의미를 이렇게 정한다:

> `user_monthly_score` 에 행이 있다 = **랭킹에 포함되는 유저다.**

그러면 읽기 쿼리에서 `active` 가 통째로 사라지고, 행의 존재 자체가 필터가 된다.
`active` 컬럼을 복사해오는 비정규화가 아니다 — 테이블의 정의를 바꾸는 것이다.

**해야 할 일 두 가지:**

```kotlin
// 1. UserService.deleteUser — 비활성화하면 점수 행도 지운다
fun deleteUser(userId: Long) {
    getUser(userId).deactivate()
    userMonthlyScoreRepository.deleteByUserId(userId)
}
```

```kotlin
// 2. CommitService.fetchAndSaveCommits — 비활성 유저가 행을 되살리지 못하게 막는다
//    deleteUser 는 소프트 삭제라 userId 가 계속 유효하고, recompute 는 무조건 쓴다.
if (!user.active) throw ... // 또는 recompute 를 건너뛴다
```

2번을 빼먹으면 탈퇴한 유저가 `POST /commits` 한 번으로 랭킹에 돌아온다. **I5 가 깨진다.**

`deleteUser` 는 API 동작 변경이므로 `AGENTS.md` 규칙에 따라 **HTTP 계층 테스트를 함께 낸다.**

---

## 4. 단계 — 각 단계 끝에서 테스트가 초록불이어야 한다

단계마다 커밋한다. 중간에 멈춰도 안전한 순서로 짰다.

### Phase 1 — 스키마와 저장소 추가 (읽는 사람 없음)

- `infra/init.sql` 에 테이블 추가
- `UserMonthlyScore` 엔티티 (복합키 `@IdClass` 또는 `@EmbeddedId`)
- `UserMonthlyScoreRepository` — `recompute`, `findScore`, `countHigherDistinctScores`, `findTop30`
  - `recompute` 는 `@Modifying` + native (MySQL `ON DUPLICATE KEY UPDATE`)
  - 나머지는 가능하면 JPQL/derived query 로. **native 는 꼭 필요한 곳만**
- `IntegrationTest.CLEANUP_ORDER` 에 `user_monthly_score` 추가 (`users` 앞)

**검증**: `./gradlew test` — 기존 테스트 전부 그대로 초록불이어야 한다. 아무것도 안 바뀌었으니까.

### Phase 2 — 쓰기 경로 추가 (Redis 는 아직 그대로)

- `CommitService.fetchAndSaveCommits()` 에 3-2 의 재계산 추가
- 기존 `CommitsSavedEvent` 발행은 **아직 지우지 않는다** — 이중 기록 상태로 둔다

**신규 테스트** `UserMonthlyScoreWriteTest` (`IntegrationTest` 상속):
- 커밋 5건 수집 → `user_monthly_score.score == 5`
- **같은 fetch 를 두 번** → 여전히 5 (I7)
- 월 경계에 걸친 커밋 → 각 달 버킷에 정확히 나뉨
- 트랜잭션 롤백 시 점수도 안 남음

**검증**: `./gradlew test`

### Phase 2.5 — `active` 를 쓰기 시점으로 옮긴다 ⚠️ Phase 3 보다 먼저

**Phase 1·2 는 이미 커밋됐지만 `UserMonthlyScoreRepository` 의 두 읽기 쿼리에
`User u` 조인이 남아 있다. 읽기 전환 전에 반드시 뺀다.**

- `findTop`, `countHigherDistinctScores` 에서 `User u` 조인과 `u.active` 제거 (3-3, 3-4)
- `UserService.deleteUser` 가 점수 행도 삭제 (3-6)
- `fetchAndSaveCommits` 가 비활성 유저의 행을 되살리지 못하게 가드 (3-6)
- `UserMonthlyScoreRepository:38` 의 "값의 종류가 적어서 싸다" 주석 정정 (3-4)

**신규 테스트**:
- 탈퇴한 유저는 Top30 과 개인 순위에서 사라진다 (I5)
- 탈퇴 후 `POST /commits` 를 해도 돌아오지 않는다 (I5)
- `deleteUser` 는 API 동작 변경이므로 `ApiTest` 상속 테스트로 낸다

**왜 Phase 3 앞이어야 하나**: 조인이 남은 채로 전환하면 첫 측정이 81ms 로 나온다.
그러면 5절 벤치가 **"집계 테이블은 별 이득이 없다"는 틀린 결론**을 내놓는다.
설계가 아니라 쿼리 결함인데 숫자는 설계를 탓하게 된다.
테스트는 이걸 못 잡는다 — fixture 가 몇 행이라 81ms 든 0.47ms 든 다 통과한다.

**검증**: `./gradlew test` + 아래를 직접 확인 (컨테이너에서)

```bash
docker exec -i -e MYSQL_PWD=reminder reminder-mysql \
  mysql --default-character-set=utf8mb4 -ureminder reminder \
  -e "EXPLAIN ANALYZE <Top30 쿼리>"
```

실행계획에 **`Covering index range scan on s using idx_rank`** 가 보여야 한다.
`Table scan on u` 나 `Sort:` 가 보이면 조인이 아직 남은 것이다.

### Phase 3 — 읽기 경로 전환

> **⚠️ Phase 3~4 사이의 알려진 한계 — 고치지 않는다**
>
> 지금 `UserService.deleteUser` 는 `user_monthly_score` 행만 지우고 Redis ZSET 은 건드리지
> 않는다. 그래서 **Phase 4 로 Redis 랭킹이 사라지기 전까지 `GET /rank` 가 탈퇴 유저를 계속
> 보여준다.** 자가 치유 스케줄러도 `findMonthlyCommitCountPerUser` 에 `active` 필터가 없어
> 매시간 다시 채워 넣는다.
>
> `DELETE /users/delete` 는 인증이 없고(`permitAll()`) `userId` 를 파라미터로 받으므로
> 아무나 아무 유저나 탈퇴시킬 수 있다 — 노출 면적이 좁지 않다.
>
> **그래도 고치지 않는 이유**: Phase 4 가 지울 코드에 버그를 고치는 일이 된다.
> 데모·시연 전이라면 아래로 비우면 스케줄러가 DB 기준으로 다시 채운다.
>
> ```bash
> redis-cli DEL rank:commit:$(date +%Y%m)
> ```
>
> Phase 4 완료 시점에 이 블록을 지운다.


- `RankService` 가 `UserMonthlyScoreRepository` 를 쓰도록 교체
- `redisRankingEnabled` 스위치와 폴백 분기 제거 — **경로가 하나가 되므로 폴백 개념이 사라진다**
- `RankController` 는 손대지 않는다 (I1, I2)

**여기서 `RankApiTest` 가 깨진다.** 이 테스트는 `JdbcTemplate` 으로 `commits` 에 직접 넣는데,
이제 `commits` 에 넣는 것만으로는 점수가 안 생긴다. fixture 가 `user_monthly_score` 도
채우도록 고친다 (3-5 백필 SQL 을 fixture 헬퍼로 쓰면 된다).
**테스트를 지우지 말고 fixture 를 고친다.**

**검증**: `./gradlew test` + I1~I6 을 `RankApiTest` 에서 확인

### Phase 4 — Redis 랭킹 제거

삭제:
```
domain/rank/RankingRedisRepository.kt
domain/rank/RankingSelfHealingScheduler.kt
domain/rank/RankingEventListener.kt
domain/commit/CommitsSavedEvent.kt
```
`CommitService` 에서 `CommitsSavedEvent` 발행 제거.
`application.yml` 에서 `ranking.redis.enabled`, `ranking.sync.cron` 제거.

삭제할 테스트 (검증 대상이 사라짐):
```
RankingScoreHealTest              — ZADD GT 규칙
RankingSelfHealingSchedulerTest   — 스케줄러 복구
RankServiceFallbackTest           — Redis 장애 폴백
RankPathConsistencyTest           — 두 경로 일치
```

> `RankPathConsistencyTest` 는 지우기 전에 **거기 있는 단언을 새 테스트로 옮긴다.**
> 특히 동점 fixture(`5,5,3,2,2,2,1` → rank `1,1,2,3,3,3,4`)와
> "커밋 0건 유저는 안 나온다"는 I3·I4 그 자체다. 경로 비교만 빼고 살린다.

`CommitDuplicateFetchTest` 는 Redis ZSET 을 단언하므로 **`user_monthly_score` 단언으로 고친다.**
지우지 않는다 — 버그 A 재발 방지가 이 테스트의 목적이고 그건 여전히 유효하다.

**남기는 것**: `RankEntry.toDenseRankEntries()`, Redis 의존성, Redis 컨테이너, 커밋 수집 락.

> **Phase 4 이후 재검토 대상 — 커밋 수집 락 (2026-08-19 추가)**
>
> 이 락(`CommitService` 의 SETNX)이 지금 막고 있는 건 버그 A 다: 동시 요청 둘이 각각
> `findExistingShas()` 를 "없음"으로 보고 같은 커밋을 신규로 세어 `ZINCRBY` 를 두 번 날리는
> 것. ZADD GT 가 하향 보정을 막아 영구화된다.
>
> **Phase 3~4 가 끝나면 그 근거가 사라진다.** 점수가 `SELECT COUNT(*)` 절대값이라 몇 번을
> 동시에 돌려도 같은 값이고, 커밋 행은 `UNIQUE(sha)` + `ON DUPLICATE KEY UPDATE` 로 이미
> 멱등이다. 락이 없어도 데이터는 틀리지 않는다.
>
> 남는 값어치는 정합성이 아니라 ①GitHub API 중복 호출 절약 ②같은 행 동시 upsert 경합 회피
> ③응답값 `newCommits.size` 의 정확도 뿐이다 — **정합성 장치에서 효율 장치로 격하된다.**
>
> 이번 범위에서는 건드리지 않는다(§0-1). Phase 4 완료 후 "이 락이 지금 무엇을 지키는가"가
> 명확해진 상태에서 존폐를 따로 판단한다.
>
> **방향은 정해둔다 (2026-08-19): Redis 를 통째로 걷어낸다.**
> Phase 4 가 끝나면 Redis 가 하는 일이 이 락 하나뿐인데, 그 락은 더 이상 정합성을 지키지
> 않는다. 컨테이너·의존성·장애 지점을 락 하나 때문에 유지하는 셈이다.
>
> | 대안 | 다중 인스턴스 | 새 의존성 |
> |---|---|---|
> | 인메모리 락 (`ConcurrentHashMap<Long, Lock>`) | ❌ 인스턴스별 | 없음 |
> | MySQL `GET_LOCK()` | ✅ | 없음 |
> | 락 행 INSERT (`UNIQUE(user_id)`) | ✅ | 없음 |
> | 락 제거 | — | 없음 |
>
> 원래 Redis 였던 이유는 정합성 보장에 인스턴스 간 원자성이 필요해서였다. 그 요구가
> 사라지면 기준이 낮아진다 — 두 인스턴스가 동시에 페치해도 커밋 삽입은 `UNIQUE(sha)` 로
> 멱등이고 점수는 절대값이라 **결과가 안 틀린다.** 다중 인스턴스 정확성이 필요 없으므로
> **인메모리 락**을 택한다.
>
> `spring.cache.type: redis` 도 함께 지운다 — `@Cacheable` 이 코드에 하나도 없어서
> 아무 일도 하지 않는 설정이다.
>
> **Phase 4 와 같은 커밋에 섞지 않는다.** 랭킹 제거와 락 교체가 한 번에 들어가면
> 문제가 생겼을 때 어느 쪽인지 가릴 수 없다. Phase 4 → 초록불 확인 → 별도 작업.

**검증**: `./gradlew test`

### Phase 5 — 정리

- `endpoint-allowlist.txt` 확인 — 엔드포인트가 늘거나 줄지 않았으므로 그대로일 것이다.
  달라졌으면 원인을 파악하고 보고한다
- `AGENTS.md` 에 반영할 게 있는지 확인 (있으면 제안만, 임의로 고치지 않는다)

---

## 5. 벤치마크 — SQL 레벨로 다시 잰다

`bench/run.sh` 는 `ranking.redis.enabled` 를 켜고 끄는 A/B 다 (`bench/run.sh:2`).
스위치가 사라지므로 **그 축이 없어진다.** `docs/기록.md` 의 측정 표 전체가 이 축 위에 서 있다.

**결정됨: 앱에 A/B 스위치를 되살리지 않는다.**

두 조건을 앱에서 재려면 옛 `findTop30Rank`(매 요청 집계)를 프로퍼티 뒤에 남겨야 하는데,
그러면 경로가 다시 둘이 되고 이 프로젝트가 물렸던 구조로 돌아간다. 하지 않는다.

대신 `docs/기록.md` 228-236행의 방식을 따른다 — **앱을 거치지 않고 MySQL 에서 쿼리만 직접 잰다.**
그때 4,882ms → 531ms 를 잡아낸 방법이 이것이다.

### 할 일

`bench/query_ab.sh` (신규) — 같은 데이터에 대해 두 쿼리를 `docker exec ... mysql` 로 직접 재고
각 3회 측정해 중앙값을 남긴다.

| 조건 | 쿼리 |
|---|---|
| 매 요청 집계 (before) | 지금의 `findTop30Rank` native SQL 을 그대로 복사 |
| 집계 테이블 (after) | 3-3 의 Top 30 쿼리 |

개인 순위(`findUserRank` vs 3-4)도 같은 방식으로 잰다.
규모는 기존 `bench/seed.sql` 이 만드는 1만/5만/10만을 그대로 쓴다.

### 기존 파일 처리

- `bench/run.sh`, `bench/rank_ab.js` — **지우지 않는다.** 헤더에
  "`ranking.redis.enabled` 가 있던 시절의 측정 도구"라고 한 줄 적고 남긴다.
  문서의 표가 이걸 근거로 서 있어서, 지우면 그 표의 출처가 사라진다
- `bench/results/` — 그대로 둔다. 과거 실행 결과다

### 주의

절대값을 믿지 말 것. 문서 244-256행이 같은 스크립트로 두 번 재서 DB 절대값이 2배 가까이
달랐던 것을 기록해뒀다. **규모 간 기울기 비교만 의미가 있다.** 보고서에도 그렇게 적는다.

---

## 6. 범위 밖 — 건드리지 않는다

`CommitRepository.findMemberCommits` (그룹 멤버 랭킹) 는 그대로 둔다.
`DENSE_RANK` + `SUM(CASE ...)` native 쿼리이고, 이번 달·지난달을 함께 뽑는다.
집계 테이블로 단순화할 수 있지만 **그룹 안에서의 순위**라 계산 대상이 다르고,
한 번에 바꾸면 실패 범위가 커진다. 별도 작업으로 남긴다.

---

## 7. 되돌리기

단계마다 커밋했으므로 `git revert` 로 단계 단위 롤백이 된다.
Phase 3(읽기 전환) 이전까지는 **추가만 한 상태**라 되돌리기가 자명하다.
Phase 4 이후로 되돌리려면 삭제한 파일이 필요하므로 반드시 커밋 이력에서 복원한다.

---

## 8. 보고 형식

1. **각 Phase 별 `./gradlew test` 실제 출력** — 통과 개수와 실패 여부.
   Docker 없어 못 돌렸으면 **"못 돌렸다"고 쓴다. 통과했다고 쓰지 않는다**
2. **삭제한 파일 목록**과 각각을 지운 근거(무엇이 그 검증 대상을 대신하는가)
3. **불변조건 I1~I8 각각을 어느 테스트가 지키는지** — 테스트 이름으로
4. **사라진 코드량** — `git diff --stat`
5. **문서에 반영할 것** — `docs/기록.md` 는 고치지 않았으므로, 사용자가 직접 쓸 수 있게
   사실만 정리해서 넘긴다 (측정값, 사라진 구성요소, 남은 한계)
6. **판단이 필요해 멈춘 지점** — 5절 벤치마크 포함
