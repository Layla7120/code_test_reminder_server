package com.reminder.server.domain.rank

data class RankEntry(
    val userId: Long,
    val commitCount: Long,
    val rank: Long,
)

// Redis ZSET에서 꺼낸 원본 데이터를 Dense Rank로 가공
// ZREVRANK는 인덱스(0-based)를 반환할 뿐 — 동점자 처리 없음
// Dense Rank 규칙: 동점자는 같은 순위, 다음 순위는 연속 (1, 1, 2 — 1, 1, 3 아님)
fun List<Pair<Long, Long>>.toDenseRankEntries(): List<RankEntry> {
    var rank = 0L
    var prevScore = Long.MAX_VALUE

    return this.map { (userId, score) ->
        // score가 이전 점수와 다를 때만 순위 증가
        // 동점이면 rank 그대로 유지
        if (score < prevScore) {
            rank++
            prevScore = score
        }
        RankEntry(userId = userId, commitCount = score, rank = rank)
    }
}
