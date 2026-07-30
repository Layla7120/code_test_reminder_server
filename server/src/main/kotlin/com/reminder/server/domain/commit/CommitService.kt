package com.reminder.server.domain.commit

import com.reminder.server.domain.user.UserRepository
import com.reminder.server.global.exception.CommitFetchAlreadyInProgressException
import com.reminder.server.global.exception.UserNotFoundException
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

@Service
class CommitService(
    private val commitRepository: CommitRepository,
    private val commitJdbcRepository: CommitJdbcRepository,
    private val userRepository: UserRepository,
    private val githubClient: GithubClientPort,
    private val redisTemplate: StringRedisTemplate,
    private val eventPublisher: ApplicationEventPublisher,
    private val clock: Clock,
) {
    // ── 커밋 동기화 ───────────────────────────────────────────────────────────

    @Transactional
    fun fetchAndSaveCommits(userId: Long): Int {
        val user = userRepository.findById(userId)
            .orElseThrow { UserNotFoundException(userId) }

        // 동일 유저의 중복 페칭 요청을 Application 레벨에서 차단 (멱등성)
        // SETNX 원자적 연산 → 분산 서버 환경에서도 안전
        val lockKey = "commit:fetch:lock:$userId"
        val acquired = redisTemplate.opsForValue()
            .setIfAbsent(lockKey, "1", Duration.ofSeconds(30))
        if (acquired != true) throw CommitFetchAlreadyInProgressException()

        try {
            val rawCommits = githubClient.fetchCommits(user.githubId, user.repositoryName)
            val dtos = rawCommits.map { it.copy(userId = userId) }

            // 실제로 새로 저장될 커밋만 랭킹에 반영한다.
            // ON DUPLICATE KEY UPDATE는 이미 있는 sha를 조용히 건너뛰는데,
            // dtos.size(요청 개수)를 그대로 증분으로 쓰면 같은 커밋을 재수집할 때마다
            // 실제 삽입 없이 점수만 계속 오른다 (버그 A).
            val existingShas = commitJdbcRepository.findExistingShas(dtos.map { it.sha })
            val newCommits = dtos
                .distinctBy { it.sha }  // 같은 fetch 안의 sha 중복 방어 (정상 GitHub 응답에서는 없음)
                .filter { it.sha !in existingShas }

            // sha 정렬 후 bulk upsert (InnoDB Next-Key Lock 순서 보장 → 데드락 방지)
            commitJdbcRepository.bulkUpsert(dtos)

            // 월별로 나눠 발행 — 버킷은 서버의 "지금"이 아니라 커밋의 실제 날짜 기준.
            // YearMonth.now(clock)을 쓰면 월초에 지난달 커밋을 수집할 때
            // 이번달 ZSET에 잘못 가산되어 다음 달까지 정합성이 어긋난다.
            //
            // DB 트랜잭션 커밋 후 Redis ZINCRBY 발행 (AFTER_COMMIT, 롤백 시 Redis 미반영)
            newCommits
                .groupingBy { YearMonth.from(it.commitDate) }
                .eachCount()
                .forEach { (yearMonth, count) ->
                    eventPublisher.publishEvent(CommitsSavedEvent(userId, count, yearMonth))
                }

            return newCommits.size
        } finally {
            redisTemplate.delete(lockKey)
        }
    }

    // ── 커밋 현황 조회 ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    fun getWeeklyActivity(userId: Long): List<LocalDate> {
        val now = LocalDateTime.now(clock)
        val from = now.minusDays(6).toLocalDate().atStartOfDay()
        val to = now.toLocalDate().atStartOfDay().plusDays(1)

        return commitRepository.findCommitSummariesByUserAndDateRange(userId, from, to)
            .map { it.getCommitDate().toLocalDate() }
            .distinct()
            .sorted()
    }

    // 이번달 + 저번달 잔디 데이터
    @Transactional(readOnly = true)
    fun getCommitGrass(userId: Long): Map<String, Map<LocalDate, Long>> {
        val now = LocalDateTime.now(clock)
        val thisMonthStart = now.withDayOfMonth(1).toLocalDate().atStartOfDay()
        val prevMonthStart = thisMonthStart.minusMonths(1)

        val thisMonth = commitRepository
            .findCommitSummariesByUserAndDateRange(userId, thisMonthStart, thisMonthStart.plusMonths(1))
            .groupingBy { it.getCommitDate().toLocalDate() }
            .eachCount()
            .mapValues { it.value.toLong() }

        val prevMonth = commitRepository
            .findCommitSummariesByUserAndDateRange(userId, prevMonthStart, thisMonthStart)
            .groupingBy { it.getCommitDate().toLocalDate() }
            .eachCount()
            .mapValues { it.value.toLong() }

        return mapOf("thisMonth" to thisMonth, "prevMonth" to prevMonth)
    }

    @Transactional(readOnly = true)
    fun getLevelDistribution(userId: Long): Map<String, Long> =
        commitRepository.findLevelDistribution(userId)
            .associate { it.getLevel() to it.getCount() }
}
