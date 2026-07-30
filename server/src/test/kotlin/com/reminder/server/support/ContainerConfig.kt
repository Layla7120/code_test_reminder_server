package com.reminder.server.support

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.GenericContainer
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.utility.MountableFile

/**
 * 테스트용 MySQL·Redis 컨테이너.
 *
 * 왜 실제 컨테이너인가:
 *   검증 대상이 InnoDB의 row lock 동작과 Redis Lua 스크립트의 원자성이다.
 *   H2나 임베디드 Redis로 바꾸면 검증하려는 대상 자체가 사라져 테스트가 의미를 잃는다.
 *
 * 스키마:
 *   docker-compose 와 동일하게 /docker-entrypoint-initdb.d 로 infra/init.sql 을 넣는다.
 *   복사본을 만들지 않고 원본을 참조하므로, 엔티티와 init.sql 이 어긋나면
 *   ddl-auto: validate 가 테스트를 실패시킨다. (서버를 띄워볼 필요 없음)
 */
@TestConfiguration(proxyBeanMethods = false)
class ContainerConfig {

    @Bean
    @ServiceConnection
    fun mysqlContainer(): MySQLContainer =
        MySQLContainer("mysql:8.0")
            .withDatabaseName("reminder")
            .withCopyFileToContainer(
                MountableFile.forHostPath(initSqlPath()),
                "/docker-entrypoint-initdb.d/init.sql",
            )

    @Bean
    @ServiceConnection(name = "redis")
    fun redisContainer(): GenericContainer<*> =
        GenericContainer("redis:7-alpine").withExposedPorts(REDIS_PORT)

    companion object {
        private const val REDIS_PORT = 6379

        // 경로는 build.gradle.kts 의 Test 태스크가 시스템 프로퍼티로 넘긴다.
        private fun initSqlPath(): String =
            System.getProperty("schema.init.sql")
                ?: error("schema.init.sql 시스템 프로퍼티가 없습니다. build.gradle.kts 의 Test 태스크 설정을 확인하세요.")
    }
}
