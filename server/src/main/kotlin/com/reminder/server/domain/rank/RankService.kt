package com.reminder.server.domain.rank

import com.reminder.server.domain.commit.CommitRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDateTime
import java.time.YearMonth

@Service
class RankService(
    private val rankingRedisRepository: RankingRedisRepository,
    private val commitRepository: CommitRepository,
    private val clock: Clock,
    // false면 아래 "Redis 장애 폴백"과 똑같은 DB 경로를 강제로 탄다 — A/B 측정과 장애 시나리오의 변수. 경위: docs/기록.md
    @Value("\${ranking.redis.enabled:true}") private val redisRankingEnabled: Boolean = true,
) {
    fun getTop30(): List<RankEntry> {
        if (!redisRankingEnabled) return getTop30FromDb()

        return try {
            val entries = rankingRedisRepository.getTop30(YearMonth.now(clock))
            // Redis가 비어있으면 (초기 기동 등) DB에서 폴백
            if (entries.isNotEmpty()) entries else getTop30FromDb()
        } catch (e: DataAccessException) {
            // Redis 장애(연결 끊김, 타임아웃 등) 시 DB 폴백 — 성능 저하 감수, 가용성 우선
            // RedisConnectionFailureException만 잡던 이전 버전은 QueryTimeoutException 같은
            // 타임아웃 계열을 못 잡아 그대로 500이 나갔다. 둘 다 DataAccessException의 하위 타입.
            getTop30FromDb()
        }
    }

    fun getUserRank(userId: Long): Long? {
        if (!redisRankingEnabled) return getUserRankFromDb(userId)

        val yearMonth = YearMonth.now(clock)
        return try {
            rankingRedisRepository.getUserDenseRank(userId, yearMonth)
            // null 은 "랭킹 미구축"과 "이 유저만 점수 없음" 둘 다라 isEmpty 로 가른다 — RankPathConsistencyTest.
                ?: if (rankingRedisRepository.isEmpty(yearMonth)) getUserRankFromDb(userId) else null
        } catch (e: DataAccessException) {
            getUserRankFromDb(userId)
        }
    }

    // ── DB 폴백 (Redis 장애 또는 초기 기동 시) ────────────────────────────────
    //
    // @Transactional 을 붙이지 않는다 — self-invocation 이라 프록시를 안 거치고, private 은 대상도 아니다.
    // (JpaRepository 메서드는 자체 트랜잭션이 있어 없어도 안전하다)

    private fun getTop30FromDb(): List<RankEntry> {
        val (thisMonthStart, nextMonthStart, _) = dateRanges()
        return commitRepository.findTop30Rank(thisMonthStart, nextMonthStart)
            .map { RankEntry(it.getUserId(), it.getCurrentMonthCount(), it.getRank()) }
    }

    private fun getUserRankFromDb(userId: Long): Long? {
        val (thisMonthStart, nextMonthStart, _) = dateRanges()
        return commitRepository.findUserRank(userId, thisMonthStart, nextMonthStart)?.getRank()
    }

    private fun dateRanges(): Triple<LocalDateTime, LocalDateTime, LocalDateTime> {
        val now = LocalDateTime.now(clock)
        val thisMonthStart = now.withDayOfMonth(1).toLocalDate().atStartOfDay()
        val nextMonthStart = thisMonthStart.plusMonths(1)
        val prevMonthStart = thisMonthStart.minusMonths(1)
        return Triple(thisMonthStart, nextMonthStart, prevMonthStart)
    }
}
