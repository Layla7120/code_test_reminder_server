package com.reminder.server.domain.user

import com.reminder.server.domain.group.GroupService
import com.reminder.server.domain.group.ParticipateRepository
import com.reminder.server.domain.rank.UserMonthlyScoreRepository
import com.reminder.server.domain.rank.toScoreMonth
import com.reminder.server.global.exception.UserNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.YearMonth

@Service
class UserService(
    private val userRepository: UserRepository,
    private val userMonthlyScoreRepository: UserMonthlyScoreRepository,
    private val participateRepository: ParticipateRepository,
    private val groupService: GroupService,
) {

    /**
     * 로그인 또는 신규 가입. githubId 로 찾고 없으면 만든다.
     *
     * 탈퇴한 계정이면 되살린다. 예전에는 비활성인 채로 그대로 로그인돼서, 쓸 수는 있는데
     * 랭킹에는 영영 안 잡히는 좀비 계정이 됐다 — 점수를 쓰는 쪽이 active 를 확인하기 때문이다.
     *
     * 커밋과 이력은 탈퇴해도 지우지 않으므로, 점수만 다시 세면 탈퇴 전 상태로 돌아온다.
     * 그룹은 탈퇴할 때 나갔으므로 다시 참여해야 한다 — 남은 멤버 입장에서 모르는 사이에
     * 사람이 돌아와 있으면 곤란하다.
     */
    @Transactional
    fun loginOrCreate(githubId: String, nickname: String, repositoryName: String): User {
        val existing = userRepository.findByGithubId(githubId.trim())
            ?: return userRepository.save(User(githubId.trim(), nickname.trim(), repositoryName.trim()))

        if (!existing.active) {
            existing.reactivate()
            restoreScores(existing.id)
        }
        return existing
    }

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
     * 탈퇴. 유저가 시스템에 남긴 상태를 전부 정리한다.
     *
     * 예전에는 active=false 와 점수 행 삭제만 했다. 그래서 그룹에 남긴 흔적이 그대로 남아
     * 탈퇴자가 정원을 계속 먹고(유령 멤버), 오너였다면 아무도 그룹을 관리할 수 없었다.
     * UserLifecycleApiTest 가 그 셋을 고정한다.
     *
     * 커밋과 이력은 지우지 않는다 — GitHub 이 원본이고, 재가입 시 그대로 되살아난다.
     */
    @Transactional
    fun deleteUser(userId: Long) {
        val user = getUser(userId)

        // 그룹 탈퇴는 GroupService 에 맡긴다. 오너 승계와 "마지막 멤버면 그룹 삭제"가
        // 거기 있고, 여기서 다시 구현하면 두 경로가 갈라진다.
        participateRepository.findByUser(user)
            .map { it.group.id }
            .forEach { groupId -> groupService.leaveGroup(userId, groupId) }

        // 다시 읽는다. leaveGroup 의 decrementMemberCounter 가 clearAutomatically 라
        // 영속성 컨텍스트를 통째로 비우고, 위 user 는 준영속이 되어 변경이 반영되지 않는다.
        getUser(userId).deactivate()

        // 랭킹 쿼리에는 active 필터가 없다 — 비활성 유저를 빼는 일을 이 삭제가 한다.
        userMonthlyScoreRepository.deleteAllByUserId(userId)
    }

    @Transactional(readOnly = true)
    fun isNicknameAvailable(nickname: String): Boolean =
        !userRepository.existsByNickname(nickname)

    // 재가입 시 점수 복구. 커밋이 남아 있는 달만 행이 생긴다.
    private fun restoreScores(userId: Long) {
        userMonthlyScoreRepository.backfillFromCommits(userId)
    }
}
