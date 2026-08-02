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
 * GET /rank, GET /rank/users — 부하 테스트가 실제로 때리는 두 엔드포인트.
 *
 * [왜 이게 우선순위였나]
 * bench/rank_ab.js 가 이 둘만 반복 호출한다. 그런데 k6 가 확인하는 건
 * `check(res, { 'top30 200': (r) => r.status === 200 })` 뿐이다.
 * 본문이 빈 배열이어도, 순위가 전부 0이어도 200이면 통과한다.
 * 폴백이 조용히 깨져 빈 결과를 초고속으로 반환하면 그게 성능 개선으로 측정된다.
 * docs/기록.md 의 벤치마크 숫자가 이 위에 서 있다.
 *
 * 서비스 계층에는 폴백 단위 테스트(RankServiceFallbackTest)가 있지만,
 * 응답 본문의 모양 — List<RankResponse>{userId, commitCount, rank} 와
 * {"rank": n|null} — 은 컨트롤러를 지나야만 존재한다.
 *
 * [fixture 를 JdbcTemplate 으로 만드는 이유]
 * 커밋을 만드는 유일한 API 는 POST /commits 인데, 이건 GithubClient 를 호출한다.
 * MockGithubClient 는 @Profile("load-test") 라 test 프로파일에서 뜨지 않는다.
 * 그래서 준비 데이터만 SQL 로 넣고, 검증은 전부 HTTP 응답으로만 한다.
 *
 * Redis 는 @BeforeEach 에서 flushDb 되므로 두 엔드포인트 모두 DB 폴백 경로를 탄다.
 * 랭킹 계산이 결정론적으로 도는 상태다.
 */
class RankApiTest : ApiTest() {

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Test
    @DisplayName("Top30 은 커밋 수 내림차순으로 userId·commitCount·rank 를 담아 돌려준다")
    fun top30ReturnsRankedEntriesWithAllFields() {
        val heavy = createUser("rank-heavy")
        val light = createUser("rank-light")
        givenCommits(heavy, 3)
        givenCommits(light, 1)

        val res = get("/rank")

        assertThat(res.statusCode).isEqualTo(HttpStatus.OK)
        val body = res.body ?: error("본문이 비어 있다")

        // 빈 배열이면 여기서 걸린다 — k6 의 200 체크가 못 잡는 지점이다.
        assertThat(body).startsWith("[").isNotEqualTo("[]")
        assertThat(body).contains("\"userId\":$heavy").contains("\"userId\":$light")
        assertThat(body).contains("commitCount").contains("\"rank\"")

        // 커밋이 많은 쪽이 앞에 온다.
        assertThat(body.indexOf("\"userId\":$heavy")).isLessThan(body.indexOf("\"userId\":$light"))
        assertThat(rankOfUserIn(body, heavy)).isEqualTo(1)
        assertThat(commitCountOfUserIn(body, heavy)).isEqualTo(3)
    }

    @Test
    @DisplayName("커밋이 하나도 없으면 빈 배열이다 — null 이나 500 이 아니다")
    fun top30IsEmptyArrayWhenNoCommits() {
        createUser("rank-nocommit")

        val res = get("/rank")

        assertThat(res.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(res.body?.trim()).isEqualTo("[]")
    }

    @Test
    @DisplayName("개인 순위는 {\"rank\": n} 으로 나가고 동점자는 같은 순위를 받는다")
    fun userRankReturnsDenseRank() {
        val first = createUser("rank-first")
        val tiedA = createUser("rank-tied-a")
        val tiedB = createUser("rank-tied-b")
        givenCommits(first, 5)
        givenCommits(tiedA, 2)
        givenCommits(tiedB, 2)

        assertThat(get("/rank/users?userId=$first").body).contains("\"rank\":1")

        // DENSE_RANK — 동점이면 같은 순위다. ROW_NUMBER 로 바뀌면 2와 3으로 갈린다.
        assertThat(get("/rank/users?userId=$tiedA").body).contains("\"rank\":2")
        assertThat(get("/rank/users?userId=$tiedB").body).contains("\"rank\":2")
    }

    @Test
    @DisplayName("이번 달 커밋이 없는 사용자의 순위는 JSON null 로 나간다")
    fun userWithoutCommitsGetsNullRank() {
        val ranked = createUser("rank-has-commits")
        val unranked = createUser("rank-no-commits")
        givenCommits(ranked, 2)

        val res = get("/rank/users?userId=$unranked")

        assertThat(res.statusCode).isEqualTo(HttpStatus.OK)
        // 키 자체가 빠지거나 0 으로 나가면 클라이언트가 "1등"으로 오해할 수 있다.
        assertThat(res.body?.replace(" ", "")).isEqualTo("""{"rank":null}""")
    }

    @Test
    @DisplayName("지난달 커밋은 이번 달 순위에 포함되지 않는다")
    fun previousMonthCommitsAreExcluded() {
        val user = createUser("rank-lastmonth")
        val lastMonth = LocalDateTime.now().withDayOfMonth(1).minusDays(3)
        givenCommits(user, 4, at = lastMonth)

        assertThat(get("/rank").body?.trim()).isEqualTo("[]")
        assertThat(get("/rank/users?userId=$user").body?.replace(" ", ""))
            .isEqualTo("""{"rank":null}""")
    }

    @Test
    @DisplayName("userId 파라미터가 빠지거나 타입이 안 맞으면 400")
    fun userRankRejectsBadParams() {
        assertThat(get("/rank/users").statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(get("/rank/users?userId=abc").statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    /** 이번 달(기본) 안의 서로 다른 시각에 커밋 [count] 개를 넣는다. */
    private fun givenCommits(userId: Long, count: Int, at: LocalDateTime? = null) {
        val base = at ?: LocalDateTime.now().withDayOfMonth(1).plusHours(1)
        repeat(count) { i ->
            jdbc.update(
                "INSERT INTO commits (user_id, commit_date, commit_url, title, level, sha) " +
                    "VALUES (?, ?, ?, ?, ?, ?)",
                userId,
                base.plusMinutes(i.toLong()),
                "https://github.com/test/repo/commit/$userId-$i",
                "테스트 커밋 $i",
                "BRONZE",
                "sha-$userId-$i-${base.toLocalDate()}",
            )
        }
    }

    private fun rankOfUserIn(body: String, userId: Long): Int =
        fieldAfterUser(body, userId, "rank")

    private fun commitCountOfUserIn(body: String, userId: Long): Int =
        fieldAfterUser(body, userId, "commitCount")

    private fun fieldAfterUser(body: String, userId: Long, field: String): Int {
        val at = body.indexOf("\"userId\":$userId")
        if (at < 0) error("응답에 userId=$userId 가 없다: $body")
        return Regex("\"$field\"\\s*:\\s*(\\d+)").find(body, at)
            ?.groupValues?.get(1)?.toInt()
            ?: error("$field 를 찾지 못했다: $body")
    }
}
