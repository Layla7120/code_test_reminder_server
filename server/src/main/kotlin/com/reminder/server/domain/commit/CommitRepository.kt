package com.reminder.server.domain.commit

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface CommitRepository : JpaRepository<Commit, Long> {

    // ── 랭킹 ──────────────────────────────────────────────────────────────────

    // dense_rank() 윈도우 함수 — HQL 로도 된다(Hibernate 6.1+ over()). 실행되는 SQL 을
    // 그대로 읽으려고 native 를 택한 것이지 제약이 아니다.
    //
    // [인덱스 활용 방식]
    // YEAR(commit_date) 방식(수정 전): 함수로 컬럼을 감싸면 B-Tree 인덱스 탐색 불가 → Full Scan
    // 범위 조건(수정 후): commit_date >= :start 형태면 idx_commit_date 인덱스 사용
    //
    // [NOW() 제거 이유]
    // DB 내장 NOW()를 쓰면 "특정 시점 랭킹" 테스트가 불가능
    // → 서비스 레이어에서 Clock으로 계산한 값을 파라미터로 전달
    //
    // 안 쓰는 컬럼을 빼면 GROUP BY 키가 좁아진다 (10만 유저 4,882→531ms). 경위: docs/기록.md
    //
    // [LEFT JOIN → JOIN]
    // 커밋이 0건인 유저는 Top 30에 들어갈 수 없다. 또한 Redis 경로(ZSET)도 점수가 있는
    // 유저만 담으므로, INNER JOIN이 두 경로의 결과를 일치시킨다.
    @Query("""
        SELECT
            u.user_id            AS userId,
            COUNT(c.commit_id)   AS currentMonthCount,
            DENSE_RANK() OVER (ORDER BY COUNT(c.commit_id) DESC) AS `rank`
        FROM users u
        JOIN commits c
            ON u.user_id = c.user_id
            AND c.commit_date >= :thisMonthStart
            AND c.commit_date < :nextMonthStart
        WHERE u.active = true
        GROUP BY u.user_id
        ORDER BY `rank`, userId
        LIMIT 30
    """, nativeQuery = true)
    fun findTop30Rank(
        @Param("thisMonthStart") thisMonthStart: LocalDateTime,
        @Param("nextMonthStart") nextMonthStart: LocalDateTime,
    ): List<RankProjection>

    // [LEFT JOIN → JOIN] findTop30Rank 와 같은 이유 — 여기만 빠져 있어 두 경로가 어긋나 있었다. 경위: docs/기록.md
    @Query("""
        SELECT rank_table.`rank`            AS `rank`,
               rank_table.currentMonthCount AS currentMonthCount
        FROM (
            SELECT
                u.user_id,
                COUNT(c.commit_id) AS currentMonthCount,
                DENSE_RANK() OVER (ORDER BY COUNT(c.commit_id) DESC) AS `rank`
            FROM users u
            JOIN commits c
                ON u.user_id = c.user_id
                AND c.commit_date >= :thisMonthStart AND c.commit_date < :nextMonthStart
            WHERE u.active = true
            GROUP BY u.user_id
        ) rank_table
        WHERE rank_table.user_id = :userId
    """, nativeQuery = true)
    fun findUserRank(
        @Param("userId") userId: Long,
        @Param("thisMonthStart") thisMonthStart: LocalDateTime,
        @Param("nextMonthStart") nextMonthStart: LocalDateTime,
    ): UserRankProjection?

    // ── 그룹 ──────────────────────────────────────────────────────────────────

    @Query("""
        SELECT
            u.user_id  AS userId,
            u.nickname AS nickname,
            SUM(CASE WHEN c.commit_date >= :thisMonthStart AND c.commit_date < :nextMonthStart
                 THEN 1 ELSE 0 END) AS currentMonthCount,
            SUM(CASE WHEN c.commit_date >= :prevMonthStart AND c.commit_date < :thisMonthStart
                 THEN 1 ELSE 0 END) AS previousMonthCount,
            DENSE_RANK() OVER (
                ORDER BY SUM(CASE WHEN c.commit_date >= :thisMonthStart AND c.commit_date < :nextMonthStart
                              THEN 1 ELSE 0 END) DESC
            ) AS `rank`
        FROM users u
        LEFT JOIN commits c
            ON u.user_id = c.user_id
            AND c.commit_date >= :prevMonthStart
        WHERE u.user_id IN (:memberIds)
        GROUP BY u.user_id, u.nickname
        ORDER BY `rank`
    """, nativeQuery = true)
    fun findMemberCommits(
        @Param("memberIds") memberIds: List<Long>,
        @Param("thisMonthStart") thisMonthStart: LocalDateTime,
        @Param("nextMonthStart") nextMonthStart: LocalDateTime,
        @Param("prevMonthStart") prevMonthStart: LocalDateTime,
    ): List<MemberCommitProjection>

    // ── 커밋 현황 (JPQL) ──────────────────────────────────────────────────────
    // 윈도우 함수 없는 단순 조회 → JPQL로 타입 안전성 확보

    // 엔티티 전체 조회 금지 — 잔디/활동 조회는 commitDate, level만 필요
    // List<Commit> 반환 시 엔티티 스냅샷 + 프록시가 영속성 컨텍스트에 전부 올라옴
    // → DTO Projection으로 필요한 컬럼만 스칼라 타입으로 추출
    @Query("""
        SELECT c.commitDate AS commitDate, c.level AS level
        FROM Commit c
        WHERE c.user.id = :userId
          AND c.commitDate >= :from
          AND c.commitDate < :to
        ORDER BY c.commitDate
    """)
    fun findCommitSummariesByUserAndDateRange(
        @Param("userId") userId: Long,
        @Param("from") from: LocalDateTime,
        @Param("to") to: LocalDateTime,
    ): List<CommitSummaryProjection>

    // 난이도별 커밋 수 — JPQL GROUP BY (native query 불필요)
    @Query("SELECT c.level AS level, COUNT(c) AS count FROM Commit c WHERE c.user.id = :userId GROUP BY c.level")
    fun findLevelDistribution(@Param("userId") userId: Long): List<LevelCountProjection>

    // 스케줄러 자가 치유용: 특정 월의 유저별 커밋 수 집계
    @Query("""
        SELECT c.user.id AS userId, COUNT(c) AS count
        FROM Commit c
        WHERE c.commitDate >= :from AND c.commitDate < :to
        GROUP BY c.user.id
    """)
    fun findMonthlyCommitCountPerUser(
        @Param("from") from: LocalDateTime,
        @Param("to") to: LocalDateTime,
    ): List<UserCommitCountProjection>

    fun existsBySha(sha: String): Boolean
}

interface UserCommitCountProjection {
    fun getUserId(): Long
    fun getCount(): Long
}

interface LevelCountProjection {
    fun getLevel(): String
    fun getCount(): Long
}
