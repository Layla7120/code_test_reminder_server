# 이 저장소에서 코드를 고칠 때

AI 에이전트와 사람 모두에게 해당한다.
일반론이 아니라 **이 프로젝트에서 실제로 물린 것만** 적는다.

## 테스트

**1. 새 API 나 API 동작 변경은 HTTP 계층 테스트를 함께 낸다.**

`ApiTest` 를 상속하면 서버가 실제로 뜬다. 서비스를 직접 부르지 않는다.

```kotlin
class GroupApiTest : ApiTest() {
    val res = post("/group", """{"userId":$id,"groupName":"","maxCount":5}""")
    assertThat(res.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
}
```

한때 테스트 35개가 전부 초록불인데 서버에는 버그 여섯 개가 있었다.
35개가 전부 `groupService.joinGroup(...)` 처럼 서비스를 직접 불렀기 때문이다.
`@Valid`, 예외→상태코드 매핑, 응답 본문은 컨트롤러를 지나야만 존재한다.

**2. 테스트를 낸 뒤 본문 코드를 일부러 망가뜨려 빨간불이 나는지 확인한다.**

지웠을 때 아무 테스트도 안 깨지는 코드는 검증되고 있지 않은 것이다.
실제로 이 확인에서 "공백 비밀번호도 참여된다" 테스트가 생성 쪽 `trim` 만
덮고 있고 참여 쪽은 안 덮는다는 걸 찾았다.

**3. DB·Redis 는 Testcontainers 로 실제를 띄운다. H2·임베디드 Redis 금지.**

증명 대상이 InnoDB row lock 과 Redis Lua 원자성이다. 대체품에는 그게 없다.

**4. Mock 은 실물처럼 동작해야 한다.**

`MockGithubClient` 가 호출마다 새 UUID 를 sha 로 만들던 시절, 중복 수집 버그가
테스트를 통과했다. Mock 이 실물과 다르게 굴면 테스트는 Mock 을 검증한다.

## 스택

**의존성을 늘리지 않는다.** 새 라이브러리가 필요해 보이면 먼저 현재
classpath 를 확인한다 — HTTP 테스트도 `spring-boot-starter-test` 만으로 됐다.

## 문서

**`docs/기록.md` 는 사실만 적는다.** 실패와 되돌린 판단도 그대로 남긴다.
숫자를 쓸 때는 어떻게 쟀는지(`bench/`)를 같이 건다.
문서가 코드와 어긋나면 문서 전체를 믿을 수 없게 된다 — 스크립트를 지우면
그걸 참조하는 문서도 같은 커밋에서 고친다.

## 실행

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./server/gradlew -p server test
```

테스트는 `docker compose up` 이 필요 없다. Testcontainers 가 컨테이너를 직접 띄운다.
Docker 데몬만 켜져 있으면 된다. 서버를 직접 띄워 눌러보려면 `server/README.md` 를 본다.
