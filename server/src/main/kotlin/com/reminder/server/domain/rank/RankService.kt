package com.reminder.server.domain.rank

import com.reminder.server.global.ServiceZone
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Service
import java.time.Clock

/**
 * 랭킹 조회. 진실 원천은 user_monthly_score 하나다.
 *
 * 경로가 하나라 폴백이라는 개념이 없다. 폴백은 "경로가 둘"이라서 필요했던 것이고,
 * 그 둘이 어긋나 있던 게 51abeb7 이었다.
 *
 * @Transactional 을 붙이지 않는다 — JpaRepository 메서드는 자체 트랜잭션이 있다.
 */
@Service
class RankService(
    private val userMonthlyScoreRepository: UserMonthlyScoreRepository,
    private val clock: Clock,
) {
    // 정렬·LIMIT 은 idx_rank(score_month, score DESC) 가 그대로 처리한다.
    // 동순위 계산만 앱에서 한다 — SQL 의 DENSE_RANK 는 전체 집계를 필요로 하는데,
    // 여기서는 이미 집계된 30행만 있으면 된다.
    fun getTop30(): List<RankEntry> =
        userMonthlyScoreRepository.findTop(scoreMonth(), Limit.of(TOP_N))
            .map { it.userId to it.score.toLong() }
            .toDenseRankEntries()

    /**
     * dense rank = 나보다 높은 점수의 "종류" 수 + 1.
     *
     * 한 쿼리로 묶지 않고 두 단계로 나눈다 — null 의 의미를 구분하기 위해서다.
     * 행이 없으면(= 랭킹 대상이 아니면) null 이고, 그건 0 등과 다르다.
     */
    fun getUserRank(userId: Long): Long? {
        val scoreMonth = scoreMonth()
        val myScore = userMonthlyScoreRepository.findScore(userId, scoreMonth) ?: return null
        // 커밋 0 건인 유저는 랭킹에 없다 (I4). findTop 의 score > 0 과 같은 규칙이다.
        if (myScore <= 0) return null
        return userMonthlyScoreRepository.countHigherDistinctScores(scoreMonth, myScore) + 1
    }

    // KST 기준 이번 달. 쓰는 쪽(CommitService.recomputeMonthlyScore)과 같은 기준이어야 한다.
    private fun scoreMonth(): String = ServiceZone.currentMonth(clock).toScoreMonth()

    companion object {
        private const val TOP_N = 30
    }
}
