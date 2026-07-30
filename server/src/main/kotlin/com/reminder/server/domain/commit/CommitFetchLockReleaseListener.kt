package com.reminder.server.domain.commit

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Component
class CommitFetchLockReleaseListener(
    private val redisTemplate: StringRedisTemplate,
) {
    // AFTER_COMPLETION: 커밋이든 롤백이든 트랜잭션이 완전히 끝난 뒤에만 실행된다.
    // AFTER_COMMIT과 달리 롤백 시에도 반드시 실행되어야 락이 TTL(30초)까지 묶이지 않는다.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMPLETION)
    fun onLockReleaseNeeded(event: CommitFetchLockReleaseEvent) {
        redisTemplate.delete(event.lockKey)
    }
}
