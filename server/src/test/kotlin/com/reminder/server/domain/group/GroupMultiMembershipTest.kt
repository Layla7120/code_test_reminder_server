package com.reminder.server.domain.group

import com.reminder.server.domain.user.User
import com.reminder.server.domain.user.UserRepository
import com.reminder.server.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * 증명하는 주장: "다중 그룹 참여는 요구사항이고, 조회 시 전부 반환된다"
 *
 * getGroupInfo()가 participations.first()로 첫 그룹만 반환하던 게 버그였다.
 * 이건 구조적 수정(반환 타입 자체를 GroupInfoResult → List<GroupInfoResult>로 변경)이라
 * 같은 시그니처로 빨간불→초록불 사이클을 만들 수 없다 — 옛 코드는 컴파일조차 안 된다.
 * participations.first()가 목록 중 하나만 쓴다는 사실 자체가 코드 인스펙션으로 이미 명백한 결함이었고,
 * 이 테스트는 고친 결과(전부 반환됨)를 증명한다.
 */
class GroupMultiMembershipTest : IntegrationTest() {

    @Autowired lateinit var groupService: GroupService
    @Autowired lateinit var userRepository: UserRepository

    @Test
    @DisplayName("한 유저가 여러 그룹에 속하면 getGroupInfo가 전부 반환한다")
    fun getGroupInfoReturnsAllJoinedGroups() {
        val user = userRepository.save(User("multi", "multi-nick", "repo"))
        val ownerA = userRepository.save(User("ownerA", "ownerA-nick", "repo"))
        val ownerB = userRepository.save(User("ownerB", "ownerB-nick", "repo"))

        val groupA = groupService.createGroup(ownerA.id, "그룹A", null, 5)
        val groupB = groupService.createGroup(ownerB.id, "그룹B", null, 5)

        groupService.joinGroup(user.id, groupA.id, null)
        groupService.joinGroup(user.id, groupB.id, null)

        val result = groupService.getGroupInfo(user.id)

        assertThat(result.map { it.group.id }).containsExactlyInAnyOrder(groupA.id, groupB.id)
    }

    @Test
    @DisplayName("아무 그룹에도 속하지 않으면 빈 목록을 반환한다")
    fun getGroupInfoReturnsEmptyListWhenNotInAnyGroup() {
        val user = userRepository.save(User("solo", "solo-nick", "repo"))

        assertThat(groupService.getGroupInfo(user.id)).isEmpty()
    }
}
