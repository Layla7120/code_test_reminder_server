# Flask → Kotlin Spring Boot 마이그레이션 계획

> 토스페이먼츠 자소서 "구현 난이도가 가장 높았던 프로젝트" 문항 소재
> 단순 포팅이 아닌 **레거시의 설계 결함을 발견하고 교정한 경험**이 핵심이다.

---

## 배경

알고리즘 문제 풀이 독려 서비스 "코테독촉기"를 Flask로 구현했다.
부하 테스트(k6)로 **p95 4,234ms → 86ms** 성능 개선 경험이 있다.

이 프로젝트를 Kotlin + Spring Boot로 마이그레이션하면서:
1. 레거시 코드에 숨어 있던 **데이터 정합성 · 성능 결함**을 발견
2. 엔티티 설계 단에서부터 방어하는 구조로 교정
3. 동일 조건(시드 데이터, 커넥션 풀)에서 **Flask vs Spring Boot p95 재측정**

---

## 기존 기술 스택 vs 전환 목표

| 항목 | Flask (레거시) | Spring Boot (목표) |
|------|--------------|------------------|
| 언어/프레임워크 | Python 3.11 + Flask 3.1 | Kotlin + Spring Boot 4.0 |
| ORM | SQLAlchemy 2.0 | Spring Data JPA (Hibernate) |
| DB | MySQL (Cloud SQL) | MySQL (동일) |
| 캐싱 | Flask-Caching (SimpleCache) | Spring Cache + Redis |
| 커넥션 풀 | SQLAlchemy Pool | HikariCP |
| 마이그레이션 | Alembic | Flyway |
| 배포 | Docker (Gunicorn) | Docker (내장 Tomcat) |

---

## 레거시에서 발견한 설계 결함 — 전체 발전 과정

### 1단계: 엔티티 설계 (데이터 정합성)

#### 결함 1. Participate 복합키

**레거시 코드 (Flask)**
```python
class Participate(db.Model):
    group_id = db.Column(db.Integer, primary_key=True)
    user_id  = db.Column(db.Integer, primary_key=True)
```

**왜 문제인가**
JPA에서 복합키는 `@EmbeddedId` 또는 `@IdClass`가 필요하다.
`equals()` / `hashCode()` 누락 시 1차 캐시(영속성 컨텍스트)가 같은 행을 두 번 올리는 오동작이 생긴다.

**해결: 대리키 + UNIQUE 제약**
```kotlin
@Entity
@Table(uniqueConstraints = [UniqueConstraint(columnNames = ["group_id", "user_id"])])
class Participate(
    val group: Group,
    val user: User,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0  // 비즈니스 식별은 UNIQUE 제약이, JPA 관리는 숫자 PK가 담당
}
```

**면접 답변**
> "비즈니스 식별 역할과 JPA 관리용 PK를 분리했습니다. 복합키가 갖던 '중복 참여 방지' 의미는 UNIQUE 제약이 DB 레벨에서 보장합니다."

---

#### 결함 2. Group.member_counter Lost Update

**레거시 코드 (Flask)**
```python
group.member_counter += 1  # 동시 요청 시 갱신 손실 발생
db.session.commit()
```

동시에 두 유저가 마지막 자리에 참여하면 둘 다 counter=4를 읽고 5로 저장 → 실제로 6명인데 counter는 5.

**해결: 컬럼 자체 제거**
```kotlin
@Entity
class Group(...) {
    // member_counter 없음 → Participate COUNT()로 항상 정확한 값

    @OneToMany(mappedBy = "group")
    val participations: MutableList<Participate> = mutableListOf()

    val memberCount: Int get() = participations.size
    fun isFull(): Boolean = memberCount >= memberMaxCount
}
```

Lock을 거는 것보다 역정규화 컬럼 자체를 제거하는 것이 근본적 해결이다.

**면접 답변**
> "Lock으로 증상을 치료하는 것보다 역정규화를 제거해 부정확할 여지 자체를 없앴습니다."

---

#### 결함 3. POST /commits Race Condition

**레거시 코드 (Flask)**
```python
# DB의 on_duplicate_key_update에만 의존 — 이미 문제가 생긴 후 수습
db.session.execute(insert(Commit).on_duplicate_key_update(...))
```

동시에 3번 호출하면 GitHub API 3번 중복 호출 + DB 락 경합이 발생한다.

**해결: Redis 분산 락 (멱등성)**
```kotlin
val acquired = redisTemplate.opsForValue()
    .setIfAbsent("commit:fetch:lock:$userId", "1", Duration.ofSeconds(30))
if (acquired != true) throw CommitFetchAlreadyInProgressException()
```

**면접 답변**
> "수동적 수습에서 능동적 차단으로. 같은 요청을 N번 보내도 결과가 1번과 동일한 멱등성 설계입니다."

---

### 2단계: Repository 설계 (쿼리 최적화)

#### 결함 4. 인덱스 무력화

**레거시 쿼리**
```sql
WHERE YEAR(commit_date) = YEAR(NOW())  -- 컬럼을 함수로 감싸면 인덱스 탐색 불가 → Full Scan
```

`commit_date`에 인덱스를 걸어뒀는데 `YEAR()`로 감싸면 B-Tree 인덱스가 무용지물이 된다.

**해결: 서비스 레이어에서 범위 파라미터 계산**
```kotlin
val thisMonthStart = now.withDayOfMonth(1).withHour(0)...
// 쿼리에서: commit_date >= :thisMonthStart AND commit_date < :nextMonthStart
```

#### 결함 5. NOW() 하드코딩으로 테스트 불가

**해결: Clock Bean 주입**
```kotlin
@Bean fun clock(): Clock = Clock.system(ZoneId.of("Asia/Seoul"))
// 테스트에서: Clock.fixed(특정시점) → 원하는 시점 재현 가능
```

#### 결함 6. 단건 Upsert 루프 (네트워크 100번)

**해결: JdbcTemplate.batchUpdate() + sha 정렬**
```kotlin
val sorted = commits.sortedBy { it.sha }  // InnoDB Next-Key Lock 순서 보장 → 데드락 방지
jdbcTemplate.batchUpdate(SQL, sorted, 100) { ps, commit -> ... }
// 100건 = 네트워크 1번
```

#### 결함 7. 단순 집계까지 Native Query 남용

윈도우 함수(`DENSE_RANK()`)가 필요한 랭킹 쿼리만 Native Query를 쓰고, 나머지는 JPQL로 교체했다.

```kotlin
// 잔디 그래프 — JPQL (타입 안전, 컴파일 타임 오류 검출)
@Query("SELECT c.commitDate AS commitDate, c.level AS level FROM Commit c WHERE ...")
fun findCommitSummariesByUserAndDateRange(...): List<CommitSummaryProjection>

// DTO Projection — 엔티티 전체 로딩 금지
// List<Commit> 반환 시 영속성 컨텍스트에 스냅샷 1,000개 로드 → OOM
```

---

### 3단계: Redis 랭킹 시스템 (아키텍처 전환)

#### 결함 8. DENSE_RANK() DB 연산의 O(N log N) 병목

`DENSE_RANK()`는 전체 유저 정렬 후 LIMIT 30을 적용한다.
유저 10만 명이면 매 호출마다 DB CPU 스파이크.

**해결: Redis ZSET으로 랭킹 연산 오프로딩**

```
Redis Keys
  rank:commit:{yyyyMM}   ZSET   userId → score     실시간 ZINCRBY 유지
  rank:dense:{yyyyMM}    HASH   score  → denseRank  배치 사전 계산
```

| | DB DENSE_RANK() | Redis ZSET |
|--|--|--|
| 읽기 복잡도 | O(N log N) | O(log N + 30) |
| 사용자 랭킹 조회 | O(N log N) | O(1) Cache Hit / O(log N) Cache Miss |

---

#### 결함 9. 마이크로 아웃티지 (delete + 재삽입 간극)

**초기 구현 문제**
```kotlin
redisTemplate.delete(key)          // 이 순간 ~
redisTemplate.executePipelined { } // 여기까지 조회하면 emptyList 반환
// 매시간 정각마다 서비스 덜컹거림
```

**해결: Shadow Key + Atomic RENAME**
```kotlin
// 1. temp 키에 먼저 쓰기
redisTemplate.opsForHash<String, String>().putAll(tempKey, denseRankMap)
// 2. 원자적 교체 O(1) — 다운타임 0ms
redisTemplate.rename(tempKey, denseKey)
```

---

#### 결함 10. Lost Update (배치 ZADD가 실시간 ZINCRBY를 덮어씀)

**시나리오**
1. 스케줄러가 DB에서 userId=1의 커밋 수 100개 읽음
2. 유저가 새 커밋 푸시 → ZINCRBY → Redis 101점
3. 스케줄러가 100점으로 RENAME → 101이 100으로 롤백

**해결: Lua ZADD GT 스크립트**
```lua
-- 현재값보다 클 때만 업데이트 (실시간 증분 보호)
local current = redis.call('ZSCORE', KEYS[1], ARGV[1])
if current == false or tonumber(ARGV[2]) > tonumber(current) then
    return redis.call('ZADD', KEYS[1], ARGV[2], ARGV[1])
end
return 0
```

---

#### 결함 11. getUserDenseRank OOM (수만 건 JVM Heap 적재)

**초기 구현 문제**
```kotlin
// 내 점수보다 높은 모든 멤버를 JVM Heap으로 → 50,000등이면 49,999건 로딩
.reverseRangeByScoreWithScores(key, myScore + 0.001, Double.MAX_VALUE)
?.toSet()  // OOM 발생 지점
```

**해결: score → denseRank HASH 사전 계산 + Graceful Degradation**

```
HASH 구조: score → denseRank
  - userId → rank 가 아님
  - 같은 점수 100명 = HASH 엔트리 1개 (훨씬 작음)
  - Cache Miss 조건: 배치 이후 처음 등장한 새 점수일 때만
```

```kotlin
fun getUserDenseRank(userId: Long, yearMonth: YearMonth): Long? {
    // 1. 내 점수 O(1)
    val myScore = redisTemplate.opsForZSet().score(zsetKey, userId.toString()) ?: return null

    // 2. score → rank HASH 조회 O(1)
    val cachedRank = redisTemplate.opsForHash<String, String>().get(hashKey, myScore.toLong().toString())

    // 3. Cache Hit → 즉시 반환
    if (cachedRank != null) return cachedRank.toLong()

    // 4. Cache Miss → Graceful Degradation
    // 동점자 처리는 일시 불완전하나 서버는 안전, 배치 후 자동 복구
    return redisTemplate.opsForZSet().reverseRank(zsetKey, userId.toString())?.plus(1)
}
```

**면접 답변**
> "Eventual Consistency의 빈틈을 인정하고 Fallback 전략을 썼습니다. 평소에는 O(1) Cache Hit, 실시간 점수 변동으로 캐시 미스가 났을 때만 O(log N) ZREVRANK로 우회합니다. 동점자 처리가 잠깐 부정확할 수 있지만, 시스템의 가용성과 응답 속도를 더 중요하게 판단했습니다."

---

## 자가 치유 스케줄러 전체 흐름

```
매시간 정각 RankingSelfHealingScheduler
  │
  ├─ Step 1: DB → Redis score ZSET 보정
  │    findMonthlyCommitCountPerUser(from, to)
  │    → Lua ZADD GT (실시간 증분 보호, Lost Update 방지)
  │
  └─ Step 2: Dense Rank HASH 재계산
       score ZSET 전체 읽기 (배치 — 요청 경로 아님)
       → toDenseRankEntries() (Kotlin Dense Rank 계산)
       → score → denseRank 매핑
       → Shadow Key 쓰기 → RENAME (원자적 교체)


실시간 Write (커밋 저장 시)
  DB bulkUpsert 성공
  → @TransactionalEventListener(AFTER_COMMIT)
  → ZINCRBY rank:commit:{yyyyMM} {count} {userId}
  → 실패해도 스케줄러가 최대 1시간 내 자동 복구


Read
  GET /rank
    → ZREVRANGE 0 29 WITHSCORES → toDenseRankEntries() (30건)
  GET /rank/users?user_id=N
    → ZSCORE → HGET score:denseRank → O(1)
    → Cache Miss → ZREVRANK → O(log N) Graceful Degradation
```

---

## 최종 자소서 한 문단

> "Flask로 구현한 코테독촉기를 Kotlin + Spring Boot로 마이그레이션하면서, 레거시 코드의 숨은 결함 11가지를 발견하고 교정했습니다. 엔티티 레벨에서는 JPA 영속성 컨텍스트 오동작을 유발하는 복합키를 대리키 + UNIQUE 제약으로 교정하고, member_counter의 Lost Update를 역정규화 제거로 원천 차단했습니다. 쿼리 레벨에서는 함수 조건으로 인한 인덱스 무력화를 범위 파라미터로 교정하고, 100건 단건 upsert를 JdbcTemplate batchUpdate로 전환했습니다. 랭킹 시스템은 DB의 O(N log N) DENSE_RANK()를 Redis ZSET으로 오프로딩하되, Atomic RENAME으로 마이크로 아웃티지를 제거하고, Lua ZADD GT 스크립트로 배치와 실시간 증분 간의 Lost Update를 방지했습니다. Cache Miss 시에는 O(1) 대신 O(log N)으로 우아하게 저하하는 Graceful Degradation을 적용해 가용성을 최우선으로 지켰습니다."

---

## 면접 예상 질문 & 답변

| 질문 | 핵심 답변 |
|------|---------|
| 왜 Flask → Spring Boot? | GIL 단일 스레드 한계 + SimpleCache 무효화 버그 발견. JVM 멀티스레딩 + Redis 분산 캐시로 정량 비교 |
| 복합키 대신 대리키를 쓴 이유? | JPA 1차 캐시는 PK로 식별. 비즈니스 규칙(UNIQUE)과 기술적 PK 분리 |
| member_counter를 Lock 대신 제거한 이유? | Lock은 증상 치료, 역정규화 제거는 원인 제거 |
| DENSE_RANK()를 Redis로 옮긴 이유? | O(N log N) → O(log N). 유저 수와 무관한 응답 시간 보장 |
| Cache Miss 때 왜 O(N) 대신 ZREVRANK? | 시스템 가용성 > 순간적 정확성. 배치 후 자동 복구되는 Eventual Consistency 수용 |
| 배치와 실시간 간 Lost Update 방어? | Lua ZADD GT: Redis 현재값 > DB값이면 덮어쓰지 않음 |
| RENAME이 왜 안전한가? | Redis 단일 스레드 기반 원자 연산 O(1). delete + 재삽입 간극 없음 |
