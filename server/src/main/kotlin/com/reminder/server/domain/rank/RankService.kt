package com.reminder.server.domain.rank

import com.reminder.server.domain.commit.CommitRepository
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
) {
    fun getTop30(): List<RankEntry> {
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
        return try {
            rankingRedisRepository.getUserDenseRank(userId, YearMonth.now(clock))
        } catch (e: DataAccessException) {
            getUserRankFromDb(userId)
        }
    }

    // ── DB 폴백 (Redis 장애 또는 초기 기동 시) ────────────────────────────────
    //
    // private 메서드에는 @Transactional을 붙이지 않는다. Spring AOP 프록시는
    // "외부에서 프록시를 거쳐 들어오는 호출"만 가로채는데, 여기는 같은 클래스 안의
    // self-invocation(getTop30() → getTop30FromDb())이라 프록시를 안 거친다.
    // 게다가 private 메서드는 애초에 오버라이드가 불가능해 프록시 대상도 될 수 없다.
    // (JpaRepository의 각 메서드는 자체적으로 이미 트랜잭션이 걸려 있어 없어도 안전하다)

    private fun getTop30FromDb(): List<RankEntry> {
        val (thisMonthStart, nextMonthStart, prevMonthStart) = dateRanges()
        return commitRepository.findTop30Rank(thisMonthStart, nextMonthStart, prevMonthStart)
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
