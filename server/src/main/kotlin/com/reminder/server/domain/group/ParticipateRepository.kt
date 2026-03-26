package com.reminder.server.domain.group

import com.reminder.server.domain.user.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ParticipateRepository : JpaRepository<Participate, Long> {

    fun existsByGroupAndUser(group: Group, user: User): Boolean

    fun findByUser(user: User): List<Participate>

    // 그룹 멤버 user_id 목록 조회 (CommitRepository.findMemberCommits에 전달)
    @Query("SELECT p.user.id FROM Participate p WHERE p.group.id = :groupId")
    fun findMemberIdsByGroupId(@Param("groupId") groupId: Long): List<Long>

    fun deleteByGroupAndUser(group: Group, user: User)
}
