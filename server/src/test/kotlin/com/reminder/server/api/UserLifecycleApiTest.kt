package com.reminder.server.api

import com.reminder.server.support.ApiTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDateTime

/**
 * 증명하는 주장: "탈퇴는 유저 행을 지우고, 유저가 시스템에 남긴 상태를 전부 정리한다"
 *
 * 두 층이 나눠 맡는다. commits·history·user_monthly_score 는 FK 의 CASCADE 가 지우고,
 * participate 는 RESTRICT 라 앱의 leaveGroup 이 지운다 — 그 분담을 여기서 HTTP 로 확인한다.
 * 경위: docs/adr/0001-사용자-삭제를-물리-삭제로-단일화한다.md
 */
class UserLifecycleApiTest : ApiTest() {

    @Autowired
    private lateinit var jdbc: JdbcTemplate

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
            .describedAs("승계가 안 되면 오너 행을 지울 수 없어 FK 위반으로 탈퇴 자체가 터진다")
            .isEqualTo(HttpStatus.NO_CONTENT)
    }

    @Test
    @DisplayName("그룹에 속한 채 탈퇴해도 유저 행이 실제로 지워진다")
    fun leavingWhileInAGroupStillDeletesTheRow() {
        val owner = createUser("life-owner4")
        val leaving = createUser("life-leaving4")

        val groupId = post("/group", """{"userId":$owner,"groupName":"life-g4","password":null,"maxCount":5}""")
            .longField("groupId")
        post("/group/member", """{"userId":$leaving,"groupId":$groupId,"password":null}""")

        delete("/users/delete?userId=$leaving")

        // 그룹 탈퇴가 먼저 도는데, 거기 벌크 UPDATE 가 clearAutomatically 라 영속성
        // 컨텍스트를 비운다. 그 뒤 준영속 엔티티로 delete() 하면 삭제가 나가지 않는다 —
        // 그룹 없이 탈퇴하는 경로에서는 안 걸리고 여기서만 걸린다.
        assertThat(get("/users?userId=$leaving").statusCode)
            .describedAs("그룹을 거친 탈퇴에서만 깨지는 구간이다")
            .isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    @DisplayName("재가입해도 그룹은 복구되지 않는다 — 다시 참여해야 한다")
    fun rejoiningDoesNotRestoreGroupMembership() {
        val owner = createUser("life-owner5")
        val leaving = createUser("life-leaving5")

        val groupId = post("/group", """{"userId":$owner,"groupName":"life-g5","password":null,"maxCount":5}""")
            .longField("groupId")
        post("/group/member", """{"userId":$leaving,"groupId":$groupId,"password":null}""")

        delete("/users/delete?userId=$leaving")
        val rejoined = post("/users", """{"githubId":"life-leaving5","nickname":"life-leaving5","repositoryName":"repo"}""")
            .longField("userId")

        // 그룹은 다른 사람들과의 관계라 한쪽 의사만으로 되돌릴 수 없다.
        assertThat(get("/group/info?userId=$rejoined").body?.trim())
            .describedAs("모르는 사이에 사람이 돌아와 있으면 남은 멤버 입장에서 곤란하다")
            .isEqualTo("[]")
        assertThat(get("/group/info?userId=$owner").body)
            .doesNotContain("\"userId\":$rejoined")
    }

    @Test
    @DisplayName("재가입하면 같은 GitHub 계정이라도 새 user_id 를 받는다")
    fun rejoiningCreatesABrandNewUser() {
        val userId = createUser("life-rejoin")
        delete("/users/delete?userId=$userId")

        val res = post("/users", """{"githubId":"life-rejoin","nickname":"life-rejoin","repositoryName":"repo-life-rejoin"}""")

        assertThat(res.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(res.longField("userId"))
            .describedAs("같은 id 가 돌아오면 행이 안 지워졌다는 뜻이다")
            .isNotEqualTo(userId)
        assertThat(get("/users?userId=$userId").statusCode)
            .describedAs("옛 user_id 는 영영 사라진다")
            .isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    @DisplayName("탈퇴하면 닉네임을 반납해 남이 쓸 수 있다")
    fun leavingReleasesTheNickname() {
        val userId = createUser("life-nick")
        assertThat(get("/users/nick_name?nickName=life-nick").body).contains("\"available\":false")

        delete("/users/delete?userId=$userId")

        assertThat(get("/users/nick_name?nickName=life-nick").body)
            .describedAs("행이 남아 있으면 uk_users_nickname 이 계속 점유된다")
            .contains("\"available\":true")

        // 확인만으로는 부족하다 — 실제로 다른 GitHub 계정이 그 닉네임으로 가입되는지 본다.
        assertThat(post("/users", """{"githubId":"life-nick-other","nickname":"life-nick","repositoryName":"repo"}""").statusCode)
            .isEqualTo(HttpStatus.CREATED)
    }

    @Test
    @DisplayName("커밋과 이력이 쌓인 유저도 탈퇴할 수 있다 — CASCADE 가 함께 지운다")
    fun leavingWithChildRowsSucceeds() {
        val userId = createUser("life-cascade")
        givenCommit(userId)
        givenHistory(userId)

        assertThat(delete("/users/delete?userId=$userId").statusCode)
            .describedAs("commits·history 가 CASCADE 가 아니면 여기서 FK 위반으로 터진다")
            .isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(get("/users?userId=$userId").statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    // ── fixture — 자식 행은 SQL 로 넣는다 (ApiTest 규칙: 검증만 HTTP) ─────────────

    private fun givenCommit(userId: Long) {
        jdbc.update(
            "INSERT INTO commits (user_id, commit_date, commit_url, title, level, sha) VALUES (?, ?, ?, ?, ?, ?)",
            userId, LocalDateTime.now(), "https://github.com/t/r/commit/$userId", "제목", "BRONZE", "life-sha-$userId",
        )
    }

    private fun givenHistory(userId: Long) {
        jdbc.update(
            "INSERT INTO history (user_id, problem_num, solve_time) VALUES (?, ?, ?)",
            userId, "1000", "10",
        )
    }
}
