package com.reminder.server.domain.commit

/**
 * GitHub 커밋 수집 추상화 인터페이스
 *
 * 실제 구현체: GithubClient           (@Profile("!load-test"))
 * 테스트 구현체: MockGithubClient      (@Profile("load-test"))
 *
 * CommitService는 이 인터페이스에만 의존 → GitHub API 없이도
 * DB Bulk Insert → CommitSavedEvent → Redis ZINCRBY 전체 경로를 통과할 수 있음
 */
interface GithubClientPort {
    fun fetchCommits(githubId: String, repositoryName: String): List<CommitInsertDto>
    fun existsRepository(githubId: String, repositoryName: String): Boolean
}
