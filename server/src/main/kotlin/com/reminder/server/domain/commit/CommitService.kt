package com.reminder.server.domain.commit

import com.reminder.server.domain.rank.UserMonthlyScoreRepository
import com.reminder.server.domain.rank.toScoreMonth
import com.reminder.server.domain.user.UserRepository
import com.reminder.server.global.ServiceZone
import com.reminder.server.global.exception.UserNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

@Service
class CommitService(
    private val commitRepository: CommitRepository,
    private val commitJdbcRepository: CommitJdbcRepository,
    private val userRepository: UserRepository,
    private val userMonthlyScoreRepository: UserMonthlyScoreRepository,
    private val githubClient: GithubClientPort,
    private val clock: Clock,
) {
    // ── 커밋 동기화 ───────────────────────────────────────────────────────────

    @Transactional
    fun fetchAndSaveCommits(userId: Long): Int {
        val user = userRepository.findById(userId)
            .orElseThrow { UserNotFoundException(userId) }

        // 중복 수집을 락으로 막지 않는다. 이 연산이 멱등이기 때문이다 —
        // 삽입은 UNIQUE(user_id, sha) + ON DUPLICATE KEY UPDATE 이고 점수는 절대값
        // 재계산이라, 같은 요청이 몇 번을 동시에 와도 결과가 같다.

        val rawCommits = githubClient.fetchCommits(user.githubId, user.repositoryName)
        val dtos = rawCommits.map { it.copy(userId = userId) }

        // 실제로 새로 저장될 커밋만 랭킹에 반영한다 — 요청 개수를 그대로 더하면 재수집마다 점수가 부푼다 (버그 A). 경위: docs/기록.md
        val existingShas = commitJdbcRepository.findExistingShas(userId, dtos.map { it.sha })
        val newCommits = dtos
            .distinctBy { it.sha }  // 같은 fetch 안의 sha 중복 방어 (정상 GitHub 응답에서는 없음)
            .filter { it.sha !in existingShas }

        commitJdbcRepository.bulkUpsert(dtos)

        // 방금 쓴 커밋을 같은 트랜잭션에서 다시 세어 절대값으로 덮어쓴다 — 이벤트도 AFTER_COMMIT 도 안 쓴다.
        // newCommits 가 아니라 dtos 기준인 이유: 절대값이라 안 바뀐 달을 다시 써도 무해하고 이쪽이 안전하다.
        //
        // 재계산할 달은 커밋의 실제 날짜 기준이다. YearMonth.now(clock) 을 쓰면 월초에
        // 지난달 커밋을 수집할 때 엉뚱한 달을 다시 세게 된다.
        dtos.map { ServiceZone.toKstMonth(it.commitDate) }
            .distinct()
            .forEach { yearMonth -> recomputeMonthlyScore(userId, yearMonth) }

        return newCommits.size
    }

    // 경계는 KST 로 잡고 UTC 로 바꿔서 넘긴다 — commit_date 가 UTC 이기 때문이다.
    private fun recomputeMonthlyScore(userId: Long, yearMonth: YearMonth) {
        userMonthlyScoreRepository.recompute(
            userId,
            yearMonth.toScoreMonth(),
            ServiceZone.startOfMonthUtc(yearMonth),
            ServiceZone.startOfMonthUtc(yearMonth.plusMonths(1)),
        )
    }

    // ── 커밋 현황 조회 ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    fun getWeeklyActivity(userId: Long): List<LocalDate> {
        val today = ServiceZone.today(clock)

        return commitRepository.findCommitSummariesByUserAndDateRange(
            userId,
            ServiceZone.startOfDayUtc(today.minusDays(6)),
            ServiceZone.startOfDayUtc(today.plusDays(1)),
        )
            .map { ServiceZone.toKstDate(it.getCommitDate()) }
            .distinct()
            .sorted()
    }

    // 이번달 + 저번달 잔디 데이터
    @Transactional(readOnly = true)
    fun getCommitGrass(userId: Long): Map<String, Map<LocalDate, Long>> {
        val thisMonth = ServiceZone.currentMonth(clock)
        val prevMonth = thisMonth.minusMonths(1)

        return mapOf(
            "thisMonth" to countByKstDate(userId, thisMonth),
            "prevMonth" to countByKstDate(userId, prevMonth),
        )
    }

    // 조회 범위는 UTC 로, 묶는 키는 KST 날짜로. 둘을 섞으면 새벽 커밋이 하루 전 칸에 찍힌다.
    private fun countByKstDate(userId: Long, month: YearMonth): Map<LocalDate, Long> =
        commitRepository.findCommitSummariesByUserAndDateRange(
            userId,
            ServiceZone.startOfMonthUtc(month),
            ServiceZone.startOfMonthUtc(month.plusMonths(1)),
        )
            .groupingBy { ServiceZone.toKstDate(it.getCommitDate()) }
            .eachCount()
            .mapValues { it.value.toLong() }

    @Transactional(readOnly = true)
    fun getLevelDistribution(userId: Long): Map<String, Long> =
        commitRepository.findLevelDistribution(userId)
            .associate { it.getLevel() to it.getCount() }
}
