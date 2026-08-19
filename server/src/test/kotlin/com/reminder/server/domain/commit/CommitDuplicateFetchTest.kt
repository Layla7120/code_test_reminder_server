package com.reminder.server.domain.commit

import com.reminder.server.domain.rank.toScoreMonth
import com.reminder.server.domain.user.User
import com.reminder.server.domain.user.UserRepository
import com.reminder.server.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.time.YearMonth

/**
 * 증명하는 주장(버그 A):
 *   "같은 커밋 목록을 두 번 수집해도 랭킹 점수가 두 배로 부풀지 않는다"
 *
 * Redis ZSET 을 보던 테스트였다. 랭킹이 user_monthly_score 로 옮겨가면서(Phase 3~4)
 * 단언 대상만 바꿨다 — 지키려는 성질은 그대로다.
 *
 * 락이 없어지면 이 테스트가 더 중요해진다. 지금까지는 분산 락이 동시 요청을 막아줘서
 * "락이 가려준 것"인지 "구조가 막는 것"인지 구분되지 않았다. 여기서 검증하는 건 후자다 —
 * 점수를 절대값으로 다시 세므로 몇 번을 수집해도 같은 값이 나온다.
 *
 * MockGithubClient(load-test 프로필, A-1에서 결정론적으로 고침)를 그대로 쓴다.
 * 같은 githubId/repositoryName은 항상 같은 sha 목록을 돌려주므로,
 * 짧은 간격으로 GitHub을 재조회했는데 새 커밋이 없는 실제 상황을 그대로 재현한다.
 *
 * 왜 이게 버그인가: bulkUpsert 는 이미 있는 sha 를 조용히 건너뛰는데 증분은 요청 개수(dtos.size)였다. 경위: docs/기록.md
 */
@ActiveProfiles("load-test")
class CommitDuplicateFetchTest : IntegrationTest() {

    @Autowired lateinit var commitService: CommitService
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var jdbcTemplate: JdbcTemplate

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

    /** 유저의 커밋을 실제 commit_date 기준 월별로 세고, 각 월의 집계 점수와 함께 반환한다. */
    private fun scoresByMonth(userId: Long): Map<YearMonth, Double> {
        val months = jdbcTemplate.queryForList(
            "SELECT DISTINCT YEAR(commit_date) AS y, MONTH(commit_date) AS m FROM commits WHERE user_id = ?",
            userId,
        ).map { YearMonth.of(it["y"] as Int, it["m"] as Int) }

        return months.associateWith { yearMonth ->
            jdbcTemplate.queryForObject(
                "SELECT score FROM user_monthly_score WHERE user_id = ? AND score_month = ?",
                Double::class.java,
                userId,
                yearMonth.toScoreMonth(),
            ) ?: 0.0
        }
    }
}
