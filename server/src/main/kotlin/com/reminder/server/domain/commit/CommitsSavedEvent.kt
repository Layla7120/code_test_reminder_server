package com.reminder.server.domain.commit

import java.time.YearMonth

// DB 트랜잭션 커밋 후 Redis ZINCRBY를 발생시키기 위한 도메인 이벤트
// @TransactionalEventListener(AFTER_COMMIT)이 수신 — DB 롤백 시 Redis 오염 방지
data class CommitsSavedEvent(
    val userId: Long,
    val addedCount: Int,
    val yearMonth: YearMonth,
)
