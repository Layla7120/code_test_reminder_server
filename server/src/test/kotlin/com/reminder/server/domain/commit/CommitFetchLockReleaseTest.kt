package com.reminder.server.domain.commit

import com.reminder.server.domain.user.User
import com.reminder.server.domain.user.UserRepository
import com.reminder.server.global.exception.GithubApiException
import com.reminder.server.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * 증명하는 주장: "락 해제를 트랜잭션 완료 이후로 미뤄도, GitHub 호출이 실패하면
 * 여전히 락이 풀려서 재시도가 30초 TTL에 막히지 않는다"
 *
 * 락 해제를 finally(즉시)에서 @TransactionalEventListener(AFTER_COMPLETION)으로
 * 옮기면서 "예외가 나도 반드시 풀린다"는 기존 보장이 깨지지 않았는지 확인하는 회귀 테스트.
 *
 * 참고: 이 리팩터링이 막으려는 "커밋 전 락 해제" race window 자체는 정상 성공 경로에서
 * 수 밀리초 이내라 sleep 기반으로 안정적으로 재현하기 어렵다. 그 부분은 Spring의
 * TransactionPhase.AFTER_COMPLETION 계약(트랜잭션 완료 후에만 실행됨)으로 구조적으로
 * 보장되고, 성공 경로에서 락이 정상적으로 풀리는 것은 CommitDuplicateFetchTest가
 * 같은 유저를 연달아 두 번 호출하는 방식으로 이미 간접 검증하고 있다.
 */
@Import(FailingGithubClientConfig::class)
class CommitFetchLockReleaseTest : IntegrationTest() {

    @Autowired lateinit var commitService: CommitService
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var redisTemplate: StringRedisTemplate

    @Test
    @DisplayName("GitHub 호출이 실패해도 락은 풀려서 곧바로 재시도할 수 있다")
    fun lockIsReleasedEvenWhenGithubCallFails() {
        val user = userRepository.save(User("lock-test", "lock-nick", "repo"))

        assertThatThrownBy { commitService.fetchAndSaveCommits(user.id) }
            .isInstanceOf(GithubApiException::class.java)

        assertThat(redisTemplate.hasKey("commit:fetch:lock:${user.id}"))
            .describedAs("실패했어도 락은 남아있으면 안 된다 — TTL(30초)까지 재시도가 막히면 안 됨")
            .isFalse()
    }
}

@TestConfiguration
class FailingGithubClientConfig {
    @Bean
    @Primary
    fun failingGithubClient(): GithubClientPort = object : GithubClientPort {
        override fun fetchCommits(githubId: String, repositoryName: String): List<CommitInsertDto> =
            throw GithubApiException("테스트용 강제 실패")

        override fun existsRepository(githubId: String, repositoryName: String): Boolean = true
    }
}
