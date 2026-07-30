package com.reminder.server.domain.group

import com.reminder.server.domain.commit.CommitRepository
import com.reminder.server.domain.commit.MemberCommitProjection
import com.reminder.server.domain.user.UserRepository
import com.reminder.server.global.exception.*
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

@Service
class GroupService(
    private val groupRepository: GroupRepository,
    private val participateRepository: ParticipateRepository,
    private val userRepository: UserRepository,
    private val commitRepository: CommitRepository,
    private val passwordEncoder: BCryptPasswordEncoder,
    private val clock: Clock,
) {
    @Transactional
    fun createGroup(userId: Long, groupName: String, password: String?, maxCount: Int): Group {
        val owner = userRepository.findById(userId).orElseThrow { UserNotFoundException(userId) }
        val encodedPw = password?.let { passwordEncoder.encode(it) }
        val group = groupRepository.save(Group(groupName, encodedPw, maxCount, owner))
        // 생성자도 멤버로 참여
        participateRepository.save(Participate(group, owner))
        groupRepository.incrementMemberCounterIfNotFull(group.id)
        return group
    }

    @Transactional
    fun joinGroup(userId: Long, groupId: Long, password: String?) {
        val user = userRepository.findById(userId).orElseThrow { UserNotFoundException(userId) }
        val group = groupRepository.findById(groupId).orElseThrow { GroupNotFoundException(groupId) }

        if (participateRepository.existsByGroupAndUser(group, user)) throw AlreadyInGroupException()

        // 비밀번호 검증
        // val로 캡처해야 스마트캐스트 가능 (var 프로퍼티는 null 체크 후에도 스마트캐스트 불가)
        val storedPw = group.groupPw
        if (storedPw != null) {
            if (password == null || !passwordEncoder.matches(password, storedPw))
                throw GroupPasswordMismatchException()
        }

        // 원자적 증가: DB 레벨에서 조건 확인 + 증가 단일 연산
        // 0 = 정원 초과, 1 = 성공
        val updated = groupRepository.incrementMemberCounterIfNotFull(groupId)
        if (updated == 0) throw GroupFullException()

        participateRepository.save(Participate(group, user))
    }

    @Transactional
    fun leaveGroup(userId: Long, groupId: Long) {
        val user = userRepository.findById(userId).orElseThrow { UserNotFoundException(userId) }
        val group = groupRepository.findById(groupId).orElseThrow { GroupNotFoundException(groupId) }

        // 실제로 삭제된 행이 있을 때만 카운터를 내린다.
        // 비멤버가 호출해도 무조건 감소시키면 member_counter가 실제 인원보다 낮아져
        // incrementMemberCounterIfNotFull()의 정원 방어가 한 자리 더 허용하게 된다.
        val deleted = participateRepository.deleteByGroupAndUser(group, user)
        if (deleted == 0L) throw NotGroupMemberException()

        groupRepository.decrementMemberCounter(groupId)
    }

    @Transactional(readOnly = true)
    fun getGroupInfo(userId: Long): GroupInfoResult {
        val user = userRepository.findById(userId).orElseThrow { UserNotFoundException(userId) }
        val participations = participateRepository.findByUser(user)
        if (participations.isEmpty()) return GroupInfoResult(null, emptyList())

        val group = participations.first().group
        val memberIds = participateRepository.findMemberIdsByGroupId(group.id)

        val (thisMonthStart, nextMonthStart, prevMonthStart) = dateRanges()
        val memberCommits = commitRepository.findMemberCommits(
            memberIds, thisMonthStart, nextMonthStart, prevMonthStart
        )

        return GroupInfoResult(group, memberCommits)
    }

    @Transactional
    fun changePassword(userId: Long, groupId: Long, newPassword: String) {
        val group = groupRepository.findById(groupId).orElseThrow { GroupNotFoundException(groupId) }
        if (group.owner.id != userId) throw NotGroupOwnerException()
        group.changePassword(passwordEncoder.encode(newPassword) ?: error("비밀번호 인코딩 실패"))
    }

    @Transactional(readOnly = true)
    fun searchGroups(prefix: String): List<Group> =
        groupRepository.findByGroupNameStartingWith(prefix)

    @Transactional(readOnly = true)
    fun isGroupNameAvailable(groupName: String): Boolean =
        !groupRepository.existsByGroupName(groupName)

    private fun dateRanges(): Triple<LocalDateTime, LocalDateTime, LocalDateTime> {
        val now = LocalDateTime.now(clock)
        val thisMonthStart = now.withDayOfMonth(1).toLocalDate().atStartOfDay()
        return Triple(thisMonthStart, thisMonthStart.plusMonths(1), thisMonthStart.minusMonths(1))
    }
}

data class GroupInfoResult(
    val group: Group?,
    val memberCommits: List<MemberCommitProjection>,
)
