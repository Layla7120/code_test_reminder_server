package com.reminder.server.api

import com.reminder.server.support.ApiTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDateTime

/**
 * 증명하는 주장: "DELETE /users/delete 한 사용자는 랭킹에서 사라진다"
 *
 * 이 테스트가 왜 따로 있나: 랭킹 쿼리는 users 를 조인하지 않는다. 조인하면 옵티마이저가
 * idx_rank 를 버리고 users 를 풀스캔한다(50k 기준 87ms vs 0.5ms). 그래서 탈퇴자를 빼는 일을
 * 쿼리가 아니라 user_monthly_score.user_id 의 ON DELETE CASCADE 가 한다.
 * 그 CASCADE 가 실제로 도는지를 HTTP 로 확인하는 게 여기다 —
 * 스키마 쪽 대조는 SchemaContractTest 가 따로 한다.
 *
 * 검증은 전부 HTTP 로 한다. fixture 만 SQL 로 넣는다 (ApiTest 규칙).
 */
class UserDeletionApiTest : ApiTest() {

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Test
    @DisplayName("탈퇴한 사용자는 Top30 에서 빠지고 남은 사람의 순위가 당겨진다")
    fun deletedUserLeavesTheRanking() {
        val staying = createUser("del-staying")
        val leaving = createUser("del-leaving")
        givenRankedCommits(staying, 2)
        givenRankedCommits(leaving, 5)

        assertThat(get("/rank").body).contains("\"userId\":$leaving")
        assertThat(get("/rank/users?userId=$staying").body).contains("\"rank\":2")

        assertThat(delete("/users/delete?userId=$leaving").statusCode)
            .isEqualTo(HttpStatus.NO_CONTENT)

        val body = get("/rank").body ?: error("본문이 비어 있다")
        assertThat(body)
            .describedAs("탈퇴자가 남아 있으면 점수 행이 CASCADE 로 안 지워진 것이다")
            .doesNotContain("\"userId\":$leaving")
        assertThat(body).contains("\"userId\":$staying")

        // 순위가 그냥 가려진 게 아니라 실제로 다시 매겨졌는지 — 2등이던 사람이 1등이 된다
        assertThat(get("/rank/users?userId=$staying").body).contains("\"rank\":1")
    }

    @Test
    @DisplayName("탈퇴한 사용자의 개인 순위는 JSON null 이다")
    fun deletedUserHasNoPersonalRank() {
        val user = createUser("del-solo")
        givenRankedCommits(user, 3)
        assertThat(get("/rank/users?userId=$user").body).contains("\"rank\":1")

        delete("/users/delete?userId=$user")

        assertThat(get("/rank/users?userId=$user").body?.replace(" ", ""))
            .isEqualTo("""{"rank":null}""")
    }

    /**
     * 이번 달 커밋 [count] 건과 그에 맞는 집계 행을 넣는다.
     *
     * 두 테이블을 함께 채우는 이유: 읽기 경로가 commits 에서 user_monthly_score 로
     * 옮겨가는 중이라, 한쪽만 채우면 옮기기 전이나 후 한쪽에서만 성립하는 fixture 가 된다.
     */
    private fun givenRankedCommits(userId: Long, count: Int) {
        val base = LocalDateTime.now().withDayOfMonth(1).plusHours(1)
        repeat(count) { i ->
            jdbc.update(
                "INSERT INTO commits (user_id, commit_date, commit_url, title, level, sha) " +
                    "VALUES (?, ?, ?, ?, ?, ?)",
                userId,
                base.plusMinutes(i.toLong()),
                "https://github.com/test/repo/commit/$userId-$i",
                "테스트 커밋 $i",
                "BRONZE",
                "del-sha-$userId-$i",
            )
        }
        jdbc.update(
            """
            INSERT INTO user_monthly_score (user_id, score_month, score)
            SELECT user_id, DATE_FORMAT(commit_date, '%Y%m'), COUNT(*)
            FROM commits WHERE user_id = ?
            GROUP BY user_id, DATE_FORMAT(commit_date, '%Y%m')
            ON DUPLICATE KEY UPDATE score = VALUES(score)
            """.trimIndent(),
            userId,
        )
    }
}
