package com.reminder.server.domain.commit

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 증명하는 주장: "MockGithubClient는 실제와 같게 동작한다"
 *
 * 이전 버전은 매 호출마다 UUID.randomUUID()로 sha를 만들어서
 * 같은 저장소를 두 번 조회해도 항상 다른 커밋을 돌려줬다.
 * 그래서 재수집 시나리오(버그 A)가 부하 테스트에서 단 한 번도 재현되지 않았다.
 *
 * Spring 컨텍스트가 필요 없는 순수 단위 테스트.
 */
class MockGithubClientTest {

    private val client = MockGithubClient()

    @Test
    @DisplayName("같은 githubId/repositoryName은 두 번 호출해도 같은 sha 목록을 반환한다")
    fun sameRepoReturnsSameShasOnRepeatedCalls() {
        val first = client.fetchCommits("octocat", "hello-world")
        val second = client.fetchCommits("octocat", "hello-world")

        assertThat(second.map { it.sha }).isEqualTo(first.map { it.sha })
    }

    @Test
    @DisplayName("다른 저장소는 다른 sha 목록을 반환한다")
    fun differentRepoReturnsDifferentShas() {
        val a = client.fetchCommits("octocat", "hello-world")
        val b = client.fetchCommits("octocat", "other-repo")

        assertThat(a.map { it.sha }).isNotEqualTo(b.map { it.sha })
    }

    @Test
    @DisplayName("sha는 실제 GitHub sha와 같은 40자 hex 형식이다")
    fun shaMatchesRealShaFormat() {
        val commits = client.fetchCommits("octocat", "hello-world")

        assertThat(commits).isNotEmpty
        commits.forEach { commit ->
            assertThat(commit.sha).hasSize(40).matches("[0-9a-f]{40}")
        }
    }
}
