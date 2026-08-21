package com.reminder.server.domain.commit

import com.reminder.server.global.exception.GithubApiException
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class GithubCommitResponse(
    val sha: String,
    val commit: CommitDetail,
    val html_url: String,
) {
    data class CommitDetail(
        val message: String,
        val author: Author,
    )
    data class Author(
        val date: String,
    )
}

/**
 * RestClient 를 생성자 안에서 만들지 않고 RestClient.Builder 를 받는다.
 * 이 클래스의 페이지네이션 루프는 포트(GithubClientPort) 아래에 있어서 테스트 더블로는
 * 통째로 건너뛰어진다 — 종료 조건을 검증하려면 HTTP 레벨에서 끼어들 자리가 필요하다.
 * 경위: docs/adr/0004-페이지네이션-루프를-포트-아래-두고-http-를-가짜로-만든다.md
 *
 * 기본값을 둔 이유: 이 classpath 에는 RestClient.Builder 빈이 없다. Boot 4 는
 * RestClientAutoConfiguration 을 spring-boot-restclient 모듈로 분리했고 그 모듈이 없다.
 * 기본값이면 운영 배선은 지금 그대로고 테스트만 자기 빌더를 넣는다 — 의존성이 늘지 않는다.
 */
@Component
@Profile("!load-test")
class GithubClient(
    @Value("\${github.token}") token: String,
    restClientBuilder: RestClient.Builder = RestClient.builder(),
) : GithubClientPort {
    private val restClient = restClientBuilder
        .baseUrl("https://api.github.com")
        .defaultHeader("Authorization", "token $token")
        .defaultHeader("Accept", "application/vnd.github.v3+json")
        .build()

    // 레거시 Flask와 동일한 커밋 메시지 파싱 패턴
    // "[LEVEL] Title: xxx, Time: xxx, Memory: xxx -"
    private val commitPattern = Regex("""\[(.*?)] Title: (.*?), Time: .*?, Memory: .*? -""")
    private val githubDateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")

    /**
     * 페이지를 끝까지 따라가 전체 이력을 수집한다. 페이지 수 상한을 두지 않는다 —
     * 상한은 조용한 절단이고, 그게 고치려는 증상 그 자체다.
     * 경위: docs/adr/0003-github-커밋을-전체-이력으로-수집한다.md
     */
    override fun fetchCommits(githubId: String, repositoryName: String): List<CommitInsertDto> {
        val collected = mutableListOf<CommitInsertDto>()
        var page = 1

        while (true) {
            val pageResponse = fetchPage(githubId, repositoryName, page)
            collected += pageResponse.mapNotNull { it.toInsertDto() }

            // 판정 기준은 받은 원본 개수다. mapNotNull 이 걸러낸 뒤의 개수로 재면
            // 알고리즘 커밋이 아닌 게 섞인 페이지에서 남은 페이지를 통째로 버린다.
            if (pageResponse.size < PER_PAGE) break
            page++
        }

        return collected
    }

    private fun fetchPage(githubId: String, repositoryName: String, page: Int): Array<GithubCommitResponse> =
        try {
            restClient.get()
                .uri("/repos/{owner}/{repo}/commits?per_page={perPage}&page={page}", githubId, repositoryName, PER_PAGE, page)
                .retrieve()
                .body(Array<GithubCommitResponse>::class.java)
                ?: emptyArray()
        } catch (e: RestClientResponseException) {
            when (e.statusCode) {
                HttpStatus.NOT_FOUND -> throw GithubApiException("레포지토리를 찾을 수 없습니다: $githubId/$repositoryName")
                HttpStatus.UNAUTHORIZED -> throw GithubApiException("GitHub 토큰이 유효하지 않습니다")
                else -> throw GithubApiException("GitHub API 오류: ${e.statusCode}")
            }
        }

    private fun GithubCommitResponse.toInsertDto(): CommitInsertDto? {
        val match = commitPattern.find(commit.message) ?: return null  // 알고리즘 커밋이 아니면 skip
        val level = CommitLevel.from(match.groupValues[1])
        val title = match.groupValues[2]
        val commitDate = LocalDateTime.parse(commit.author.date, githubDateFormat)

        return CommitInsertDto(
            userId = 0L,  // CommitService에서 userId 주입
            commitDate = commitDate,
            commitUrl = html_url,
            title = title,
            level = level.name,
            sha = sha,
        )
    }

    companion object {
        // GitHub 이 허용하는 최댓값. 기본값은 30 이라 안 적으면 30건에서 조용히 잘린다.
        private const val PER_PAGE = 100
    }
}
