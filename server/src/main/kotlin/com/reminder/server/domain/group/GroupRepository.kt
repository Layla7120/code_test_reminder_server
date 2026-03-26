package com.reminder.server.domain.group

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface GroupRepository : JpaRepository<Group, Long> {

    fun findByGroupName(groupName: String): Group?

    fun existsByGroupName(groupName: String): Boolean

    // 그룹 참여 시 정원 초과 방지용 비관적 락
    // 락을 잡은 상태에서 isFull() 체크 → 동시 요청이 와도 순차 처리됨
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT g FROM Group g WHERE g.id = :groupId")
    fun findByIdWithLock(@Param("groupId") groupId: Long): Group?

    // 그룹명 prefix 검색 (그룹 탐색 기능)
    @Query("SELECT g FROM Group g WHERE g.groupName LIKE :prefix%")
    fun findByGroupNameStartingWith(@Param("prefix") prefix: String): List<Group>
}
