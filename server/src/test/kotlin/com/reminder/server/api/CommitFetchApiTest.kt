package com.reminder.server.api

import com.reminder.server.support.ApiTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles

/**
 * POST /commits — 전송 계층 검증.
 *
 * [왜 지금 생겼나]
 * endpoint-allowlist.txt 에 "409/404 매핑, {saved:n} 응답 모양" 사유로 올라가 있던 부채다.
 * 그중 409(실제로는 400) 매핑은 분산 락을 없애면서 **동작 자체가 사라졌다.**
 * 계약이 바뀌었으므로 새 계약을 여기에 고정한다 — 중복 요청은 거부되지 않고 성공한다.
 *
 * 예전에는 같은 유저의 두 번째 요청이 CommitFetchAlreadyInProgressException 으로 400 이었다.
 * 지금은 연산이 멱등이라 거부할 이유가 없다 — 커밋 삽입은 UNIQUE(sha) 로, 점수는 절대값
 * 재계산으로 보호된다. 연타 방지는 데모 페이지가 버튼 비활성화로 한다.
 *
 * MockGithubClient 를 쓰려고 load-test 프로필로 돈다. 같은 githubId/repositoryName 은
 * 항상 같은 sha 목록을 돌려주므로 "새 커밋이 없는 재수집"이 그대로 재현된다.
 */
@ActiveProfiles("load-test")
class CommitFetchApiTest : ApiTest() {

    @Test
    @DisplayName("커밋 수집은 {\"saved\": n} 을 돌려준다")
    fun fetchReturnsSavedCount() {
        val userId = createUser("fetch-api")

        val res = post("/commits", """{"userId":$userId}""")

        assertThat(res.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(res.longField("saved"))
            .describedAs("첫 수집은 새 커밋이 저장되므로 0 보다 커야 한다")
            .isGreaterThan(0)
    }

    @Test
    @DisplayName("연달아 수집해도 거부되지 않는다 — 두 번째는 saved=0 인 200")
    fun repeatedFetchIsAcceptedAndIdempotent() {
        val userId = createUser("fetch-api-twice")

        val first = post("/commits", """{"userId":$userId}""")
        val second = post("/commits", """{"userId":$userId}""")

        assertThat(first.statusCode).isEqualTo(HttpStatus.OK)

        // 락이 있던 시절 이 자리는 400 이었다. 이제 거부하지 않는다.
        assertThat(second.statusCode)
            .describedAs("중복 수집은 멱등이므로 거부하지 않는다")
            .isEqualTo(HttpStatus.OK)
        assertThat(second.longField("saved"))
            .describedAs("새 커밋이 없으므로 0 건이어야 한다 — 부풀면 버그 A")
            .isZero()
    }

    @Test
    @DisplayName("없는 userId 로 수집하면 404")
    fun fetchWithUnknownUserIs404() {
        val res = post("/commits", """{"userId":999999}""")

        assertThat(res.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }
}
