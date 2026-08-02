package com.reminder.server.domain.commit

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface CommitRepository : JpaRepository<Commit, Long> {

    // ── 랭킹 ──────────────────────────────────────────────────────────────────

    // dense_rank() 윈도우 함수 → native query 불가피 (JPQL 미지원)
    //
    // [인덱스 활용 방식]
    // YEAR(commit_date) 방식(수정 전): 함수로 컬럼을 감싸면 B-Tree 인덱스 탐색 불가 → Full Scan
    // 범위 조건(수정 후): commit_date >= :start 형태면 idx_commit_date 인덱스 사용
    //
    // [NOW() 제거 이유]
    // DB 내장 NOW()를 쓰면 "특정 시점 랭킹" 테스트가 불가능
    // → 서비스 레이어에서 Clock으로 계산한 값을 파라미터로 전달
    //
    // [응답에 쓰이는 컬럼만 조회한다]
    // 이전 버전은 github_id·nickname·previousMonthCount를 함께 뽑았는데
    // 유일한 소비자(RankService.getTop30FromDb)는 userId·currentMonthCount·rank만 쓴다.
    // 안 쓰는 VARCHAR 두 개가 GROUP BY에 들어가면서 넓은 정렬 키로 그룹핑이 일어나고,
    // 지난달 집계 때문에 조인 범위도 2개월로 늘어나 있었다.
    // 유저 10만 기준 실측: 4,882ms → 531ms (9.2배). DENSE_RANK 자체 비용은 9ms에 불과했다.
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

    // [LEFT JOIN → JOIN] — findTop30Rank 와 같은 이유이고, 여기만 빠져 있었다.
    // 이전에는 LEFT JOIN + SUM(CASE ...) 라서 이번 달 커밋이 0건인 유저도
    // currentMonthCount = 0 으로 DENSE_RANK 를 받았다. 그런데 Redis 경로는 ZSET 에
    // 점수가 없으면 null 을 돌려준다(RankingRedisRepository.getUserDenseRank).
    // 같은 유저의 순위를 묻는데 Redis 는 null, DB 는 숫자를 주는 상태였다.
    // bench/rank_ab.js 가 두 경로의 지연시간을 A/B 로 비교하므로,
    // 등가가 아닌 두 구현을 비교하고 있었다.
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
