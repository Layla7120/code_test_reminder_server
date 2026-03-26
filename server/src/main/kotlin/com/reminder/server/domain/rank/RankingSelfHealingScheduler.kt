package com.reminder.server.domain.rank

import com.reminder.server.domain.commit.CommitRepository
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDateTime
import java.time.YearMonth

// 분산 트랜잭션 실패 시나리오:
//   DB bulkUpsert 성공 → 네트워크 단절 → Redis ZINCRBY 실패
//   → DB 커밋 100개, Redis score 0 → 정합성 파괴
//
// 방어: @TransactionalEventListener(실시간) + 이 스케줄러(자가 치유) 병행
// 스케줄러가 주기적으로 DB를 source of truth로 Redis를 재동기화
@Component
class RankingSelfHealingScheduler(
    private val commitRepository: CommitRepository,
    private val rankingRedisRepository: RankingRedisRepository,
    private val clock: Clock,
) {
    // 매시간 정각 실행
    // Redis 장애에서 복구되거나 ZINCRBY가 유실된 경우 최대 1시간 내 자동 복구
    @Scheduled(cron = "0 0 * * * *")
    fun syncCurrentMonthRankFromDb() {
        val now = LocalDateTime.now(clock)
        val yearMonth = YearMonth.now(clock)

        val from = now.withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0)
        val to = from.plusMonths(1)

        val scores = commitRepository
            .findMonthlyCommitCountPerUser(from, to)
            .associate { it.getUserId() to it.getCount() }

        rankingRedisRepository.rebuildFromDb(scores, yearMonth)
    }
}
