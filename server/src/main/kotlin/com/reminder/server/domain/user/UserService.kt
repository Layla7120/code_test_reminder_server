package com.reminder.server.domain.user

import com.reminder.server.domain.group.GroupService
import com.reminder.server.domain.group.ParticipateRepository
import com.reminder.server.global.exception.UserNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UserService(
    private val userRepository: UserRepository,
    private val participateRepository: ParticipateRepository,
    private val groupService: GroupService,
) {

    // 로그인 또는 신규 가입. githubId 로 찾고 없으면 만든다.
    // 탈퇴한 계정은 행이 없으므로 여기서 갈라질 상태가 없다 — 재가입은 완전한 신규 유저다.
    @Transactional
    fun loginOrCreate(githubId: String, nickname: String, repositoryName: String): User =
        userRepository.findByGithubId(githubId.trim())
            ?: userRepository.save(User(githubId.trim(), nickname.trim(), repositoryName.trim()))

    @Transactional(readOnly = true)
    fun getUser(userId: Long): User =
        userRepository.findById(userId).orElseThrow { UserNotFoundException(userId) }

    @Transactional
    fun updateUser(userId: Long, nickname: String?, repositoryName: String?) {
        val user = getUser(userId)
        user.updateProfile(
            nickname?.trim() ?: user.nickname,
            repositoryName?.trim() ?: user.repositoryName,
        )
        // 변경 감지(Dirty Checking) → 별도 save() 불필요
    }

    /**
     * 탈퇴. 유저 행을 실제로 지운다.
     *
     * commits·history·user_monthly_score 는 FK 의 ON DELETE CASCADE 가 함께 지운다.
     * participate 만 RESTRICT 라 앱이 책임진다 — groups.member_counter 가 그 행들에서
     * 파생되기 때문이다. 아래 leaveGroup 을 빠뜨리면 조용히 어긋나는 대신 FK 위반으로 터진다.
     * 경위: docs/adr/0002-cascade-는-파생값의-원천이-아닐-때만-건다.md
     */
    @Transactional
    fun deleteUser(userId: Long) {
        val user = getUser(userId)

        // 그룹 탈퇴는 GroupService 에 맡긴다. 오너 승계와 "마지막 멤버면 그룹 삭제"가
        // 거기 있고, 여기서 다시 구현하면 두 경로가 갈라진다.
        participateRepository.findByUser(user)
            .map { it.group.id }
            .forEach { groupId -> groupService.leaveGroup(userId, groupId) }

        // leaveGroup 의 decrementMemberCounter 가 clearAutomatically 라 여기서 user 는
        // 준영속일 수 있다. delete() 는 준영속 엔티티를 merge 한 뒤 지우므로 그대로 나간다 —
        // 변경 감지에 기대는 코드였다면 다시 읽어야 했다.
        userRepository.delete(user)
    }

    @Transactional(readOnly = true)
    fun isNicknameAvailable(nickname: String): Boolean =
        !userRepository.existsByNickname(nickname)
}
