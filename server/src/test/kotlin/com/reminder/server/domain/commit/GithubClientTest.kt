package com.reminder.server.domain.commit

import com.reminder.server.global.exception.GithubApiException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient

/**
 * 증명하는 주장: "GithubClient 는 페이지를 끝까지 따라가고, 정확히 마지막 페이지에서 멈춘다"
 *
 * 이 루프는 GithubClientPort 아래에 있다. MockGithubClient 를 끼우는 테스트는 루프를
 * 통째로 건너뛰므로 종료 조건을 반대로 써도 초록불이 난다. 그래서 경계를 포트가 아니라
 * 소켓으로 한 칸 내려 MockRestServiceServer 로 HTTP 를 가짜로 만든다.
 * 경위: docs/adr/0004-페이지네이션-루프를-포트-아래-두고-http-를-가짜로-만든다.md
 *
 * Spring 컨텍스트도 컨테이너도 필요 없다.
 *
 * server.verify() 가 두 방향을 다 잡는다 — 기대한 요청이 안 나가면 실패하고,
 * 기대하지 않은 요청이 나가면 그 자리에서 실패한다. "덜 멈춤"과 "더 멈춤"이 같이 고정된다.
 */
class GithubClientTest {

    private val builder = RestClient.builder()
    private val server: MockRestServiceServer = MockRestServiceServer.bindTo(builder).build()
    private val client = GithubClient(token = "test-token", restClientBuilder = builder)

    @Test
    @DisplayName("100건이 꽉 찬 페이지 뒤에 빈 페이지가 오면 거기서 멈춘다")
    fun stopsAtEmptyPageAfterFullPage() {
        expectPage(1, commitsJson(count = 100, startIndex = 0))
        expectPage(2, "[]")

        val commits = client.fetchCommits(GITHUB_ID, REPO)

        assertThat(commits)
            .describedAs("꽉 찬 페이지에서 멈추면 커밋이 조용히 누락된다")
            .hasSize(100)
        server.verify()
    }

    @Test
    @DisplayName("100건 미만인 단일 페이지는 다음 페이지를 부르지 않고 끝난다")
    fun stopsOnFirstPartialPage() {
        expectPage(1, commitsJson(count = 37, startIndex = 0))

        val commits = client.fetchCommits(GITHUB_ID, REPO)

        assertThat(commits).hasSize(37)
        // 2페이지를 부르면 기대하지 않은 요청이라 여기서 깨진다.
        server.verify()
    }

    @Test
    @DisplayName("100 / 100 / 37 세 페이지를 모두 이어 붙여 237건을 돌려준다")
    fun collectsEveryPage() {
        expectPage(1, commitsJson(count = 100, startIndex = 0))
        expectPage(2, commitsJson(count = 100, startIndex = 100))
        expectPage(3, commitsJson(count = 37, startIndex = 200))

        val commits = client.fetchCommits(GITHUB_ID, REPO)

        assertThat(commits).hasSize(237)
        assertThat(commits.map { it.sha })
            .describedAs("페이지 사이에 누락도 중복도 없어야 한다")
            .isEqualTo((0 until 237).map { shaOf(it) })
        server.verify()
    }

    @Test
    @DisplayName("2페이지째가 500 이면 1페이지분을 부분 반환하지 않고 예외를 던진다")
    fun failsWholeFetchWhenAPageErrors() {
        expectPage(1, commitsJson(count = 100, startIndex = 0))
        server.expect(requestTo(pageUri(2))).andRespond(withServerError())

        // 부분 목록이 돌아가면 CommitService 는 그걸 정상 결과로 저장한다.
        // 예외를 던져야 @Transactional 이 걷어내고 아무 일도 일어나지 않는다.
        assertThatThrownBy { client.fetchCommits(GITHUB_ID, REPO) }
            .isInstanceOf(GithubApiException::class.java)
        server.verify()
    }

    @Test
    @DisplayName("알고리즘 커밋이 아닌 게 섞여도 그 페이지 개수로 종료를 판정한다")
    fun terminationUsesRawPageSizeNotParsedSize() {
        // 100건 중 파싱되는 건 60건. 걸러진 뒤 개수로 재면 60 < 100 이라 2페이지를 안 부른다.
        expectPage(1, commitsJson(count = 100, startIndex = 0, parsableCount = 60))
        expectPage(2, commitsJson(count = 5, startIndex = 100))

        val commits = client.fetchCommits(GITHUB_ID, REPO)

        assertThat(commits).hasSize(65)
        server.verify()
    }

    private fun expectPage(page: Int, body: String) {
        server.expect(requestTo(pageUri(page)))
            .andRespond(withSuccess(body, MediaType.APPLICATION_JSON))
    }

    /** per_page·page 가 실제로 나가는지도 이 URI 대조로 같이 고정된다. */
    private fun pageUri(page: Int) =
        "https://api.github.com/repos/$GITHUB_ID/$REPO/commits?per_page=100&page=$page"

    private fun commitsJson(count: Int, startIndex: Int, parsableCount: Int = count): String =
        (0 until count).joinToString(",", "[", "]") { offset ->
            val n = startIndex + offset
            val message =
                if (offset < parsableCount) "[Gold IV] Title: 문제 $n, Time: 100 ms, Memory: 30 MB -"
                else "docs: 리드미 수정 $n"
            """
            {
              "sha": "${shaOf(n)}",
              "html_url": "https://github.com/$GITHUB_ID/$REPO/commit/${shaOf(n)}",
              "commit": {
                "message": "$message",
                "author": { "date": "2026-08-21T09:00:00Z" }
              }
            }
            """.trimIndent()
        }

    private fun shaOf(n: Int) = "%040x".format(n)

    companion object {
        private const val GITHUB_ID = "octocat"
        private const val REPO = "hello-world"
    }
}
