package com.reminder.server.domain.commit

import com.reminder.server.domain.rank.RankingRedisRepository
import com.reminder.server.domain.user.User
import com.reminder.server.domain.user.UserRepository
import com.reminder.server.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.time.YearMonth

/**
 * 증명하는 주장(버그 A):
 *   "같은 커밋 목록을 두 번 수집해도 Redis 랭킹 점수가 두 배로 부풀지 않는다"
 *
 * MockGithubClient(load-test 프로필, A-1에서 결정론적으로 고침)를 그대로 쓴다.
 * 같은 githubId/repositoryName은 항상 같은 sha 목록을 돌려주므로,
 * 짧은 간격으로 GitHub을 재조회했는데 새 커밋이 없는 실제 상황을 그대로 재현한다.
 *
 * 왜 이게 버그인가:
 *   CommitJdbcRepository.bulkUpsert()는 ON DUPLICATE KEY UPDATE라 이미 있는 sha를 조용히 건너뛴다.
 *   그런데 CommitService는 "삽입 시도한 개수"(dtos.size)를 그대로 랭킹 증분으로 발행했다 —
 *   실제 삽입 여부와 무관하게 매번 같은 수만큼 점수가 오른다.
 */
@ActiveProfiles("load-test")
class CommitDuplicateFetchTest : IntegrationTest() {

    @Autowired lateinit var commitService: CommitService
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var jdbcTemplate: JdbcTemplate
    @Autowired lateinit var redisTemplate: StringRedisTemplate

    @Test
    @DisplayName("같은 저장소를 두 번 수집해도 DB 커밋 수와 랭킹 점수가 늘어나지 않는다")
    fun fetchingSameCommitsTwiceDoesNotInflateRankScore() {
        val user = userRepository.save(User("dup-test", "dup-nick", "dup-repo"))

        val firstSaved = commitService.fetchAndSaveCommits(user.id)
        val commitCountAfterFirst = countCommits(user.id)
        val scoresAfterFirst = scoresByMonth(user.id)

        assertThat(firstSaved)
            .describedAs("첫 수집은 요청한 만큼 전부 새로 저장되어야 한다")
            .isEqualTo(commitCountAfterFirst)
        assertThat(scoresAfterFirst.values.sum())
            .describedAs("첫 수집 직후 랭킹 점수 합은 DB 커밋 수와 같아야 한다")
            .isEqualTo(commitCountAfterFirst.toDouble())

        val secondSaved = commitService.fetchAndSaveCommits(user.id)
        val commitCountAfterSecond = countCommits(user.id)
        val scoresAfterSecond = scoresByMonth(user.id)

        assertThat(secondSaved)
            .describedAs("두 번째 수집은 새 커밋이 없으므로 0건이어야 한다")
            .isZero()
        assertThat(commitCountAfterSecond)
            .describedAs("DB 커밋 수는 그대로여야 한다 (ON DUPLICATE KEY UPDATE가 막아줌)")
            .isEqualTo(commitCountAfterFirst)
        assertThat(scoresAfterSecond)
            .describedAs("랭킹 점수는 DB 실제 개수와 계속 일치해야 한다 — 두 배가 되면 버그 A")
            .isEqualTo(scoresAfterFirst)
    }

    private fun countCommits(userId: Long): Int =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM commits WHERE user_id = ?", Int::class.java, userId,
        ) ?: 0

    /** 유저의 커밋을 실제 commit_date 기준 월별로 세고, 각 월의 Redis 점수와 함께 반환한다. */
    private fun scoresByMonth(userId: Long): Map<YearMonth, Double> {
        val months = jdbcTemplate.queryForList(
            "SELECT DISTINCT YEAR(commit_date) AS y, MONTH(commit_date) AS m FROM commits WHERE user_id = ?",
            userId,
        ).map { YearMonth.of(it["y"] as Int, it["m"] as Int) }

        return months.associateWith { yearMonth ->
            redisTemplate.opsForZSet()
                .score(RankingRedisRepository.rankKey(yearMonth), userId.toString()) ?: 0.0
        }
    }
}
