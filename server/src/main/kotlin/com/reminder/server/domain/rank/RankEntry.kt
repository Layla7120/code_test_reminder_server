package com.reminder.server.domain.rank

data class RankEntry(
    val userId: Long,
    val commitCount: Long,
    val rank: Long,
)

// Redis ZSET에서 꺼낸 원본 데이터를 Dense Rank로 가공
// ZREVRANK는 인덱스(0-based)를 반환할 뿐 — 동점자 처리 없음
// Dense Rank 규칙: 동점자는 같은 순위, 다음 순위는 연속 (1, 1, 2 — 1, 1, 3 아님)
//
// [동점자 순서를 userId 오름차순으로 고정한다]
// Redis ZREVRANGE는 동점이면 member 문자열 역순으로 준다("53"이 "52"보다 앞).
// DB 쿼리는 동점 시 순서가 미지정이다. 그대로 두면 Redis 장애로 폴백이 발동한
// 순간 동점자들의 표시 순서가 뒤바뀐다 — 순위·커밋 수는 같은데 줄만 섞이는 형태다.
// 30건 정렬은 비용이 없으므로 여기서 결정적으로 만든다.
// (DB 경로도 ORDER BY `rank`, userId 로 같은 기준을 쓴다)
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
