package com.reminder.server.domain.commit

import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.util.UUID

/**
 * 부하 테스트 전용 GitHub Client Mock
 *
 * @Profile("load-test") 활성 시 GithubClient 대신 주입됨
 *
 * [목적]
 * mixed 시나리오: POST /commits를 반복 호출하여
 *   DB Bulk Insert → CommitSavedEvent → Redis ZINCRBY
 * 전체 경로를 실제로 통과시키면서 GitHub API Rate Limit 없이 부하를 발생시킨다.
 *
 * [핵심]
 * 외부 API만 우회하고 내부 아키텍처(이벤트 발행, Redis 갱신)는 그대로 유지.
 * POST /history로 대체하면 CommitSavedEvent가 발행되지 않아 Redis 정합성 검증이 불가능.
 *
 * [실행]
 * SPRING_PROFILES_ACTIVE=load-test ./gradlew bootRun
 */
@Component
@Profile("load-test")
class MockGithubClient : GithubClientPort {

    // 매 호출마다 3~7개의 커밋을 반환 (실제 GitHub 응답과 유사한 양)
    // UUID sha → DB UNIQUE 제약 통과 (중복 없음)
    override fun fetchCommits(githubId: String, repositoryName: String): List<CommitInsertDto> {
        val count = (3..7).random()
        val now = LocalDateTime.now()
        return (1..count).map {
            CommitInsertDto(
                userId = 0L,  // CommitService에서 실제 userId로 교체
                commitDate = now.minusHours((0..72).random().toLong()),
                commitUrl = "https://github.com/$githubId/$repositoryName/commit/${UUID.randomUUID()}",
                title = DUMMY_PROBLEMS.random(),
                level = CommitLevel.entries.random().name,
                sha = UUID.randomUUID().toString().replace("-", "").take(40),
            )
        }
    }

    override fun existsRepository(githubId: String, repositoryName: String): Boolean = true

    companion object {
        private val DUMMY_PROBLEMS = listOf(
            "두 수의 합", "피보나치 수", "소수 판별", "DFS와 BFS", "최단경로",
            "동적 계획법", "이진 탐색", "투 포인터", "유니온 파인드", "세그먼트 트리",
        )
    }
}
