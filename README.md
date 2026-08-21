# 코테독촉기

[![tests](https://github.com/Layla7120/code_test_reminder_server/actions/workflows/test.yml/badge.svg)](https://github.com/Layla7120/code_test_reminder_server/actions/workflows/test.yml)

GitHub 저장소의 백준 풀이 커밋을 모아 **월별 랭킹**을 매기는 서비스. 그룹을 만들어 서로의 진척을 비교한다.
대학교 4학년 프로젝트로 **Flask**로 만들고 이후 **Kotlin + Spring Boot**로 옮겼다 — 두 구현이 한 저장소에 있다.

**기능은 조회·정렬·삽입·삭제가 전부다.**


<img src="docs/demo.png" alt="웹 데모" width="640"/>

## 아키텍처

```mermaid
flowchart LR
    Client["웹 데모 · API 클라이언트"] --> API["Spring Boot<br/>Controller · Service"]
    API -->|커밋 수집| GitHub["GitHub API"]
    API -->|"영속 데이터 · 랭킹"| MySQL[("MySQL<br/>commits · groups<br/>user_monthly_score")]
```

### 테이블

```mermaid
erDiagram
    users ||--o{ commits : "커밋을 남긴다"
    users ||--o{ history : "풀이를 기록한다"
    users ||--o{ participate : "그룹에 참여한다"
    users ||--o{ groups : "소유한다 (owner_id)"
    users ||--o{ user_monthly_score : "월별 점수를 갖는다"
    groups ||--o{ participate : "멤버를 갖는다"

    users {
        bigint user_id PK
        varchar github_id UK "GitHub login"
        varchar nickname UK
        varchar repository_name
        boolean active "소프트 삭제"
    }
    commits {
        bigint commit_id PK
        bigint user_id FK
        datetime commit_date "idx_commit_date"
        varchar sha "UNIQUE(user_id, sha) — 포크는 유저끼리 같은 sha 를 갖는다"
        varchar level "BRONZE~RUBY, UNRATED"
    }
    groups {
        bigint group_id PK
        bigint owner_id FK
        varchar group_name UK
        int member_max_count
        int member_counter "participate 에서 파생"
    }
    participate {
        bigint participate_id PK
        bigint group_id FK
        bigint user_id FK
    }
    user_monthly_score {
        bigint user_id PK "FK → users"
        char score_month PK "yyyyMM"
        int score "commits 에서 파생"
    }
    history {
        bigint history_id PK
        bigint user_id FK
        varchar problem_num
        varchar solve_time "HH:MM:SS 문자열"
    }
```

**진실 원천과 파생을 구분하는 게 이 스키마를 읽는 핵심이다.**

| 파생 | 원천 | 갱신 방식 |
|---|---|---|
| `user_monthly_score.score` | `commits` | 커밋 저장과 **같은 트랜잭션**에서 `COUNT(*)` 절대값으로 덮어쓴다 |
| `groups.member_counter` | `participate` | 조건부 원자적 `UPDATE` (`WHERE counter < max`) |

절대값이라 몇 번을 다시 계산해도 같은 값이 나온다 — 증분(`+= n`)이 한 번 틀리면 복구가
안 됐던 문제(`docs/기록.md` ②)를 구조로 없앤 것이다.
랭킹은 `user_monthly_score` **하나만** 읽는다. 예전에는 Redis ZSET 이 세 번째 사본이었고,
그 사본들이 어긋나는 게 결함의 출처였다.

`user_monthly_score` 에 **행이 있다 = 랭킹에 포함된다**. 그래서 랭킹 쿼리에 `active` 필터가
없다 — 탈퇴 시 행을 지워서 읽기 경로에서 조인을 없앴다(5만 행 기준 87ms → 0.5ms).

## 구조

```
server/       Kotlin + Spring Boot 구현 (현재)
app/          Flask 구현 (원본. 대조용, 유지보수 안 함)
migrations/   Flask 시절 Alembic 마이그레이션 (2개)
bench/        랭킹 성능 A/B 측정
docs/         기록
```

DB 스키마는 `server/src/main/resources/db/migration/`의 Flyway 마이그레이션이 진실 원천이다
(구 infra/init.sql 을 V1 베이스라인으로 승격 — [PLAN-db-foundation.md](PLAN-db-foundation.md) Phase 0).

설계 판단과 트레이드오프 — 왜 Redis가 이 규모에 과했는지, `init.sql`이 마이그레이션 도구가 아닌 이유,
결함이 어디서 왔는지 — 는 **[docs/기록.md](docs/기록.md)** 에 있다. 잘한 것보다 틀린 것을 더 자세히 적었다.

> 필요 없는 복잡도를 넣었고, 거기서 버그가 나왔고, 측정해보니 그 복잡도가 애초에 필요 없었다.

## 실행

**테스트** (Docker만 있으면 됨):

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
cd server && ./gradlew test
```

Testcontainers가 실제 MySQL을 띄운다. 이어서 `verifyEndpointCoverage`가
HTTP 테스트 없는 엔드포인트를 찾으면 빌드를 깬다. push·PR마다 GitHub Actions에서도 같은 명령이 돈다.

> `openjdk@21`이 PATH에 없으면(Homebrew keg-only) JAVA_HOME을 직접 지정:
> `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`

**서버**:

```bash
docker compose up -d          # MySQL (스키마는 기동 시 Flyway 가 만든다)
export DB_USER=reminder DB_PASSWORD=reminder DB_NAME=reminder GITHUB_TOKEN=...
cd server && ./gradlew bootRun    # http://localhost:8080 (웹 데모 포함)
```

실행 상세·API 명세·트러블슈팅 → [server/README.md](server/README.md) · 성능 측정 → [bench/README.md](bench/README.md)

## 기술 스택

|            | Flask (원본)                  | Kotlin/Spring Boot (현재) |
| ---------- | ----------------------------- | ------------------------- |
| 언어       | Python 3.11                   | Kotlin (JDK 21)           |
| 프레임워크 | Flask 3.1 + Smorest           | Spring Boot 4.0           |
| ORM        | SQLAlchemy 2.0                | Spring Data JPA           |
| 랭킹       | 매 요청 집계                  | 집계 테이블(user_monthly_score) |
| 마이그레이션 | Alembic                     | Flyway                    |
| 테스트     | 없음                          | Testcontainers            |

## 주요 API

| 메서드 | 경로                      | 설명                 |
| ------ | ------------------------- | -------------------- |
| `POST` | `/commits`                | GitHub에서 커밋 수집 |
| `GET`  | `/commits/grass`          | 잔디(날짜별 커밋 수) |
| `GET`  | `/rank`                   | 전체 상위 30명       |
| `GET`  | `/rank/users?userId=`     | 개인 순위            |
| `POST` | `/group`, `/group/member` | 그룹 생성 · 참여     |
| `GET`  | `/group/info?userId=`     | 내 그룹 + 멤버 현황  |

인증은 없다(`permitAll`). `userId`를 파라미터로 받는다 — 원본과 동일하며 범위에 넣지 않았다.
