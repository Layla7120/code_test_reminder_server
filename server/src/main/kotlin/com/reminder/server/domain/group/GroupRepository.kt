package com.reminder.server.domain.group

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface GroupRepository : JpaRepository<Group, Long> {

    fun findByGroupName(groupName: String): Group?

    fun existsByGroupName(groupName: String): Boolean

    // 원자적 증가: DB 레벨에서 조건 확인 + 증가를 단일 연산으로 처리
    //
    // [비관적 락 방식과의 비교]
    // 비관적 락: SELECT FOR UPDATE → 다른 트랜잭션 블로킹 → 처리량 저하
    // 원자적 UPDATE: 락 없이 DB 엔진이 row-level로 처리 → 더 가볍고 빠름
    //
    // [TOCTOU(check-then-act)와의 비교]
    // COUNT 조회 후 INSERT: 두 요청이 동시에 count=4 확인 후 둘 다 삽입 가능
    // WHERE counter < max: 조건이 false면 UPDATE 자체가 실패 → 원자적 방어
    //
    // flushAutomatically = true: 이 UPDATE를 날리기 전에 영속성 컨텍스트에 아직
    // 플러시되지 않은 변경(예: 같은 트랜잭션에서 방금 호출한 derived delete)을 먼저 DB에
    // 반영한다. 이게 없으면 대기 중인 변경이 있는 상태로 clearAutomatically가 실행됐을 때
    // 그 변경이 DB에 한 번도 쓰이지 못한 채 통째로 버려질 수 있다.
    //
    // clearAutomatically = true: 이 UPDATE 이후 같은 트랜잭션에서 Group을 다시 조회하면
    // 영속성 컨텍스트 캐시가 아니라 DB에서 새로 읽어온다. 없으면 먼저 로딩해둔 Group
    // 엔티티의 memberCounter가 이 UPDATE를 반영하지 못한 채 stale 상태로 남는다.
    //
    // 반환값: 1 = 성공, 0 = 정원 초과 (서비스에서 0이면 예외 발생)
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE Group g
        SET g.memberCounter = g.memberCounter + 1
        WHERE g.id = :groupId AND g.memberCounter < g.memberMaxCount
    """)
    fun incrementMemberCounterIfNotFull(@Param("groupId") groupId: Long): Int

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Group g SET g.memberCounter = g.memberCounter - 1 WHERE g.id = :groupId AND g.memberCounter > 0")
    fun decrementMemberCounter(@Param("groupId") groupId: Long): Int

    @Query("SELECT g FROM Group g WHERE g.groupName LIKE :prefix%")
    fun findByGroupNameStartingWith(@Param("prefix") prefix: String): List<Group>
}
