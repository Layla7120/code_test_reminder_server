# Flask → Kotlin Spring Boot 마이그레이션 계획

> 토스페이먼츠 자소서 "구현 난이도가 가장 높았던 프로젝트" 문항 소재
> 단순 포팅이 아닌 **레거시의 설계 결함을 발견하고 교정한 경험**이 핵심이다.

---

## 배경

알고리즘 문제 풀이 독려 서비스 "코테독촉기"를 Flask로 구현했다.
부하 테스트(k6)로 **p95 4,234ms → 86ms** 성능 개선 경험이 있다.

이 프로젝트를 Kotlin + Spring Boot로 마이그레이션하면서:
1. 레거시 코드에 숨어 있던 **데이터 정합성 결함 3개**를 발견
2. 이를 엔티티 설계 단에서부터 방어하는 구조로 교정
3. 동일 조건(시드 데이터, 커넥션 풀 설정)에서 **Flask vs Spring Boot p95 재측정**

---

## 기존 기술 스택 vs 전환 목표

| 항목 | Flask (레거시) | Spring Boot (목표) |
|------|--------------|------------------|
| 언어/프레임워크 | Python 3.11 + Flask 3.1 | Kotlin + Spring Boot 3.2 |
| ORM | SQLAlchemy 2.0 | Spring Data JPA (Hibernate) |
| DB | MySQL (Cloud SQL) | MySQL (동일) |
| 캐싱 | Flask-Caching (SimpleCache) | Spring Cache + Redis |
| 커넥션 풀 | SQLAlchemy Pool | HikariCP |
| 마이그레이션 | Alembic | Flyway |
| 배포 | Docker (Gunicorn) | Docker (내장 Tomcat) |

---

## 레거시에서 발견한 설계 결함 3가지

> 이 3가지가 자소서와 면접의 핵심 소재다.
> "단순히 언어를 바꾼 게 아니라, 기존 코드의 무엇이 왜 문제였는지 발견하고 고쳤다"는 것을 보여준다.

---

### 결함 1. Participate 테이블의 복합키(Composite Key)

#### 레거시 코드 (Flask)
```python
class Participate(db.Model):
    group_id = db.Column(db.Integer, db.ForeignKey('group.group_id'), primary_key=True)
    user_id  = db.Column(db.Integer, db.ForeignKey('users.user_id'), primary_key=True)
```
`group_id + user_id`를 묶어서 PK로 사용하고 있다.

#### 왜 문제인가
JPA(Java/Kotlin의 ORM)에서 복합키를 구현하려면 `@EmbeddedId` 또는 `@IdClass`를 써야 한다.
이 방식에는 두 가지 함정이 있다.

1. **`equals()` / `hashCode()` 직접 구현 필수**
   JPA의 1차 캐시(영속성 컨텍스트)는 객체를 `equals()`로 구분한다.
   이를 빠트리면 같은 행이 캐시에 두 번 올라가서 데이터 불일치가 발생한다.

2. **merge 연산 시 예측 불가한 동작**
   복합키 엔티티를 수정 후 `merge()`하면 Hibernate가 새 객체인지 기존 객체인지
   판단하지 못해 INSERT/UPDATE 중 하나를 잘못 선택하는 버그가 생길 수 있다.

#### 해결책: 대리키(Surrogate Key) 도입
```kotlin
@Entity
@Table(
    name = "participate",
    uniqueConstraints = [
        // 복합키가 갖던 "한 유저는 한 그룹에 한 번만" 의미는
        // UNIQUE 제약으로 그대로 보존
        UniqueConstraint(name = "uk_participate_group_user", columnNames = ["group_id", "user_id"])
    ]
)
class Participate(
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = false, updatable = false)
    val group: Group,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    val user: User,
) {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0  // 인위적 PK 추가
}
```

**핵심 논리**: 복합키가 갖던 비즈니스 의미(중복 참여 방지)는 UNIQUE 제약으로 DB가 보장한다.
대신 JPA 관리용 PK는 단순한 숫자로 분리한다. 역할을 분리한 것이다.

**면접 답변 포인트**:
> "JPA의 영속성 컨텍스트는 PK 기반으로 객체를 관리합니다. 복합키를 쓰면
> equals/hashCode 구현 누락 시 1차 캐시가 오동작합니다. 비즈니스 식별 역할과
> 기술적 PK 역할을 분리하는 것이 더 안전합니다."

---

### 결함 2. POST /commits 동시 호출 Race Condition

#### 레거시 코드 (Flask)
```python
# on_duplicate_key_update로 중복 sha를 DB에서 처리
db.session.execute(
    insert(Commit).on_duplicate_key_update(sha=Commit.sha)
)
```

#### 왜 문제인가
클라이언트가 GitHub 커밋 페칭 API를 **거의 동시에 3번 호출**하면:

```
요청 A → GitHub API 호출 시작
요청 B → GitHub API 호출 시작  (A가 끝나기 전)
요청 C → GitHub API 호출 시작  (A, B가 끝나기 전)
```

세 요청이 동시에 GitHub API를 호출하고, 동시에 동일한 커밋 목록을 INSERT 시도한다.
`on_duplicate_key_update`가 충돌을 처리해주지만:
- GitHub API를 3배로 중복 호출 (API rate limit 낭비)
- DB 락 경합 발생
- 불필요한 트랜잭션 3개가 동시에 실행

레거시는 **이미 문제가 생긴 다음에 DB가 수습하는** 수동적 구조다.

#### 해결책: Redis 분산 락으로 Application 레벨 멱등성 확보
```kotlin
fun fetchAndSaveCommits(userId: Long) {
    val lockKey = "commit:fetch:lock:$userId"

    // setIfAbsent = "없으면 세팅, 있으면 false 반환" (원자적 연산)
    val acquired = redisTemplate.opsForValue()
        .setIfAbsent(lockKey, "locked", Duration.ofSeconds(30))

    if (acquired != true) {
        // 이미 처리 중 → 두 번째, 세 번째 요청을 즉시 차단
        throw CommitFetchAlreadyInProgressException()
    }

    try {
        // GitHub API 호출 → 커밋 저장 (단 한 번만 실행됨)
    } finally {
        redisTemplate.delete(lockKey)  // 작업 완료 시 락 해제
    }
}
```

**핵심 논리**: "문제가 생기면 DB가 처리" → "문제 자체가 생기지 않도록 Application이 막는다".
이것이 **멱등성(Idempotency)** 설계다. 같은 요청을 N번 보내도 결과가 1번과 동일하다.

**면접 답변 포인트**:
> "레거시는 DB의 ON DUPLICATE KEY에 의존해 중복을 수습했습니다.
> 분산 환경에서는 Application 레벨에서 먼저 차단하는 것이 맞습니다.
> Redis의 SETNX(setIfAbsent)는 원자적 연산이라 분산 서버 환경에서도 안전합니다."

---

### 결함 3. Group.member_counter의 갱신 손실(Lost Update)

#### 레거시 코드 (Flask)
```python
class Group(db.Model):
    member_counter = db.Column(db.Integer, default=0)

# 그룹 참여 시
group.member_counter += 1
db.session.commit()
```

#### 왜 문제인가
동시에 두 유저가 마지막 자리(4명 → 5명 정원)에 참여 요청을 보내면:

```
유저 A: member_counter 읽음 → 4
유저 B: member_counter 읽음 → 4  (A가 커밋하기 전)
유저 A: 4 + 1 = 5, 저장
유저 B: 4 + 1 = 5, 저장  ← A의 업데이트를 덮어씀
결과: 실제로 6명인데 counter는 5
```

이것이 **갱신 손실(Lost Update)** 이다. 정원 초과 참여가 가능해지는 버그다.

#### 해결책 A — 비관적 락 (선택지 1)
```kotlin
@Lock(LockModeType.PESSIMISTIC_WRITE)  // SELECT FOR UPDATE
fun findByIdWithLock(groupId: Long): Group?

fun joinGroup(userId: Long, groupId: Long) {
    val group = groupRepository.findByIdWithLock(groupId)
    // 락을 잡은 상태에서 확인 → 다른 트랜잭션은 이 락이 풀릴 때까지 대기
    if (group.isFull()) throw GroupFullException()
    participateRepository.save(Participate(group, user))
}
```

#### 해결책 B — member_counter 컬럼 자체를 제거 (채택)
```kotlin
@Entity
class Group(...) {
    // member_counter 컬럼 없음
    // participations 관계에서 COUNT()로 항상 정확한 값을 가져옴

    @OneToMany(mappedBy = "group")
    val participations: MutableList<Participate> = mutableListOf()

    val memberCount: Int get() = participations.size  // 항상 정확

    fun isFull(): Boolean = memberCount >= memberMaxCount
}
```

**왜 B를 선택했나**:
비관적 락은 counter 갱신 문제를 해결하지만, counter가 존재하는 한 "부정확할 수 있는 필드"가 남아 있다.
counter 컬럼 자체를 제거하면 부정확할 여지 자체가 없다.
역정규화(Denormalization)가 원인이었으므로 정규화로 되돌리는 것이 근본적인 해결이다.

단, 그룹 참여 동시성은 여전히 비관적 락으로 보호한다 — 정원 초과를 막기 위해.

**면접 답변 포인트**:
> "member_counter는 성능을 위한 역정규화였지만, 동시성 환경에서 Lost Update를
> 발생시키는 시한폭탄이었습니다. Lock을 추가하는 것보다 역정규화를 제거하는 것이
> 문제의 근원을 없애는 방법이라 판단했습니다."

---

## 실행 계획 (4~5시간)

### Hour 1 — Spring Boot 프로젝트 뼈대

```
build.gradle.kts 핵심:
  - kotlin-spring 플러그인: Kotlin 클래스는 기본이 final → JPA 프록시 생성 불가
                            이 플러그인이 자동으로 open 처리해준다
  - kotlin-jpa 플러그인: JPA는 기본 생성자가 필요 → 자동 생성해준다
  - HikariCP pool-size=20 (Flask tuned 시나리오와 동일 조건)
```

### Hour 2 — 엔티티 5개 (방어적 설계 적용)

| 엔티티 | 핵심 설계 결정 |
|--------|-------------|
| Commit | 모든 필드 `updatable = false`, `sha` UNIQUE → 불변 |
| Group | `member_counter` 제거, `isFull()` 엔티티 메서드 |
| Participate | 대리키 + UNIQUE 제약 |
| User | `updateProfile()`, `deactivate()` 메서드로 상태 변경 통제 |
| History | `solveTime` String 유지 (하위호환) |

### Hour 3 — 서비스 레이어 (결함 3개 수정)

- Redis 분산 락 → 결함 2 해결
- 비관적 락 → 그룹 정원 초과 방지
- Native upsert → sha 배치 충돌 처리

### Hour 4 — 랭킹 쿼리 + 캐시 무효화 버그 수정

```kotlin
// Flask 버그: 새 커밋이 들어와도 60초간 stale 랭킹 유지
// 수정: @CacheEvict로 커밋 저장 시 캐시 무효화

@Cacheable("rank_top30")
fun getTop30(): List<RankResponse> { ... }

@CacheEvict("rank_top30", allEntries = true)
fun saveCommits(...) { ... }  // 쓰기 시 무효화
```

### Hour 5 — k6 동일 조건 재측정

```
측정 조건:
  - 동일 시드 데이터 (seed_data.py: 100유저, 5만 커밋)
  - 동일 VU 구성 (tuned 시나리오: 0→20→50→100)
  - 동일 커넥션 풀 (pool=20)

측정 대상: GET /rank, GET /group/info
비교: Flask p95 86ms vs Spring Boot p95 ?ms
```

---

## 기대 성과 (자소서 작성용)

| 항목 | Flask | Spring Boot |
|------|-------|-------------|
| p95 (최적화 전) | 4,234ms | 측정 예정 |
| p95 (최적화 후) | 86ms | 측정 예정 |
| Participate 복합키 버그 | 잠재적 캐시 오동작 | 대리키로 원천 차단 |
| 커밋 중복 페칭 | DB가 수습 | App 레벨 차단 |
| member_counter Lost Update | 버그 잠재 | 컬럼 제거 |
| 캐시 무효화 | 버그 있음 | @CacheEvict 수정 |

---

## 면접 예상 질문 & 답변 요약

**Q. 왜 Flask를 Spring Boot로 옮겼나요?**
> Python GIL로 인한 단일 스레드 한계와 SimpleCache의 무효화 부재 버그를 발견했고,
> JVM 멀티스레딩과 Redis 분산 캐시로 전환하면서 정량적으로 비교하고 싶었습니다.

**Q. 마이그레이션에서 가장 어려운 점이 뭐였나요?**
> 단순 코드 변환이 아니라 레거시의 숨은 결함을 찾는 것이었습니다.
> member_counter Lost Update, 커밋 중복 페칭 Race Condition, 복합키 JPA 관리 문제
> 세 가지를 엔티티 설계 단에서부터 구조적으로 방어하는 것이 핵심이었습니다.

**Q. @EmbeddedId 대신 대리키를 쓴 이유는?**
> JPA 1차 캐시는 PK로 객체를 식별합니다. 복합키에서 equals/hashCode 누락 시
> 같은 행이 캐시에 두 번 올라가는 오동작이 생깁니다. 비즈니스 식별(UNIQUE 제약)과
> 기술적 PK를 분리하는 것이 더 안전합니다.

**Q. 비관적 락 vs 낙관적 락, 왜 비관적 락을 선택했나요?**
> 그룹 참여는 정원이 꽉 찬 상황에서 동시 요청이 몰릴 수 있습니다.
> 낙관적 락은 충돌 후 예외를 던지고 클라이언트가 재시도해야 하는데,
> 정원 초과 같은 비즈니스 규칙 위반은 재시도 자체를 허용하면 안 됩니다.
> 따라서 먼저 락을 잡고 검사하는 비관적 락이 맞습니다.
