# 코테독촉기

GitHub 저장소의 백준 풀이 커밋을 모아 **월별 랭킹**을 매기는 서비스.
그룹을 만들어 서로의 진척을 비교한다.

대학교 4학년 학교 프로젝트로 **Flask**로 처음 만들었고, 나중에 **Kotlin + Spring Boot**로 옮겼다.
두 구현이 한 저장소에 함께 있다.

```
app/       Flask 구현 (원본, 운영했던 것)
server/    Kotlin + Spring Boot 구현 (현재)
bench/     랭킹 성능 A/B 측정
infra/     init.sql (DB 스키마)
docs/      기록
```

**기능은 조회·정렬·삽입·삭제가 전부다.**

---

## 읽을 것

**→ [docs/기록.md](docs/기록.md)**

무엇을 만들었고, **무엇이 과했고**, 그걸 어떻게 확인했는지의 기록.
잘한 것보다 틀린 것을 더 자세히 적었다.

한 문장으로 요약하면 이렇다.

> 필요 없는 복잡도를 넣었고, 그게 나를 물었고, 측정해서 왜 틀렸는지 말할 수 있게 됐다.

---

## 실행

### 테스트 (Docker만 있으면 됨)

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
cd server && ./gradlew test
```

Testcontainers가 실제 MySQL·Redis를 자동으로 띄운다. **26개 통과.**

> 이 맥은 자바가 Homebrew keg-only로 설치돼 PATH에 안 잡혀서 `JAVA_HOME`이 필요하다.

### 서버

```bash
docker compose up -d          # MySQL + Redis

export DB_USER=reminder DB_PASSWORD=reminder DB_NAME=reminder GITHUB_TOKEN=...
cd server && ./gradlew bootRun
```

`http://localhost:8080` — 웹 데모 페이지가 함께 뜬다.

### 랭킹 성능 측정

```bash
bash bench/run_benchmark.sh     # 유저 1만/5만/10만 x Redis on/off
```

→ [bench/README.md](bench/README.md)

---

## 기술 스택

| | Flask (원본) | Kotlin/Spring Boot (현재) |
|---|---|---|
| 언어 | Python 3.11 | Kotlin (JDK 21) |
| 프레임워크 | Flask 3.1 + Smorest | Spring Boot 4.0 |
| ORM | SQLAlchemy 2.0 | Spring Data JPA |
| DB | MySQL | MySQL |
| 캐싱 | Flask-Caching (프로세스 로컬) | Redis |
| 테스트 | 없음 | Testcontainers, 26개 |

Redis를 넣은 건 **이 규모에 과했다.** 이유와 근거는 [docs/기록.md](docs/기록.md)에 있다.

---

## 주요 API

| 메서드 | 경로 | 설명 |
|---|---|---|
| `POST` | `/commits` | GitHub에서 커밋 수집 |
| `GET` | `/commits/grass` | 잔디(이번달·저번달 날짜별 커밋 수) |
| `GET` | `/rank` | 전체 상위 30명 |
| `GET` | `/rank/users?userId=` | 개인 순위 |
| `POST` | `/group`, `/group/member` | 그룹 생성 · 참여 |
| `GET` | `/group/info?userId=` | 내 그룹 + 멤버 현황 |

인증은 없다(`permitAll`). `userId`를 파라미터로 받는다 — 원본과 동일하며 범위에 넣지 않았다.
