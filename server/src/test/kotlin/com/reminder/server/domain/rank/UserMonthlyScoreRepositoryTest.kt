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
import org.springframework.data.domain.Limit
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.LocalDateTime
import java.time.YearMonth

/**
 * 증명하는 주장: "집계 테이블은 commits 를 다시 센 절대값을 담고, 읽기 쿼리는 랭킹 규칙을 지킨다"
 *
 * 아직 아무도 이 저장소를 읽지 않는 단계라, 읽는 쪽이 붙기 전에 쿼리 자체를 고정해둔다.
 */
class UserMonthlyScoreRepositoryTest : IntegrationTest() {

    @Autowired lateinit var scoreRepository: UserMonthlyScoreRepository
    @Autowired lateinit var commitJdbcRepository: CommitJdbcRepository
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var clock: Clock

    @Test
    @DisplayName("recompute 는 몇 번을 돌려도 commits 실제 개수와 같은 값을 남긴다")
    fun recomputeIsIdempotent() {
        val user = givenUser("ums-idem")
        givenCommits(user, 4)

        repeat(3) { recompute(user) }

        assertThat(scoreRepository.findScore(user, scoreMonth())).isEqualTo(4)
    }

    @Test
    @DisplayName("커밋이 지워지면 recompute 가 점수를 내린다 — 증분이면 못 하는 일이다")
    fun recomputeFollowsCommitsDownward() {
        val user = givenUser("ums-down")
        givenCommits(user, 3)
        recompute(user)

        deleteAllCommits()
        recompute(user)

        assertThat(scoreRepository.findScore(user, scoreMonth())).isZero()
    }

    @Test
    @DisplayName("다른 달 커밋은 그 달 버킷에만 들어간다")
    fun recomputeBucketsByCommitDate() {
        val user = givenUser("ums-month")
        givenCommits(user, 2)
        givenCommits(user, 5, at = monthStart().minusMonths(1).plusHours(1))

        recompute(user)
        recompute(user, YearMonth.now(clock).minusMonths(1))

        assertThat(scoreRepository.findScore(user, scoreMonth())).isEqualTo(2)
        assertThat(scoreRepository.findScore(user, scoreMonth(YearMonth.now(clock).minusMonths(1))))
            .isEqualTo(5)
    }

    @Test
    @DisplayName("행이 아예 없으면 findScore 는 null 이다 — 0 과 구분된다")
    fun findScoreIsNullWhenNoRow() {
        val user = givenUser("ums-norow")

        assertThat(scoreRepository.findScore(user, scoreMonth())).isNull()
    }

    @Test
    @DisplayName("findTop 은 0점 유저를 빼고 점수 내림차순으로 준다")
    fun findTopExcludesZeroScores() {
        val ranked = givenUser("ums-top-ranked", score = 3)
        val higher = givenUser("ums-top-higher", score = 9)
        givenUser("ums-top-zero", score = 0)

        val top = scoreRepository.findTop(scoreMonth(), Limit.of(30))

        assertThat(top.map { it.userId }).containsExactly(higher, ranked)
        assertThat(top.map { it.score }).containsExactly(9, 3)
    }

    @Test
    @DisplayName("findTop 은 limit 만큼만 준다")
    fun findTopHonorsLimit() {
        (1..5).forEach { givenUser("ums-limit-$it", score = it) }

        assertThat(scoreRepository.findTop(scoreMonth(), Limit.of(3))).hasSize(3)
    }

    @Test
    @DisplayName("countHigherDistinctScores 는 사람 수가 아니라 점수 종류를 센다")
    fun countHigherCountsDistinctScores() {
        givenUser("ums-cnt-a", score = 9)
        givenUser("ums-cnt-b", score = 9)
        givenUser("ums-cnt-c", score = 5)
        givenUser("ums-cnt-d", score = 2)

        // 9 와 5 두 종류 — 9점이 두 명이어도 1로 센다
        assertThat(scoreRepository.countHigherDistinctScores(scoreMonth(), 2)).isEqualTo(2)
        assertThat(scoreRepository.countHigherDistinctScores(scoreMonth(), 9)).isZero()
    }

    // ── fixture ───────────────────────────────────────────────────────────────

    private fun givenUser(githubId: String, score: Int? = null): Long {
        val saved = userRepository.save(User(githubId, "nick-$githubId", "repo"))
        if (score != null) scoreRepository.save(UserMonthlyScore(saved.id, scoreMonth(), score))
        return saved.id
    }

    private fun givenCommits(userId: Long, count: Int, at: LocalDateTime? = null) {
        val base = at ?: monthStart().plusHours(1)
        commitJdbcRepository.bulkUpsert(
            (1..count).map { seq ->
                CommitInsertDto(
                    userId = userId,
                    commitDate = base.plusMinutes(seq.toLong()),
                    commitUrl = "https://example.com/$userId-$seq",
                    title = "문제",
                    level = CommitLevel.GOLD.name,
                    sha = "ums-$userId-${base.toLocalDate()}-$seq".padEnd(40, '0'),
                )
            }
        )
    }

    private fun recompute(userId: Long, yearMonth: YearMonth = YearMonth.now(clock)) {
        val from = yearMonth.atDay(1).atStartOfDay()
        scoreRepository.recompute(userId, yearMonth.toScoreMonth(), from, from.plusMonths(1))
    }

    private fun deleteAllCommits() = jdbc.update("DELETE FROM commits")

    private fun monthStart(): LocalDateTime =
        LocalDateTime.now(clock).withDayOfMonth(1).toLocalDate().atStartOfDay()

    private fun scoreMonth(yearMonth: YearMonth = YearMonth.now(clock)): String =
        yearMonth.toScoreMonth()
}
