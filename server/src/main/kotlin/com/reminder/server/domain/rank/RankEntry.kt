package com.reminder.server.domain.rank

data class RankEntry(
    val userId: Long,
    val commitCount: Long,
    val rank: Long,
)

// (userId, score) 목록을 Dense Rank로 가공
// Dense Rank 규칙: 동점자는 같은 순위, 다음 순위는 연속 (1, 1, 2 — 1, 1, 3 아님)
//
// 동점 순서는 userId 오름차순으로 두 경로에서 같게 — RankPathConsistencyTest 가 고정한다.
fun List<Pair<Long, Long>>.toDenseRankEntries(): List<RankEntry> {
    var rank = 0L
    var prevScore = Long.MAX_VALUE

    return this
        .sortedWith(compareByDescending<Pair<Long, Long>> { it.second }.thenBy { it.first })
        .map { (userId, score) ->
            // score가 이전 점수와 다를 때만 순위 증가
            // 동점이면 rank 그대로 유지
            if (score < prevScore) {
                rank++
                prevScore = score
            }
            RankEntry(userId = userId, commitCount = score, rank = rank)
        }
}
