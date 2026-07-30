package com.reminder.server.domain.group

import com.reminder.server.domain.user.User
import com.reminder.server.domain.user.UserRepository
import com.reminder.server.global.exception.GroupFullException
import com.reminder.server.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 증명하는 주장:
 *   "정원 체크와 증가를 DB 레벨 원자적 UPDATE 한 번으로 처리해 Lost Update 를 없앴다"
 *
 * 대상: GroupRepository.incrementMemberCounterIfNotFull()
 *   UPDATE groups SET member_counter = member_counter + 1
 *   WHERE group_id = ? AND member_counter < member_max_count
 *
 * 이 테스트가 진짜 MySQL 을 쓰는 이유:
 *   검증 대상이 InnoDB 가 같은 행에 대한 동시 UPDATE 를 직렬화하는 동작 그 자체다.
 *   H2 로 바꾸면 검증 대상이 사라진다.
 */
class GroupJoinConcurrencyTest : IntegrationTest() {

    @Autowired lateinit var groupService: GroupService
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var groupRepository: GroupRepository
    @Autowired lateinit var participateRepository: ParticipateRepository

    @Test
    @DisplayName("정원 1자리에 20명이 동시에 참여하면 정확히 1명만 성공한다")
    fun onlyOneWinsTheLastSlot() {
        // 정원 2 = 오너가 1자리를 차지하므로 남은 자리는 1
        val group = createGroupWithMaxCount(2)
        val candidates = createUsers(CONCURRENCY)

        val result = joinAllAtOnce(candidates, group.id)

        assertThat(result.unexpected)
            .describedAs("GroupFullException 외의 예외가 나오면 안 된다")
            .isEmpty()
        assertThat(result.succeeded).isEqualTo(1)
        assertThat(result.rejectedAsFull).isEqualTo(CONCURRENCY - 1)

        assertMemberCount(group.id, expected = 2)
    }

    /**
     * 위 테스트가 "항상 1만 나오는 고장난 테스트"가 아님을 보이는 대조군.
     * 자리가 충분하면 동시에 들어와도 전원 성공해야 한다.
     */
    @Test
    @DisplayName("자리가 충분하면 20명이 동시에 참여해도 전원 성공한다")
    fun allSucceedWhenThereIsRoom() {
        val group = createGroupWithMaxCount(CONCURRENCY + 1) // 오너 1 + 후보 20
        val candidates = createUsers(CONCURRENCY)

        val result = joinAllAtOnce(candidates, group.id)

        assertThat(result.unexpected).isEmpty()
        assertThat(result.succeeded).isEqualTo(CONCURRENCY)
        assertThat(result.rejectedAsFull).isZero()

        assertMemberCount(group.id, expected = CONCURRENCY + 1)
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private fun createGroupWithMaxCount(maxCount: Int): Group {
        val owner = userRepository.save(User("owner", "owner", "owner-repo"))
        return groupService.createGroup(owner.id, "동시성테스트그룹", null, maxCount)
    }

    private fun createUsers(count: Int): List<User> =
        (1..count).map { userRepository.save(User("github$it", "nick$it", "repo$it")) }

    /** 모든 스레드를 래치로 붙잡았다가 동시에 풀어 실제 경합을 만든다. */
    private fun joinAllAtOnce(users: List<User>, groupId: Long): JoinResult {
        val startGate = CountDownLatch(1)
        val finished = CountDownLatch(users.size)
        val succeeded = AtomicInteger()
        val rejectedAsFull = AtomicInteger()
        val unexpected = CopyOnWriteArrayList<Throwable>()
        val pool = Executors.newFixedThreadPool(users.size)

        users.forEach { user ->
            pool.submit {
                try {
                    startGate.await()
                    groupService.joinGroup(user.id, groupId, null)
                    succeeded.incrementAndGet()
                } catch (e: GroupFullException) {
                    rejectedAsFull.incrementAndGet()
                } catch (e: Throwable) {
                    unexpected.add(e)
                } finally {
                    finished.countDown()
                }
            }
        }

        startGate.countDown()
        val completed = finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        pool.shutdownNow()
        assertThat(completed).describedAs("모든 스레드가 제한 시간 안에 끝나야 한다").isTrue()

        return JoinResult(succeeded.get(), rejectedAsFull.get(), unexpected)
    }

    /** 카운터와 실제 행 수가 함께 맞아야 한다. 하나만 보면 드리프트를 놓친다. */
    private fun assertMemberCount(groupId: Long, expected: Int) {
        val group = groupRepository.findById(groupId).orElseThrow()

        assertThat(group.memberCounter)
            .describedAs("member_counter")
            .isEqualTo(expected)
        assertThat(participateRepository.findMemberIdsByGroupId(groupId))
            .describedAs("participate 실제 행 수")
            .hasSize(expected)
    }

    private data class JoinResult(
        val succeeded: Int,
        val rejectedAsFull: Int,
        val unexpected: List<Throwable>,
    )

    companion object {
        private const val CONCURRENCY = 20
        private const val TIMEOUT_SECONDS = 30L
    }
}
