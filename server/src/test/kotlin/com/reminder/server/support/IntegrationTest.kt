package com.reminder.server.support

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

/**
 * 통합 테스트 공통 베이스.
 *
 * 컨테이너는 클래스마다 새로 뜨지 않는다 — Spring 테스트 컨텍스트가 재사용되면
 * 같은 컨테이너를 공유한다. 그래서 테스트 간 데이터 격리는 각 테스트가 직접 챙겨야 한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(ContainerConfig::class)
abstract class IntegrationTest
