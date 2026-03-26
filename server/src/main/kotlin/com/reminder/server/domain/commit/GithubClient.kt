package com.reminder.server.domain.commit

import com.reminder.server.global.exception.GithubApiException
import org.springframework.beans.factory.annotation.Value
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

@Component
class GithubClient(
    @Value("\${github.token}") private val token: String,
) {
    private val restClient = RestClient.builder()
        .baseUrl("https://api.github.com")
        .defaultHeader("Authorization", "token $token")
        .defaultHeader("Accept", "application/vnd.github.v3+json")
        .build()

    // 레거시 Flask와 동일한 커밋 메시지 파싱 패턴
    // "[LEVEL] Title: xxx, Time: xxx, Memory: xxx -"
    private val commitPattern = Regex("""\[(.*?)] Title: (.*?), Time: .*?, Memory: .*? -""")
    private val githubDateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")

    fun fetchCommits(githubId: String, repositoryName: String): List<CommitInsertDto> {
        val response = try {
            restClient.get()
                .uri("/repos/{owner}/{repo}/commits", githubId, repositoryName)
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

        return response.mapNotNull { it.toInsertDto() }
    }

    fun existsRepository(githubId: String, repositoryName: String): Boolean {
        return try {
            restClient.get()
                .uri("/repos/{owner}/{repo}", githubId, repositoryName)
                .retrieve()
                .toBodilessEntity()
            true
        } catch (e: RestClientResponseException) {
            if (e.statusCode == HttpStatus.NOT_FOUND) false
            else throw GithubApiException("GitHub API 오류: ${e.statusCode}")
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
}
