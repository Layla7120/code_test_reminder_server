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
        if (group.groupPw != null) {
            if (password == null || !passwordEncoder.matches(password, group.groupPw))
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

        participateRepository.deleteByGroupAndUser(group, user)
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
        group.changePassword(passwordEncoder.encode(newPassword))
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
