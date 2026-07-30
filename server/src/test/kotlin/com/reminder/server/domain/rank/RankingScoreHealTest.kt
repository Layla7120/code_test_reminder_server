package com.reminder.server.domain.rank

import com.reminder.server.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.YearMonth

/**
 * 증명하는 주장: "healScores(ZADD GT)는 실시간 점수보다 낮은 값으로 되돌리지 않는다"
 *
 * 이 규칙 자체를 회귀 방지로 고정한다. 버그 A(증분이 실제 삽입 건수가 아니라
 * 요청 개수였던 문제)를 고치기 전에는, 이 GT 규칙 때문에 한번 부풀어 오른 점수가
 * 스케줄러로도 절대 낮아지지 않았다 — 안전장치 두 개가 서로를 무력화한 지점이다.
 *
 *   GT를 넣은 이유: 배치가 DB에서 읽는 동안 실시간 증분(ZINCRBY)이 먼저 반영되면,
 *                  배치가 그 값을 옛 스냅샷으로 덮어써 증분을 날려버리는 것을 막기 위함
 *   그 대가:       실시간 값이 잘못 부풀었을 때 배치가 이를 하향 보정할 수 없음
 *
 * 해법은 GT를 없애는 게 아니라(그러면 Lost Update가 돌아온다),
 * 증분 자체를 정확하게 만드는 것이었다 — CommitService의 실제 삽입 건수 반영.
 * 이 테스트는 그 판단이 맞다는 전제(GT는 유지되어야 한다)를 고정한다.
 */
class RankingScoreHealTest : IntegrationTest() {

    @Autowired lateinit var rankingRedisRepository: RankingRedisRepository
    @Autowired lateinit var redisTemplate: StringRedisTemplate

    @Test
    @DisplayName("healScores는 이미 더 높은 실시간 점수를 낮추지 않는다")
    fun healScoresNeverLowersAHigherLiveScore() {
        val yearMonth = YearMonth.of(2026, 7)
        val userId = 1L

        // 실시간 증분으로 15점이 된 상태 (예: 버그로 부풀었거나, 배치 이후 새 커밋 반영)
        rankingRedisRepository.incrementScore(userId, 15, yearMonth)

        // 스케줄러가 DB에서 5(더 낮은 값)를 읽어 보정을 시도
        rankingRedisRepository.healScores(mapOf(userId to 5L), yearMonth)

        assertThat(scoreOf(userId, yearMonth)).isEqualTo(15.0)
    }

    @Test
    @DisplayName("healScores는 실시간 점수보다 큰 DB 값이면 반영한다")
    fun healScoresAppliesWhenDbValueIsHigher() {
        val yearMonth = YearMonth.of(2026, 7)
        val userId = 2L

        rankingRedisRepository.incrementScore(userId, 3, yearMonth)
        rankingRedisRepository.healScores(mapOf(userId to 10L), yearMonth)

        assertThat(scoreOf(userId, yearMonth)).isEqualTo(10.0)
    }

    @Test
    @DisplayName("healScores는 Redis에 아직 없는 유저는 DB 값 그대로 채운다")
    fun healScoresFillsMissingUser() {
        val yearMonth = YearMonth.of(2026, 7)
        val userId = 3L

        rankingRedisRepository.healScores(mapOf(userId to 7L), yearMonth)

        assertThat(scoreOf(userId, yearMonth)).isEqualTo(7.0)
    }

    private fun scoreOf(userId: Long, yearMonth: YearMonth): Double? =
        redisTemplate.opsForZSet().score(RankingRedisRepository.rankKey(yearMonth), userId.toString())
}
