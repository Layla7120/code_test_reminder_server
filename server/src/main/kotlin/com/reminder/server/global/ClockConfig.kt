package com.reminder.server.global

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.ZoneId

@Configuration
class ClockConfig {
    // Clock을 Bean으로 주입 → 서비스에서 Clock.instant()로 현재 시간 사용
    // 테스트에서 Clock.fixed()로 교체하면 특정 시점 시나리오 검증 가능
    @Bean
    fun clock(): Clock = Clock.system(ZoneId.of("Asia/Seoul"))
}
