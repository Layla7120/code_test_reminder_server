package com.reminder.server.domain.rank

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Repository
import java.time.Duration
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@Repository
class RankingRedisRepository(private val redisTemplate: StringRedisTemplate) {

    companion object {
        private val KEY_FORMAT = DateTimeFormatter.ofPattern("yyyyMM")
        private val KEY_TTL = Duration.ofDays(90)  // 3개월 후 자동 삭제

        fun rankKey(yearMonth: YearMonth): String = "rank:commit:${yearMonth.format(KEY_FORMAT)}"
    }

    // Write: 커밋 추가 시 score 누적 — O(log N)
    fun incrementScore(userId: Long, count: Long, yearMonth: YearMonth) {
        val key = rankKey(yearMonth)
        redisTemplate.opsForZSet().incrementScore(key, userId.toString(), count.toDouble())
        redisTemplate.expire(key, KEY_TTL)
    }

    // Read: 상위 30명 조회 — O(log N + 30)
    // ZREVRANGE: score 내림차순 정렬 (커밋 많은 순)
    fun getTop30(yearMonth: YearMonth): List<RankEntry> {
        val key = rankKey(yearMonth)
        val tuples = redisTemplate.opsForZSet().reverseRangeWithScores(key, 0, 29)
            ?: return emptyList()

        // Redis가 반환한 순서(score 내림차순)로 Dense Rank 계산
        return tuples
            .map { tuple -> Pair(tuple.value!!.toLong(), tuple.score!!.toLong()) }
            .toDenseRankEntries()
    }

    // Read: 특정 유저의 Dense Rank
    // ZREVRANK는 단순 인덱스 반환 → Dense Rank 불가
    // 해결: 내 score보다 높은 distinct score 개수 + 1
    fun getUserDenseRank(userId: Long, yearMonth: YearMonth): Long? {
        val key = rankKey(yearMonth)
        val myScore = redisTemplate.opsForZSet().score(key, userId.toString()) ?: return null

        // (myScore, +inf) 범위 멤버를 내림차순으로 가져와 distinct score 계산
        val higherScores = redisTemplate.opsForZSet()
            .reverseRangeByScoreWithScores(key, myScore + 0.001, Double.MAX_VALUE)
            ?.map { it.score!! }
            ?.toSet()       // distinct
            ?: emptySet()

        return higherScores.size.toLong() + 1
    }

    // Self-healing: DB 집계 결과로 Redis 전체 덮어쓰기
    // 스케줄러 또는 서버 기동 시 호출
    fun rebuildFromDb(scores: Map<Long, Long>, yearMonth: YearMonth) {
        val key = rankKey(yearMonth)
        redisTemplate.delete(key)

        if (scores.isEmpty()) return

        // pipeline으로 한 번에 전송
        redisTemplate.executePipelined {
            scores.forEach { (userId, count) ->
                redisTemplate.opsForZSet().add(key, userId.toString(), count.toDouble())
            }
        }
        redisTemplate.expire(key, KEY_TTL)
    }
}
