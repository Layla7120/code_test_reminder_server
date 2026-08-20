package com.reminder.server.api

import com.reminder.server.support.ApiTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

/**
 * 증명하는 주장: "탈퇴는 유저가 시스템에 남긴 상태를 전부 정리한다"
 *
 * 지금까지 탈퇴가 한 일은 active=false 와 점수 행 삭제뿐이었다. 그래서 유저가 그룹에
 * 남긴 흔적은 그대로 남는다 — 이 테스트가 그 구멍을 고정한다.
 */
class UserLifecycleApiTest : ApiTest() {

    @Test
    @DisplayName("탈퇴하면 참여 중이던 그룹에서도 빠지고 정원이 돌아온다")
    fun leavingUserFreesGroupSlot() {
        val owner = createUser("life-owner")
        val leaving = createUser("life-leaving")

        val groupId = post("/group", """{"userId":$owner,"groupName":"life-g","password":null,"maxCount":2}""")
            .longField("groupId")
        assertThat(post("/group/member", """{"userId":$leaving,"groupId":$groupId,"password":null}""").statusCode)
            .isEqualTo(HttpStatus.CREATED)

        // 정원 2명이 찼다 — 세 번째는 못 들어온다
        val blocked = createUser("life-blocked")
        assertThat(post("/group/member", """{"userId":$blocked,"groupId":$groupId,"password":null}""").statusCode)
            .describedAs("정원이 찼으므로 거부돼야 한다")
            .isNotEqualTo(HttpStatus.CREATED)

        delete("/users/delete?userId=$leaving")

        // 탈퇴자가 자리를 비웠으니 이제 들어갈 수 있어야 한다
        assertThat(post("/group/member", """{"userId":$blocked,"groupId":$groupId,"password":null}""").statusCode)
            .describedAs("탈퇴자가 정원을 계속 먹고 있으면 여기서 걸린다 — 유령 멤버")
            .isEqualTo(HttpStatus.CREATED)
    }

    @Test
    @DisplayName("탈퇴한 사용자는 그룹 멤버 목록에서 사라진다")
    fun leavingUserDisappearsFromMemberList() {
        val owner = createUser("life-owner2")
        val leaving = createUser("life-leaving2")

        val groupId = post("/group", """{"userId":$owner,"groupName":"life-g2","password":null,"maxCount":5}""")
            .longField("groupId")
        post("/group/member", """{"userId":$leaving,"groupId":$groupId,"password":null}""")

        delete("/users/delete?userId=$leaving")

        assertThat(get("/group/info?userId=$owner").body)
            .describedAs("탈퇴자가 멤버 목록에 남아 있으면 안 된다")
            .doesNotContain("\"userId\":$leaving")
    }

    @Test
    @DisplayName("그룹 오너가 탈퇴하면 남은 멤버에게 승계된다")
    fun ownerLeavingTransfersOwnership() {
        val owner = createUser("life-owner3")
        val member = createUser("life-member3")

        val groupId = post("/group", """{"userId":$owner,"groupName":"life-g3","password":null,"maxCount":5}""")
            .longField("groupId")
        post("/group/member", """{"userId":$member,"groupId":$groupId,"password":null}""")

        delete("/users/delete?userId=$owner")

        // 승계됐다면 남은 멤버가 비밀번호를 바꿀 수 있다 (오너만 가능한 동작)
        assertThat(patch("/group/password", """{"userId":$member,"groupId":$groupId,"newPassword":"newpw"}""").statusCode)
            .describedAs("승계가 안 되면 비활성 오너만 남아 아무도 그룹을 관리할 수 없다")
            .isEqualTo(HttpStatus.NO_CONTENT)
    }

    @Test
    @DisplayName("그룹에 속한 채 탈퇴해도 계정은 비활성으로 남는다")
    fun leavingWhileInAGroupStillDeactivatesTheAccount() {
        val owner = createUser("life-owner4")
        val leaving = createUser("life-leaving4")

        val groupId = post("/group", """{"userId":$owner,"groupName":"life-g4","password":null,"maxCount":5}""")
            .longField("groupId")
        post("/group/member", """{"userId":$leaving,"groupId":$groupId,"password":null}""")

        delete("/users/delete?userId=$leaving")

        // 그룹 탈퇴가 먼저 도는데, 거기 벌크 UPDATE 가 clearAutomatically 라 영속성
        // 컨텍스트를 비운다. 그 뒤 준영속 엔티티로 deactivate() 하면 저장되지 않는다 —
        // 그러면 탈퇴자가 재수집만으로 랭킹에 되살아난다.
        assertThat(get("/users?userId=$leaving").body)
            .describedAs("그룹을 거친 탈퇴에서만 깨지는 구간이다")
            .contains("\"active\":false")
    }

    @Test
    @DisplayName("탈퇴한 사용자가 다시 로그인하면 계정이 되살아난다")
    fun rejoiningReactivatesTheAccount() {
        val userId = createUser("life-rejoin")
        delete("/users/delete?userId=$userId")

        val res = post("/users", """{"githubId":"life-rejoin","nickname":"life-rejoin","repositoryName":"repo-life-rejoin"}""")

        assertThat(res.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(res.longField("userId"))
            .describedAs("같은 GitHub 계정이므로 같은 유저로 돌아와야 한다")
            .isEqualTo(userId)
        assertThat(res.body)
            .describedAs("비활성인 채로 로그인되면 랭킹에 영영 못 들어가는 좀비 계정이 된다")
            .contains("\"active\":true")
    }
}
