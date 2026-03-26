package com.reminder.server.domain.user

import org.springframework.data.jpa.repository.JpaRepository

interface UserRepository : JpaRepository<User, Long> {
    fun findByGithubId(githubId: String): User?
    fun existsByNickname(nickname: String): Boolean
    fun findByNickname(nickname: String): User?
}
