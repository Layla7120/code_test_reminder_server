package com.reminder.server.domain.rank

import com.reminder.server.domain.commit.CommitRepository
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime
import java.time.YearMonth

@Service
class RankService(
    private val rankingRedisRepository: RankingRedisRepository,
    private val commitRepository: CommitRepository,
    private val clock: Clock,
) {
    fun getTop30(): List<RankEntry> {
        return try {
            val entries = rankingRedisRepository.getTop30(YearMonth.now(clock))
            // Redis가 비어있으면 (초기 기동 등) DB에서 폴백
            if (entries.isNotEmpty()) entries else getTop30FromDb()
        } catch (e: RedisConnectionFailureException) {
            // Redis 장애 시 DB 폴백 — 성능 저하 감수, 가용성 우선
            getTop30FromDb()
        }
    }

    fun getUserRank(userId: Long): Long? {
        return try {
            rankingRedisRepository.getUserDenseRank(userId, YearMonth.now(clock))
        } catch (e: RedisConnectionFailureException) {
            getUserRankFromDb(userId)
        }
    }

    // ── DB 폴백 (Redis 장애 또는 초기 기동 시) ────────────────────────────────

    @Transactional(readOnly = true)
    private fun getTop30FromDb(): List<RankEntry> {
        val (thisMonthStart, nextMonthStart, prevMonthStart) = dateRanges()
        return commitRepository.findTop30Rank(thisMonthStart, nextMonthStart, prevMonthStart)
            .map { RankEntry(it.getUserId(), it.getCurrentMonthCount(), it.getRank()) }
    }

    @Transactional(readOnly = true)
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
