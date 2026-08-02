package com.reminder.server.api

import com.reminder.server.support.ApiTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

/**
 * 존재하지 않는 자원 → 404 스윕.
 *
 * 서비스 전반이 orElseThrow { UserNotFoundException(...) } / GroupNotFoundException 을
 * 던지고 GlobalExceptionHandler 가 이 둘을 404 로 매핑한다. 그런데 이 매핑을 실제로
 * 확인하는 테스트는 joinGroup 의 그룹 없음 하나뿐이었다.
 *
 * 매핑이 빠지면 404 가 아니라 500 이 나가고, 스택 트레이스가 클라이언트에 노출된다.
 * 예외를 던지는 것과 그게 올바른 상태 코드로 나가는 것은 다른 문제다 —
 * 후자는 컨트롤러를 지나야만 존재한다.
 */
class NotFoundApiTest : ApiTest() {

    private val missingId = 999999L

    @Test
    @DisplayName("없는 사용자로 그룹을 만들면 404")
    fun createGroupWithMissingUserReturns404() {
        val res = post(
            "/group",
            """{"userId":$missingId,"groupName":"유령그룹","password":null,"maxCount":5}""",
        )

        assertThat(res.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    @DisplayName("없는 사용자가 참여하면 404")
    fun joinGroupWithMissingUserReturns404() {
        val owner = createUser("nf-join-owner")
        val groupId = post(
            "/group",
            """{"userId":$owner,"groupName":"참여404그룹","password":null,"maxCount":5}""",
        ).longField("groupId")

        val res = post("/group/member", """{"userId":$missingId,"groupId":$groupId,"password":null}""")

        assertThat(res.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    @DisplayName("없는 그룹에서 나가면 404")
    fun leaveMissingGroupReturns404() {
        val userId = createUser("nf-leave")

        val res = delete("/group/leave?userId=$userId&groupId=$missingId")

        assertThat(res.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    @DisplayName("없는 사용자의 그룹 조회는 404 — 빈 배열이 아니다")
    fun groupInfoForMissingUserReturns404() {
        val res = get("/group/info?userId=$missingId")

        // 속한 그룹이 없는 것(빈 배열)과 사용자 자체가 없는 것은 다르다.
        assertThat(res.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    @DisplayName("없는 사용자 조회·수정·탈퇴는 전부 404")
    fun userEndpointsReturn404ForMissingUser() {
        assertThat(get("/users?userId=$missingId").statusCode)
            .describedAs("GET /users")
            .isEqualTo(HttpStatus.NOT_FOUND)

        assertThat(
            patch("/users/update", """{"userId":$missingId,"nickname":"아무개","repositoryName":null}""").statusCode,
        ).describedAs("PATCH /users/update").isEqualTo(HttpStatus.NOT_FOUND)

        assertThat(delete("/users/delete?userId=$missingId").statusCode)
            .describedAs("DELETE /users/delete")
            .isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    @DisplayName("필수 쿼리 파라미터가 빠지거나 타입이 안 맞으면 400")
    fun missingOrMalformedQueryParamsReturn400() {
        // @RequestParam 은 기본이 required = true 다. 빠지면 400 이어야 한다.
        assertThat(get("/users").statusCode)
            .describedAs("userId 누락")
            .isEqualTo(HttpStatus.BAD_REQUEST)

        // Long 자리에 문자열이 오면 바인딩 실패로 400 이어야 한다.
        assertThat(get("/users?userId=abc").statusCode)
            .describedAs("userId 타입 불일치")
            .isEqualTo(HttpStatus.BAD_REQUEST)

        assertThat(get("/group/info").statusCode)
            .describedAs("/group/info userId 누락")
            .isEqualTo(HttpStatus.BAD_REQUEST)
    }
}
