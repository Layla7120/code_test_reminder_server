package com.reminder.server.domain.commit

// 커밋 수집 락 해제를 트랜잭션 완료 시점까지 미루기 위한 이벤트.
// finally에서 즉시 해제하면 DB 커밋 "전에" 락이 풀려, 그 틈에 들어온 요청이
// 아직 커밋되지 않은 신규 커밋을 findExistingShas()에서 "없음"으로 보고
// GitHub을 중복 호출하고 랭킹도 중복 계상할 수 있다 (버그 A가 이 경로로 재발할 수 있음).
data class CommitFetchLockReleaseEvent(val lockKey: String)
