package com.reminder.server.domain.rank

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Repository
import java.time.Duration
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@Repository
class RankingRedisRepository(private val redisTemplate: StringRedisTemplate) {

    companion object {
        private val KEY_FORMAT = DateTimeFormatter.ofPattern("yyyyMM")
        private val KEY_TTL = Duration.ofDays(90)

        fun rankKey(yearMonth: YearMonth) = "rank:commit:${yearMonth.format(KEY_FORMAT)}"
        fun denseRankKey(yearMonth: YearMonth) = "rank:dense:${yearMonth.format(KEY_FORMAT)}"
    }

    // ZADD GT Lua 스크립트
    // 배치가 DB에서 읽은 100을 Redis에 반영할 때,
    // 실시간 ZINCRBY로 이미 101이 된 경우 덮어쓰지 않음 (Lost Update 방지)
    // Redis ZADD GT 플래그와 동일한 동작, 구버전 Redis 호환성 확보
    private val zAddGtScript: RedisScript<Long> = RedisScript.of(
        """
        local current = redis.call('ZSCORE', KEYS[1], ARGV[1])
        if current == false or tonumber(ARGV[2]) > tonumber(current) then
            return redis.call('ZADD', KEYS[1], ARGV[2], ARGV[1])
        end
        return 0
        """.trimIndent(),
        Long::class.java
    )

    // ── Write (실시간) ────────────────────────────────────────────────────────

    fun incrementScore(userId: Long, count: Long, yearMonth: YearMonth) {
        val key = rankKey(yearMonth)
        redisTemplate.opsForZSet().incrementScore(key, userId.toString(), count.toDouble())
        redisTemplate.expire(key, KEY_TTL)
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    // Top 30: ZREVRANGE + Kotlin Dense Rank 계산 (30건이므로 메모리 부담 없음)
    fun getTop30(yearMonth: YearMonth): List<RankEntry> {
        val tuples = redisTemplate.opsForZSet()
            .reverseRangeWithScores(rankKey(yearMonth), 0, 29)
            ?: return emptyList()

        return tuples
            .map { Pair(it.value!!.toLong(), it.score!!.toLong()) }
            .toDenseRankEntries()
    }

    // 사용자 개별 Dense Rank: O(1) HGET — 데이터 이동 없음
    // 배치가 사전 계산한 HASH에서 직접 조회
    fun getUserDenseRank(userId: Long, yearMonth: YearMonth): Long? =
        redisTemplate.opsForHash<String, String>()
            .get(denseRankKey(yearMonth), userId.toString())
            ?.toLong()

    // ── Batch (자가 치유 스케줄러에서 호출) ───────────────────────────────────

    // Step 1: DB 기준으로 score ZSET 보정 (ZADD GT — 실시간 증분 보호)
    fun healScores(scores: Map<Long, Long>, yearMonth: YearMonth) {
        val key = rankKey(yearMonth)
        scores.forEach { (userId, count) ->
            redisTemplate.execute(zAddGtScript, listOf(key), userId.toString(), count.toString())
        }
        redisTemplate.expire(key, KEY_TTL)
    }

    // Step 2: Dense Rank HASH 사전 계산 (Shadow Key + Atomic RENAME)
    // score ZSET 전체를 읽어 Kotlin에서 Dense Rank 계산 후 HASH로 저장
    // 배치에서만 수행 — 요청 경로가 아니므로 전체 ZSET 로딩 허용
    fun rebuildDenseRankHash(yearMonth: YearMonth) {
        val scoreKey = rankKey(yearMonth)
        val denseKey = denseRankKey(yearMonth)
        val tempKey = "$denseKey:temp"

        val allEntries = redisTemplate.opsForZSet()
            .reverseRangeWithScores(scoreKey, 0, -1)
            ?: return

        if (allEntries.isEmpty()) return

        val denseRankMap: Map<String, String> = allEntries
            .map { Pair(it.value!!.toLong(), it.score!!.toLong()) }
            .toDenseRankEntries()
            .associate { it.userId.toString() to it.rank.toString() }

        // 1. Shadow key에 먼저 쓰기
        redisTemplate.opsForHash<String, String>().putAll(tempKey, denseRankMap)
        redisTemplate.expire(tempKey, KEY_TTL)

        // 2. 원자적 RENAME (O(1)) — 클라이언트는 항상 완전한 데이터를 조회
        // delete + executePipelined 방식의 마이크로 아웃티지 제거
        redisTemplate.rename(tempKey, denseKey)
    }
}
