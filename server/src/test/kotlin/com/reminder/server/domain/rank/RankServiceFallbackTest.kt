package com.reminder.server.domain.rank

import com.reminder.server.domain.commit.CommitRepository
import com.reminder.server.domain.commit.UserRankProjection
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.dao.QueryTimeoutException
import org.springframework.data.redis.RedisConnectionFailureException
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneOffset

/**
 * 증명하는 주장: "Redis 장애 시 DB로 폴백한다"
 *
 * 기존 코드는 RedisConnectionFailureException만 잡았다. 실제 Redis 타임아웃은
 * QueryTimeoutException(더 일반적으로는 DataAccessException 계열)으로 올라오는데,
 * 이건 안 잡혀서 그대로 500이 나갔다 — "Redis가 죽으면요?"에 대한 답이 반쪽이었다.
 *
 * Spring 컨텍스트 없이 RankingRedisRepository를 Mockito로 대체하는 순수 단위 테스트.
 * Mockito.any()는 Kotlin의 non-null 파라미터에서 NPE를 내므로(Kotlin/Mockito interop
 * 이슈), any() 대신 고정 Clock으로 계산한 정확한 값을 stub에 사용한다.
 */
class RankServiceFallbackTest {

    private val clock: Clock = Clock.fixed(Instant.parse("2026-07-30T12:00:00Z"), ZoneOffset.UTC)
    private val yearMonth: YearMonth = YearMonth.now(clock)
    private val thisMonthStart: LocalDateTime =
        LocalDateTime.now(clock).withDayOfMonth(1).toLocalDate().atStartOfDay()
    private val nextMonthStart: LocalDateTime = thisMonthStart.plusMonths(1)

    private val rankingRedisRepository = mock(RankingRedisRepository::class.java)
    private val commitRepository = mock(CommitRepository::class.java)
    private val rankService = RankService(rankingRedisRepository, commitRepository, clock)

    @Test
    @DisplayName("Redis 연결 실패 시 DB로 폴백한다")
    fun fallsBackOnConnectionFailure() {
        `when`(rankingRedisRepository.getUserDenseRank(1L, yearMonth))
            .thenThrow(RedisConnectionFailureException("연결 끊김"))
        `when`(commitRepository.findUserRank(1L, thisMonthStart, nextMonthStart))
            .thenReturn(fakeUserRank(5L))

        val result = rankService.getUserRank(1L)

        assertThat(result).isEqualTo(5L)
    }

    @Test
    @DisplayName("Redis 타임아웃(QueryTimeoutException) 시에도 DB로 폴백한다")
    fun fallsBackOnTimeout() {
        `when`(rankingRedisRepository.getUserDenseRank(1L, yearMonth))
            .thenThrow(QueryTimeoutException("응답 지연"))
        `when`(commitRepository.findUserRank(1L, thisMonthStart, nextMonthStart))
            .thenReturn(fakeUserRank(7L))

        val result = rankService.getUserRank(1L)

        assertThat(result).isEqualTo(7L)
    }

    @Test
    @DisplayName("Redis가 정상이면 DB를 조회하지 않는다")
    fun doesNotHitDbWhenRedisIsHealthy() {
        `when`(rankingRedisRepository.getUserDenseRank(1L, yearMonth)).thenReturn(3L)

        val result = rankService.getUserRank(1L)

        assertThat(result).isEqualTo(3L)
        verifyNoInteractions(commitRepository)
    }

    private fun fakeUserRank(rank: Long): UserRankProjection = object : UserRankProjection {
        override fun getRank() = rank
        override fun getCurrentMonthCount() = 10L
    }
}
