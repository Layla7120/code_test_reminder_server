package com.reminder.server.domain.group

import com.reminder.server.domain.user.User
import com.reminder.server.domain.user.UserRepository
import com.reminder.server.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * 증명하는 주장: "그룹 생성 응답의 memberCount가 실제 값(1)을 반영한다"
 *
 * incrementMemberCounterIfNotFull()은 @Modifying 벌크 UPDATE라 DB는 직접 바꾸지만
 * Hibernate가 세터를 거치지 않으므로 createGroup()이 들고 있던 group 객체의
 * memberCounter 필드는 그대로 0에 머문다. 그 group을 그대로 반환하면
 * POST /group 응답의 memberCount가 항상 0으로 나간다 — 생성자 본인이 참여했는데도.
 */
class GroupCreateResponseTest : IntegrationTest() {

    @Autowired lateinit var groupService: GroupService
    @Autowired lateinit var userRepository: UserRepository

    @Test
    @DisplayName("그룹 생성 직후 반환된 memberCounter는 1이다 (생성자 본인 포함)")
    fun createdGroupReflectsOwnerAsMember() {
        val owner = userRepository.save(User("create-resp", "create-resp-nick", "repo"))

        val group = groupService.createGroup(owner.id, "응답확인그룹", null, 5)

        assertThat(group.memberCounter).isEqualTo(1)
    }
}
