package com.reminder.server.api

import com.reminder.server.support.ApiTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

/**
 * POST /group/member — 중복 참여와 정원 초과.
 *
 * 정원 초과(GroupFullException)를 건드리는 테스트가 GroupJoinConcurrencyTest 하나뿐이었다.
 * 동시성 테스트가 있다고 순차 정원 초과가 검증되는 게 아니다 — 동시 요청 20개를
 * 던지는 테스트는 "두 번째 사람이 들어가려 할 때 무슨 상태 코드가 나가는가"를 답하지
 * 않는다. 그리고 그 테스트는 서비스를 직접 부르므로 상태 코드 자체가 존재하지 않는다.
 *
 * 중복 참여(AlreadyInGroupException)는 어느 계층에도 테스트가 없었다.
 *
 * 두 경우 모두 거부 후 memberCount 가 오르지 않았는지까지 확인한다.
 * 거부하면서 카운터만 올리면 그 이후로 아무도 못 들어온다.
 */
class GroupMemberApiTest : ApiTest() {

    @Test
    @DisplayName("같은 사용자가 두 번 참여하면 400이고, 인원수는 그대로다")
    fun duplicateJoinIsRejectedAndCountUnchanged() {
        val owner = createUser("dup-join-owner")
        val joiner = createUser("dup-join-joiner")
        val groupId = post(
            "/group",
            """{"userId":$owner,"groupName":"중복참여그룹","password":null,"maxCount":5}""",
        ).longField("groupId")

        val first = post("/group/member", """{"userId":$joiner,"groupId":$groupId,"password":null}""")
        assertThat(first.statusCode).isEqualTo(HttpStatus.CREATED)

        val second = post("/group/member", """{"userId":$joiner,"groupId":$groupId,"password":null}""")
        assertThat(second.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)

        // 거부된 요청이 카운터를 올리지 않았는지 확인한다.
        assertThat(memberCountOf(owner, "중복참여그룹")).isEqualTo(2)
    }

    @Test
    @DisplayName("정원이 찬 그룹에 순차로 참여하면 400이고, 인원수는 정원 그대로다")
    fun joiningFullGroupIsRejectedSequentially() {
        val owner = createUser("full-owner")
        val second = createUser("full-second")
        val third = createUser("full-third")

        // maxCount=2 — 생성자가 이미 한 자리를 차지하므로 남은 자리는 하나다.
        val groupId = post(
            "/group",
            """{"userId":$owner,"groupName":"정원2그룹","password":null,"maxCount":2}""",
        ).longField("groupId")

        val fillsLastSeat = post("/group/member", """{"userId":$second,"groupId":$groupId,"password":null}""")
        assertThat(fillsLastSeat.statusCode).isEqualTo(HttpStatus.CREATED)

        val overCapacity = post("/group/member", """{"userId":$third,"groupId":$groupId,"password":null}""")
        assertThat(overCapacity.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)

        assertThat(memberCountOf(owner, "정원2그룹")).isEqualTo(2)
    }

    /**
     * 오너의 /group/info 응답에서 해당 그룹의 인원수를 꺼낸다.
     *
     * /group/info 는 GroupResponse 가 아니라 Group 엔티티를 그대로 직렬화하므로
     * 필드명이 memberCount 가 아니라 memberCounter 다. 두 엔드포인트가 같은 값을
     * 다른 이름으로 내보내고 있다.
     */
    private fun memberCountOf(userId: Long, groupName: String): Int {
        val body = get("/group/info?userId=$userId").body ?: error("본문이 비어 있다")
        // 응답이 중첩 객체라(group 안에 owner 가 또 있다) 객체 단위로 잘라내지 않는다.
        // 직렬화 순서상 memberCounter 가 groupName 뒤에 오므로 그 뒤 첫 값을 읽는다.
        val nameAt = body.indexOf("\"groupName\":\"$groupName\"")
        if (nameAt < 0) error("응답에 $groupName 이 없다: $body")
        return Regex("\"memberCounter\"\\s*:\\s*(\\d+)").find(body, nameAt)
            ?.groupValues?.get(1)?.toInt()
            ?: error("memberCounter 를 찾지 못했다: $body")
    }
}
