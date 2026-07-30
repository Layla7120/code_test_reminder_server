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

    // 삭제된 행 수를 반환한다 — 비멤버의 탈퇴 요청과 실제 탈퇴를 구분하기 위함
    fun deleteByGroupAndUser(group: Group, user: User): Long
}
