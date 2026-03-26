package com.reminder.server.domain.rank

import com.reminder.server.domain.commit.CommitsSavedEvent
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Component
class RankingEventListener(
    private val rankingRedisRepository: RankingRedisRepository,
) {
    // AFTER_COMMIT: DB 트랜잭션이 성공적으로 커밋된 후에만 실행
    // → DB 롤백 시 Redis 미반영 (일관성 보호)
    // → 실패해도 스케줄러가 최대 1시간 내 자동 복구
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onCommitsSaved(event: CommitsSavedEvent) {
        if (event.addedCount <= 0) return
        rankingRedisRepository.incrementScore(event.userId, event.addedCount.toLong(), event.yearMonth)
    }
}
