package com.reminder.server.domain.user

import com.reminder.server.domain.rank.UserMonthlyScoreRepository
import com.reminder.server.global.exception.UserNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UserService(
    private val userRepository: UserRepository,
    private val userMonthlyScoreRepository: UserMonthlyScoreRepository,
) {

    // 레거시와 동일: githubId로 조회, 없으면 생성 (login or register)
    @Transactional
    fun loginOrCreate(githubId: String, nickname: String, repositoryName: String): User =
        userRepository.findByGithubId(githubId)
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

    // 랭킹 쿼리에는 active 필터가 없다 — 비활성 유저를 랭킹에서 빼는 일은 이 삭제가 한다.
    @Transactional
    fun deleteUser(userId: Long) {
        getUser(userId).deactivate()
        userMonthlyScoreRepository.deleteAllByUserId(userId)
    }

    @Transactional(readOnly = true)
    fun isNicknameAvailable(nickname: String): Boolean =
        !userRepository.existsByNickname(nickname)
}
