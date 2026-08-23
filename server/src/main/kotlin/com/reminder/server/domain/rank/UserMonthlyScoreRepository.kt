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

    // score > 0 은 "커밋 0건인 유저는 랭킹에 없다".
    // 탈퇴자를 빼는 일은 여기가 아니라 FK 의 ON DELETE CASCADE 가 행을 지워서 한다 —
    // users 를 조인하면 옵티마이저가 idx_rank 를 버리고 users 를 풀스캔한다 (50k 기준 87ms vs 0.5ms, 2026-08-17 측정).
    // 동순위 계산은 SQL 이 아니라 toDenseRankEntries 가 한다.
    @Query(
        """
        SELECT s FROM UserMonthlyScore s
        WHERE s.scoreMonth = :scoreMonth
          AND s.score > 0
        ORDER BY s.score DESC, s.userId
        """
    )
    fun findTop(@Param("scoreMonth") scoreMonth: String, limit: Limit): List<UserMonthlyScore>

    @Query("SELECT s.score FROM UserMonthlyScore s WHERE s.userId = :userId AND s.scoreMonth = :scoreMonth")
    fun findScore(@Param("userId") userId: Long, @Param("scoreMonth") scoreMonth: String): Int?

    // dense rank = 나보다 높은 점수의 "종류" 수 + 1.
    // 결과는 distinct 개수지만 비용은 아니다 — 내 점수 위의 행을 전부 훑는다.
    // 커버링 인덱스라 행당은 싸도 50k 유저 중앙값에서 25,000행 11ms 다. 루스 인덱스 스캔은 안 걸린다.
    // users 를 조인하지 않는 이유는 findTop 과 같다.
    @Query(
        """
        SELECT COUNT(DISTINCT s.score) FROM UserMonthlyScore s
        WHERE s.scoreMonth = :scoreMonth
          AND s.score > :score
        """
    )
    fun countHigherDistinctScores(@Param("scoreMonth") scoreMonth: String, @Param("score") score: Int): Long
}
