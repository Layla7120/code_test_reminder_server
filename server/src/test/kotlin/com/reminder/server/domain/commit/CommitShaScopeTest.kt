package com.reminder.server.domain.commit

import com.reminder.server.domain.user.User
import com.reminder.server.domain.user.UserRepository
import com.reminder.server.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDateTime

/**
 * 증명하는 주장: "sha 의 유일성 범위는 전 세계가 아니라 유저 한 명이다"
 *
 * git sha 는 내용 주소다. 저장소를 포크하거나 같은 템플릿에서 갈라지면 **서로 다른 유저가
 * 같은 sha 를 정당하게 갖는다.** 코테 스터디에서 포크는 흔하다.
 *
 * 전역 UNIQUE(sha) 였을 때의 증상: 두 번째 유저의 커밋이 ON DUPLICATE KEY UPDATE 에
 * 걸려 **조용히 사라진다.** 예외도 로그도 없고 saved 만 0 으로 나간다.
 * 그래서 UNIQUE(user_id, sha) 로 바꿨다.
 */
class CommitShaScopeTest : IntegrationTest() {

    @Autowired lateinit var commitJdbcRepository: CommitJdbcRepository
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var jdbc: JdbcTemplate

    private val sharedSha = "a".repeat(40)

    @Test
    @DisplayName("두 유저가 같은 sha 를 올려도 둘 다 저장된다 — 포크 저장소")
    fun sameShaFromDifferentUsersAreBothStored() {
        val alice = userRepository.save(User("alice", "alice-nick", "algo-study"))
        val bob = userRepository.save(User("bob", "bob-nick", "algo-study"))  // alice 의 포크

        commitJdbcRepository.bulkUpsert(listOf(commitOf(alice.id)))
        commitJdbcRepository.bulkUpsert(listOf(commitOf(bob.id)))

        assertThat(countCommits(alice.id))
            .describedAs("먼저 올린 유저의 커밋")
            .isEqualTo(1)
        assertThat(countCommits(bob.id))
            .describedAs("포크한 유저의 커밋 — 전역 UNIQUE(sha) 였을 때 여기서 조용히 사라졌다")
            .isEqualTo(1)
    }

    @Test
    @DisplayName("같은 유저가 같은 sha 를 두 번 올리면 한 번만 저장된다")
    fun sameShaFromSameUserIsStoredOnce() {
        val alice = userRepository.save(User("alice", "alice-nick", "algo-study"))

        commitJdbcRepository.bulkUpsert(listOf(commitOf(alice.id)))
        commitJdbcRepository.bulkUpsert(listOf(commitOf(alice.id)))

        assertThat(countCommits(alice.id))
            .describedAs("유저 안에서는 여전히 멱등이어야 한다 — 이게 깨지면 재수집마다 커밋이 늘어난다")
            .isEqualTo(1)
    }

    @Test
    @DisplayName("한 번의 수집 안에 같은 sha 가 중복돼도 한 번만 저장된다")
    fun duplicateShaWithinOneBatchIsStoredOnce() {
        val alice = userRepository.save(User("alice", "alice-nick", "algo-study"))

        // 정상 GitHub 응답에는 없지만, 방어가 실제로 도는지 확인한다.
        // bulkUpsert 는 sha 정렬 후 배치로 보내므로 같은 배치 안의 중복도 DB 가 걸러야 한다.
        commitJdbcRepository.bulkUpsert(listOf(commitOf(alice.id), commitOf(alice.id)))

        assertThat(countCommits(alice.id)).isEqualTo(1)
    }

    @Test
    @DisplayName("findExistingShas 는 다른 유저의 sha 를 내 것으로 세지 않는다")
    fun findExistingShasIsScopedToUser() {
        val alice = userRepository.save(User("alice", "alice-nick", "algo-study"))
        val bob = userRepository.save(User("bob", "bob-nick", "algo-study"))

        commitJdbcRepository.bulkUpsert(listOf(commitOf(alice.id)))

        assertThat(commitJdbcRepository.findExistingShas(bob.id, listOf(sharedSha)))
            .describedAs("bob 은 아직 이 커밋이 없다 — alice 것을 보고 '있다'고 하면 bob 의 신규 건수가 0이 된다")
            .isEmpty()
        assertThat(commitJdbcRepository.findExistingShas(alice.id, listOf(sharedSha)))
            .containsExactly(sharedSha)
    }

    private fun commitOf(userId: Long) = CommitInsertDto(
        userId = userId,
        commitDate = LocalDateTime.now().withDayOfMonth(1).plusHours(1),
        commitUrl = "https://github.com/x/algo-study/commit/$sharedSha",
        title = "두 수의 합",
        level = CommitLevel.GOLD.name,
        sha = sharedSha,
    )

    private fun countCommits(userId: Long): Int =
        jdbc.queryForObject("SELECT COUNT(*) FROM commits WHERE user_id = ?", Int::class.java, userId) ?: 0
}
