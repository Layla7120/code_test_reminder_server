package com.reminder.server.support

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.mysql.MySQLContainer

/**
 * 테스트용 MySQL 컨테이너.
 *
 * 왜 실제 컨테이너인가:
 *   검증 대상이 InnoDB 의 락 동작과 원자적 UPDATE 다. H2 로 바꾸면 검증하려는 대상
 *   자체가 사라져 테스트가 의미를 잃는다.
 *
 * 스키마:
 *   운영과 같은 Flyway 마이그레이션 체인(db/migration)이 빈 컨테이너에 스키마를 만든다.
 *   마이그레이션이 엔티티와 어긋나면 ddl-auto: validate 가 잡는다. 경위: docs/기록.md
 *
 * Redis 컨테이너는 없다 — 랭킹이 집계 테이블로, 커밋 수집 락이 멱등성으로 대체되면서
 * 애플리케이션에서 Redis 를 쓰지 않는다.
 */
@TestConfiguration(proxyBeanMethods = false)
class ContainerConfig {

    @Bean
    @ServiceConnection
    fun mysqlContainer(): MySQLContainer =
        MySQLContainer("mysql:8.0").withDatabaseName("reminder")
}
