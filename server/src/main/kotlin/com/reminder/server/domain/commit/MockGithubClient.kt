package com.reminder.server.domain.commit

import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.LocalDateTime
import kotlin.random.Random

/**
 * 부하 테스트 전용 GitHub Client Mock
 *
 * @Profile("load-test") 활성 시 GithubClient 대신 주입됨
 *
 * [목적]
 * mixed 시나리오: POST /commits를 반복 호출하여
 *   DB Bulk Insert → user_monthly_score 재계산
 * 전체 경로를 실제로 통과시키면서 GitHub API Rate Limit 없이 부하를 발생시킨다.
 *
 * [핵심]
 * 외부 API만 우회하고 내부 경로(저장 → 점수 재계산)는 그대로 유지.
 * POST /history로 대체하면 그 경로를 안 타서 점수 정합성 검증이 불가능.
 *
 * [결정론적 응답 — 중요]
 * githubId/repositoryName 조합마다 항상 같은 sha 목록을 돌려준다. 이전 버전(매 호출 UUID)은
 * 중복이 구조적으로 불가능해 재수집 버그를 부하 테스트로 못 잡았다. 경위: docs/기록.md
 *
 * [실행]
 * SPRING_PROFILES_ACTIVE=load-test ./gradlew bootRun
 */
@Component
@Profile("load-test")
class MockGithubClient : GithubClientPort {

    override fun fetchCommits(githubId: String, repositoryName: String): List<CommitInsertDto> {
        val random = Random(seedFor(githubId, repositoryName))
        val count = 3 + random.nextInt(5) // 3~7
        val now = LocalDateTime.now()

        return (1..count).map { index ->
            val sha = shaFor(githubId, repositoryName, index)
            CommitInsertDto(
                userId = 0L,  // CommitService에서 실제 userId로 교체
                commitDate = now.minusHours(random.nextInt(73).toLong()),
                commitUrl = "https://github.com/$githubId/$repositoryName/commit/$sha",
                title = DUMMY_PROBLEMS[random.nextInt(DUMMY_PROBLEMS.size)],
                level = CommitLevel.entries[random.nextInt(CommitLevel.entries.size)].name,
                sha = sha,
            )
        }
    }

    override fun existsRepository(githubId: String, repositoryName: String): Boolean = true

    companion object {
        private val DUMMY_PROBLEMS = listOf(
            "두 수의 합", "피보나치 수", "소수 판별", "DFS와 BFS", "최단경로",
            "동적 계획법", "이진 탐색", "투 포인터", "유니온 파인드", "세그먼트 트리",
        )

        private fun seedFor(githubId: String, repositoryName: String): Long =
            "$githubId/$repositoryName".hashCode().toLong()

        // SHA-1 다이제스트 40자 hex → 실제 GitHub sha와 같은 길이(컬럼 length=40과 일치)
        private fun shaFor(githubId: String, repositoryName: String, index: Int): String =
            MessageDigest.getInstance("SHA-1")
                .digest("$githubId/$repositoryName#$index".toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
