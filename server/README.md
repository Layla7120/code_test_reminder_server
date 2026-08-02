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

# MySQL + Redis (저장소 루트에서)
docker compose up -d
```

스키마는 `infra/init.sql`이 컨테이너 최초 생성 시 자동 적용됩니다.
**이미 `mysql-data` 볼륨이 있으면 다시 실행되지 않습니다** — 스키마를 바꿨다면
`docker compose down -v` 후 다시 올려야 합니다.

### 환경 변수

```bash
export DB_USER=reminder DB_PASSWORD=reminder DB_NAME=reminder
export GITHUB_TOKEN=ghp_...        # GitHub API 호출용
```

기본값이 있어 로컬에서는 `DB_HOST`(localhost) / `DB_PORT`(3306) /
`REDIS_HOST`(localhost) / `REDIS_PORT`(6379) / `PORT`(8080)는 생략할 수 있습니다.

### 기동

```bash
cd server && ./gradlew bootRun
```

`http://localhost:8080`에 웹 데모 페이지가 함께 뜹니다.

### 테스트

```bash
cd server && ./gradlew test        # 74개. Docker만 있으면 됨
```

Testcontainers가 실제 MySQL·Redis를 띄우므로 `docker compose`를 따로 켜지 않아도 됩니다.
운영과 **같은 `infra/init.sql`**을 그대로 물리기 때문에, 엔티티와 스키마가 어긋나면
서버를 띄우지 않아도 테스트가 잡습니다.

테스트 뒤에 `verifyEndpointCoverage`가 이어 돕니다. 엔드포인트에 HTTP 테스트가 없으면
빌드가 깨집니다 — 판정 근거는 `build/endpoint-audit.txt`(테스트 실행 중 실제로 라우팅된
핸들러를 측정한 값)이고, 아직 못 채운 것은 사유와 함께
`src/test/resources/endpoint-allowlist.txt`에 적혀 있습니다.

---

## API

서버 기동 후 `http://localhost:8080`에서 웹 UI로 직접 호출해볼 수 있습니다.

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
  rank/      랭킹. Redis 경로 + DB 폴백
  user/      유저
  history/   풀이 기록
global/
  exception/ 도메인 예외 + @RestControllerAdvice
  security/  SecurityConfig (인증 없음, CORS)
  ClockConfig, BaseTimeEntity
```

각 도메인은 `Controller → Service → Repository` 3계층입니다.

### 설정 스위치

| 프로퍼티 | 기본값 | 용도 |
|---|---|---|
| `ranking.redis.enabled` | `true` | `false`면 Redis를 건너뛰고 DB 경로로. A/B 측정과 장애 재현용 |
| `ranking.sync.cron` | `0 0 * * * *` | 자가 치유 스케줄러 주기 |

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

엔티티와 `infra/init.sql`이 어긋난 것입니다. 스키마를 고쳤다면 볼륨을 지우고 다시 올려야 합니다.

```bash
docker compose down -v && docker compose up -d
```

> `validate`는 **컬럼과 타입만** 검사합니다. UNIQUE 제약과 인덱스는 검증하지 않으므로,
> 그쪽이 어긋나도 부팅은 정상입니다.

### Redis 연결 실패

```bash
docker exec -i reminder-redis redis-cli ping     # PONG
```

### `/rank`가 빈 배열

Redis가 비어 있으면 `getTop30()`이 **에러 없이** DB 폴백을 탑니다.
그래도 빈 배열이면 이번 달 커밋 데이터가 없는 것입니다.

```bash
docker exec -i reminder-redis redis-cli ZCARD rank:commit:$(TZ=Asia/Seoul date +%Y%m)
```

### Java 버전 오류

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
java -version    # 21.x
```

Homebrew의 `openjdk@21`은 keg-only라 PATH에 자동으로 잡히지 않습니다.
