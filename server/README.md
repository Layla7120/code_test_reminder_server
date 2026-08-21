# 코테독촉기 — Kotlin + Spring Boot 서버

**운영 레퍼런스**입니다. 실행 방법, API, 트러블슈팅만 다룹니다.

- 이 프로젝트가 무엇이고 **무엇이 과했는지** → [`../docs/기록.md`](../docs/기록.md)
- 랭킹 성능 측정 재현 → [`../bench/README.md`](../bench/README.md)

---

## 실행

### 사전 조건

```bash
# JDK 21
brew install openjdk@21
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home

# MySQL (저장소 루트에서)
docker compose up -d
```

스키마는 앱 기동 시 Flyway가 적용합니다 (`src/main/resources/db/migration/`).
볼륨을 지울 필요가 없습니다 — 새 마이그레이션은 다음 기동 때 자동 적용됩니다.
(구 `init.sql` 시절 볼륨은 첫 기동에서 baseline으로 흡수됩니다.)

### 환경 변수

```bash
export DB_USER=reminder DB_PASSWORD=reminder DB_NAME=reminder
export GITHUB_TOKEN=ghp_...        # GitHub API 호출용
```

기본값이 있어 로컬에서는 `DB_HOST`(localhost) / `DB_PORT`(3306) / `PORT`(8080)는
생략할 수 있습니다.

### 기동

```bash
cd server && ./gradlew bootRun
```

`http://localhost:8080`에 웹 데모 페이지가 함께 뜹니다.

### 테스트

```bash
cd server && ./gradlew test        # Docker만 있으면 됨
```

Testcontainers가 실제 MySQL을 띄우므로 `docker compose`를 따로 켜지 않아도 됩니다.
운영과 **같은 Flyway 마이그레이션 체인**이 빈 컨테이너에 스키마를 만들기 때문에,
마이그레이션과 엔티티가 어긋나면 서버를 띄우지 않아도 테스트가 잡습니다.

테스트 뒤에 `verifyEndpointCoverage`가 이어 돕니다. 엔드포인트에 HTTP 테스트가 없으면
빌드가 깨집니다 — 판정 근거는 `build/endpoint-audit.txt`(테스트 실행 중 실제로 라우팅된
핸들러를 측정한 값)이고, 아직 못 채운 것은 사유와 함께
`src/test/resources/endpoint-allowlist.txt`에 적혀 있습니다.

---

## API

서버 기동 후 `http://localhost:8080`에서 웹 UI로 직접 호출해볼 수 있습니다.

| 주소 | 용도 |
|---|---|
| `http://localhost:8080/docs/swagger-ui` | Swagger UI |
| `http://localhost:8080/docs/openapi.json` | OpenAPI 3.1 문서 |

경로는 Flask 시절(`flask-smorest`)과 같습니다 — 옮기면서 빠졌던 것을 되돌린 것이라
주소도 그대로 뒀습니다. [`../docs/기록.md`](../docs/기록.md) 참조.

> **인증이 없습니다**(`permitAll`). `userId`를 클라이언트가 지정합니다.
> 레거시와 동일하며 이번 범위에서 다루지 않았습니다 — [`../docs/기록.md`](../docs/기록.md) 참조.

### Users

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/users` | 로그인 또는 신규 가입 (GitHub ID 기준 upsert) |
| GET | `/users?userId={id}` | 유저 조회 |
| PATCH | `/users/update` | 닉네임 / 레포명 수정 |
| DELETE | `/users/delete?userId={id}` | 탈퇴 (soft delete: `active=false`) |
| GET | `/users/nick_name?nickName={name}` | 닉네임 중복 확인 |

### Commits

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/commits` | GitHub에서 커밋 수집·저장. 응답 `saved`는 **실제 신규 저장 건수** |
| GET | `/commits/grass?userId={id}` | 이번달 + 저번달 날짜별 커밋 수 |
| GET | `/commits/activity?userId={id}` | 최근 7일 커밋 날짜 목록 |
| GET | `/commits/level?userId={id}` | 난이도별 커밋 수 |

### Rank

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/rank` | 이번달 커밋 TOP 30 |
| GET | `/rank/users?userId={id}` | 개인 순위 |

### Groups

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/group` | 그룹 생성 |
| POST | `/group/member` | 그룹 참가 |
| DELETE | `/group/leave?userId={id}&groupId={id}` | 탈퇴. **멤버가 아니면 예외** |
| GET | `/group/info?userId={id}` | 내 그룹 정보 (**목록** 반환 — 다중 참여 가능) |
| GET | `/group/search?groupName={name}` | 그룹명 앞부분 검색 |
| GET | `/group/check/name?groupName={name}` | 그룹명 중복 확인 |
| PATCH | `/group/password` | 그룹 비밀번호 변경 (소유자만) |

### History

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/history` | 풀이 기록 저장 |

---

## 구조

```
domain/
  commit/    커밋 수집·조회. GithubClientPort 로 외부 API 추상화
  group/     그룹 생성·참여·탈퇴
  rank/      랭킹. user_monthly_score 집계 테이블 단일 경로
  user/      유저
  history/   풀이 기록
global/
  exception/ 도메인 예외 + @RestControllerAdvice
  security/  SecurityConfig (인증 없음, CORS)
  ClockConfig, BaseTimeEntity
```

각 도메인은 `Controller → Service → Repository` 3계층입니다.

### 설정 스위치

랭킹 관련 스위치(`ranking.redis.enabled`, `ranking.sync.cron`)는 없어졌습니다 —
Redis 랭킹 경로를 걷어내면서 함께 사라졌습니다.

프로파일 `load-test`를 켜면 `GithubClient` 대신 `MockGithubClient`가 주입됩니다
(GitHub API 없이 수집 경로를 통과시킴).

---

## 트러블슈팅

### DB 연결 실패

```bash
docker compose ps                       # 컨테이너 상태
docker compose logs mysql | tail -20
echo $DB_USER $DB_PASSWORD              # 환경 변수 확인
```

### `ddl-auto: validate` 오류

엔티티와 Flyway 마이그레이션(`db/migration/`)이 어긋난 것입니다. 엔티티를 바꿨다면
대응하는 마이그레이션(`V<yyyyMMddHHmmss>__<설명>.sql`)을 추가해야 합니다 —
적용된 파일을 수정하면 Flyway 체크섬 오류가 납니다.

> `validate`는 **컬럼과 타입만** 검사합니다. UNIQUE 제약과 인덱스는 검증하지 않으므로,
> 그쪽이 어긋나도 부팅은 정상입니다.

#### 예외: `missing table [user_monthly_score]`

집계 테이블 추가(785ee3c) **이전에** 만든 볼륨입니다. `baseline-on-migrate`는 기존 스키마를
"V1과 같다"고 **선언만 하고 V1을 실행하지 않으므로**, 그때 누락된 테이블은 채워지지
않습니다. Flyway는 과거의 드리프트를 소급해 고치지 못합니다 (`docs/archive/PLAN-db-foundation.md` Phase 0).

개발 데이터라면 볼륨을 지우는 게 가장 깨끗합니다:

```bash
docker compose down -v && docker compose up -d
```

데이터를 지켜야 한다면 누락된 테이블만 만들고 재기동합니다 (`V1__baseline.sql`에서 발췌):

```sql
CREATE TABLE user_monthly_score (
    user_id     BIGINT   NOT NULL,
    score_month CHAR(6)  NOT NULL,
    score       INT      NOT NULL,
    PRIMARY KEY (user_id, score_month),
    INDEX idx_rank (score_month, score DESC),
    CONSTRAINT fk_ums_user FOREIGN KEY (user_id) REFERENCES users (user_id)
);
```

### `/rank`가 빈 배열

랭킹은 `user_monthly_score` 만 읽습니다. `commits` 에 행이 있어도 이 테이블이 비어 있으면
빈 배열이 나갑니다 — 점수는 커밋 수집 시점에 파생되기 때문입니다.

```sql
SELECT * FROM user_monthly_score WHERE score_month = DATE_FORMAT(NOW(), '%Y%m');
```

비어 있다면 이번 달 커밋이 없거나, 수집 경로(`POST /commits`)를 안 탄 데이터입니다.
SQL 로 직접 넣은 커밋은 점수가 자동으로 생기지 않습니다 —
`V20260819212939__backfill_user_monthly_score.sql` 의 SQL 을 다시 돌리면 채워집니다.

### Java 버전 오류

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
java -version    # 21.x
```

Homebrew의 `openjdk@21`은 keg-only라 PATH에 자동으로 잡히지 않습니다.
