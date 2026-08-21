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
 * 왜 static 싱글턴인가:
 *   @Bean 이 만드는 컨테이너는 Spring 테스트 컨텍스트마다 하나씩 생긴다. 이 저장소는
 *   프로파일(test/load-test)·@Import(FixedClockConfig, TimezoneClockConfig)·@MockitoBean·
 *   webEnvironment(MOCK/RANDOM_PORT) 조합으로 컨텍스트가 7개로 갈리고, 측정해보니
 *   MySQL 컨테이너가 실제로 7번 떴다(각 5초). 컨테이너는 컨텍스트가 아니라 JVM 에
 *   묶이는 게 맞다 — 여기서 한 번 띄워 전부가 공유한다.
 *
 *   멈추지 않는다. Testcontainers 의 Ryuk 이 JVM 종료 시 정리한다.
 *
 * 공유해도 되는 이유:
 *   IntegrationTest.clearStores() 가 매 테스트 앞에서 테이블을 비운다. 격리는 컨테이너를
 *   나누는 게 아니라 데이터를 지워서 얻는다 — 원래부터 그 구조였다.
 *
 * 스키마:
 *   운영과 같은 Flyway 마이그레이션 체인(db/migration)이 만든다. 두 번째 컨텍스트부터는
 *   이미 적용된 상태라 Flyway 가 검증만 하고 지나간다.
 *   마이그레이션이 엔티티와 어긋나면 ddl-auto: validate 가 잡는다. 경위: docs/기록.md
 *
 * Redis 컨테이너는 없다 — 랭킹이 집계 테이블로, 커밋 수집 락이 멱등성으로 대체되면서
 * 애플리케이션에서 Redis 를 쓰지 않는다.
 */
@TestConfiguration(proxyBeanMethods = false)
class ContainerConfig {

    @Bean
    @ServiceConnection
    fun mysqlContainer(): MySQLContainer = MYSQL

    companion object {
        private val MYSQL: MySQLContainer =
            MySQLContainer("mysql:8.0")
                .withDatabaseName("reminder")
                .apply { start() }
    }
}
