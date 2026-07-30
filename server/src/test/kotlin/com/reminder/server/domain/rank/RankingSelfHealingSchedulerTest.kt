package com.reminder.server.domain.rank

import com.reminder.server.domain.commit.CommitInsertDto
import com.reminder.server.domain.commit.CommitJdbcRepository
import com.reminder.server.domain.commit.CommitLevel
import com.reminder.server.domain.user.User
import com.reminder.server.domain.user.UserRepository
import com.reminder.server.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Clock
import java.time.LocalDateTime
import java.time.YearMonth

/**
 * 증명하는 주장: "스케줄러가 돌고 나면 Redis 랭킹 점수는 DB 실제 개수와 일치한다"
 *
 * CommitService가 아니라 CommitJdbcRepository로 직접 커밋을 심어서
 * "CommitsSavedEvent가 유실된" 상황(네트워크 단절, 서버 크래시 등)을 재현한다.
 * 이 경우 Redis 점수는 없어야 하고, 스케줄러가 돈 뒤에만 DB 값으로 채워져야 한다.
 */
class RankingSelfHealingSchedulerTest : IntegrationTest() {

    @Autowired lateinit var scheduler: RankingSelfHealingScheduler
    @Autowired lateinit var commitJdbcRepository: CommitJdbcRepository
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var redisTemplate: StringRedisTemplate
    @Autowired lateinit var clock: Clock

    @Test
    @DisplayName("이벤트 유실 상황에서도 스케줄러가 돌면 Redis 점수가 DB 개수와 일치한다")
    fun schedulerHealsRedisScoreToMatchDb() {
        val user = userRepository.save(User("heal-test", "heal-nick", "repo"))
        val yearMonth = YearMonth.now(clock)
        // 이번 달 1일 + 몇 시간 — "지금"이 언제든 이번 달 안에 들어오도록 월초를 기준으로 잡는다
        val monthStart = LocalDateTime.now(clock).withDayOfMonth(1).toLocalDate().atStartOfDay()

        // CommitService를 거치지 않고 직접 삽입 → Redis 이벤트가 발행되지 않은 상태를 재현
        commitJdbcRepository.bulkUpsert(
            (1..4).map {
                CommitInsertDto(
                    userId = user.id,
                    commitDate = monthStart.plusHours(it.toLong()),
                    commitUrl = "https://example.com/heal-$it",
                    title = "문제$it",
                    level = CommitLevel.SILVER.name,
                    sha = "heal-sha-$it".padEnd(40, '0'),
                )
            }
        )

        assertThat(scoreOf(user.id, yearMonth))
            .describedAs("이벤트가 발행되지 않았으므로 아직 Redis에 점수가 없어야 한다")
            .isNull()

        scheduler.syncCurrentMonthRank()

        assertThat(scoreOf(user.id, yearMonth))
            .describedAs("스케줄러가 돈 뒤에는 DB 실제 커밋 수(4)와 일치해야 한다")
            .isEqualTo(4.0)
    }

    private fun scoreOf(userId: Long, yearMonth: YearMonth): Double? =
        redisTemplate.opsForZSet().score(RankingRedisRepository.rankKey(yearMonth), userId.toString())
}
