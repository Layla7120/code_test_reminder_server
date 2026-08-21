# PLAN: 단순화 — 삭제 정책에서 시작해 코드·주석·문서까지

**미래 층이다.** 완료되면 `docs/archive/` 로 옮긴다 →
[ADR-0007](docs/adr/0007-문서를-시제로-세-층으로-나눈다.md)

이 계획은 결정을 담지 않는다. **결정은 전부 `docs/adr/` 에 있다.**
여기 있는 건 **순서와 검증**뿐이다.

---

## 0. 시작 전 반드시 읽을 것

**이 계획은 다른 에이전트가 그대로 실행하기 위한 것이다. 추측하지 말고 여기 적힌 것만 한다.**

### 0-1. 손대면 안 되는 것

| 대상 | 이유 |
|---|---|
| `docs/기록.md` | **사용자 본인이 쓴 글이다. 한 글자도 고치지 않는다.** 반영할 내용이 생기면 보고만 한다 |
| `docs/archive/` | 과거 층. 완료 시점의 사실이라 현재 코드와 어긋나는 게 정상이다 |
| `app/`, `migrations/` | Flask 원본. 대조용이라 건드리지 않는다 |
| 적용된 Flyway 마이그레이션 | 수정하지 않는다. Flyway 가 체크섬으로 막는다 — 고치려면 새 파일 |

`docs/기록.md` 에는 Phase 4 에서 삭제된 테스트 이름이 5개 남아 있다.
**낡은 게 아니라 그 시점의 사실이다.** 고치려 들지 않는다 →
[ADR-0007](docs/adr/0007-문서를-시제로-세-층으로-나눈다.md)

### 0-2. 먼저 읽을 것 — 이 순서로

1. `docs/adr/README.md` — 문서 층위와 ADR 목록
2. 착수할 Phase 가 참조하는 ADR (각 Phase 머리에 링크가 있다)
3. `docs/용어집.md` — 진실 원천 / 파생 사본 / 독립적 제2진술 / 앵커 / 게이트 / 포트

**결정은 전부 ADR 에 있다. 이 계획서에는 순서와 검증만 있다.**
ADR 과 이 문서가 어긋나면 **ADR 이 맞다.** 어긋난 걸 발견하면 고치지 말고 보고한다.

### 0-3. ADR 상태를 바꾸는 조건

지금 ADR 9개는 전부 `**상태** 승인됨(미구현)` 이다.

**Phase 를 끝내고 게이트가 초록불일 때만** 해당 ADR 을 `**상태** 승인됨` 으로 바꾼다.
바꾸는 순간 `scripts/check-doc-refs.sh` 가 그 ADR 이 약속한 테스트의 실재를 검사하기 시작한다.
**게이트가 없는 ADR 을 `승인됨` 으로 표시하면 빨간불이 난다.**

*"결정했다"* 와 *"했다"* 를 문서가 구별하게 만드는 장치다. 순서를 뒤집지 않는다.

### 0-4. 검증 명령 — 모든 Phase 끝에 실행

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
cd server && ./gradlew test
```

`test` 뒤에 `verifyEndpointCoverage` 가 자동으로 이어 돈다 (`server/build.gradle.kts`).
Docker 가 안 떠 있으면 Testcontainers 가 못 뜬다 —
**"못 돌렸다"고 명시하고 멈춘다. 통과했다고 쓰지 않는다.**

문서를 건드렸으면 이것도 같이:

```bash
./scripts/check-doc-refs.sh
```

### 0-5. 고의 파손을 빠뜨리지 않는다

`AGENTS.md` §2 — 테스트를 낸 뒤 본문을 일부러 망가뜨려 빨간불이 나는지 확인한다.
되돌릴 때 `git checkout -- <파일>` 을 **쓰지 않는다** (커밋 안 한 정당한 수정까지 날아간다).
파손 전에 먼저 커밋하거나 편집기로 파손하고 편집기로 복원한다.

그리고 `AGENTS.md` §2-1 — **새 테스트가 실패하면 본문이 맞는지 먼저 확인한다.**
반사적으로 기대값을 실제 동작에 맞추면 결함을 테스트로 박제한다.
Phase 4 의 `NO ACTION` vs `RESTRICT` 가 정확히 그 위험이 있는 자리다.

---

## 1. 왜 이 순서인가

의존성이 있다.

```
Phase 1 (수집 전체화) ──▶ Phase 2 (물리 삭제)
   "GitHub 이 원본"이 참이 돼야        재가입 복구를 포기할 수 있다
   
Phase 3 (history) ── 독립

Phase 4 (게이트) ──▶ Phase 5 (문서·주석)
   앵커가 있어야                    문서를 게이트에 태울 수 있다
```

**Phase 1 이 Phase 2 보다 먼저여야 한다.** 순서를 뒤집으면 물리 삭제를 넣는 순간
*"재가입하면 최신 30건만 돌아온다"* 는 상태가 생긴다 —
[ADR-0001](docs/adr/0001-사용자-삭제를-물리-삭제로-단일화한다.md) 이
[ADR-0003](docs/adr/0003-github-커밋을-전체-이력으로-수집한다.md) 에 기대고 있다.

각 Phase 끝에서 `./gradlew test` 가 초록불이어야 한다.
**Docker 가 없어 못 돌렸으면 "못 돌렸다"고 쓴다. 통과했다고 쓰지 않는다.**

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
cd server && ./gradlew test
```

---

## Phase 1 — GitHub 커밋 전체 수집

**근거** [ADR-0003](docs/adr/0003-github-커밋을-전체-이력으로-수집한다.md) ·
[ADR-0004](docs/adr/0004-페이지네이션-루프를-포트-아래-두고-http-를-가짜로-만든다.md)

- `GithubClient` 가 `RestClient.Builder` 를 주입받도록 변경 (테스트가 끼어들 수 있게)
- `?per_page=100&page=N` 반복. 빈 응답에서 종료. **상한 없음**
- `GithubClientPort` 시그니처는 **바꾸지 않는다** — 루프는 포트 아래에 둔다

**신규 테스트** `GithubClientTest` (`MockRestServiceServer`):

| 경우 | 무엇을 증명 |
|---|---|
| 100건 페이지 → 빈 페이지 | 경계에서 멈춘다 (무한 루프 아님) |
| 100건 미만 단일 페이지 | 첫 페이지에서 끝난다 |
| 100 / 100 / 37 | 237건 전부 저장된다 |
| 2페이지째 500 | 부분 저장이 아니라 전체 롤백 |

> **고의 파손 확인**: 종료 조건을 `<` → `<=` 로 뒤집어 빨간불이 나는지 본다.
> 안 나면 테스트가 루프에 안 닿고 있는 것이다 (= 배선이 틀렸다).

---

## Phase 2 — 물리 삭제로 단일화

**근거** [ADR-0001](docs/adr/0001-사용자-삭제를-물리-삭제로-단일화한다.md) ·
[ADR-0002](docs/adr/0002-cascade-는-파생값의-원천이-아닐-때만-건다.md)

**2-1. 마이그레이션** `V<yyyyMMddHHmmss>__physical_user_deletion.sql`

- FK 재생성: `commits`·`history`·`user_monthly_score` → `ON DELETE CASCADE`
- `participate.user_id`, `groups.owner_id` → **그대로 둔다** (RESTRICT)
- `users.active` 컬럼 DROP

**2-2. 코드**

- `User.active`·`deactivate()`·`reactivate()` 제거
- `UserService.deleteUser` — `leaveGroup` 루프 후 `userRepository.delete(user)`.
  `deleteAllByUserId` 호출 제거 (CASCADE 가 한다)
- `UserService.loginOrCreate` — 비활성 분기와 `restoreScores` 제거
- `CommitService.fetchAndSaveCommits` — `if (user.active)` 가드 제거
- `UserMonthlyScoreRepository.backfillFromCommits` 제거 (호출자가 사라짐)

**테스트**
- `UserLifecycleApiTest` 개정 — 탈퇴 후 `GET /users` 가 **404**, 닉네임 재사용 가능,
  재가입 시 **새 `user_id`**
- `UserDeactivationApiTest` — 이름이 맞지 않게 된다. `UserDeletionApiTest` 로 개명

> **API 계약 변경이다.** `AGENTS.md` 규칙에 따라 HTTP 계층 테스트를 함께 낸다.

---

## Phase 3 — history 를 살린다

**근거** [ADR-0005](docs/adr/0005-history-를-살리고-문제당-한-행으로-정의한다.md)

**3-1. 마이그레이션** `V<...>__history_rebuild.sql`

```sql
-- 되돌릴 수 없다. 기존 행을 지우고 스키마를 바꾼다.
-- 근거: solved_at 을 NOT NULL 로 넣어야 하는데 기존 행에 그 값을 만들 근거가 없다.
--       실사용자가 없고 이 기능은 미완성이라 지금 데이터는 개발 중 넣어본 값뿐이다.
DELETE FROM history;
```
이어서 `solve_time` → `INT`, `solved_at DATETIME(6) NOT NULL` 추가,
`uk_history_user_problem UNIQUE (user_id, problem_num)`, FK 를 CASCADE 로.

**3-2. 코드**
- `History` 엔티티 — `solveTime: Int`, `solvedAt: LocalDateTime`
- `POST /history` — 같은 문제 재등록 시 **갱신** (upsert). `@Valid` 추가
- `GET /history?userId=` 신규 — `solvedAt DESC`, 페이징 없음, `historyId` 미노출

**테스트** `HistoryApiTest` — 응답 모양, 정렬, 재등록 시 행이 안 늘고 갱신,
파라미터 누락 400

**결과**: `endpoint-allowlist.txt` 에서 `HistoryController#saveHistory` 삭제 (6 → 5).

---

## Phase 4 — 앵커와 게이트

**근거** [ADR-0008](docs/adr/0008-검증을-앵커와-게이트로-한다.md)

**4-1. `SchemaContractTest`** — `information_schema` 대조

| 앵커 | |
|---|---|
| FK 5개의 `DELETE_RULE` | ADR-0002 표와 일치 |
| `users` 에 `active` 없음 | |
| `history.solve_time` = `int`, `solved_at` `NOT NULL` | |
| `uk_history_user_problem` 존재 | |

> 기대값을 쓰기 전에 실제 출력을 본다 (`NO ACTION` vs `RESTRICT`).
> 단 `AGENTS.md` §2-1 — **기대값을 실제에 맞추기 전에 본문이 맞는지 먼저 확인한다.**

**4-2. 백틱 게이트** (`scripts/check-doc-refs.sh` 프로토타입 있음 — 빌드에 연결한다) — 현재·미래 층 문서와 `.kt` 주석에서 세 종류만 검사:
① `[A-Z]\w*Test` ② `/` 포함 경로 ③ `uk_`·`fk_`·`idx_` 접두사.
`docs/기록.md` 와 `docs/archive/` 는 **제외**.

**4-3. 죽은 코드 스크립트** — 선언됐지만 호출 0회인 심볼.
프레임워크 호출(`@Bean`, `@ExceptionHandler`, 컨트롤러 핸들러, JPA projection getter)은 예외.

**이번에 지울 것 (측정으로 확인된 4건):**

| 대상 | 비고 |
|---|---|
| `CommitRepository.existsBySha` | 호출 0회. **의미도 틀렸다** — 전역 sha 조회라 남의 커밋에 true |
| `GroupRepository.findByGroupName` | 호출 0회 |
| `UserRepository.findByNickname` | 호출 0회 |
| `RankProjection.kt` → `Projections.kt` | 파일명이 가리키는 타입이 없다 |

> **오탐이 늘면 규칙을 넓히지 말고 게이트를 지운다.** (ADR-0008)

---

## Phase 5 — 문서와 주석

**근거** [ADR-0007](docs/adr/0007-문서를-시제로-세-층으로-나눈다.md) ·
[ADR-0009](docs/adr/0009-주석은-이-파일의-코드를-설명할-때만-남긴다.md)

- `docs/삭제-명세.md` 개정 — §1~5 를 물리 삭제로 다시 쓰고, §6 의 미결정 4개는
  ADR 링크로 대체 (전부 결정됐다)
- `README.md` — ERD 해상도 낮춤(엔티티·관계만), "81개 통과" 제거,
  측정값에 시점 병기
- `AGENTS.md` — 게이트 있는 규칙은 한 줄 + 경위 링크로 축약. 주석 규칙 추가.
  **순 증가 없이**
- 주석 정리 — 판정 질문 하나: *이 주석이 설명하는 코드가 이 파일에 있는가?*
  대상: `CommitService`(없어진 Redis 락 9줄), `UserService`(없어진 `active` 경위) 등

**검증**: 4-2 백틱 게이트가 초록불.

---

## 보고 형식

1. Phase 별 `./gradlew test` **실제 출력**. 못 돌렸으면 못 돌렸다고 쓴다
2. 지운 파일·함수 목록과 각각의 근거
3. **사라진 코드량** — `git diff --stat`
4. 각 ADR 의 "어떻게 지켜지나"가 실제로 어느 테스트인지 — 이름으로
5. 판단이 필요해 멈춘 지점

---

## 범위 밖

- `findMemberCommits` (그룹 멤버 랭킹) — 유일하게 남은 매 요청 집계 경로. 별도 작업
- `PLAN-db-foundation.md` Phase 2 잔여(`github_numeric_id`), Phase 4 잔여(타입 축소),
  Phase 5(CHECK 제약), Phase 6(인덱스)
- 테스트 컨테이너 static 싱글턴 전환
- `bench/query_ab.sh` (SQL 레벨 재측정)
