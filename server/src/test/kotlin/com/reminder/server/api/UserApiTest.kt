package com.reminder.server.api

import com.reminder.server.support.ApiTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

/**
 * /users — 전송 계층 테스트가 하나도 없던 도메인.
 *
 * POST /users 는 이름이 loginOrCreate 다. 같은 githubId 로 다시 요청하면 새로
 * 만들지 않고 기존 사용자를 돌려주는 게 핵심 동작인데, 그걸 확인하는 테스트가
 * 없었다. findByGithubId 조회를 지워도 아무 테스트도 깨지지 않는 상태였다 —
 * 그러면 재로그인마다 사용자가 새로 생기다가 UNIQUE 위반으로 터진다.
 *
 * createUser 헬퍼가 이 엔드포인트를 쓰고 있었지만, 헬퍼는 userId 만 꺼내갈 뿐
 * 응답 본문도 상태 코드도 단언하지 않는다. 사용되는 것과 검증되는 것은 다르다.
 */
class UserApiTest : ApiTest() {

    @Test
    @DisplayName("같은 githubId 로 다시 요청하면 새 사용자를 만들지 않고 같은 userId 를 돌려준다")
    fun loginIsIdempotentForSameGithubId() {
        val body = """{"githubId":"idem-gh","nickname":"idem-nick","repositoryName":"idem-repo"}"""

        val first = post("/users", body)
        val second = post("/users", body)

        assertThat(first.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(second.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(second.longField("userId")).isEqualTo(first.longField("userId"))
    }

    @Test
    @DisplayName("생성 응답은 userId 뿐 아니라 프로필 필드를 전부 담는다")
    fun createResponseCarriesProfileFields() {
        val res = post(
            "/users",
            """{"githubId":"fields-gh","nickname":"fields-nick","repositoryName":"fields-repo"}""",
        )

        assertThat(res.statusCode).isEqualTo(HttpStatus.CREATED)
        val body = res.body ?: error("본문이 비어 있다")
        assertThat(body)
            .contains("\"githubId\":\"fields-gh\"")
            .contains("\"nickname\":\"fields-nick\"")
            .contains("\"repositoryName\":\"fields-repo\"")
            .contains("\"active\":true")
            .contains("createdAt")
    }

    @Test
    @DisplayName("다른 githubId 가 이미 쓰인 닉네임을 요청하면 500이 아니라 409")
    fun duplicateNicknameReturns409() {
        post("/users", """{"githubId":"nick-first","nickname":"같은닉네임","repositoryName":"repo-1"}""")

        val res = post(
            "/users",
            """{"githubId":"nick-second","nickname":"같은닉네임","repositoryName":"repo-2"}""",
        )

        // uk_users_nickname 위반이 DataIntegrityViolationException 으로 올라오고,
        // GlobalExceptionHandler 가 마지막 방어선으로 409 로 바꾼다.
        // 이 핸들러가 없으면 500 이 나간다.
        assertThat(res.statusCode).isEqualTo(HttpStatus.CONFLICT)
    }

    @Test
    @DisplayName("사용자 조회는 본인 프로필을 돌려준다")
    fun getUserReturnsProfile() {
        val userId = createUser("get-user")

        val res = get("/users?userId=$userId")

        assertThat(res.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(res.body).contains("\"userId\":$userId").contains("\"githubId\":\"get-user\"")
    }

    @Test
    @DisplayName("프로필 수정은 204 이고 다시 조회하면 반영돼 있다")
    fun updateUserIsReflectedOnNextRead() {
        val userId = createUser("update-me")

        val res = patch(
            "/users/update",
            """{"userId":$userId,"nickname":"바뀐닉네임","repositoryName":null}""",
        )

        assertThat(res.statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        val after = get("/users?userId=$userId").body ?: error("본문이 비어 있다")
        assertThat(after).contains("\"nickname\":\"바뀐닉네임\"")
        // repositoryName 은 null 을 보냈으므로 기존 값이 유지돼야 한다.
        assertThat(after).contains("\"repositoryName\":\"repo-update-me\"")
    }

    @Test
    @DisplayName("탈퇴는 소프트 삭제 — active 가 false 가 되고 조회는 계속 된다")
    fun deleteUserDeactivatesInsteadOfRemoving() {
        val userId = createUser("delete-me")

        val res = delete("/users/delete?userId=$userId")

        assertThat(res.statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        val after = get("/users?userId=$userId")
        assertThat(after.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(after.body).contains("\"active\":false")
    }

    @Test
    @DisplayName("닉네임 중복 확인은 쓰인 닉네임에 false, 안 쓰인 닉네임에 true 를 준다")
    fun nicknameAvailabilityReflectsExistingUsers() {
        createUser("nick-check")

        val taken = get("/users/nick_name?nickName=nick-check")
        val free = get("/users/nick_name?nickName=아무도안쓰는닉네임")

        assertThat(taken.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(taken.body).contains("\"available\":false")
        assertThat(free.body).contains("\"available\":true")
    }
}
