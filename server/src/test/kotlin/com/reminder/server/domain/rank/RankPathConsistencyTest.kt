package com.reminder.server.domain.rank

import com.reminder.server.domain.commit.CommitInsertDto
import com.reminder.server.domain.commit.CommitJdbcRepository
import com.reminder.server.domain.commit.CommitLevel
import com.reminder.server.domain.commit.CommitRepository
import com.reminder.server.domain.user.User
import com.reminder.server.domain.user.UserRepository
import com.reminder.server.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Clock
import java.time.LocalDateTime

/**
 * 증명하는 주장: "Redis 경로와 DB 폴백 경로는 같은 랭킹을 돌려준다"
 *
 * 이게 깨지면 두 가지가 동시에 망가진다.
 *   1. 운영 — Redis 장애로 폴백이 발동한 순간 사용자가 보는 순위가 바뀐다
 *   2. 측정 — A/B의 두 조건이 애초에 다른 것을 계산하고 있으므로 비교가 성립하지 않는다
 *
 * findTop30Rank 를 "응답에 쓰이는 컬럼만" 조회하도록 줄이면서
 * LEFT JOIN 을 INNER JOIN 으로 바꿨다. 커밋 0건인 유저를 제외하는 이 변경이
 * 오히려 Redis 경로(점수가 있는 유저만 ZSET에 담김)와 동작을 일치시킨다 —
 * 그 일치를 여기서 고정한다.
 */
class RankPathConsistencyTest : IntegrationTest() {

    @Autowired lateinit var rankService: RankService
    @Autowired lateinit var rankingRedisRepository: RankingRedisRepository
    @Autowired lateinit var commitRepository: CommitRepository
    @Autowired lateinit var scheduler: RankingSelfHealingScheduler
    @Autowired lateinit var commitJdbcRepository: CommitJdbcRepository
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var clock: Clock

    @Test
    @DisplayName("Redis 경로와 DB 폴백 경로의 Top 30이 완전히 일치한다")
    fun redisPathAndDbFallbackAgree() {
        // 커밋 수를 서로 다르게 주되 동점자도 만든다 — dense rank 계산이 갈릴 수 있는 조건
        val commitCounts = listOf(5, 5, 3, 2, 2, 2, 1)
        val monthStart = LocalDateTime.now(clock).withDayOfMonth(1).toLocalDate().atStartOfDay()

        commitCounts.forEachIndexed { index, count ->
            val user = userRepository.save(User("gh$index", "nick$index", "repo"))
            commitJdbcRepository.bulkUpsert(
                (1..count).map { seq ->
                    CommitInsertDto(
                        userId = user.id,
                        commitDate = monthStart.plusHours(seq.toLong()),
                        commitUrl = "https://example.com/$index-$seq",
                        title = "문제",
                        level = CommitLevel.GOLD.name,
                        sha = "sha-$index-$seq".padEnd(40, '0'),
                    )
                }
            )
        }

        // 커밋이 0건인 유저 — 어느 경로에도 나타나면 안 된다
        userRepository.save(User("gh-empty", "nick-empty", "repo"))

        scheduler.syncCurrentMonthRank()   // Redis 채우기 (앱의 실제 경로)

        val fromRedis = rankService.getTop30()
        val fromDb = dbOnlyRankService().getTop30()

        assertThat(fromRedis)
            .describedAs("Redis 경로가 비어 있으면 조용히 DB 폴백을 탄 것이라 비교가 무의미하다")
            .isNotEmpty()
        assertThat(fromRedis).isEqualTo(fromDb)
        assertThat(fromRedis.map { it.commitCount }).containsExactly(5, 5, 3, 2, 2, 2, 1)
        assertThat(fromRedis.map { it.rank }).containsExactly(1, 1, 2, 3, 3, 3, 4)
    }

    /** ranking.redis.enabled=false 인 인스턴스 — 항상 DB 경로로 간다 */
    private fun dbOnlyRankService() =
        RankService(rankingRedisRepository, commitRepository, clock, redisRankingEnabled = false)
}
