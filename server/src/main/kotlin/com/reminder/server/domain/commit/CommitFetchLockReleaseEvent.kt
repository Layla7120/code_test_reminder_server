package com.reminder.server.domain.commit

// 커밋 수집 락 해제를 트랜잭션 완료 시점까지 미루기 위한 이벤트. 이유는 발행 지점(CommitService)에 있다.
data class CommitFetchLockReleaseEvent(val lockKey: String)
