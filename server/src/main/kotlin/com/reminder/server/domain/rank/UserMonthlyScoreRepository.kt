package com.reminder.server.domain.rank

import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

interface UserMonthlyScoreRepository : JpaRepository<UserMonthlyScore, UserMonthlyScoreId> {

    // 몇 번을 실행해도 결과가 같다 — 이게 "재수집해도 점수가 안 오른다"를 구조적으로 보장한다.
    // native 인 이유는 ON DUPLICATE KEY UPDATE 가 JPQL 에 없어서다.
    // 커밋이 0건이어도 COUNT(*) 는 한 행을 돌려주므로 score = 0 으로 정확히 내려간다.
    // REQUIRED 라 CommitService 의 트랜잭션에 그대로 합류한다 — 커밋도 롤백도 같이 간다.
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query(
        value = """
        INSERT INTO user_monthly_score (user_id, score_month, score)
        SELECT :userId, :scoreMonth, COUNT(*)
        FROM commits
        WHERE user_id = :userId
          AND commit_date >= :from
          AND commit_date <  :to
        ON DUPLICATE KEY UPDATE score = VALUES(score)
        """,
        nativeQuery = true,
    )
    fun recompute(
        @Param("userId") userId: Long,
        @Param("scoreMonth") scoreMonth: String,
        @Param("from") from: LocalDateTime,
        @Param("to") to: LocalDateTime,
    )

    // score > 0 은 "커밋 0건인 유저는 랭킹에 없다", active 는 탈퇴 유저 제외.
    // 동순위 계산은 SQL 이 아니라 toDenseRankEntries 가 한다.
    @Query(
        """
        SELECT s FROM UserMonthlyScore s, User u
        WHERE u.id = s.userId
          AND s.scoreMonth = :scoreMonth
          AND s.score > 0
          AND u.active = true
        ORDER BY s.score DESC, s.userId
        """
    )
    fun findTop(@Param("scoreMonth") scoreMonth: String, limit: Limit): List<UserMonthlyScore>

    @Query("SELECT s.score FROM UserMonthlyScore s WHERE s.userId = :userId AND s.scoreMonth = :scoreMonth")
    fun findScore(@Param("userId") userId: Long, @Param("scoreMonth") scoreMonth: String): Int?

    // dense rank = 나보다 높은 점수의 "종류" 수 + 1.
    // 커밋 수는 값의 종류가 적어서(10만 명이어도 distinct score 는 수십 개) 이 셈이 싸다.
    @Query(
        """
        SELECT COUNT(DISTINCT s.score) FROM UserMonthlyScore s, User u
        WHERE u.id = s.userId
          AND s.scoreMonth = :scoreMonth
          AND u.active = true
          AND s.score > :score
        """
    )
    fun countHigherDistinctScores(@Param("scoreMonth") scoreMonth: String, @Param("score") score: Int): Long
}
