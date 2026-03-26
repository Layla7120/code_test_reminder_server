package com.reminder.server.domain.rank

import com.reminder.server.domain.commit.CommitRepository
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDateTime
import java.time.YearMonth

@Component
class RankingSelfHealingScheduler(
    private val commitRepository: CommitRepository,
    private val rankingRedisRepository: RankingRedisRepository,
    private val clock: Clock,
) {
    // 매시간 정각 실행
    // @TransactionalEventListener 실패(네트워크 단절, 서버 크래시)로 유실된 score를 복구
    // 최대 1시간 내 자동 복구 보장
    @Scheduled(cron = "0 0 * * * *")
    fun syncCurrentMonthRank() {
        val now = LocalDateTime.now(clock)
        val yearMonth = YearMonth.now(clock)

        val from = now.withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0)
        val to = from.plusMonths(1)

        val scores = commitRepository
            .findMonthlyCommitCountPerUser(from, to)
            .associate { it.getUserId() to it.getCount() }

        // Step 1: score ZSET 보정 (GT — 실시간 증분 보호, Lost Update 방지)
        rankingRedisRepository.healScores(scores, yearMonth)

        // Step 2: Dense Rank HASH 재계산 (Shadow Key + Atomic RENAME, 다운타임 없음)
        rankingRedisRepository.rebuildDenseRankHash(yearMonth)
    }
}
