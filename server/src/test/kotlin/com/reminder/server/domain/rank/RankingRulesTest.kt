package com.reminder.server.domain.rank

import com.reminder.server.domain.commit.CommitInsertDto
import com.reminder.server.domain.commit.CommitJdbcRepository
import com.reminder.server.domain.commit.CommitLevel
import com.reminder.server.domain.user.User
import com.reminder.server.domain.user.UserRepository
import com.reminder.server.support.IntegrationTest
import com.reminder.server.support.recomputeAllScores
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.LocalDateTime

/**
 * 증명하는 주장: "랭킹의 동순위·제외 규칙은 집계 테이블 경로에서도 그대로다"
 *
 * 前身은 RankPathConsistencyTest 였다. 그 테스트가 고정하던 건 "Redis 경로와 DB 폴백이
 * 같은 답을 준다"였는데, 읽기 경로가 하나가 되면서(Phase 3) 비교할 두 경로가 없어졌다.
 *
 * 다만 거기 있던 단언 자체는 경로 비교가 아니라 랭킹 규칙이었다. 그래서 옮겨왔다:
 *   - 동점 fixture 5,5,3,2,2,2,1 → rank 1,1,2,3,3,3,4   (I3 dense rank)
 *   - 커밋 0 건인 유저는 나오지 않는다                    (I4)
 *   - 동점자 표시 순서는 userId 오름차순                  (I6)
 *
 * 경로가 하나뿐이라 "폴백이 조용히 다른 답을 준다"는 실패 양상 자체가 사라졌다.
 */
class RankingRulesTest : IntegrationTest() {

    @Autowired lateinit var rankService: RankService
    @Autowired lateinit var commitJdbcRepository: CommitJdbcRepository
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var clock: Clock

    @Test
    @DisplayName("동점자는 같은 순위를 받고 다음 순위가 연속된다 — 커밋 0건 유저는 빠진다")
    fun denseRankAndExclusionRules() {
        val commitCounts = listOf(5, 5, 3, 2, 2, 2, 1)
        val users = commitCounts.mapIndexed { index, count ->
            val user = userRepository.save(User("gh$index", "nick$index", "repo"))
            givenCommits(user.id, count, index)
            user.id
        }

        // 커밋이 0건인 유저 — 랭킹에 나타나면 안 된다 (I4)
        val empty = userRepository.save(User("gh-empty", "nick-empty", "repo"))

        jdbc.recomputeAllScores()

        val top = rankService.getTop30()

        assertThat(top).isNotEmpty()
        assertThat(top.map { it.commitCount }).containsExactly(5, 5, 3, 2, 2, 2, 1)
        assertThat(top.map { it.rank }).containsExactly(1, 1, 2, 3, 3, 3, 4)
        assertThat(top.map { it.userId }).doesNotContain(empty.id)

        // 동점자 표시 순서는 userId 오름차순 (I6)
        val tiedAtRank3 = top.filter { it.rank == 3L }.map { it.userId }
        assertThat(tiedAtRank3).isSorted()
        assertThat(tiedAtRank3).containsExactlyElementsOf(users.slice(3..5).sorted())
    }

    @Test
    @DisplayName("개인 순위도 Top30 과 같은 규칙을 따른다 — 동점은 같은 순위, 0건은 null")
    fun userRankFollowsSameRules() {
        val first = userRepository.save(User("gh-a", "nick-a", "repo"))
        val tiedA = userRepository.save(User("gh-b", "nick-b", "repo"))
        val tiedB = userRepository.save(User("gh-c", "nick-c", "repo"))
        val empty = userRepository.save(User("gh-d", "nick-d", "repo"))

        givenCommits(first.id, 5, 0)
        givenCommits(tiedA.id, 2, 1)
        givenCommits(tiedB.id, 2, 2)
        jdbc.recomputeAllScores()

        assertThat(rankService.getUserRank(first.id)).isEqualTo(1)
        assertThat(rankService.getUserRank(tiedA.id)).isEqualTo(2)
        assertThat(rankService.getUserRank(tiedB.id)).isEqualTo(2)

        // 행 자체가 없다 = 랭킹 대상이 아니다. 0 등과 구분된다.
        assertThat(rankService.getUserRank(empty.id)).isNull()
    }

    private fun givenCommits(userId: Long, count: Int, tag: Int) {
        val monthStart = LocalDateTime.now(clock).withDayOfMonth(1).toLocalDate().atStartOfDay()
        commitJdbcRepository.bulkUpsert(
            (1..count).map { seq ->
                CommitInsertDto(
                    userId = userId,
                    commitDate = monthStart.plusHours(seq.toLong()),
                    commitUrl = "https://example.com/$tag-$seq",
                    title = "문제",
                    level = CommitLevel.GOLD.name,
                    sha = "sha-$tag-$seq".padEnd(40, '0'),
                )
            }
        )
    }
}
