package com.reminder.server.domain.group

import com.reminder.server.domain.user.User
import com.reminder.server.domain.user.UserRepository
import com.reminder.server.global.exception.NotGroupMemberException
import com.reminder.server.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * 증명하는 주장: "그룹 멤버가 아닌 유저가 탈퇴를 시도해도 정원 카운터는 줄지 않는다"
 *
 * 이전 코드는 participateRepository.deleteByGroupAndUser()의 삭제 건수를 보지 않고
 * decrementMemberCounter()를 무조건 호출했다. 비멤버가 leaveGroup을 호출하면
 * 실제 참가자는 그대로인데 member_counter만 내려가서, incrementMemberCounterIfNotFull()이
 * 방어하는 정원 규칙이 실제보다 한 자리 더 허용하게 된다.
 */
class GroupLeaveTest : IntegrationTest() {

    @Autowired lateinit var groupService: GroupService
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var groupRepository: GroupRepository
    @Autowired lateinit var participateRepository: ParticipateRepository

    @Test
    @DisplayName("멤버가 아닌 유저가 탈퇴를 시도하면 예외가 나고 정원 카운터는 그대로다")
    fun nonMemberLeaveDoesNotDecrementCounter() {
        val owner = userRepository.save(User("owner", "leave-owner", "repo"))
        val group = groupService.createGroup(owner.id, "탈퇴테스트그룹", null, 5)
        val outsider = userRepository.save(User("outsider", "leave-outsider", "repo"))

        assertThatThrownBy { groupService.leaveGroup(outsider.id, group.id) }
            .isInstanceOf(NotGroupMemberException::class.java)

        val reloaded = groupRepository.findById(group.id).orElseThrow()
        assertThat(reloaded.memberCounter)
            .describedAs("member_counter는 오너 1명 그대로여야 한다")
            .isEqualTo(1)
    }

    @Test
    @DisplayName("마지막 멤버가 나가면 그룹 자체가 삭제된다")
    fun lastMemberLeavingDeletesTheGroup() {
        val owner = userRepository.save(User("solo-owner", "solo-nick", "repo"))
        val group = groupService.createGroup(owner.id, "혼자그룹", null, 5)

        groupService.leaveGroup(owner.id, group.id)

        // Flask 의 handleGroupLeave 에 있던 동작이다. 마이그레이션에서 사라져
        // 오너가 나가면 아무도 못 지우는 빈 그룹이 남아 있었다.
        assertThat(groupRepository.findById(group.id))
            .describedAs("마지막 멤버가 나갔으면 그룹이 남아 있으면 안 된다")
            .isEmpty
    }

    @Test
    @DisplayName("오너가 나가면 가장 먼저 들어온 남은 멤버에게 승계된다")
    fun ownerLeavingTransfersOwnershipToOldestMember() {
        val owner = userRepository.save(User("own", "own-nick", "repo"))
        val first = userRepository.save(User("first", "first-nick", "repo"))
        val second = userRepository.save(User("second", "second-nick", "repo"))

        val group = groupService.createGroup(owner.id, "승계그룹", null, 5)
        groupService.joinGroup(first.id, group.id, null)
        groupService.joinGroup(second.id, group.id, null)

        groupService.leaveGroup(owner.id, group.id)

        val reloaded = groupRepository.findById(group.id).orElseThrow()
        assertThat(reloaded.owner.id)
            .describedAs("먼저 들어온 멤버가 새 오너여야 한다")
            .isEqualTo(first.id)
        assertThat(reloaded.memberCounter).isEqualTo(2)
    }

    @Test
    @DisplayName("오너가 아닌 멤버가 나가면 오너는 그대로다")
    fun nonOwnerLeavingKeepsOwner() {
        val owner = userRepository.save(User("own2", "own2-nick", "repo"))
        val member = userRepository.save(User("mem2", "mem2-nick", "repo"))
        val group = groupService.createGroup(owner.id, "오너유지그룹", null, 5)
        groupService.joinGroup(member.id, group.id, null)

        groupService.leaveGroup(member.id, group.id)

        val reloaded = groupRepository.findById(group.id).orElseThrow()
        assertThat(reloaded.owner.id).isEqualTo(owner.id)
        assertThat(reloaded.memberCounter).isEqualTo(1)
    }

    @Test
    @DisplayName("실제 멤버가 탈퇴하면 정원 카운터가 정확히 1 줄어든다")
    fun memberLeaveDecrementsCounterByOne() {
        val owner = userRepository.save(User("owner2", "leave-owner2", "repo"))
        val group = groupService.createGroup(owner.id, "탈퇴테스트그룹2", null, 5)
        val member = userRepository.save(User("member", "leave-member", "repo"))
        groupService.joinGroup(member.id, group.id, null)

        groupService.leaveGroup(member.id, group.id)

        val reloaded = groupRepository.findById(group.id).orElseThrow()
        assertThat(reloaded.memberCounter).isEqualTo(1)
        assertThat(participateRepository.findMemberIdsByGroupId(group.id)).containsExactly(owner.id)
    }
}
