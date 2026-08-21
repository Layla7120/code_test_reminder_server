package com.reminder.server.api

import com.reminder.server.domain.commit.CommitLevel
import com.reminder.server.support.ApiTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles

/**
 * GET /commits/level — 전송 계층 검증.
 *
 * [왜 지금 생겼나]
 * endpoint-allowlist.txt 에 "{level:count} 모양" 사유로 올라가 있던 부채인데,
 * 난이도 파싱을 세 사이트로 넓히면서 **이 엔드포인트의 응답 키가 늘었다.**
 * 계약이 바뀌었으므로 AGENTS.md §1 에 따라 여기서 고정한다.
 *
 * 파싱 자체의 정확성은 CommitLevelTest 가 본다. 여기서 보는 건 그 결과가
 * HTTP 응답으로 나오는 모양이다 — 키가 CommitLevel 이름인가, 합이 맞는가.
 *
 * MockGithubClient 를 쓰려고 load-test 프로필로 돈다. Mock 은 커밋 메시지를 만들지 않고
 * 레벨을 직접 고르므로, 여기서 특정 난이도를 기대하지 않는다.
 */
@ActiveProfiles("load-test")
class CommitLevelApiTest : ApiTest() {

    @Test
    @DisplayName("난이도 분포는 {레벨이름: 개수} 이고 합이 저장된 커밋 수와 같다")
    fun levelDistributionShapeAndTotal() {
        val userId = createUser("level-api")
        val saved = post("/commits", """{"userId":$userId}""").longField("saved")

        val res = get("/commits/level?userId=$userId")

        assertThat(res.statusCode).isEqualTo(HttpStatus.OK)

        val counts = parseCounts(res.body)
        assertThat(counts).isNotEmpty
        assertThat(counts.keys)
            .describedAs("키는 CommitLevel 이름이어야 한다 — 새 난이도를 넣고 enum 을 안 늘리면 여기서 깨진다")
            .allMatch { name -> CommitLevel.entries.any { it.name == name } }
        assertThat(counts.values.sum())
            .describedAs("분포의 합은 저장된 커밋 수와 같아야 한다 — 어긋나면 파싱이 커밋을 흘린 것")
            .isEqualTo(saved)
    }

    @Test
    @DisplayName("커밋을 한 번도 안 모은 유저는 빈 분포를 받는다")
    fun distributionIsEmptyBeforeAnyFetch() {
        val userId = createUser("level-api-empty")

        val res = get("/commits/level?userId=$userId")

        assertThat(res.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(parseCounts(res.body)).isEmpty()
    }

    @Test
    @DisplayName("userId 를 빼면 400")
    fun missingUserIdIs400() {
        assertThat(get("/commits/level").statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    private fun parseCounts(body: String?): Map<String, Long> =
        Regex("\"([A-Z0-9]+)\"\\s*:\\s*(\\d+)").findAll(body ?: "")
            .associate { it.groupValues[1] to it.groupValues[2].toLong() }
}
