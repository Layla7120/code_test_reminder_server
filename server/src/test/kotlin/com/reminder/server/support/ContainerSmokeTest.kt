package com.reminder.server.support

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

/**
 * 0단계 검증 — 기반이 실제로 서는지만 확인한다. 비즈니스 로직은 보지 않는다.
 *
 * 이 테스트가 통과한다는 것의 의미:
 *   1. MySQL 컨테이너가 뜨고 애플리케이션이 붙었다
 *   2. Flyway 가 빈 컨테이너에 마이그레이션 체인을 전부 적용했다
 *   3. ddl-auto: validate 를 통과했다 = 엔티티와 마이그레이션의 테이블·컬럼이 일치한다
 *
 * 3번이 핵심이다. 지금까지는 서버를 띄워봐야 알 수 있었다.
 *
 * Redis 검증은 없다 — 애플리케이션이 Redis 를 쓰지 않는다.
 */
class ContainerSmokeTest : IntegrationTest() {

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    @DisplayName("Flyway 가 도메인 테이블 6개 + 이력 테이블을 만든다")
    fun mysqlSchemaIsApplied() {
        val tables = jdbcTemplate.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()",
            String::class.java,
        ).map { it.lowercase() }

        // flyway_schema_history 가 있다 = 스키마가 init 스크립트가 아니라
        // 마이그레이션 체인으로 만들어졌다는 직접 증거
        assertThat(tables)
            .containsExactlyInAnyOrder(
                "users", "commits", "groups", "participate", "history", "user_monthly_score",
                "flyway_schema_history",
            )
    }
}
