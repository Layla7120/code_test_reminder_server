package com.reminder.server.domain.commit

import com.reminder.server.domain.rank.UserMonthlyScoreRepository
import com.reminder.server.domain.rank.toScoreMonth
import com.reminder.server.domain.user.User
import com.reminder.server.domain.user.UserRepository
import com.reminder.server.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.YearMonth

/**
 * 증명하는 주장: "커밋을 저장하면 그 달 점수가 commits 실제 개수와 같아지고, 트랜잭션과 운명을 같이한다"
 *
 * GitHub 응답을 직접 고정한다 — MockGithubClient 는 3~7건을 랜덤 시각에 주므로
 * 건수도 월 경계도 통제할 수 없다.
 */
class UserMonthlyScoreWriteTest : IntegrationTest() {

    @MockitoBean lateinit var githubClient: GithubClientPort

    @Autowired lateinit var commitService: CommitService
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var scoreRepository: UserMonthlyScoreRepository
    @Autowired lateinit var transactionTemplate: TransactionTemplate
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var clock: Clock

    @Test
    @DisplayName("커밋 5건을 수집하면 이번 달 점수가 5가 된다")
    fun scoreMatchesSavedCommitCount() {
        val user = givenUser("ums-w-basic")
        givenGithubCommits(user, thisMonth(5))

        commitService.fetchAndSaveCommits(user)

        assertThat(thisMonthScore(user)).isEqualTo(5)
    }

    @Test
    @DisplayName("같은 커밋 목록을 두 번 수집해도 점수는 그대로다")
    fun refetchingDoesNotInflateScore() {
        val user = givenUser("ums-w-idem")
        givenGithubCommits(user, thisMonth(5))

        commitService.fetchAndSaveCommits(user)
        val secondSaved = commitService.fetchAndSaveCommits(user)

        assertThat(secondSaved).describedAs("새 커밋이 없으므로 0건이다").isZero()
        assertThat(thisMonthScore(user))
            .describedAs("절대값으로 다시 세므로 두 배가 될 방법이 없다")
            .isEqualTo(5)
    }

    @Test
    @DisplayName("월 경계에 걸친 커밋은 각 달 버킷으로 정확히 나뉜다")
    fun commitsAreBucketedByTheirOwnMonth() {
        val user = givenUser("ums-w-month")
        val lastMonth = YearMonth.now(clock).minusMonths(1)
        givenGithubCommits(user, thisMonth(2) + atMonth(lastMonth, 3, shaPrefix = "prev"))

        commitService.fetchAndSaveCommits(user)

        assertThat(thisMonthScore(user)).isEqualTo(2)
        assertThat(scoreRepository.findScore(user, lastMonth.toScoreMonth())).isEqualTo(3)
    }

    @Test
    @DisplayName("트랜잭션이 롤백되면 커밋도 점수도 남지 않는다")
    fun scoreRollsBackWithTheTransaction() {
        val user = givenUser("ums-w-rollback")
        givenGithubCommits(user, thisMonth(2))
        commitService.fetchAndSaveCommits(user)

        // 이미 2점이 커밋된 상태에서, 3건이 더 붙는 수집을 롤백시킨다
        givenGithubCommits(user, thisMonth(5))
        transactionTemplate.executeWithoutResult { status ->
            commitService.fetchAndSaveCommits(user)
            status.setRollbackOnly()
        }

        assertThat(commitCount(user))
            .describedAs("커밋이 롤백됐다")
            .isEqualTo(2)
        assertThat(thisMonthScore(user))
            .describedAs("점수도 같은 트랜잭션이라 함께 롤백된다 — AFTER_COMMIT 이었다면 3점이 남았다")
            .isEqualTo(2)
    }

    // ── fixture ───────────────────────────────────────────────────────────────

    private fun givenUser(githubId: String): Long =
        userRepository.save(User(githubId, "nick-$githubId", "repo")).id

    private fun givenGithubCommits(userId: Long, commits: List<CommitInsertDto>) {
        val user = userRepository.findById(userId).orElseThrow()
        `when`(githubClient.fetchCommits(user.githubId, user.repositoryName)).thenReturn(commits)
    }

    private fun thisMonth(count: Int) = atMonth(YearMonth.now(clock), count, shaPrefix = "cur")

    private fun atMonth(yearMonth: YearMonth, count: Int, shaPrefix: String): List<CommitInsertDto> {
        // 1일 12시 기준 — 월 경계에서 시간대 때문에 옆 달로 새지 않게 한다
        val base = yearMonth.atDay(1).atTime(12, 0)
        return (1..count).map { seq ->
            CommitInsertDto(
                userId = 0L,  // CommitService 가 실제 userId 로 교체한다
                commitDate = base.plusMinutes(seq.toLong()),
                commitUrl = "https://example.com/$shaPrefix-$seq",
                title = "문제 $seq",
                level = CommitLevel.GOLD.name,
                sha = "$shaPrefix-${yearMonth.toScoreMonth()}-$seq".padEnd(40, '0'),
            )
        }
    }

    private fun thisMonthScore(userId: Long): Int? =
        scoreRepository.findScore(userId, YearMonth.now(clock).toScoreMonth())

    private fun commitCount(userId: Long): Int =
        jdbc.queryForObject("SELECT COUNT(*) FROM commits WHERE user_id = ?", Int::class.java, userId) ?: 0
}
