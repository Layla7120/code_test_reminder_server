package com.reminder.server.api

import com.reminder.server.support.ApiTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

/**
 * PATCH /group/password — 감사에서 심각도 1위로 나온 구간.
 *
 * GroupService.changePassword 에 `if (group.owner.id != userId) throw NotGroupOwnerException()`
 * 이 있는데, 이 엔드포인트를 호출하는 테스트가 저장소 전체에 하나도 없었다.
 * 막아뒀다고 믿고 있었을 뿐 확인한 적이 없는 상태였다 — 그 줄을 지워도 아무 테스트도
 * 빨간불이 나지 않았다.
 *
 * 상태 코드만 보지 않는다. 거부된 뒤 비밀번호가 실제로 그대로인지까지 확인한다.
 * 거부는 하면서 값은 바뀌는 경우가 이 계열의 전형적인 결함이다.
 */
class GroupPasswordApiTest : ApiTest() {

    @Test
    @DisplayName("오너가 아니면 비밀번호를 바꿀 수 없고, 기존 비밀번호가 그대로 유지된다")
    fun nonOwnerCannotChangePassword() {
        val owner = createUser("pw-owner")
        val intruder = createUser("pw-intruder")
        val groupId = post(
            "/group",
            """{"userId":$owner,"groupName":"오너전용그룹","password":"원래비번","maxCount":5}""",
        ).longField("groupId")

        val res = patch(
            "/group/password",
            """{"userId":$intruder,"groupId":$groupId,"newPassword":"탈취비번"}""",
        )

        assertThat(res.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)

        // 부수효과 확인: 거부됐다면 비밀번호는 바뀌지 않았어야 한다.
        // 탈취 비번으로는 못 들어가고, 원래 비번으로는 들어가진다.
        val withStolen = post(
            "/group/member",
            """{"userId":$intruder,"groupId":$groupId,"password":"탈취비번"}""",
        )
        assertThat(withStolen.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)

        val withOriginal = post(
            "/group/member",
            """{"userId":$intruder,"groupId":$groupId,"password":"원래비번"}""",
        )
        assertThat(withOriginal.statusCode).isEqualTo(HttpStatus.CREATED)
    }

    @Test
    @DisplayName("오너는 비밀번호를 바꿀 수 있고, 새 비밀번호로만 참여된다")
    fun ownerChangesPasswordAndOnlyNewOneWorks() {
        val owner = createUser("pw-change-owner")
        val joiner = createUser("pw-change-joiner")
        val groupId = post(
            "/group",
            """{"userId":$owner,"groupName":"비번변경그룹","password":"이전비번","maxCount":5}""",
        ).longField("groupId")

        val res = patch(
            "/group/password",
            """{"userId":$owner,"groupId":$groupId,"newPassword":"새비번"}""",
        )

        assertThat(res.statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        val withOld = post(
            "/group/member",
            """{"userId":$joiner,"groupId":$groupId,"password":"이전비번"}""",
        )
        assertThat(withOld.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)

        val withNew = post(
            "/group/member",
            """{"userId":$joiner,"groupId":$groupId,"password":"새비번"}""",
        )
        assertThat(withNew.statusCode).isEqualTo(HttpStatus.CREATED)
    }

    @Test
    @DisplayName("없는 그룹의 비밀번호 변경은 404")
    fun changePasswordOnMissingGroupReturns404() {
        val userId = createUser("pw-missing-group")

        val res = patch(
            "/group/password",
            """{"userId":$userId,"groupId":999999,"newPassword":"아무거나"}""",
        )

        assertThat(res.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    @DisplayName("빈 비밀번호와 공백뿐인 비밀번호는 둘 다 400")
    fun blankNewPasswordIsRejected() {
        val owner = createUser("pw-blank")
        val groupId = post(
            "/group",
            """{"userId":$owner,"groupName":"빈비번그룹","password":"초기비번","maxCount":5}""",
        ).longField("groupId")

        // @NotBlank 는 trim 후 길이를 보므로 공백뿐인 값도 여기서 걸린다.
        // 서비스의 require(pw.isNotEmpty()) 는 그래서 도달하지 않는다 — 둘 다 400 이어야 한다.
        listOf("", " ").forEach { blank ->
            val res = patch(
                "/group/password",
                """{"userId":$owner,"groupId":$groupId,"newPassword":"$blank"}""",
            )
            assertThat(res.statusCode)
                .describedAs("newPassword=\"$blank\"")
                .isEqualTo(HttpStatus.BAD_REQUEST)
        }
    }
}
