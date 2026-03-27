# 코테독촉기 — Spring Boot 4.0 서버

Flask 레거시를 Kotlin + Spring Boot로 마이그레이션한 프로젝트입니다.
단순 포팅이 아니라 레거시의 **데이터 정합성·성능·동시성 결함**을 발견하고, 엔티티 설계 단계부터 방어하는 구조로 재설계했습니다.

---

## 기술 스택

| 항목 | Flask (레거시) | Spring Boot (현재) |
|------|--------------|------------------|
| 언어/프레임워크 | Python 3.11 + Flask 3.1 | Kotlin 2.2 + Spring Boot 4.0 |
| ORM | SQLAlchemy 2.0 | Spring Data JPA (Hibernate 7) |
| DB | MySQL (Cloud SQL) | MySQL (동일) |
| 캐싱 | Flask-Caching (SimpleCache) | Spring Cache + Redis ZSET |
| 커넥션 풀 | SQLAlchemy Pool (기본값 5) | HikariCP (pool-size=20) |
| 배포 | Docker + Gunicorn | Docker + 내장 Tomcat |
| 부하 테스트 | k6 | k6 (동일 시나리오) |

---

## 로컬 실행

### 사전 조건

Java 21, MySQL 8.0+, Redis 7.0+가 필요합니다.

```bash
# Java 21 설치 (macOS)
brew install openjdk@21

# MySQL 실행
brew services start mysql

# Redis 실행
brew services start redis
```

### 환경 변수

서버 실행 전 아래 환경 변수를 설정해야 합니다.

```bash
export DB_USER=root
export DB_PASSWORD=your_password
export GITHUB_TOKEN=ghp_your_token   # GitHub Personal Access Token (repo read 권한)

# 선택 (기본값이 있으므로 로컬에서는 생략 가능)
export DB_HOST=localhost
export DB_PORT=3306
export DB_NAME=reminder
export REDIS_HOST=localhost
export REDIS_PORT=6379
```

GitHub 토큰은 Settings → Developer Settings → Personal Access Tokens → Fine-grained tokens에서 발급합니다.
`Contents: Read` 권한이 필요합니다.

### DB 스키마 생성

`ddl-auto: validate`로 설정되어 있으므로, 스키마가 없으면 서버가 시작되지 않습니다.
MySQL에서 아래를 실행하세요.

```sql
CREATE DATABASE reminder CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE reminder;

CREATE TABLE users (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    github_id      VARCHAR(100) NOT NULL,
    nickname       VARCHAR(100) NOT NULL,
    repository_name VARCHAR(200) NOT NULL,
    active         BOOLEAN NOT NULL DEFAULT TRUE,
    created_at     DATETIME(6) NOT NULL,
    updated_at     DATETIME(6) NOT NULL,
    CONSTRAINT uk_users_github_id UNIQUE (github_id),
    CONSTRAINT uk_users_nickname  UNIQUE (nickname)
);

CREATE TABLE commits (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT NOT NULL,
    sha        VARCHAR(40) NOT NULL,
    level      VARCHAR(20) NOT NULL,
    problem    VARCHAR(200),
    commit_date DATE NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_commits_sha UNIQUE (sha),
    INDEX idx_commit_user_date (user_id, commit_date),
    INDEX idx_commit_date (commit_date)
);

CREATE TABLE `groups` (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_name       VARCHAR(100) NOT NULL,
    group_pw         VARCHAR(200),
    member_counter   INT NOT NULL DEFAULT 0,
    member_max_count INT NOT NULL DEFAULT 5,
    created_at       DATETIME(6) NOT NULL,
    updated_at       DATETIME(6) NOT NULL,
    CONSTRAINT uk_groups_name UNIQUE (group_name)
);

CREATE TABLE participates (
    participate_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_id       BIGINT NOT NULL,
    user_id        BIGINT NOT NULL,
    created_at     DATETIME(6) NOT NULL,
    updated_at     DATETIME(6) NOT NULL,
    CONSTRAINT uk_participate UNIQUE (group_id, user_id)
);

CREATE TABLE histories (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT NOT NULL,
    problem_num VARCHAR(50),
    solve_time  VARCHAR(50),
    created_at  DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NOT NULL
);
```

### 서버 실행

```bash
cd server

# JAVA_HOME이 설정된 경우
./gradlew bootRun

# JAVA_HOME을 직접 지정하는 경우
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.10/libexec/openjdk.jdk/Contents/Home \
  ./gradlew bootRun
```

서버가 정상 기동되면 `http://localhost:8080`에서 웹 데모 페이지로 API를 테스트할 수 있습니다.

---

## 프로젝트 구조

```
server/
├── build.gradle.kts
└── src/main/kotlin/com/reminder/server/
    ├── ServerApplication.kt                  # @EnableJpaAuditing, @EnableScheduling
    ├── global/
    │   ├── BaseTimeEntity.kt                 # createdAt(updatable=false), updatedAt
    │   ├── ClockConfig.kt                    # Clock Bean (테스트 가능한 시간), BCryptPasswordEncoder
    │   ├── exception/
    │   │   └── GlobalExceptionHandler.kt     # @RestControllerAdvice
    │   └── security/
    │       └── SecurityConfig.kt             # CSRF 비활성화, CORS 전체 허용
    └── domain/
        ├── user/
        │   ├── User.kt                       # updateProfile(), deactivate() 메서드만 노출
        │   ├── UserRepository.kt
        │   ├── UserService.kt
        │   └── UserController.kt
        ├── commit/
        │   ├── Commit.kt                     # 모든 필드 updatable=false (GitHub 데이터 불변)
        │   ├── CommitLevel.kt                # from() — 알 수 없는 레벨 즉시 예외
        │   ├── CommitRepository.kt           # JPQL + 네이티브 쿼리 (window function 최소화)
        │   ├── CommitJdbcRepository.kt       # bulkUpsert — sha 정렬 후 batchUpdate (데드락 방지)
        │   ├── GithubClient.kt               # RestClient + 정규식 파싱
        │   ├── CommitsSavedEvent.kt
        │   └── CommitService.kt              # Redis 분산 락(SETNX), DTO Projection
        ├── rank/
        │   ├── RankEntry.kt                  # Dense Rank 계산 확장 함수
        │   ├── RankingRedisRepository.kt     # ZSET + HASH, Lua ZADD GT, Shadow Key RENAME
        │   ├── RankingSelfHealingScheduler.kt # 매시 자가 치유 (DB → Redis 재동기화)
        │   ├── RankingEventListener.kt       # @TransactionalEventListener(AFTER_COMMIT)
        │   ├── RankService.kt                # Graceful Degradation
        │   └── RankController.kt
        ├── group/
        │   ├── Group.kt                      # member_counter (atomic UPDATE, OOM 방지)
        │   ├── Participate.kt                # 서로게이트 키 + UNIQUE(group_id, user_id)
        │   ├── GroupRepository.kt            # incrementMemberCounterIfNotFull (원자적 UPDATE)
        │   ├── GroupService.kt
        │   └── GroupController.kt
        └── history/
            ├── History.kt
            ├── HistoryRepository.kt
            ├── HistoryService.kt
            └── HistoryController.kt
```

---

## API 명세

서버 기동 후 `http://localhost:8080`에서 웹 UI로 직접 테스트할 수 있습니다.

### Users

| 메서드 | 경로 | 설명 |
|--------|------|------|
| POST | `/users` | 로그인 또는 신규 가입 (GitHub ID 기준 upsert) |
| GET | `/users?userId={id}` | 유저 조회 |
| PATCH | `/users/update` | 닉네임 / 레포명 수정 |
| DELETE | `/users/delete?userId={id}` | 회원 탈퇴 (soft delete: active=false) |
| GET | `/users/nick_name?nickName={name}` | 닉네임 중복 확인 |

### Commits

| 메서드 | 경로 | 설명 |
|--------|------|------|
| POST | `/commits` | GitHub에서 커밋 수집 및 저장 |
| GET | `/commits/grass?userId={id}` | 이번달 + 저번달 잔디 데이터 |
| GET | `/commits/activity?userId={id}` | 최근 7일 커밋 날짜 목록 |
| GET | `/commits/level?userId={id}` | 난이도별 커밋 수 |

### Rank

| 메서드 | 경로 | 설명 |
|--------|------|------|
| GET | `/rank` | 이번달 커밋 TOP 30 |
| GET | `/rank/users?userId={id}` | 내 랭킹 조회 |

### Groups

| 메서드 | 경로 | 설명 |
|--------|------|------|
| POST | `/group` | 그룹 생성 |
| POST | `/group/member` | 그룹 참가 |
| DELETE | `/group/leave?userId={id}&groupId={id}` | 그룹 탈퇴 |
| GET | `/group/info?userId={id}` | 내 그룹 정보 |
| GET | `/group/search?groupName={name}` | 그룹 검색 |
| GET | `/group/check/name?groupName={name}` | 그룹명 중복 확인 |
| PATCH | `/group/password` | 그룹 비밀번호 변경 |

### History

| 메서드 | 경로 | 설명 |
|--------|------|------|
| POST | `/history` | 풀이 기록 저장 |

---

## 주요 구현 결정 — 레거시 결함과 교정

### 1. Participate 복합키 → 서로게이트 키

**레거시 문제**: `(group_id, user_id)` 복합키를 JPA에 그대로 쓰면 `@EmbeddedId`가 필요하고,
`equals()` / `hashCode()` 누락 시 1차 캐시가 같은 행을 두 번 올리는 오동작이 생깁니다.

**교정**: `participate_id BIGINT AUTO_INCREMENT`를 PK로 사용하고
`UNIQUE(group_id, user_id)` 제약으로 비즈니스 규칙을 DB 레벨에서 보장합니다.

```kotlin
@Entity
class Participate(
    @Id @GeneratedValue val participateId: Long = 0,
    // ...
) {
    // UNIQUE(group_id, user_id)는 DDL에서 보장
}
```

---

### 2. member_counter — OOM 없는 원자적 카운트

**레거시 문제**: `participations.size`로 멤버 수를 세면 LAZY 컬렉션 전체 로딩이 발생합니다.
그룹에 멤버가 많을수록 OOM 리스크가 선형으로 증가합니다.

**교정**: DB 컬럼 `member_counter`를 유지하고, 참가 시 `WHERE counter < max` 조건의 원자적 UPDATE를 사용합니다.
반환값이 0이면 그룹이 가득 찬 것이므로 즉시 예외를 던집니다.

```sql
-- GroupRepository.kt
UPDATE groups
SET member_counter = member_counter + 1
WHERE id = :groupId AND member_counter < member_max_count
```

이 방식은 SELECT + UPDATE 두 번의 왕복 없이 단일 쿼리로 **정원 초과를 방지**합니다.

---

### 3. 랭킹 — Redis ZSET + Dense Rank

**레거시 문제**: 매 요청마다 `DENSE_RANK() OVER (ORDER BY commit_count DESC)` window function이
전체 테이블을 스캔합니다. VU 50에서 커넥션 풀 고갈 → p95 2,000ms 초과가 발생했습니다.

**교정**: Redis ZSET에 `(userId, commitCount)` 쌍을 유지하고, HASH에 `score → denseRank` 매핑을 미리 계산해 둡니다.

```
rank:commit:{yyyyMM}   ZSET   userId → commitCount
rank:dense:{yyyyMM}    HASH   commitCount → denseRank
```

조회 경로:

```
GET /rank/users?userId=N
  1. HGET rank:dense:{month} score    → O(1) 캐시 히트
  2. Cache Miss → ZREVRANK O(log N) fallback
  3. Redis 장애 → DB DENSE_RANK() fallback (Graceful Degradation)
```

**Lost Update 방지**: 배치 ZADD가 실시간 ZINCRBY를 덮어쓰는 문제를 Lua 스크립트로 해결합니다.
현재 점수보다 높을 때만 업데이트합니다 (ZADD GT).

**Micro-outage 방지**: 캐시 재빌드 시 Shadow Key에 먼저 쓰고 `RENAME`으로 원자적으로 교체합니다.
삭제 후 재생성 사이에 빈 응답이 반환되는 구간이 없습니다.

**자가 치유**: 매 정각 스케줄러가 DB 집계값으로 Redis를 재동기화합니다.
Redis 장애 복구 후에도 데이터 정합성이 자동으로 회복됩니다.

---

### 4. 커밋 Bulk Upsert — 데드락 방지

**레거시 문제**: 100개 커밋을 개별 INSERT하면 100번의 네트워크 왕복이 발생합니다.

**교정**: `JdbcTemplate.batchUpdate`로 100건을 1회 왕복으로 처리합니다.
INSERT 전에 sha를 정렬하는 이유는 InnoDB Next-Key Lock 순서를 통일하기 위해서입니다.
서로 다른 순서로 INSERT하면 트랜잭션끼리 교착 상태에 빠집니다.

```kotlin
// CommitJdbcRepository.kt
val sorted = commits.sortedBy { it.sha }  // 데드락 방지: 잠금 획득 순서 통일
jdbcTemplate.batchUpdate(sql, sorted, chunkSize) { ps, commit -> ... }
```

---

### 5. 커밋 중복 수집 방지 — Redis 분산 락

**문제**: 같은 유저에 대해 동시에 여러 수집 요청이 들어오면 GitHub API를 중복 호출하고
DB에 중복 INSERT 시도가 발생합니다.

**교정**: SETNX로 30초 TTL 락을 획득합니다. 락 획득 실패 시 즉시 409 반환합니다.
sha UNIQUE 제약이 DB 레벨 최후 방어선 역할을 합니다.

---

### 6. 인덱스 무효화 방지 — 날짜 컬럼 함수 제거

**레거시 문제**: `WHERE YEAR(commit_date) = 2025 AND MONTH(commit_date) = 3`은
함수 적용으로 인해 `idx_commit_date` 인덱스를 타지 않고 Full Scan을 합니다.

**교정**: 파라미터로 범위를 직접 전달합니다.

```kotlin
// CommitRepository.kt
@Query("SELECT c FROM Commit c WHERE c.userId = :userId AND c.commitDate BETWEEN :start AND :end")
fun findByUserAndDateRange(userId: Long, start: LocalDate, end: LocalDate): List<Commit>
```

---

### 7. @TransactionalEventListener — DB 커밋 후 Redis 업데이트

**문제**: DB 트랜잭션 중에 Redis를 업데이트하면, DB 롤백 시 Redis는 이미 변경된 상태로 불일치가 생깁니다.

**교정**: `@TransactionalEventListener(phase = AFTER_COMMIT)`을 사용합니다.
DB 커밋이 완료된 이후에만 Redis ZINCRBY를 실행하므로, DB와 Redis가 항상 일관된 상태를 유지합니다.

---

## 성능 비교 (k6)

Flask와 동일한 조건(더미 유저 100명, 커밋 ~50,000건, HikariCP=20)에서 측정합니다.

```bash
# Flask 먼저 측정
k6 run --out json=k6_result_before.json k6/k6_flask.js

# Spring Boot 측정
k6 run --out json=k6_result_spring.json k6/k6_spring.js
```

| 시나리오 | Flask p95 | Spring Boot 목표 | 개선 원인 |
|---------|-----------|-----------------|----------|
| tuned (VU 100) | 86ms | < 30ms | Redis HGET O(1) |
| exhaustion (VU 50) | 2,000ms+ | < 100ms | 커넥션 풀 고갈 → Redis 오프로딩 |
| redis 전용 (VU 50) | — | < 10ms | HGET 직접 경로 |

---

## 트러블슈팅

### 서버 시작 시 DB 연결 실패

```
Communications link failure — The last packet sent successfully to the server was 0 milliseconds ago.
```

MySQL이 실행 중이지 않거나 환경 변수가 설정되지 않은 경우입니다.

```bash
# MySQL 상태 확인
brew services list | grep mysql

# MySQL 시작
brew services start mysql

# 환경 변수 확인
echo $DB_USER $DB_PASSWORD
```

### ddl-auto: validate 오류

스키마와 엔티티가 불일치하는 경우입니다.
"DB 스키마 생성" 섹션의 DDL을 실행했는지 확인하세요.

### Redis 연결 실패

```bash
brew services start redis
redis-cli ping  # PONG이 나와야 정상
```

Redis가 없어도 서버는 기동됩니다. 단, 랭킹 조회 시 DB fallback으로 동작합니다.

### Java 버전 오류

```bash
java -version  # openjdk 21이어야 함

# JAVA_HOME 직접 지정
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.10/libexec/openjdk.jdk/Contents/Home \
  ./gradlew bootRun
```
