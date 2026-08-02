package com.reminder.server.api

import com.reminder.server.support.ApiTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

/**
 * GET /group/info — 격리.
 *
 * 기존 groupInfoReturnsAllJoinedGroups 는 "내 그룹이 다 나오는가"를 검증한다.
 * "남의 그룹이 안 나오는가"는 검증하지 않는다. 둘은 다른 명제다.
 * 전자만 있으면 findByUser 의 사용자 필터를 지워도 테스트가 통과한다 —
 * 오히려 그룹이 더 많이 나오므로 contains 단언은 여전히 성립한다.
 *
 * 그 테스트가 사용자를 세 명 만든다는 점에 주의한다. "사용자를 한 명만 만드는
 * 테스트는 격리를 검증하지 못한다"는 판정은 맞지만, 역은 성립하지 않는다.
 * 여러 명을 만들어도 한 사람의 관점만 확인하면 격리는 여전히 미검증이다.
 */
class GroupIsolationApiTest : ApiTest() {

    @Test
    @DisplayName("내 그룹만 조회되고 남의 그룹은 섞이지 않는다")
    fun groupInfoDoesNotLeakOtherUsersGroups() {
        val alice = createUser("iso-alice")
        val bob = createUser("iso-bob")
        post("/group", """{"userId":$alice,"groupName":"앨리스전용","password":null,"maxCount":5}""")
        post("/group", """{"userId":$bob,"groupName":"밥전용","password":null,"maxCount":5}""")

        val res = get("/group/info?userId=$alice")

        assertThat(res.statusCode).isEqualTo(HttpStatus.OK)
        val body = res.body ?: error("본문이 비어 있다")
        assertThat(body).contains("앨리스전용")
        // 핵심 단언 — 사용자 필터가 빠지면 여기서 걸린다.
        assertThat(body).doesNotContain("밥전용")
        assertThat(groupCountIn(body)).isEqualTo(1)
    }

    @Test
    @DisplayName("어느 그룹에도 속하지 않은 사용자는 빈 배열을 받는다")
    fun userWithNoGroupsGetsEmptyArray() {
        val loner = createUser("iso-loner")
        val other = createUser("iso-other")
        post("/group", """{"userId":$other,"groupName":"남의그룹","password":null,"maxCount":5}""")

        val res = get("/group/info?userId=$loner")

        assertThat(res.statusCode).isEqualTo(HttpStatus.OK)
        // null 이나 404 가 아니라 빈 배열이어야 한다.
        assertThat(res.body?.trim()).isEqualTo("[]")
    }

    @Test
    @DisplayName("같은 그룹에 속하면 서로의 변화가 보인다 — 격리의 반대편")
    fun sharedGroupIsVisibleToBothMembers() {
        val owner = createUser("iso-shared-owner")
        val joiner = createUser("iso-shared-joiner")
        val groupId = post(
            "/group",
            """{"userId":$owner,"groupName":"공유그룹","password":null,"maxCount":5}""",
        ).longField("groupId")

        post("/group/member", """{"userId":$joiner,"groupId":$groupId,"password":null}""")

        // 참여 응답이 아니라 오너 쪽에서 다시 조회해서 확인한다.
        // 벌크 UPDATE 나 캐시가 끼면 여기서 갈린다.
        val ownerView = get("/group/info?userId=$owner").body ?: error("본문이 비어 있다")
        assertThat(ownerView).contains("공유그룹").contains("\"memberCounter\":2")

        val joinerView = get("/group/info?userId=$joiner").body ?: error("본문이 비어 있다")
        assertThat(joinerView).contains("공유그룹")
    }

    private fun groupCountIn(body: String) = Regex("\"groupName\"").findAll(body).count()
}
