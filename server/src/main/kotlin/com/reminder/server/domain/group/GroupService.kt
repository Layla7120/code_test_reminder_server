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

        // 그룹명·비밀번호를 trim 한다. 참여할 때도 같은 기준으로 trim 하므로 양쪽이 대칭이다.
        // 한쪽만 trim 하면 "분명 맞는 비밀번호인데 안 들어가진다"가 된다.
        val name = groupName.trim()
        if (groupRepository.existsByGroupName(name)) throw DuplicateGroupNameException(name)

        val encodedPw = password?.trim()?.takeIf { it.isNotEmpty() }?.let { passwordEncoder.encode(it) }
        val group = groupRepository.save(Group(name, encodedPw, maxCount, owner))

        // 생성자도 멤버로 참여 — joinGroup과 동일한 순서: 증가 먼저, 성공 확인 후 참여 기록.
        // maxCount=0처럼 정원이 자기 자신도 못 채우는 값이면 여기서 막힌다.
        val updated = groupRepository.incrementMemberCounterIfNotFull(group.id)
        if (updated == 0) throw GroupFullException()
        participateRepository.save(Participate(group, owner))

        // incrementMemberCounterIfNotFull은 @Modifying 벌크 UPDATE라 DB는 바뀌지만
        // Hibernate가 세터를 거치지 않아 위 group 객체의 memberCounter는 여전히 0이다.
        // 그대로 반환하면 응답의 memberCount가 항상 0으로 나간다. clearAutomatically가
        // 걸려있어 이 재조회는 1차 캐시가 아니라 DB에서 정확한 값을 다시 읽어온다.
        return groupRepository.findById(group.id).orElseThrow { GroupNotFoundException(group.id) }
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
            // 생성 시 trim 해서 저장했으므로 여기서도 trim 해야 대칭이 맞는다.
            // 복사·붙여넣기로 앞뒤 공백이 붙는 경우가 흔한데, 한쪽만 trim 하면
            // 맞는 비밀번호인데도 계속 불일치가 난다.
            val given = password?.trim()
            if (given.isNullOrEmpty() || !passwordEncoder.matches(given, storedPw))
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

    // 유저는 여러 그룹에 동시에 속할 수 있다(요구사항). participations.first()로
    // 하나만 반환하면 두 번째부터는 조용히 버려진다 — 스키마가 아니라 이 조회가 버그였다.
    @Transactional(readOnly = true)
    fun getGroupInfo(userId: Long): List<GroupInfoResult> {
        val user = userRepository.findById(userId).orElseThrow { UserNotFoundException(userId) }
        val participations = participateRepository.findByUser(user)

        val (thisMonthStart, nextMonthStart, prevMonthStart) = dateRanges()

        return participations.map { participation ->
            val group = participation.group
            val memberIds = participateRepository.findMemberIdsByGroupId(group.id)
            val memberCommits = commitRepository.findMemberCommits(
                memberIds, thisMonthStart, nextMonthStart, prevMonthStart
            )
            GroupInfoResult(group, memberCommits)
        }
    }

    @Transactional
    fun changePassword(userId: Long, groupId: Long, newPassword: String) {
        val group = groupRepository.findById(groupId).orElseThrow { GroupNotFoundException(groupId) }
        if (group.owner.id != userId) throw NotGroupOwnerException()
        val pw = newPassword.trim()
        require(pw.isNotEmpty()) { "새 비밀번호는 비워둘 수 없습니다" }
        group.changePassword(passwordEncoder.encode(pw) ?: error("비밀번호 인코딩 실패"))
    }

    @Transactional(readOnly = true)
    fun searchGroups(prefix: String): List<Group> =
        groupRepository.findByGroupNameStartingWith(prefix)

    @Transactional(readOnly = true)
    fun isGroupNameAvailable(groupName: String): Boolean =
        !groupRepository.existsByGroupName(groupName.trim())

    private fun dateRanges(): Triple<LocalDateTime, LocalDateTime, LocalDateTime> {
        val now = LocalDateTime.now(clock)
        val thisMonthStart = now.withDayOfMonth(1).toLocalDate().atStartOfDay()
        return Triple(thisMonthStart, thisMonthStart.plusMonths(1), thisMonthStart.minusMonths(1))
    }
}

data class GroupInfoResult(
    val group: Group,
    val memberCommits: List<MemberCommitProjection>,
)
