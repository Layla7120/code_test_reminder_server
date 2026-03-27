package com.reminder.server.global.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain

@Configuration
class SecurityConfig {
    // 이 프로젝트는 인증/인가 없음 (레거시 Flask와 동일)
    // BCryptPasswordEncoder 사용을 위해 spring-security 의존성만 추가한 상태
    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .authorizeHttpRequests { it.anyRequest().permitAll() }
        return http.build()
    }
}
