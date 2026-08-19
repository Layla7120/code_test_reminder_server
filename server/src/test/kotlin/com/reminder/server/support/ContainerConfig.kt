package com.reminder.server.support

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.GenericContainer
import org.testcontainers.mysql.MySQLContainer

/**
 * 테스트용 MySQL·Redis 컨테이너.
 *
 * 왜 실제 컨테이너인가:
 *   검증 대상이 InnoDB의 row lock 동작과 Redis Lua 스크립트의 원자성이다.
 *   H2나 임베디드 Redis로 바꾸면 검증하려는 대상 자체가 사라져 테스트가 의미를 잃는다.
 *
 * 스키마:
 *   운영과 같은 Flyway 마이그레이션 체인(db/migration)이 빈 컨테이너에 스키마를 만든다.
 *   init.sql 마운트 시절과 달리 복사본이 아예 없다 — 마이그레이션이 엔티티와 어긋나면
 *   ddl-auto: validate 가 잡는다. 경위: docs/기록.md
 */
@TestConfiguration(proxyBeanMethods = false)
class ContainerConfig {

    @Bean
    @ServiceConnection
    fun mysqlContainer(): MySQLContainer =
        MySQLContainer("mysql:8.0").withDatabaseName("reminder")

    @Bean
    @ServiceConnection(name = "redis")
    fun redisContainer(): GenericContainer<*> =
        GenericContainer("redis:7-alpine").withExposedPorts(REDIS_PORT)

    companion object {
        private const val REDIS_PORT = 6379
    }
}
