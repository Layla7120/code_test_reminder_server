package com.reminder.server.support

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.RedisCallback
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles

/**
 * 통합 테스트 공통 베이스.
 *
 * 컨테이너는 테스트 클래스마다 새로 뜨지 않는다 — Spring 테스트 컨텍스트가 재사용되면
 * 같은 컨테이너를 공유한다. 그래서 테스트 간 격리는 매번 데이터를 지워서 확보한다.
 *
 * 이 클래스에 @Transactional 을 붙이지 않는다.
 * 붙이면 테스트 전체가 한 트랜잭션에 묶여 동시성 테스트가 성립하지 않는다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(ContainerConfig::class, EndpointAuditConfig::class)
@ExtendWith(UserCountRecorder::class)
abstract class IntegrationTest {

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var redisTemplate: StringRedisTemplate

    @BeforeEach
    fun clearStores() {
        // FK 때문에 삭제 순서가 중요하다 (자식 → 부모)
        CLEANUP_ORDER.forEach { jdbcTemplate.execute("DELETE FROM $it") }

        redisTemplate.execute(RedisCallback { connection ->
            connection.serverCommands().flushDb()
            null
        })
    }

    companion object {
        private val CLEANUP_ORDER = listOf("participate", "commits", "history", "`groups`", "users")
    }
}
