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
    // 비활성 유저를 빼는 일은 여기가 아니라 deleteUser 가 행을 지워서 한다 — users 를 조인하면
    // 옵티마이저가 idx_rank 를 버리고 users 를 풀스캔한다 (50k 기준 87ms vs 0.5ms).
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
    // active 필터가 없는 이유는 findTop 과 같다.
    @Query(
        """
        SELECT COUNT(DISTINCT s.score) FROM UserMonthlyScore s
        WHERE s.scoreMonth = :scoreMonth
          AND s.score > :score
        """
    )
    fun countHigherDistinctScores(@Param("scoreMonth") scoreMonth: String, @Param("score") score: Int): Long

    /**
     * 이 유저의 모든 달 점수를 commits 에서 다시 만든다. 재가입 시 쓴다.
     *
     * recompute 는 달 하나를 받지만 여기서는 어느 달에 커밋이 있는지 모른다 —
     * 커밋이 있는 달만 GROUP BY 로 뽑아 한 번에 채운다.
     * 백필 마이그레이션(V20260819212939)과 같은 SQL 을 유저 하나로 좁힌 것이다.
     *
     * CONVERT_TZ 로 KST 월을 뽑는다 — commit_date 는 UTC 라 그냥 DATE_FORMAT 하면
     * 매월 1일 새벽(KST) 커밋이 지난달로 들어간다. 경위: global/ServiceZone
     */
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query(
        value = """
        INSERT INTO user_monthly_score (user_id, score_month, score)
        SELECT user_id, DATE_FORMAT(CONVERT_TZ(commit_date, '+00:00', '+09:00'), '%Y%m'), COUNT(*)
        FROM commits
        WHERE user_id = :userId
        GROUP BY user_id, DATE_FORMAT(CONVERT_TZ(commit_date, '+00:00', '+09:00'), '%Y%m')
        ON DUPLICATE KEY UPDATE score = VALUES(score)
        """,
        nativeQuery = true,
    )
    fun backfillFromCommits(@Param("userId") userId: Long)

    // 비활성 유저는 행 자체를 갖지 않는다는 불변조건을 세우는 쪽.
    // 랭킹 쿼리에서 active 필터를 뺄 수 있는 근거가 전부 여기에 있다.
    @Transactional
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM UserMonthlyScore s WHERE s.userId = :userId")
    fun deleteAllByUserId(@Param("userId") userId: Long)
}
