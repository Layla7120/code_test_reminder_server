package com.reminder.server.support

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate

/**
 * 0단계 검증 — 기반이 실제로 서는지만 확인한다. 비즈니스 로직은 보지 않는다.
 *
 * 이 테스트가 통과한다는 것의 의미:
 *   1. MySQL·Redis 컨테이너가 뜨고 애플리케이션이 붙었다
 *   2. infra/init.sql 이 컨테이너에 적용됐다
 *   3. ddl-auto: validate 를 통과했다 = 엔티티와 init.sql 의 테이블·컬럼이 일치한다
 *
 * 3번이 핵심이다. 지금까지는 서버를 띄워봐야 알 수 있었다.
 */
class ContainerSmokeTest : IntegrationTest() {

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var redisTemplate: StringRedisTemplate

    @Test
    @DisplayName("MySQL 컨테이너에 infra/init.sql 의 테이블 5개가 만들어진다")
    fun mysqlSchemaIsApplied() {
        val tables = jdbcTemplate.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()",
            String::class.java,
        ).map { it.lowercase() }

        assertThat(tables)
            .containsExactlyInAnyOrder("users", "commits", "groups", "participate", "history")
    }

    @Test
    @DisplayName("Redis 컨테이너에 읽고 쓸 수 있다")
    fun redisIsReachable() {
        redisTemplate.opsForValue().set("smoke:key", "ok")

        assertThat(redisTemplate.opsForValue().get("smoke:key")).isEqualTo("ok")

        redisTemplate.delete("smoke:key")
    }

    @Test
    @DisplayName("Redis 가 Lua 스크립트를 실행한다 — ZADD GT 검증의 전제")
    fun redisRunsLuaScript() {
        val script = org.springframework.data.redis.core.script.RedisScript.of(
            "return redis.call('SET', KEYS[1], ARGV[1])",
            String::class.java,
        )

        redisTemplate.execute(script, listOf("smoke:lua"), "ok")

        assertThat(redisTemplate.opsForValue().get("smoke:lua")).isEqualTo("ok")

        redisTemplate.delete("smoke:lua")
    }
}
